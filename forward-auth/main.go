package main

import (
	"fmt"
	"log"
	"net/http"
	"strings"
)

func main() {
	cfg, err := LoadConfig()
	if err != nil {
		log.Fatalf("Config error: %v", err)
	}

	auth, err := NewOIDCAuth(cfg)
	if err != nil {
		log.Fatalf("OIDC init error: %v", err)
	}

	log.Printf("calcifer-auth starting on :%d", cfg.Port)
	log.Printf("Issuer: %s", cfg.IssuerURL)
	log.Printf("Auth host: %s | Cookie domain: %s", cfg.AuthHost, cfg.CookieDomain)
	log.Printf("Default policy: %s", cfg.Rules.DefaultPolicy)
	log.Printf("Rules for: %s", strings.Join(ruleHosts(cfg), ", "))

	mux := http.NewServeMux()

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, "OK")
	})

	mux.HandleFunc(cfg.CallbackPath, func(w http.ResponseWriter, r *http.Request) {
		auth.HandleCallback(w, r)
	})

	mux.HandleFunc("/logout", func(w http.ResponseWriter, r *http.Request) {
		auth.ClearSessionCookie(w)
		http.Redirect(w, r, auth.LogoutURL(), http.StatusTemporaryRedirect)
	})

	// ForwardAuth handler
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		fwdHost := r.Header.Get("X-Forwarded-Host")
		fwdUri := r.Header.Get("X-Forwarded-Uri")

		// Handle callback forwarded via Traefik
		if fwdHost == cfg.AuthHost && strings.HasPrefix(fwdUri, cfg.CallbackPath) {
			auth.HandleCallback(w, r)
			return
		}

		// Check session
		session, err := auth.GetSession(r)
		if err != nil {
			auth.RedirectToLogin(w, r)
			return
		}

		// Check authorization
		host := fwdHost
		if host == "" {
			host = r.Host
		}
		allowed, extraHeaders := CheckAccess(&cfg.Rules, host, session.Groups)
		if !allowed {
			log.Printf("DENIED %s -> %s (groups: %v)", session.Email, host, session.Groups)
			http.Error(w, "Forbidden: insufficient group membership", http.StatusForbidden)
			return
		}

		// Authorized
		w.Header().Set("X-Forwarded-User", session.Email)
		w.Header().Set("X-Forwarded-Groups", strings.Join(session.Groups, ","))
		for k, v := range extraHeaders {
			w.Header().Set(k, v)
		}
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, "OK")
	})

	log.Fatal(http.ListenAndServe(fmt.Sprintf(":%d", cfg.Port), mux))
}

func ruleHosts(cfg *Config) []string {
	hosts := make([]string, 0, len(cfg.Rules.Rules))
	for h := range cfg.Rules.Rules {
		hosts = append(hosts, h)
	}
	return hosts
}
