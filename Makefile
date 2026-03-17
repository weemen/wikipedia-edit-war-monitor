.PHONY: start stop restart build logs logs-app logs-jaeger clean help check-docker status

# Default target
.DEFAULT_GOAL := help

# Colors for output
BLUE := \033[0;34m
GREEN := \033[0;32m
YELLOW := \033[0;33m
RED := \033[0;31m
NC := \033[0m # No Color

help: ## Show this help message
	@echo "$(BLUE)Wikipedia Edit War Monitor - Available Commands$(NC)"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(GREEN)%-15s$(NC) %s\n", $$1, $$2}'
	@echo ""

check-docker: ## Check if Docker is running
	@if ! docker info > /dev/null 2>&1; then \
		echo "$(RED)❌ Error: Docker is not running. Please start Docker Desktop.$(NC)"; \
		exit 1; \
	fi

start: check-docker ## Start all services (Jaeger + Wikipedia Monitor)
	@echo "$(BLUE)🚀 Starting Wikipedia Edit War Monitor with Tracing$(NC)"
	@echo ""
	@echo "$(BLUE)🔨 Building and starting services...$(NC)"
	@echo "   This may take a few minutes on first run (downloading dependencies)"
	@echo ""
	@docker-compose up -d --build
	@echo ""
	@echo "$(YELLOW)⏳ Waiting for services to be ready...$(NC)"
	@counter=0; \
	timeout=60; \
	until docker-compose exec -T jaeger wget --spider -q http://localhost:16686 2>/dev/null; do \
		sleep 2; \
		counter=$$((counter + 2)); \
		if [ $$counter -ge $$timeout ]; then \
			echo "$(YELLOW)⚠️  Warning: Jaeger health check timed out$(NC)"; \
			break; \
		fi; \
		printf "."; \
	done
	@echo ""
	@echo ""
	@echo "$(GREEN)✅ Services are running!$(NC)"
	@echo ""
	@echo "$(BLUE)📊 Jaeger UI:        http://localhost:16686$(NC)"
	@echo "$(BLUE)🌐 Application API:  http://localhost:8080/stats.html$(NC)"
	@echo ""
	@echo "$(BLUE)📝 Useful commands:$(NC)"
	@echo "   make logs          - View logs from all services"
	@echo "   make logs-app      - View application logs only"
	@echo "   make logs-jaeger   - View Jaeger logs only"
	@echo "   make stop          - Stop all services"
	@echo "   make restart       - Restart application"
	@echo ""

stop: ## Stop all services
	@echo "$(BLUE)🛑 Stopping Wikipedia Edit War Monitor services...$(NC)"
	@echo ""
	@echo "$(BLUE)📊 Stopping all services...$(NC)"
	@docker-compose down
	@echo ""
	@echo "$(GREEN)✅ All services stopped$(NC)"
	@echo ""
	@echo "$(BLUE)Additional commands:$(NC)"
	@echo "  make clean           - Remove all data (including traces)"
	@echo "  make status          - View stopped containers"
	@echo "  docker-compose down --rmi local  - Remove built images"
	@echo ""

restart: ## Restart the application container
	@echo "$(BLUE)🔄 Restarting Wikipedia Monitor application...$(NC)"
	@docker-compose restart wikipedia-monitor
	@echo "$(GREEN)✅ Application restarted$(NC)"

build: check-docker ## Build services without starting
	@echo "$(BLUE)🔨 Building services...$(NC)"
	@docker-compose build
	@echo "$(GREEN)✅ Build complete$(NC)"

logs: ## View logs from all services
	@docker-compose logs -f

logs-app: ## View application logs only
	@docker-compose logs -f wikipedia-monitor

logs-jaeger: ## View Jaeger logs only
	@docker-compose logs -f jaeger

status: ## Show status of all containers
	@docker-compose ps -a

clean: ## Remove all data including traces (stops services and removes volumes)
	@echo "$(YELLOW)⚠️  This will remove all data including traces!$(NC)"
	@docker-compose down -v
	@echo "$(GREEN)✅ All data removed$(NC)"

clean-images: ## Remove built images
	@echo "$(YELLOW)⚠️  This will remove built Docker images!$(NC)"
	@docker-compose down --rmi local
	@echo "$(GREEN)✅ Images removed$(NC)"

rebuild: clean-images build start ## Clean, rebuild, and start everything

up: start ## Alias for start

down: stop ## Alias for stop

