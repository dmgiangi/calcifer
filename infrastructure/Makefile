# =============================================================================
# Calcifer Infrastructure - Makefile
# =============================================================================
# Unified commands for managing cloud and home environments
# Usage: make <target> [ENV=cloud|home]
# =============================================================================

.PHONY: help up down restart logs status ps clean pull build validate

# Default environment
ENV ?= home

# Compose files based on environment
ifeq ($(ENV),cloud)
    COMPOSE_DIR := cloud
    COMPOSE_FILES := -f cloud/docker-compose.yaml
    PROJECT_NAME := calcifer-cloud
else ifeq ($(ENV),home)
    COMPOSE_DIR := home
    COMPOSE_FILES := -f home/docker-compose.yaml
    PROJECT_NAME := calcifer-home
else
    $(error Invalid ENV: $(ENV). Use 'cloud' or 'home')
endif

DOCKER_COMPOSE := docker compose -p $(PROJECT_NAME) $(COMPOSE_FILES)

# Colors
GREEN  := \033[0;32m
YELLOW := \033[0;33m
CYAN   := \033[0;36m
RESET  := \033[0m

# =============================================================================
# Help
# =============================================================================
help:
	@echo "$(CYAN)Calcifer Infrastructure$(RESET)"
	@echo ""
	@echo "$(YELLOW)Usage:$(RESET) make <target> [ENV=cloud|home]"
	@echo ""
	@echo "$(YELLOW)Targets:$(RESET)"
	@echo "  $(GREEN)up$(RESET)        Start all services"
	@echo "  $(GREEN)down$(RESET)      Stop all services"
	@echo "  $(GREEN)restart$(RESET)   Restart all services"
	@echo "  $(GREEN)logs$(RESET)      Show logs (use SERVICE=name for specific)"
	@echo "  $(GREEN)status$(RESET)    Show service status as JSON"
	@echo "  $(GREEN)ps$(RESET)        List running containers"
	@echo "  $(GREEN)pull$(RESET)      Pull latest images"
	@echo "  $(GREEN)build$(RESET)     Build images"
	@echo "  $(GREEN)validate$(RESET)  Validate compose files"
	@echo "  $(GREEN)clean$(RESET)     Remove containers and volumes"
	@echo ""
	@echo "$(YELLOW)Environments:$(RESET)"
	@echo "  $(GREEN)cloud$(RESET)     Monitoring + Keycloak + Traefik"
	@echo "  $(GREEN)home$(RESET)      Core Server deps + Monitoring (default)"
	@echo ""
	@echo "$(YELLOW)Examples:$(RESET)"
	@echo "  make up ENV=cloud"
	@echo "  make logs ENV=home SERVICE=rabbitmq"
	@echo "  make status ENV=home"

# =============================================================================
# Lifecycle Commands
# =============================================================================
up:
	@echo "$(GREEN)Starting $(ENV) environment...$(RESET)"
	$(DOCKER_COMPOSE) up -d
	@echo "$(GREEN)Done!$(RESET)"

down:
	@echo "$(YELLOW)Stopping $(ENV) environment...$(RESET)"
	$(DOCKER_COMPOSE) down
	@echo "$(GREEN)Done!$(RESET)"

restart: down up

pull:
	@echo "$(CYAN)Pulling latest images for $(ENV)...$(RESET)"
	$(DOCKER_COMPOSE) pull

build:
	@echo "$(CYAN)Building images for $(ENV)...$(RESET)"
	$(DOCKER_COMPOSE) build

# =============================================================================
# Monitoring Commands
# =============================================================================
logs:
ifdef SERVICE
	$(DOCKER_COMPOSE) logs -f $(SERVICE)
else
	$(DOCKER_COMPOSE) logs -f
endif

ps:
	$(DOCKER_COMPOSE) ps

status:
	@echo '{"environment":"$(ENV)","services":'
	@$(DOCKER_COMPOSE) ps --format json | jq -s '.'
	@echo '}'

# =============================================================================
# Validation
# =============================================================================
validate:
	@echo "$(CYAN)Validating $(ENV) compose files...$(RESET)"
	$(DOCKER_COMPOSE) config --quiet && echo "$(GREEN)Valid!$(RESET)" || echo "$(YELLOW)Invalid!$(RESET)"

# =============================================================================
# Cleanup
# =============================================================================
clean:
	@echo "$(YELLOW)Removing $(ENV) containers and volumes...$(RESET)"
	$(DOCKER_COMPOSE) down -v --remove-orphans
	@echo "$(GREEN)Done!$(RESET)"

# =============================================================================
# Environment-specific shortcuts
# =============================================================================
cloud-up:
	@$(MAKE) up ENV=cloud

cloud-down:
	@$(MAKE) down ENV=cloud

home-up:
	@$(MAKE) up ENV=home

home-down:
	@$(MAKE) down ENV=home

# =============================================================================
# Both environments
# =============================================================================
all-up:
	@$(MAKE) up ENV=home
	@$(MAKE) up ENV=cloud

all-down:
	@$(MAKE) down ENV=cloud
	@$(MAKE) down ENV=home

