package main

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"golang.org/x/oauth2"
)

type Session struct {
	Email  string   `json:"email"`
	Groups []string `json:"groups"`
	Exp    int64    `json:"exp"`
}

type OIDCAuth struct {
	provider  *oidc.Provider
	oauth2Cfg oauth2.Config
	verifier  *oidc.IDTokenVerifier
	gcm       cipher.AEAD
	config    *Config
}

func NewOIDCAuth(cfg *Config) (*OIDCAuth, error) {
	ctx := context.Background()

	// Use internal URL for discovery (avoids TLS hairpin through Traefik)
	discoveryURL := cfg.IssuerURL
	if cfg.InternalIssuer != "" {
		discoveryURL = cfg.InternalIssuer
	}

	provider, err := oidc.NewProvider(ctx, discoveryURL)
	if err != nil {
		return nil, fmt.Errorf("oidc provider: %w", err)
	}

	// AuthURL must use public URL (browser redirect), TokenURL stays internal (server-to-server)
	endpoint := provider.Endpoint()
	if cfg.InternalIssuer != "" {
		endpoint.AuthURL = strings.Replace(endpoint.AuthURL, cfg.InternalIssuer, cfg.IssuerURL, 1)
	}

	redirectURL := fmt.Sprintf("https://%s%s", cfg.AuthHost, cfg.CallbackPath)
	oauth2Cfg := oauth2.Config{
		ClientID: cfg.ClientID, ClientSecret: cfg.ClientSecret,
		Endpoint: endpoint, RedirectURL: redirectURL,
		Scopes: []string{oidc.ScopeOpenID, "email", "profile"},
	}

	// Verifier uses the public issuer URL for token validation
	verifier := provider.Verifier(&oidc.Config{
		ClientID: cfg.ClientID,
		// Skip issuer check if using internal discovery (issuer in token is the public URL)
		SkipIssuerCheck: cfg.InternalIssuer != "",
	})
	key := sha256.Sum256([]byte(cfg.Secret))
	block, _ := aes.NewCipher(key[:])
	gcm, _ := cipher.NewGCM(block)
	return &OIDCAuth{provider: provider, oauth2Cfg: oauth2Cfg, verifier: verifier, gcm: gcm, config: cfg}, nil
}

func (a *OIDCAuth) RedirectToLogin(w http.ResponseWriter, r *http.Request) {
	orig := fmt.Sprintf("https://%s%s", r.Header.Get("X-Forwarded-Host"), r.Header.Get("X-Forwarded-Uri"))
	if orig == "https://" {
		orig = fmt.Sprintf("https://%s/", a.config.CookieDomain)
	}
	state, _ := a.encrypt([]byte(orig))
	http.Redirect(w, r, a.oauth2Cfg.AuthCodeURL(state), http.StatusTemporaryRedirect)
}

func (a *OIDCAuth) HandleCallback(w http.ResponseWriter, r *http.Request) {
	code := r.URL.Query().Get("code")
	stateEnc := r.URL.Query().Get("state")
	if code == "" {
		http.Error(w, "Missing code", http.StatusBadRequest)
		return
	}
	token, err := a.oauth2Cfg.Exchange(r.Context(), code)
	if err != nil {
		http.Error(w, "Token exchange failed", http.StatusInternalServerError)
		return
	}
	rawIDToken, ok := token.Extra("id_token").(string)
	if !ok {
		http.Error(w, "Missing id_token", http.StatusInternalServerError)
		return
	}
	idToken, err := a.verifier.Verify(r.Context(), rawIDToken)
	if err != nil {
		http.Error(w, "Token verification failed", http.StatusInternalServerError)
		return
	}
	var claims struct {
		Email  string   `json:"email"`
		Groups []string `json:"groups"`
	}
	if err := idToken.Claims(&claims); err != nil {
		http.Error(w, "Claims extraction failed", http.StatusInternalServerError)
		return
	}
	session := Session{Email: claims.Email, Groups: claims.Groups, Exp: idToken.Expiry.Unix()}
	if err := a.setSessionCookie(w, &session); err != nil {
		http.Error(w, "Session creation failed", http.StatusInternalServerError)
		return
	}
	redirectURL := fmt.Sprintf("https://%s/", a.config.CookieDomain)
	if stateEnc != "" {
		if orig, err := a.decrypt(stateEnc); err == nil {
			redirectURL = string(orig)
		}
	}
	http.Redirect(w, r, redirectURL, http.StatusTemporaryRedirect)
}

func (a *OIDCAuth) GetSession(r *http.Request) (*Session, error) {
	cookie, err := r.Cookie(a.config.CookieName)
	if err != nil {
		return nil, fmt.Errorf("no session cookie")
	}
	data, err := a.decrypt(cookie.Value)
	if err != nil {
		return nil, fmt.Errorf("decrypt failed")
	}
	var s Session
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, fmt.Errorf("unmarshal failed")
	}
	if time.Now().Unix() > s.Exp {
		return nil, fmt.Errorf("session expired")
	}
	return &s, nil
}

func (a *OIDCAuth) setSessionCookie(w http.ResponseWriter, s *Session) error {
	data, _ := json.Marshal(s)
	enc, err := a.encrypt(data)
	if err != nil {
		return err
	}
	http.SetCookie(w, &http.Cookie{
		Name: a.config.CookieName, Value: enc, Domain: a.config.CookieDomain,
		Path: "/", HttpOnly: true, Secure: true, SameSite: http.SameSiteLaxMode, MaxAge: 86400,
	})
	return nil
}

func (a *OIDCAuth) ClearSessionCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name: a.config.CookieName, Value: "", Domain: a.config.CookieDomain,
		Path: "/", HttpOnly: true, Secure: true, MaxAge: -1,
	})
}

func (a *OIDCAuth) encrypt(pt []byte) (string, error) {
	nonce := make([]byte, a.gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}
	return base64.URLEncoding.EncodeToString(a.gcm.Seal(nonce, nonce, pt, nil)), nil
}

func (a *OIDCAuth) decrypt(enc string) ([]byte, error) {
	ct, err := base64.URLEncoding.DecodeString(enc)
	if err != nil {
		return nil, err
	}
	ns := a.gcm.NonceSize()
	if len(ct) < ns {
		return nil, fmt.Errorf("ciphertext too short")
	}
	return a.gcm.Open(nil, ct[:ns], ct[ns:], nil)
}

func (a *OIDCAuth) LogoutURL() string {
	return strings.TrimSuffix(a.config.IssuerURL, "/") + "/protocol/openid-connect/logout"
}

