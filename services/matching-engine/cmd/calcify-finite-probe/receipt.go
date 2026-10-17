package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/IBM/sarama"
)

type receipt struct {
	Schema string `json:"schema"`
	Mode   string `json:"mode"`
	Status string `json:"status"`
	Code   string `json:"code,omitempty"`
	Data   any    `json:"data,omitempty"`
}

func errorReceipt(q request, err error) receipt {
	r := receipt{Schema: "calcify-finite-probe-result-v1", Mode: q.Mode, Status: "error", Code: "operation_failed"}
	var historyError *historyPhaseError
	if q.Mode == "history" && errors.As(err, &historyError) {
		switch historyError.phase {
		case "offset_lookup", "lost_genesis", "leader_lookup", "fetch_request", "missing_block", "kafka_error", "unstable_cut", "missing_batch", "partial_batch", "unsupported_codec", "record_budget", "no_progress", "fetch_budget":
			r.Code = "history_" + historyError.phase
			r.Data = map[string]any{"phase": historyError.phase, "fetchCount": historyError.fetchCount, "position": strconv.FormatInt(historyError.position, 10), "readCommittedEndOffset": strconv.FormatInt(historyError.end, 10), "recordCount": historyError.recordCount, "payloadBytes": historyError.payloadBytes, "codec": historyError.codec}
			var k sarama.KError
			if errors.As(err, &k) {
				r.Data.(map[string]any)["kafkaErrorCode"] = int(k)
			}
		}
	}
	var phase *seedPhaseError
	if errors.As(err, &phase) {
		r.Data = map[string]any{"phase": phase.phase, "commitMayHaveStarted": phase.commitAttempted}
		var k sarama.KError
		if errors.As(err, &k) {
			r.Code = strconv.Itoa(int(k))
		}
	}
	for _, k := range []sarama.KError{sarama.ErrTopicAuthorizationFailed, sarama.ErrGroupAuthorizationFailed, sarama.ErrClusterAuthorizationFailed, sarama.ErrTransactionalIDAuthorizationFailed} {
		if errors.Is(err, k) {
			r.Status = "denied"
			r.Code = strconv.Itoa(int(k))
			return r
		}
	}
	if errors.Is(err, sarama.ErrSASLAuthenticationFailed) {
		r.Status = "authentication"
		r.Code = strconv.Itoa(int(sarama.ErrSASLAuthenticationFailed))
	}
	var pe sarama.ProducerErrors
	if errors.As(err, &pe) {
		for _, e := range pe {
			return errorReceipt(q, e.Err)
		}
	}
	if q.Mode == "matcher-observe" {
		for prefix, code := range map[string]string{"finite actual topic identity/routing mismatch": "topic_identity_mismatch", "finite canonical history missing genesis prefix": "history_missing_prefix", "finite actual cluster identity mismatch": "cluster_identity_mismatch", "finite canonical history must retain": "history_configuration_mismatch", "finite managed changelog policy mismatch": "changelog_configuration_mismatch", "finite topic record limit too small": "record_limit_mismatch"} {
			if strings.HasPrefix(err.Error(), prefix) {
				r.Status = "refused"
				r.Code = code
				return r
			}
		}
	}
	return r // Never return arbitrary server error text: it can echo credentials.
}

func httpClient() *http.Client {
	return &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return errors.New("redirect refused") }}
}

func requestHTTP(ctx context.Context, q request, url string, body []byte, basic bool) (any, error) {
	r, err := http.NewRequestWithContext(ctx, q.Method, url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	r.Header.Set("Content-Type", "application/json")
	if basic {
		r.SetBasicAuth(q.Auth.Username, q.Auth.Password)
	} else if q.Bearer != "" {
		r.Header.Set("Authorization", "Bearer "+q.Bearer)
	}
	for k, v := range q.Headers {
		r.Header.Set(k, v)
	}
	startedAt := time.Now()
	resp, err := httpClient().Do(r)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	limit := int64(32768)
	if q.Mode == "admin-read" {
		limit = 262144
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil || int64(len(b)) > limit {
		return nil, errors.New("HTTP response cap")
	}
	// Public HTTP bodies are retained only after exact credential spill check.
	for _, secret := range []string{q.Auth.Password, q.Bearer} {
		if secret != "" && bytes.Contains(b, []byte(secret)) {
			return nil, errors.New("credential spill")
		}
	}
	return map[string]any{"httpStatus": resp.StatusCode, "bodyBase64": base64.StdEncoding.EncodeToString(b), "bodySha256": digest(b), "bytes": len(b), "transportStartedAt": startedAt.UTC().Format(time.RFC3339Nano), "transportCompletedAt": time.Now().UTC().Format(time.RFC3339Nano), "transportDurationNanos": fmt.Sprint(time.Since(startedAt).Nanoseconds()), "measurementScope": "HTTP transport start through complete bounded response body; excludes Docker/controller startup"}, nil
}

func digest(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }

const adminReadPath = "/v1/cluster_config?include_defaults=true"

// Return only fixture safety fields; missing properties remain missing and fail supervisor guards.
func projectAdminConfig(raw []byte) (map[string]json.RawMessage, error) {
	var values map[string]json.RawMessage
	if json.Unmarshal(raw, &values) != nil || values == nil {
		return nil, errors.New("invalid admin config")
	}
	public := map[string]json.RawMessage{}
	for _, key := range []string{"kafka_enable_authorization", "sasl_mechanisms", "admin_api_require_auth", "http_authentication", "superusers", "write_caching_default", "auto_create_topics_enabled", "log_segment_size", "log_segment_size_min", "transaction_coordinator_log_segment_size", "group_topic_partitions", "transaction_coordinator_partitions", "compacted_log_segment_size", "id_allocator_log_capacity"} {
		if v, ok := values[key]; ok {
			public[key] = v
		}
	}
	return public, nil
}

func encodeReceipt(r receipt, max int) ([]byte, error) {
	b, err := json.Marshal(r)
	if err != nil || len(b) > max {
		return nil, errors.New("public output cap")
	}
	return append(b, '\n'), nil
}
