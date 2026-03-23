package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

func main() {
	cfg, err := LoadConfig()
	if err != nil {
		log.Fatalf("Config error: %v", err)
	}

	var auth *OIDCAuth
	for attempt := 1; ; attempt++ {
		auth, err = NewOIDCAuth(cfg)
		if err == nil {
			break
		}
		if attempt >= 30 {
			log.Fatalf("OIDC init failed after %d attempts: %v", attempt, err)
		}
		wait := time.Duration(min(attempt*2, 30)) * time.Second
		log.Printf("OIDC init attempt %d failed: %v (retrying in %s)", attempt, err, wait)
		time.Sleep(wait)
	}

	// Initialize OpenTelemetry
	otelEndpoint := envOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "alloy:4317")
	var tel *Telemetry
	tel, err = InitTelemetry(context.Background(), otelEndpoint)
	if err != nil {
		log.Printf("WARN: OTel init failed (continuing without telemetry): %v", err)
	} else {
		defer tel.Shutdown(context.Background())
	}

	log.Printf("calcifer-auth starting on :%d", cfg.Port)
	log.Printf("Issuer: %s", cfg.IssuerURL)
	log.Printf("Auth host: %s | Cookie domain: %s", cfg.AuthHost, cfg.CookieDomain)
	log.Printf("Default policy: %s", cfg.Rules.DefaultPolicy)
	log.Printf("Rules for: %s", strings.Join(ruleHosts(cfg), ", "))
	log.Printf("OTLP endpoint: %s", otelEndpoint)

	mux := http.NewServeMux()

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, "OK")
	})

	mux.HandleFunc(cfg.CallbackPath, func(w http.ResponseWriter, r *http.Request) {
		ctx, span := startSpan(tel, r.Context(), "oauth_callback")
		defer span.End()
		if tel != nil {
			tel.CallbacksTotal.Add(ctx, 1)
		}
		auth.HandleCallback(w, r.WithContext(ctx))
	})

	mux.HandleFunc("/logout", func(w http.ResponseWriter, r *http.Request) {
		auth.ClearSessionCookie(w)
		http.Redirect(w, r, auth.LogoutURL(), http.StatusTemporaryRedirect)
	})

	// ForwardAuth handler
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		fwdHost := r.Header.Get("X-Forwarded-Host")
		fwdUri := r.Header.Get("X-Forwarded-Uri")

		ctx, span := startSpan(tel, r.Context(), "forward_auth",
			trace.WithAttributes(
				attribute.String("http.host", fwdHost),
				attribute.String("http.uri", fwdUri),
			),
		)
		defer func() {
			span.End()
			if tel != nil {
				tel.RequestDuration.Record(ctx, float64(time.Since(start).Milliseconds()))
			}
		}()

		if tel != nil {
			tel.RequestsTotal.Add(ctx, 1)
		}

		// Handle callback forwarded via Traefik
		if fwdHost == cfg.AuthHost && strings.HasPrefix(fwdUri, cfg.CallbackPath) {
			auth.HandleCallback(w, r.WithContext(ctx))
			return
		}

		// Check session
		session, err := auth.GetSession(r)
		if err != nil {
			span.SetAttributes(attribute.String("auth.action", "login_redirect"))
			if tel != nil {
				tel.LoginRedirects.Add(ctx, 1)
			}
			auth.RedirectToLogin(w, r)
			return
		}

		span.SetAttributes(
			attribute.String("auth.user", session.Email),
			attribute.StringSlice("auth.groups", session.Groups),
		)

		// Check authorization
		host := fwdHost
		if host == "" {
			host = r.Host
		}
		allowed, extraHeaders := CheckAccess(&cfg.Rules, host, session.Groups)
		if !allowed {
			log.Printf("DENIED %s -> %s (groups: %v)", session.Email, host, session.Groups)
			span.SetAttributes(attribute.String("auth.result", "denied"))
			span.SetStatus(codes.Error, "forbidden")
			if tel != nil {
				tel.DeniedTotal.Add(ctx, 1)
			}
			http.Error(w, "Forbidden: insufficient group membership", http.StatusForbidden)
			return
		}

		// Authorized
		span.SetAttributes(attribute.String("auth.result", "authorized"))
		if tel != nil {
			tel.AuthorizedTotal.Add(ctx, 1)
		}
		w.Header().Set("X-Forwarded-User", session.Email)
		w.Header().Set("X-Forwarded-Groups", strings.Join(session.Groups, ","))
		for k, v := range extraHeaders {
			w.Header().Set(k, v)
		}
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, "OK")
	})

	// Graceful shutdown
	srv := &http.Server{Addr: fmt.Sprintf(":%d", cfg.Port), Handler: mux}
	go func() {
		sigCh := make(chan os.Signal, 1)
		signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT)
		<-sigCh
		log.Println("Shutting down...")
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if tel != nil {
			tel.Shutdown(ctx)
		}
		_ = srv.Shutdown(ctx)
	}()

	log.Fatal(srv.ListenAndServe())
}

func startSpan(tel *Telemetry, ctx context.Context, name string, opts ...trace.SpanStartOption) (context.Context, trace.Span) {
	if tel != nil {
		return tel.Tracer.Start(ctx, name, opts...)
	}
	return ctx, trace.SpanFromContext(ctx)
}

func ruleHosts(cfg *Config) []string {
	hosts := make([]string, 0, len(cfg.Rules.Rules))
	for h := range cfg.Rules.Rules {
		hosts = append(hosts, h)
	}
	return hosts
}
