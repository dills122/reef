package main

import "testing"

func TestDefaultConfigUsesAdminApiTokenForAdminSeedRoutes(t *testing.T) {
	t.Setenv("REEF_ADMIN_API_BEARER_TOKEN", "")
	t.Setenv("ADMIN_API_TOKEN", "admin-token")
	cfg := defaultConfigFromEnv()

	if cfg.AdminAPIBearerToken != "admin-token" {
		t.Fatalf("AdminAPIBearerToken = %q, want admin-token", cfg.AdminAPIBearerToken)
	}
}
