package main

import "strings"

// CheckAccess verifies if user groups are allowed to access a host.
// Returns (allowed, extraHeaders).
func CheckAccess(rules *AuthzRules, host string, userGroups []string) (bool, map[string]string) {
	// Strip port if present
	if idx := strings.IndexByte(host, ':'); idx != -1 {
		host = host[:idx]
	}

	rule, exists := rules.Rules[host]
	if !exists {
		return rules.DefaultPolicy == "allow", nil
	}

	// Check if any user group is in the allowed list
	groupSet := make(map[string]bool, len(userGroups))
	for _, g := range userGroups {
		groupSet[g] = true
	}

	var matchedGroup string
	for _, allowed := range rule.AllowedGroups {
		if groupSet[allowed] {
			matchedGroup = allowed
			break
		}
	}

	if matchedGroup == "" {
		return false, nil
	}

	// Collect extra headers for the matched group (highest priority)
	headers := make(map[string]string)
	if rule.Headers != nil {
		if groupHeaders, ok := rule.Headers[matchedGroup]; ok {
			for k, v := range groupHeaders {
				headers[k] = v
			}
		}
	}

	return true, headers
}

