rootProject.name = "validation-platform"

// Core modules
include("shared")
include("platform")
include("collector")
include("agent")

// Cross-module integration tests (Envoy + platform + collector)
include("e2e-tests")

// Test services - standalone microservices for k3s integration testing
include("test-services:api-gateway")
include("test-services:order-service")
include("test-services:notification-service")
include("test-services:webhook-stub")
include("test-services:traffic-generator")
