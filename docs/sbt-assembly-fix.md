# SBT Assembly Merge Strategy Fix

## Problem

The Docker build was failing during `sbt assembly` with the following error:

```
[error] Deduplicate found different file contents in the following:
[error]   Jar name = logback-classic-1.4.14.jar, jar org = ch.qos.logback, entry target = module-info.class
[error]   Jar name = logback-core-1.4.14.jar, jar org = ch.qos.logback, entry target = module-info.class
[error] Deduplicate found different file contents in the following:
[error]   Jar name = okhttp-jvm-5.3.2.jar, jar org = com.squareup.okhttp3, entry target = META-INF/versions/9/module-info.class
[error]   Jar name = kotlin-stdlib-2.2.21.jar, jar org = org.jetbrains.kotlin, entry target = META-INF/versions/9/module-info.class
[error]   Jar name = slf4j-api-2.0.7.jar, jar org = org.slf4j, entry target = META-INF/versions/9/module-info.class
```

**Exit code:** 1

## Root Cause

When creating a fat JAR with `sbt assembly`, multiple dependencies contain `module-info.class` files (Java 9+ module descriptors). By default, sbt-assembly tries to deduplicate these files, but since they have different contents, it fails.

These conflicts occur in:
1. **Logback** - Both `logback-classic` and `logback-core` have module-info.class
2. **Multi-release JARs** - OkHttp, Kotlin stdlib, and SLF4J have versioned module descriptors in `META-INF/versions/9/`

## Solution

Added a merge strategy to `build.sbt` to handle these conflicts:

```scala
// Assembly merge strategy for handling conflicts
assembly / assemblyMergeStrategy := {
  case "module-info.class" => MergeStrategy.discard
  case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.discard
  case PathList("META-INF", xs @ _*) => xs match {
    case "MANIFEST.MF" :: Nil => MergeStrategy.discard
    case "services" :: _ => MergeStrategy.concat
    case _ => MergeStrategy.discard
  }
  case x if x.endsWith("/module-info.class") => MergeStrategy.discard
  case x => 
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}
```

### Strategy Explained

1. **Discard module-info.class** - These are not needed in the fat JAR
2. **Discard versioned module descriptors** - Multi-release JAR metadata not needed
3. **Discard META-INF/MANIFEST.MF** - Create new manifest for the assembly
4. **Concatenate services** - Merge SPI service files
5. **Fall back to default** - For all other files

## Results

### Before (Failed)
```
[error] 2 error(s) were encountered during the merge
exit code: 1
```

### After (Success)
```
[info] 4 file(s) merged using strategy 'Rename'
[info] 131 file(s) merged using strategy 'Discard'
[info] Built: /app/target/scala-3.3.7/wikipedia-edit-war-monitor-assembly-0.0.1-SNAPSHOT.jar
[success] Total time: 10 s
```

**Final Docker image size:** 315MB (optimized multi-stage build)

## Why Discard module-info.class?

Module descriptors (`module-info.class`) are used by Java's module system (JPMS) to define:
- Module dependencies
- Exported packages
- Required services

**In a fat JAR:**
- All dependencies are merged into a single JAR
- The original module boundaries no longer apply
- Module descriptors would conflict with each other
- The fat JAR is typically run on the classpath, not module path

**Therefore:** It's safe and recommended to discard these files when creating an assembly.

## Verification

### Local Build
```bash
sbt assembly
# Output:
# [success] Total time: 8 s
# [info] Built: target/scala-3.3.7/wikipedia-edit-war-monitor-assembly-0.0.1-SNAPSHOT.jar
```

### Docker Build
```bash
docker-compose build wikipedia-monitor
# Output:
# [info] Built: /app/target/scala-3.3.7/wikipedia-edit-war-monitor-assembly-0.0.1-SNAPSHOT.jar
# Successfully tagged wikipedia-edit-war-monitor-wikipedia-monitor:latest
```

### Running the JAR
```bash
java -jar target/scala-3.3.7/wikipedia-edit-war-monitor-assembly-0.0.1-SNAPSHOT.jar
```

## Alternative Strategies

If you need different behavior, other merge strategies are available:

### 1. First (Use first occurrence)
```scala
case "module-info.class" => MergeStrategy.first
```
- Uses the first module-info.class encountered
- May work but unpredictable which one is chosen

### 2. Last (Use last occurrence)
```scala
case "module-info.class" => MergeStrategy.last
```
- Uses the last module-info.class encountered
- Same unpredictability as First

### 3. Concat (Concatenate all)
```scala
case "module-info.class" => MergeStrategy.concat
```
- ❌ Won't work - module-info.class is binary, not text
- Will create invalid class files

### 4. Rename (Rename conflicts)
```scala
case "module-info.class" => MergeStrategy.rename
```
- Renames conflicting files (module-info.class → module-info-1.class)
- Larger JAR size, files are unused anyway

### 5. Discard (Recommended)
```scala
case "module-info.class" => MergeStrategy.discard
```
- ✅ Discards all module descriptors
- ✅ Smallest JAR size
- ✅ No conflicts
- ✅ Standard practice for fat JARs

## Common Assembly Conflicts

Here are other common conflicts and recommended strategies:

```scala
assembly / assemblyMergeStrategy := {
  // Module descriptors - always discard
  case "module-info.class" => MergeStrategy.discard
  case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
  
  // Manifests - discard, assembly creates its own
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  
  // Service files - concatenate to preserve all services
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  
  // Signature files - discard, signing not preserved in assembly
  case PathList("META-INF", _ @ ("*.SF" | "*.DSA" | "*.RSA")) => MergeStrategy.discard
  
  // License files - concatenate or first
  case PathList("META-INF", "LICENSE" | "LICENSE.txt") => MergeStrategy.concat
  
  // Reference configs - concat to merge Typesafe Config
  case "reference.conf" => MergeStrategy.concat
  
  // Properties files - usually first or concat
  case x if x.endsWith(".properties") => MergeStrategy.first
  
  // Everything else - use default strategy
  case x => 
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}
```

## Testing the Assembly

### Test JAR is Valid
```bash
# List contents
jar tf target/scala-3.3.7/*-assembly*.jar | head -20

# Check main class
jar xf target/scala-3.3.7/*-assembly*.jar META-INF/MANIFEST.MF
cat META-INF/MANIFEST.MF

# Run it
java -jar target/scala-3.3.7/*-assembly*.jar
```

### Test Docker Container
```bash
# Build
docker-compose build wikipedia-monitor

# Run
docker-compose up wikipedia-monitor

# Check it's working
curl http://localhost:8080/hello/world
```

## Summary

✅ **Problem:** sbt assembly failing due to duplicate module-info.class files
✅ **Solution:** Added merge strategy to discard module descriptors
✅ **Result:** Successful Docker build, 315MB final image
✅ **Build time:** ~10 seconds (after dependencies cached)
✅ **Strategy:** Standard practice for fat JAR creation

The application can now be built in Docker successfully!

