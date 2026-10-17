package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strings"
)

func seedReferenceData(client *http.Client, cfg Config) error {
	headers := seedHeaders(cfg)
	if cfg.HasSessionConfig && len(cfg.MarketEquities) > 0 {
		for _, eq := range cfg.MarketEquities {
			if err := seedPOST(client, seedURL(cfg, "/reference/instruments"), map[string]string{
				"instrumentId": eq.InstrumentID,
				"symbol":       eq.Symbol,
			}, headers); err != nil {
				return err
			}
		}
	} else {
		if err := seedPOST(client, seedURL(cfg, "/reference/instruments"), map[string]string{
			"instrumentId": cfg.InstrumentID,
			"symbol":       cfg.InstrumentSymbol,
		}, headers); err != nil {
			return err
		}
	}
	for _, identity := range orderIdentities(cfg) {
		if err := seedPOST(client, seedURL(cfg, "/reference/participants"), map[string]string{
			"participantId": identity.ParticipantID,
			"name":          identity.ParticipantName,
		}, headers); err != nil {
			return err
		}
		if err := seedPOST(client, seedURL(cfg, "/reference/accounts"), map[string]string{
			"accountId":     identity.AccountID,
			"participantId": identity.ParticipantID,
		}, headers); err != nil {
			return err
		}
	}
	if err := seedOrderAuthorization(client, cfg, headers); err != nil {
		return err
	}
	return nil
}

func seedURL(cfg Config, legacyPath string) string {
	if cfg.UseApiV1 {
		return cfg.BaseURL + "/admin/v1" + legacyPath
	}
	return cfg.BaseURL + legacyPath
}

func seedHeaders(cfg Config) map[string]string {
	headers := map[string]string{}
	if cfg.UseApiV1 {
		if token := strings.TrimSpace(cfg.AdminAPIBearerToken); token != "" {
			headers["Authorization"] = "Bearer " + token
		}
		return headers
	}
	if cfg.LegacyInternalRoute {
		headers["X-Reef-Internal-Route"] = "true"
	}
	return headers
}

type orderIdentity struct {
	ParticipantID   string
	ParticipantName string
	AccountID       string
}

func orderIdentities(cfg Config) []orderIdentity {
	if !cfg.HasSessionConfig || len(cfg.SessionActors) == 0 {
		return []orderIdentity{defaultOrderIdentity(cfg)}
	}
	seen := map[string]orderIdentity{}
	for _, actor := range cfg.SessionActors {
		actorID := strings.TrimSpace(actor.ActorID)
		if actorID == "" {
			continue
		}
		identity := sessionActorOrderIdentity(actorID)
		seen[identity.ParticipantID] = identity
	}
	if len(seen) == 0 {
		return []orderIdentity{defaultOrderIdentity(cfg)}
	}
	out := make([]orderIdentity, 0, len(seen))
	for _, identity := range seen {
		out = append(out, identity)
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i].ParticipantID < out[j].ParticipantID
	})
	return out
}

func orderIdentityForActor(cfg Config, actorID string) (string, string) {
	if cfg.HasSessionConfig {
		normalizedActorID := strings.TrimSpace(actorID)
		for _, actor := range cfg.SessionActors {
			if strings.TrimSpace(actor.ActorID) == normalizedActorID && normalizedActorID != "" {
				identity := sessionActorOrderIdentity(normalizedActorID)
				return identity.ParticipantID, identity.AccountID
			}
		}
	}
	identity := defaultOrderIdentity(cfg)
	return identity.ParticipantID, identity.AccountID
}

func defaultOrderIdentity(cfg Config) orderIdentity {
	return orderIdentity{
		ParticipantID:   cfg.ParticipantID,
		ParticipantName: cfg.ParticipantName,
		AccountID:       cfg.AccountID,
	}
}

func sessionActorOrderIdentity(actorID string) orderIdentity {
	normalizedActorID := strings.TrimSpace(actorID)
	return orderIdentity{
		ParticipantID:   normalizedActorID + "-participant",
		ParticipantName: normalizedActorID,
		AccountID:       normalizedActorID + "-account",
	}
}

func seedOrderAuthorization(client *http.Client, cfg Config, internalHeaders map[string]string) error {
	if err := seedPOST(client, seedURL(cfg, "/auth/roles"), map[string]string{
		"roleId":      "order_trader",
		"permissions": "order.submit,order.cancel,order.modify",
	}, internalHeaders); err != nil {
		return err
	}
	for _, actorID := range orderActorIDs(cfg) {
		if err := seedPOST(client, seedURL(cfg, "/auth/actor-roles"), map[string]string{
			"actorId": actorID,
			"roleId":  "order_trader",
		}, internalHeaders); err != nil {
			return err
		}
	}
	return nil
}

func orderActorIDs(cfg Config) []string {
	seen := map[string]struct{}{}
	if cfg.HasSessionConfig {
		for _, actor := range cfg.SessionActors {
			actorID := strings.TrimSpace(actor.ActorID)
			if actorID != "" {
				seen[actorID] = struct{}{}
			}
		}
	}
	if len(seen) == 0 {
		workers := cfg.Workers
		if workers <= 0 {
			workers = 1
		}
		for workerID := 0; workerID < workers; workerID++ {
			seen[fmt.Sprintf("bot-%d", workerID)] = struct{}{}
		}
	}
	out := make([]string, 0, len(seen))
	for actorID := range seen {
		out = append(out, actorID)
	}
	sort.Strings(out)
	return out
}

func seedPOST(client *http.Client, url string, payload map[string]string, headers map[string]string) error {
	status, body, err := doPOST(client, url, payload, headers)
	if err != nil {
		return err
	}
	if status < 200 || status >= 300 {
		return fmt.Errorf("seed POST %s failed (%d): %s", url, status, strings.TrimSpace(string(body)))
	}
	return nil
}

func doPOST(client *http.Client, url string, payload map[string]string, headers map[string]string) (int, []byte, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return 0, nil, err
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(resp.Body)
	return resp.StatusCode, respBody, err
}
