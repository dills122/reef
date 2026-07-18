package main

import (
	"os"
	"testing"
	"time"
)

func TestEnvOr(t *testing.T) {
	key := "REEF_TEST_ENV_OR"
	os.Unsetenv(key)
	if got := envOr(key, "fallback"); got != "fallback" {
		t.Errorf("envOr unset = %q, want fallback", got)
	}
	t.Setenv(key, "explicit")
	if got := envOr(key, "fallback"); got != "explicit" {
		t.Errorf("envOr set = %q, want explicit", got)
	}
}

func TestEnvInt(t *testing.T) {
	key := "REEF_TEST_ENV_INT"
	os.Unsetenv(key)
	if got := envInt(key, 7); got != 7 {
		t.Errorf("envInt unset = %d, want 7", got)
	}
	t.Setenv(key, "42")
	if got := envInt(key, 7); got != 42 {
		t.Errorf("envInt valid = %d, want 42", got)
	}
	t.Setenv(key, "not-a-number")
	if got := envInt(key, 7); got != 7 {
		t.Errorf("envInt invalid = %d, want fallback 7", got)
	}
	t.Setenv(key, "100xyz")
	if got := envInt(key, 7); got != 7 {
		t.Errorf("envInt trailing garbage = %d, want fallback 7", got)
	}
}

func TestEnvInt64(t *testing.T) {
	key := "REEF_TEST_ENV_INT64"
	os.Unsetenv(key)
	if got := envInt64(key, 700); got != 700 {
		t.Errorf("envInt64 unset = %d, want 700", got)
	}
	t.Setenv(key, "9000000000")
	if got := envInt64(key, 700); got != 9000000000 {
		t.Errorf("envInt64 valid = %d, want 9000000000", got)
	}
	t.Setenv(key, "not-a-number")
	if got := envInt64(key, 700); got != 700 {
		t.Errorf("envInt64 invalid = %d, want fallback 700", got)
	}
	t.Setenv(key, "9000000000xyz")
	if got := envInt64(key, 700); got != 700 {
		t.Errorf("envInt64 trailing garbage = %d, want fallback 700", got)
	}
}

func TestEnvDuration(t *testing.T) {
	key := "REEF_TEST_ENV_DURATION"
	os.Unsetenv(key)
	if got := envDuration(key, 5*time.Second); got != 5*time.Second {
		t.Errorf("envDuration unset = %v, want 5s", got)
	}
	t.Setenv(key, "250ms")
	if got := envDuration(key, 5*time.Second); got != 250*time.Millisecond {
		t.Errorf("envDuration valid = %v, want 250ms", got)
	}
	t.Setenv(key, "not-a-duration")
	if got := envDuration(key, 5*time.Second); got != 5*time.Second {
		t.Errorf("envDuration invalid = %v, want fallback 5s", got)
	}
}

func TestEnvBool(t *testing.T) {
	key := "REEF_TEST_ENV_BOOL"
	os.Unsetenv(key)
	if got := envBool(key, true); got != true {
		t.Errorf("envBool unset = %v, want fallback true", got)
	}

	truthy := []string{"1", "true", "t", "yes", "y", "on", "TRUE", " Yes "}
	for _, v := range truthy {
		t.Setenv(key, v)
		if got := envBool(key, false); got != true {
			t.Errorf("envBool(%q) = %v, want true", v, got)
		}
	}

	falsy := []string{"0", "false", "f", "no", "n", "off", "FALSE"}
	for _, v := range falsy {
		t.Setenv(key, v)
		if got := envBool(key, true); got != false {
			t.Errorf("envBool(%q) = %v, want false", v, got)
		}
	}

	t.Setenv(key, "maybe")
	if got := envBool(key, true); got != true {
		t.Errorf("envBool unrecognized = %v, want fallback true", got)
	}
	t.Setenv(key, "maybe")
	if got := envBool(key, false); got != false {
		t.Errorf("envBool unrecognized = %v, want fallback false", got)
	}
}

func TestDefaultConfigUsesAdminApiTokenForAdminSeedRoutes(t *testing.T) {
	t.Setenv("REEF_ADMIN_API_BEARER_TOKEN", "")
	t.Setenv("ADMIN_API_TOKEN", "admin-token")
	cfg := defaultConfigFromEnv()

	if cfg.AdminAPIBearerToken != "admin-token" {
		t.Fatalf("AdminAPIBearerToken = %q, want admin-token", cfg.AdminAPIBearerToken)
	}
}
