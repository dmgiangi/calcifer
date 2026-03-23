package main

import (
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

// Config holds all configuration for the service.
type Config struct {
	// OIDC / OAuth2
	IssuerURL       string
	InternalIssuer  string // optional: internal URL for OIDC discovery (avoids TLS hairpin)
	ClientID        string
	ClientSecret    string
	Secret          string // encryption key for session cookie
	AuthHost        string // e.g. auth.dmgiangi.dev
	CookieDomain    string // e.g. dmgiangi.dev
	CookieName      string
	CallbackPath    string
	Port            int

	// Authorization rules
	Rules AuthzRules
}

// AuthzRules loaded from YAML config.
type AuthzRules struct {
	DefaultPolicy string              `yaml:"default_policy"`
	Rules         map[string]HostRule `yaml:"rules"`
}

// HostRule defines access for a specific host.
type HostRule struct {
	AllowedGroups []string                       `yaml:"allowed_groups"`
	Headers       map[string]map[string]string   `yaml:"headers"`
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func LoadConfig() (*Config, error) {
	cfg := &Config{
		IssuerURL:      envOrDefault("OIDC_ISSUER_URL", ""),
		InternalIssuer: envOrDefault("OIDC_INTERNAL_ISSUER_URL", ""),
		ClientID:       envOrDefault("OIDC_CLIENT_ID", "calcifer-gateway"),
		ClientSecret: envOrDefault("OIDC_CLIENT_SECRET", ""),
		Secret:       envOrDefault("SECRET", ""),
		AuthHost:     envOrDefault("AUTH_HOST", ""),
		CookieDomain: envOrDefault("COOKIE_DOMAIN", ""),
		CookieName:   envOrDefault("COOKIE_NAME", "_calcifer_auth"),
		CallbackPath: envOrDefault("CALLBACK_PATH", "/_oauth"),
		Port:         4181,
	}

	if cfg.IssuerURL == "" {
		return nil, fmt.Errorf("OIDC_ISSUER_URL is required")
	}
	if cfg.ClientSecret == "" {
		return nil, fmt.Errorf("OIDC_CLIENT_SECRET is required")
	}
	if cfg.Secret == "" {
		return nil, fmt.Errorf("SECRET is required")
	}
	if cfg.AuthHost == "" {
		return nil, fmt.Errorf("AUTH_HOST is required")
	}
	if cfg.CookieDomain == "" {
		return nil, fmt.Errorf("COOKIE_DOMAIN is required")
	}

	// Load authorization rules
	rulesPath := envOrDefault("RULES_PATH", "/etc/calcifer-auth/auth-config.yaml")
	rules, err := loadRules(rulesPath)
	if err != nil {
		return nil, fmt.Errorf("loading rules from %s: %w", rulesPath, err)
	}
	cfg.Rules = *rules

	return cfg, nil
}

func loadRules(path string) (*AuthzRules, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	var rules AuthzRules
	if err := yaml.Unmarshal(data, &rules); err != nil {
		return nil, err
	}

	if rules.DefaultPolicy == "" {
		rules.DefaultPolicy = "deny"
	}

	return &rules, nil
}

