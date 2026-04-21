rootProject.name = "knative-fluss-broker"

// Data plane modules
include(":data-plane:common")
include(":data-plane:ingress")
include(":data-plane:dispatcher")
include(":data-plane:storage-fluss")
include(":data-plane:schema")
include(":data-plane:delivery")


// Control plane modules
include(":control-plane:api")
include(":control-plane:controller")

// Test modules
include(":test:testlib")
include(":test:containers")
include(":test:wiremock")
include(":test:integration")
include(":test:e2e")
include(":test:performance-smoke")

// Tools
include(":tools:tui")
