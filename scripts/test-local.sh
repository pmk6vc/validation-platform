#!/bin/bash
#
# Local Test Runner
# =================
#
# This script is a workaround for running tests locally when Testcontainers
# fails to connect to Docker Desktop on macOS.
#
# WHY IS THIS NEEDED?
# -------------------
# Testcontainers uses the docker-java library to communicate with Docker.
# On some macOS Docker Desktop configurations, docker-java receives a malformed
# HTTP 400 response when trying to connect, even though Docker itself works fine
# (e.g., `docker run` commands work). This is a known compatibility issue.
#
# This script manually starts a PostgreSQL container and passes the connection
# details to the tests via environment variables.
#
# CI (GitHub Actions) does NOT need this script - Testcontainers works correctly
# there because Docker is properly configured in the Ubuntu runner environment.
#
# USAGE
# -----
#   ./scripts/test-local.sh              # Run all tests
#   ./scripts/test-local.sh --rerun-tasks  # Force re-run all tests
#   ./scripts/test-local.sh --tests "*.ServiceRepositoryTest"  # Run specific tests
#

set -e

CONTAINER_NAME="platform-test-db"
DB_PORT=5433

echo "Starting PostgreSQL test container..."
docker run -d --name $CONTAINER_NAME \
  -p $DB_PORT:5432 \
  -e POSTGRES_DB=platform_test \
  -e POSTGRES_USER=test \
  -e POSTGRES_PASSWORD=test \
  postgres:16-alpine > /dev/null

cleanup() {
  echo "Cleaning up test container..."
  docker stop $CONTAINER_NAME > /dev/null 2>&1 || true
  docker rm $CONTAINER_NAME > /dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Waiting for PostgreSQL to be ready..."
sleep 3

echo "Running tests..."
TEST_DATABASE_URL="jdbc:postgresql://localhost:$DB_PORT/platform_test" \
TEST_DATABASE_USER="test" \
TEST_DATABASE_PASSWORD="test" \
./gradlew test "$@"

echo "Tests complete!"
