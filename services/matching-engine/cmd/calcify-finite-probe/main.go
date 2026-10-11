// calcify-finite-probe is a fixture-only, bounded stdin/stdout client. Secrets
// never belong in argv or public receipts. It cannot authorize finite startup.
package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode/utf8"

	"github.com/IBM/sarama"
	"github.com/dills122/reef/services/matching-engine/internal/streamdirect"
)

const inputLimit = 32768
const ordinaryOutputLimit = 65536
const historyOutputLimit = 2 * 1024 * 1024

type credential struct {
	Username string `json:"username"`
	Password string `json:"password"`
}
type user struct {
	Username string `json:"username"`
	Password string `json:"password"`
}
type grant struct {
	Principal string `json:"principal"`
	Kind      string `json:"kind"`
	Name      string `json:"name"`
	Operation string `json:"operation"`
	Pattern   string `json:"pattern,omitempty"`
}
type request struct {
	Schema  string            `json:"schema"`
	Mode    string            `json:"mode"`
	Prefix  string            `json:"prefix"`
	Auth    credential        `json:"auth"`
	Users   []user            `json:"users,omitempty"`
	Topics  []string          `json:"topics,omitempty"`
	Topic   string            `json:"topic,omitempty"`
	TopicID string            `json:"topicId,omitempty"`
	Adverse string            `json:"adverse,omitempty"`
	Opening json.RawMessage   `json:"opening,omitempty"`
	Digest  string            `json:"digest,omitempty"`
	Group   string            `json:"group,omitempty"`
	Config  map[string]string `json:"config,omitempty"`
	Grants  []grant           `json:"grants,omitempty"`
	Offset  *int64            `json:"offset,omitempty"`
	Value   string            `json:"value,omitempty"`
	Key     string            `json:"key,omitempty"`
	Host    string            `json:"host,omitempty"`
	Method  string            `json:"method,omitempty"`
	Path    string            `json:"path,omitempty"`
	Bearer  string            `json:"bearer,omitempty"`
	Headers map[string]string `json:"headers,omitempty"`
	Body    json.RawMessage   `json:"body,omitempty"`
}
type receipt struct {
	Schema string `json:"schema"`
	Mode   string `json:"mode"`
	Status string `json:"status"`
	Code   string `json:"code,omitempty"`
	Data   any    `json:"data,omitempty"`
}

var ownedName = regexp.MustCompile(`^calcify-o2-[a-z0-9-]{1,64}$`)

func uniqueJSON(d *json.Decoder) error {
	t, err := d.Token()
	if err != nil {
		return err
	}
	if delim, ok := t.(json.Delim); ok {
		switch delim {
		case '{':
			seen := map[string]bool{}
			for d.More() {
				k, err := d.Token()
				if err != nil {
					return err
				}
				name, ok := k.(string)
				if !ok || seen[name] {
					return errors.New("duplicate field")
				}
				seen[name] = true
				if err := uniqueJSON(d); err != nil {
					return err
				}
			}
		case '[':
			for d.More() {
				if err := uniqueJSON(d); err != nil {
					return err
				}
			}
		default:
			return errors.New("invalid delimiter")
		}
		_, err = d.Token()
		return err
	}
	return nil
}
func decode(r io.Reader) (request, error) {
	var q request
	b, err := io.ReadAll(io.LimitReader(r, inputLimit+1))
	if err != nil || len(b) > inputLimit || !utf8.Valid(b) {
		return q, errors.New("invalid input size/encoding")
	}
	d := json.NewDecoder(bytes.NewReader(b))
	d.UseNumber()
	if err := uniqueJSON(d); err != nil {
		return q, err
	}
	if _, err := d.Token(); err != io.EOF {
		return q, errors.New("trailing input")
	}
	d = json.NewDecoder(bytes.NewReader(b))
	d.DisallowUnknownFields()
	if err := d.Decode(&q); err != nil {
		return q, err
	}
	if err := validate(q); err != nil {
		return q, err
	}
	return q, nil
}
func validate(q request) error {
	if q.Schema != "calcify-finite-probe-v1" || !ownedName.MatchString(q.Prefix) {
		return errors.New("invalid schema/prefix")
	}
	for _, c := range []credential{q.Auth} {
		if len(c.Username) > 96 || len(c.Password) > 256 || strings.ContainsAny(c.Username, "\r\n\x00") {
			return errors.New("invalid credentials")
		}
	}
	if len(q.Users) > 8 || len(q.Topics) > 6 || len(q.Grants) > 48 || len(q.Config) > 16 || len(q.Headers) > 8 {
		return errors.New("collection cap")
	}
	for _, name := range append(append([]string{}, q.Topics...), q.Topic, q.Group) {
		if name != "" && (len(name) > 192 || !strings.HasPrefix(name, q.Prefix+"-") || strings.ContainsAny(name, " \r\n\x00")) {
			return errors.New("resource outside fixture")
		}
	}
	for _, u := range q.Users {
		if !strings.HasPrefix(u.Username, q.Prefix+"-") || len(u.Username) > 96 || len(u.Password) < 16 || len(u.Password) > 256 {
			return errors.New("invalid user")
		}
	}
	for _, g := range q.Grants {
		if !strings.HasPrefix(g.Principal, "User:"+q.Prefix+"-") || !strings.HasPrefix(g.Name, q.Prefix+"-") || len(g.Name) > 192 {
			return errors.New("ACL outside fixture")
		}
		if _, ok := kinds[g.Kind]; !ok {
			return errors.New("invalid resource kind")
		}
		if g.Pattern != "" && (g.Pattern != "prefixed" || g.Kind != "transactional-id" || g.Name != q.Prefix+"-capture-") {
			return errors.New("unapproved ACL prefix")
		}
		if _, ok := operations[g.Operation]; !ok {
			return errors.New("invalid operation")
		}
	}
	if q.Offset != nil && *q.Offset < 0 {
		return errors.New("negative offset")
	}
	for k, v := range q.Config {
		if !configKeys[k] || len(v) > 128 {
			return errors.New("unapproved config")
		}
	}
	switch q.Mode {
	case "users", "admin-auth", "admin-read", "admin-denied", "authenticate", "sasl-empty", "unauthenticated-state", "metadata", "create-topics", "grant", "acls", "group-offset", "seed-group", "history", "range", "wire-fit", "publish", "delete-canary", "alter-canary", "recreate-source", "truncate-command-genesis", "matcher-observe", "grpc-refused", "http":
	default:
		return errors.New("invalid mode")
	}
	if (q.Mode == "sasl-empty" || q.Mode == "unauthenticated-state") && (q.Auth.Username != "" || q.Auth.Password != "" || len(q.Topics) != 0 || q.Topic != "") {
		return errors.New("raw SASL probe requires empty credentials and no topic")
	}
	if q.Mode == "http" {
		if q.Host != "runtime:8080" && q.Host != "matcher:8081" {
			return errors.New("unapproved HTTP host")
		}
		if q.Method != "GET" && q.Method != "POST" {
			return errors.New("unapproved HTTP method")
		}
		allowed := strings.HasPrefix(q.Path, "/internal/calcify/finite-") || q.Path == "/api/v1/orders/submit" || q.Path == "/api/v1/orders/modify" || q.Path == "/api/v1/orders/cancel" || q.Path == "/orders/submit" || q.Path == "/orders/modify" || q.Path == "/orders/cancel" || q.Path == "/health" || q.Path == "/internal/stream-direct/stats"
		if !allowed || len(q.Path) > 512 || strings.ContainsAny(q.Path, "\r\n") || len(q.Bearer) > 256 || len(q.Body) > 8192 {
			return errors.New("unapproved HTTP request")
		}
		for k, v := range q.Headers {
			if k != "Idempotency-Key" && k != "X-Client-Id" && k != "Cookie" {
				return errors.New("unapproved HTTP header")
			}
			if len(v) > 256 || strings.ContainsAny(v, "\r\n") {
				return errors.New("invalid header")
			}
		}
	}
	if (q.Mode == "delete-canary" || q.Mode == "alter-canary") && q.Topic != q.Prefix+"-canary" {
		return errors.New("canary only")
	}
	if q.Mode == "wire-fit" && q.Topic != q.Prefix+"-state-fit" {
		return errors.New("state-fit canary only")
	}
	if q.Mode == "recreate-source" || q.Mode == "truncate-command-genesis" {
		if q.Auth.Username != q.Prefix+"-operator" || !regexp.MustCompile(`^[A-Za-z0-9_-]{22}$`).MatchString(q.TopicID) || q.TopicID == "AAAAAAAAAAAAAAAAAAAAAA" {
			return errors.New("exact adverse operator/UUID required")
		}
		if q.Mode == "recreate-source" && (q.Adverse != "topic-identity" || q.Topic != q.Prefix+"-source") {
			return errors.New("fresh source adverse case only")
		}
		if q.Mode == "truncate-command-genesis" && (q.Adverse != "command-history" || q.Topic != q.Prefix+"-commands" || q.Offset == nil || *q.Offset != 1) {
			return errors.New("fixed command genesis trim only")
		}
	}
	if q.Mode == "matcher-observe" {
		if _, err := observedOpening(q); err != nil {
			return err
		}
	}
	return nil
}
func observedOpening(q request) (streamdirect.FiniteOpening, error) {
	var o streamdirect.FiniteOpening
	if q.Auth.Username != q.Prefix+"-matcher" || len(q.Opening) > 16384 {
		return o, errors.New("exact matcher observation scope")
	}
	d := json.NewDecoder(bytes.NewReader(q.Opening))
	d.DisallowUnknownFields()
	if e := d.Decode(&o); e != nil {
		return o, e
	}
	h, e := o.Digest()
	if e != nil || h != q.Digest || o.RunID != q.Prefix {
		return o, errors.New("immutable opening mismatch")
	}
	names := []string{q.Prefix + "-commands", q.Prefix + "-source", q.Prefix + "-capture", q.Prefix + "-ownership", q.Prefix + "-capture-finite-lifecycle-changelog"}
	for i, t := range o.Topics {
		if t.Name != names[i] {
			return o, errors.New("topic outside fixture")
		}
	}
	return o, nil
}

var configKeys = map[string]bool{"retention.ms": true, "retention.bytes": true, "segment.bytes": true, "max.message.bytes": true, "cleanup.policy": true, "write.caching": true, "compression.type": true, "redpanda.remote.read": true, "redpanda.remote.write": true}
var kinds = map[string]sarama.AclResourceType{"topic": sarama.AclResourceTopic, "group": sarama.AclResourceGroup, "transactional-id": sarama.AclResourceTransactionalID}
var operations = map[string]sarama.AclOperation{"read": sarama.AclOperationRead, "write": sarama.AclOperationWrite, "describe": sarama.AclOperationDescribe, "describe-configs": sarama.AclOperationDescribeConfigs}

func kafkaConfig(q request) *sarama.Config {
	c := sarama.NewConfig()
	c.Version = sarama.V2_8_0_0
	c.ClientID = q.Prefix + "-probe"
	c.Metadata.AllowAutoTopicCreation = false
	c.Metadata.Retry.Max = 0
	c.Metadata.Timeout = 5 * time.Second
	c.Net.DialTimeout = 5 * time.Second
	c.Net.ReadTimeout = 5 * time.Second
	c.Net.WriteTimeout = 5 * time.Second
	c.Admin.Timeout = 5 * time.Second
	c.Admin.Retry.Max = 0
	c.Net.SASL.Enable = true
	c.Net.SASL.Mechanism = sarama.SASLTypePlaintext
	c.Net.SASL.User = q.Auth.Username
	c.Net.SASL.Password = q.Auth.Password
	c.Producer.Return.Successes = true
	c.Producer.RequiredAcks = sarama.WaitForAll
	c.Producer.Retry.Max = 0
	c.Producer.Partitioner = sarama.NewManualPartitioner
	c.Producer.MaxMessageBytes = 8192
	c.Producer.Compression = sarama.CompressionNone
	c.Consumer.IsolationLevel = sarama.ReadCommitted
	c.Consumer.Fetch.Max = 8 * 1024 * 1024
	c.Consumer.Fetch.Default = 2 * 1024 * 1024
	c.Consumer.MaxWaitTime = 250 * time.Millisecond
	if q.Mode == "wire-fit" {
		c.Producer.MaxMessageBytes = 6307840 + 1023
		c.Consumer.Fetch.Max = 6307840 + 1024
		c.Producer.Idempotent = true
		c.Net.MaxOpenRequests = 1
		c.Producer.Retry.Max = 1
		c.Producer.Transaction.ID = q.Prefix + "-state-fit-tx"
	}
	return c
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

// rawSaslProbe bypasses Sarama's local empty-credential validation only for
// two fixed fixture security probes. Raw server messages/authbytes stay private.
func rawSaslProbe(conn net.Conn, q request) (int16, map[string]any, error) {
	stages := []map[string]any{}
	if q.Mode == "sasl-empty" {
		body := append([]byte{0, 5}, []byte("PLAIN")...)
		raw, err := saslExchange(conn, 17, 1, 1, q.Prefix+"-probe", body)
		if err != nil {
			return 0, nil, err
		}
		code, err := decodeSaslHandshake(raw)
		if err != nil {
			return 0, nil, err
		}
		if code != 0 {
			return 0, nil, sarama.KError(code)
		}
		stages = append(stages, saslStage(17, 1, 1, raw, code))
	}
	// PLAIN authzid/authcid/password are all empty: exactly two NUL bytes.
	raw, err := saslExchange(conn, 36, 0, 2, q.Prefix+"-probe", []byte{0, 0, 0, 2, 0, 0})
	if err != nil {
		return 0, nil, err
	}
	code, err := decodeSaslAuthenticate(raw)
	if err != nil {
		return 0, nil, err
	}
	stages = append(stages, saslStage(36, 0, 2, raw, code))
	scope := "Empty PLAIN credential authentication"
	if q.Mode == "unauthenticated-state" {
		scope = "Unauthenticated out-of-sequence SASL state; no canonical READ/WRITE claim"
	}
	return code, map[string]any{"stages": stages, "scope": scope}, nil
}
func saslStage(api, version int16, correlation int32, raw []byte, code int16) map[string]any {
	hash := sha256.Sum256(raw)
	return map[string]any{"apiKey": api, "apiVersion": version, "correlationId": correlation, "brokerErrorCode": code, "responseBodyBytes": len(raw), "responseBodySha256": hex.EncodeToString(hash[:])}
}
func saslExchange(conn net.Conn, api, version int16, correlation int32, clientID string, body []byte) ([]byte, error) {
	if len(clientID) > 128 || 10+len(clientID)+len(body) > 32768 {
		return nil, errors.New("SASL request cap")
	}
	request := make([]byte, 4+10+len(clientID)+len(body))
	binary.BigEndian.PutUint32(request[:4], uint32(len(request)-4))
	binary.BigEndian.PutUint16(request[4:6], uint16(api))
	binary.BigEndian.PutUint16(request[6:8], uint16(version))
	binary.BigEndian.PutUint32(request[8:12], uint32(correlation))
	binary.BigEndian.PutUint16(request[12:14], uint16(len(clientID)))
	copy(request[14:], clientID)
	copy(request[14+len(clientID):], body)
	for remaining := request; len(remaining) > 0; {
		n, err := conn.Write(remaining)
		if err != nil {
			return nil, err
		}
		if n <= 0 {
			return nil, io.ErrShortWrite
		}
		remaining = remaining[n:]
	}
	var header [4]byte
	if _, err := io.ReadFull(conn, header[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(header[:])
	if n < 4 || n > 32768 {
		return nil, errors.New("SASL response cap")
	}
	raw := make([]byte, int(n))
	if _, err := io.ReadFull(conn, raw); err != nil {
		return nil, err
	}
	if int32(binary.BigEndian.Uint32(raw[:4])) != correlation {
		return nil, errors.New("SASL correlation mismatch")
	}
	return raw[4:], nil
}
func decodeSaslHandshake(raw []byte) (int16, error) {
	reader := bytes.NewReader(raw)
	var code int16
	var count int32
	if binary.Read(reader, binary.BigEndian, &code) != nil || binary.Read(reader, binary.BigEndian, &count) != nil || count < 0 || count > 16 {
		return 0, errors.New("SASL handshake shape")
	}
	plain := false
	for i := int32(0); i < count; i++ {
		var size int16
		if binary.Read(reader, binary.BigEndian, &size) != nil || size < 0 || size > 64 || int(size) > reader.Len() {
			return 0, errors.New("SASL mechanism shape")
		}
		value := make([]byte, int(size))
		if _, err := io.ReadFull(reader, value); err != nil {
			return 0, err
		}
		if string(value) == "PLAIN" {
			plain = true
		}
	}
	if reader.Len() != 0 {
		return 0, errors.New("SASL handshake trailing bytes")
	}
	if code == 0 && !plain {
		return 0, errors.New("PLAIN not advertised")
	}
	return code, nil
}
func decodeSaslAuthenticate(raw []byte) (int16, error) {
	reader := bytes.NewReader(raw)
	var code int16
	var messageSize int16
	var authSize int32
	if binary.Read(reader, binary.BigEndian, &code) != nil || binary.Read(reader, binary.BigEndian, &messageSize) != nil || messageSize < -1 || int(messageSize) > reader.Len() {
		return 0, errors.New("SASL auth shape")
	}
	if messageSize >= 0 {
		reader.Seek(int64(messageSize), io.SeekCurrent)
	}
	if binary.Read(reader, binary.BigEndian, &authSize) != nil || authSize < 0 || int64(authSize) != int64(reader.Len()) {
		return 0, errors.New("SASL auth bytes shape")
	}
	return code, nil
}

// Genesis seeding is a one-time mutation; only coordinator READ discovery is retried.
// Timed-out reads may finish in their bounded client call, but cannot authorize a commit.
type seedOperations struct{ bounds, fetch, commit func() (any, error) }
type seedPhaseError struct {
	phase           string
	commitAttempted bool
	cause           error
}

func (e *seedPhaseError) Error() string { return "seed phase refused" }
func (e *seedPhaseError) Unwrap() error { return e.cause }
func seedCall(ctx context.Context, call func() (any, error)) (any, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	type result struct {
		value any
		err   error
	}
	done := make(chan result, 1)
	go func() {
		if e := ctx.Err(); e != nil {
			done <- result{nil, e}
			return
		}
		v, e := call()
		done <- result{v, e}
	}()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case r := <-done:
		if e := ctx.Err(); e != nil {
			return nil, e
		}
		return r.value, r.err
	}
}
func seedRead(ctx context.Context, call func() (any, error), interval time.Duration) (any, int, error) {
	attempts := 0
	for {
		attempts++
		v, e := seedCall(ctx, call)
		if e == nil {
			return v, attempts, nil
		}
		if !errors.Is(e, sarama.ErrConsumerCoordinatorNotAvailable) && !errors.Is(e, sarama.ErrNotCoordinatorForConsumer) {
			return nil, attempts, e
		}
		timer := time.NewTimer(interval)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, attempts, ctx.Err()
		case <-timer.C:
		}
	}
}
func seedOffset(value any, topic string) (int64, error) {
	response, ok := value.(*sarama.OffsetFetchResponse)
	if !ok || response == nil {
		return 0, errors.New("missing offset response")
	}
	if response.Err != sarama.ErrNoError {
		return 0, response.Err
	}
	block := response.GetBlock(topic, 0)
	if block == nil {
		return 0, errors.New("missing offset block")
	}
	if block.Err != sarama.ErrNoError {
		return 0, block.Err
	}
	return block.Offset, nil
}
func seedGenesis(ctx context.Context, q request, ops seedOperations, interval time.Duration) (any, error) {
	committed := false
	fail := func(phase string, e error) (any, error) { return nil, &seedPhaseError{phase, committed, e} }
	raw, boundAttempts, e := seedRead(ctx, ops.bounds, interval)
	if e != nil {
		return fail("topic-bounds", e)
	}
	bounds, ok := raw.([2]int64)
	if !ok || bounds != [2]int64{0, 0} {
		return fail("topic-bounds", errors.New("nonempty genesis"))
	}
	fetch := func() (any, error) {
		v, e := ops.fetch()
		if e != nil {
			return nil, e
		}
		offset, e := seedOffset(v, q.Topic)
		return offset, e
	}
	raw, beforeAttempts, e := seedRead(ctx, fetch, interval)
	if e != nil {
		return fail("read-before", e)
	}
	if raw.(int64) != -1 {
		return fail("read-before", errors.New("existing cut refuses reseed"))
	}
	// Check topic remains empty immediately before first and only commit.
	raw, finalBoundsAttempts, e := seedRead(ctx, ops.bounds, interval)
	if e != nil {
		return fail("precommit-bounds", e)
	}
	if b, ok := raw.([2]int64); !ok || b != [2]int64{0, 0} {
		return fail("precommit-bounds", errors.New("changed genesis"))
	}
	if e = ctx.Err(); e != nil {
		return fail("commit", e)
	}
	committed = true
	raw, e = seedCall(ctx, ops.commit)
	if e != nil {
		return fail("commit", e)
	}
	response, ok := raw.(*sarama.OffsetCommitResponse)
	if !ok || response == nil {
		return fail("commit", errors.New("missing commit response"))
	}
	partitions, ok := response.Errors[q.Topic]
	if !ok {
		return fail("commit", errors.New("missing commit topic"))
	}
	code, ok := partitions[0]
	if !ok {
		return fail("commit", errors.New("missing commit partition"))
	}
	if code != sarama.ErrNoError {
		return fail("commit", code)
	}
	raw, afterAttempts, e := seedRead(ctx, fetch, interval)
	if e != nil {
		return fail("read-after", e)
	}
	if raw.(int64) != 0 {
		return fail("read-after", errors.New("commit readback mismatch"))
	}
	return map[string]any{"group": q.Group, "topic": q.Topic, "offset": "0", "previousOffset": "-1", "beginningOffset": "0", "endOffset": "0", "commitAttempts": 1, "boundsReadAttempts": boundAttempts + finalBoundsAttempts, "beforeReadAttempts": beforeAttempts, "afterReadAttempts": afterAttempts, "scope": "Observed empty topic, explicit absent cut, single acknowledged commit, actual OffsetFetch0 readback"}, nil
}

func execute(ctx context.Context, q request) (receipt, error) {
	r := receipt{Schema: "calcify-finite-probe-result-v1", Mode: q.Mode, Status: "ok"}
	if q.Mode == "sasl-empty" || q.Mode == "unauthenticated-state" {
		bounded, cancel := context.WithTimeout(ctx, 5*time.Second)
		defer cancel()
		conn, err := (&net.Dialer{}).DialContext(bounded, "tcp", "broker:9092")
		if err != nil {
			return r, err
		}
		defer conn.Close()
		deadline, _ := bounded.Deadline()
		if err = conn.SetDeadline(deadline); err != nil {
			return r, err
		}
		code, data, err := rawSaslProbe(conn, q)
		if err != nil {
			return r, err
		}
		r.Data = data
		if q.Mode == "sasl-empty" && code == 58 {
			r.Status, r.Code = "authentication", "58"
			return r, nil
		}
		if q.Mode == "unauthenticated-state" && code == 34 {
			r.Status, r.Code = "security-refusal", "34"
			return r, nil
		}
		if code != 0 {
			return r, sarama.KError(code)
		}
		return r, nil // Unexpected success is retained and fails supervisor gate.
	}
	if q.Mode == "grpc-refused" {
		positive, e := net.DialTimeout("tcp", "matcher:8081", 2*time.Second)
		if e != nil {
			return r, e
		}
		positive.Close()
		negative, e := net.DialTimeout("tcp", "matcher:50051", 2*time.Second)
		if e == nil {
			negative.Close()
			return r, errors.New("gRPC TCP listener reachable")
		}
		if !errors.Is(e, syscall.ECONNREFUSED) {
			return r, errors.New("gRPC transport inconclusive")
		}
		r.Data = map[string]any{"positiveAddress": "matcher:8081", "positiveConnection": "connected", "negativeAddress": "matcher:50051", "negativeError": "ECONNREFUSED"}
		return r, nil
	}
	if q.Mode == "matcher-observe" {
		o, e := observedOpening(q)
		if e != nil {
			return r, e
		}
		observation, e := streamdirect.ObserveFiniteBroker(ctx, o, streamdirect.KafkaConfig{BootstrapServers: "broker:9092", PartitionCount: 1, CommandTopic: o.Topics[0].Name, EventTopic: o.Topics[1].Name, OwnershipTopic: o.Topics[3].Name, OwnershipGroup: o.OwnershipGroup, Finite: &streamdirect.FiniteBrokerCredentials{Username: q.Auth.Username, Password: q.Auth.Password}, FetchTimeout: 2 * time.Second})
		if e != nil {
			return r, e
		}
		r.Data = observation
		return r, nil
	}
	if q.Mode == "admin-auth" || q.Mode == "admin-read" || q.Mode == "admin-denied" {
		copy := q
		copy.Headers = nil
		copy.Method = "GET"
		url := "http://broker:9644" + adminReadPath
		var body []byte
		if q.Mode != "admin-read" {
			copy.Method = "PUT"
			url = "http://broker:9644/v1/cluster_config"
			upsert := map[string]any{"write_caching_default": "true"}
			if q.Mode == "admin-auth" {
				if q.Auth.Username != q.Prefix+"-operator" {
					return r, errors.New("operator required")
				}
				upsert = map[string]any{"kafka_enable_authorization": true, "sasl_mechanisms": []string{"SCRAM", "PLAIN"}, "admin_api_require_auth": true, "http_authentication": []string{"BASIC"}, "superusers": []string{q.Auth.Username}, "write_caching_default": "false", "auto_create_topics_enabled": false, "log_segment_size": 8388608, "log_segment_size_min": 1048576, "transaction_coordinator_log_segment_size": 8388608, "group_topic_partitions": 1, "transaction_coordinator_partitions": 1, "compacted_log_segment_size": 8388608}
			}
			body, _ = json.Marshal(map[string]any{"upsert": upsert, "remove": []string{}})
		}
		data, err := requestHTTP(ctx, copy, url, body, true)
		if err != nil {
			return r, err
		}
		row := data.(map[string]any)
		if q.Mode == "admin-read" && row["httpStatus"] == 200 {
			raw, _ := base64.StdEncoding.DecodeString(row["bodyBase64"].(string))
			public, err := projectAdminConfig(raw)
			if err != nil {
				return r, err
			}
			r.Data = map[string]any{"httpStatus": 200, "config": public, "configResponseBytes": row["bytes"], "configResponseSha256": row["bodySha256"], "configResponseLimit": 262144, "transportDurationNanos": row["transportDurationNanos"]}
		} else if q.Mode == "admin-read" {
			r.Data = map[string]any{"httpStatus": row["httpStatus"], "configResponseBytes": row["bytes"], "configResponseSha256": row["bodySha256"], "configResponseLimit": 262144}
		} else {
			r.Data = data
		}
		return r, nil
	}
	if q.Mode == "http" {
		data, err := requestHTTP(ctx, q, "http://"+q.Host+q.Path, q.Body, false)
		r.Data = data
		return r, err
	}
	if q.Mode == "users" {
		out := []any{}
		for _, u := range q.Users {
			body, _ := json.Marshal(map[string]string{"username": u.Username, "password": u.Password, "algorithm": "SCRAM-SHA-256"})
			copy := q
			copy.Method = "POST"
			copy.Headers = nil
			data, err := requestHTTP(ctx, copy, "http://broker:9644/v1/security/users", body, true)
			if err != nil {
				return r, err
			}
			row := data.(map[string]any)
			status := row["httpStatus"].(int)
			if status < 200 || status >= 300 {
				return r, errors.New("user provision refused")
			}
			out = append(out, map[string]any{"principal": u.Username, "httpStatus": status})
		}
		r.Data = out
		return r, nil
	}
	if q.Mode == "authenticate" {
		broker := sarama.NewBroker("broker:9092")
		if err := broker.Open(kafkaConfig(q)); err != nil {
			return r, err
		}
		defer broker.Close()
		connected, err := broker.Connected()
		if err != nil {
			return r, err
		}
		if !connected {
			return r, errors.New("not connected")
		}
		m, err := broker.GetMetadata(&sarama.MetadataRequest{Version: 11, Topics: q.Topics, AllowAutoTopicCreation: false})
		if err != nil {
			return r, err
		}
		for _, t := range m.Topics {
			if t.Err != sarama.ErrNoError {
				return r, t.Err
			}
		}
		r.Data = map[string]any{"authenticatedPrincipal": q.Auth.Username}
		return r, nil
	}
	if q.Mode == "wire-fit" {
		// Package-global: restore so nothing else in this process inherits the enlarged request ceiling.
		previousMaxRequestSize := sarama.MaxRequestSize
		sarama.MaxRequestSize = 6307840 + 1024
		defer func() { sarama.MaxRequestSize = previousMaxRequestSize }()
	}
	c, err := sarama.NewClient([]string{"broker:9092"}, kafkaConfig(q))
	if err != nil {
		return r, err
	}
	defer c.Close()
	a, err := sarama.NewClusterAdminFromClient(c)
	if err != nil {
		return r, err
	}
	defer a.Close()
	switch q.Mode {
	case "recreate-source", "truncate-command-genesis":
		controller, e := c.Controller()
		if e != nil {
			return r, e
		}
		observe := func() (string, error) {
			m, e := controller.GetMetadata(&sarama.MetadataRequest{Version: 11, Topics: []string{q.Topic}, AllowAutoTopicCreation: false})
			if e != nil {
				return "", e
			}
			if len(m.Topics) != 1 {
				return "", errors.New("adverse metadata count")
			}
			if m.Topics[0].Err != sarama.ErrNoError {
				return "", m.Topics[0].Err
			}
			return m.Topics[0].Uuid.String(), nil
		}
		old, e := observe()
		if e != nil {
			return r, e
		}
		lo, e := c.GetOffset(q.Topic, 0, sarama.OffsetOldest)
		if e != nil {
			return r, e
		}
		hi, e := c.GetOffset(q.Topic, 0, sarama.OffsetNewest)
		if e != nil {
			return r, e
		}
		if old != q.TopicID || lo != 0 {
			return r, errors.New("adverse identity/history mismatch")
		}
		if q.Mode == "truncate-command-genesis" {
			if hi != 1 {
				return r, errors.New("exact one-command adverse history required")
			}
			if e = a.DeleteRecords(q.Topic, map[int32]int64{0: 1}); e != nil {
				return r, e
			}
			newLo, e := c.GetOffset(q.Topic, 0, sarama.OffsetOldest)
			if e != nil {
				return r, e
			}
			newHi, e := c.GetOffset(q.Topic, 0, sarama.OffsetNewest)
			if e != nil {
				return r, e
			}
			uuid, e := observe()
			if e != nil {
				return r, e
			}
			if newLo != 1 || newHi != hi || uuid != old {
				return r, errors.New("command trim readback mismatch")
			}
			r.Data = map[string]any{"topic": q.Topic, "oldUuid": old, "newUuid": uuid, "beginBefore": "0", "endBefore": "1", "beginAfter": "1", "endAfter": "1", "adverse": q.Adverse}
			break
		}
		if hi != 0 {
			return r, errors.New("nonempty source never recreated")
		}
		configs, e := a.DescribeConfig(sarama.ConfigResource{Type: sarama.TopicResource, Name: q.Topic})
		if e != nil {
			return r, e
		}
		entries := map[string]*string{}
		public := map[string]string{}
		for _, v := range configs {
			if configKeys[v.Name] {
				value := v.Value
				entries[v.Name] = &value
				public[v.Name] = value
			}
		}
		if e = a.DeleteTopic(q.Topic); e != nil {
			return r, e
		}
		deadline := time.Now().Add(5 * time.Second)
		gone := false
		for time.Now().Before(deadline) {
			_, e = observe()
			if errors.Is(e, sarama.ErrUnknownTopicOrPartition) {
				gone = true
				break
			}
			if e != nil {
				return r, e
			}
			time.Sleep(50 * time.Millisecond)
		}
		if !gone {
			return r, errors.New("source delete not observed")
		}
		if e = a.CreateTopic(q.Topic, &sarama.TopicDetail{NumPartitions: 1, ReplicationFactor: 1, ConfigEntries: entries}, false); e != nil {
			return r, e
		}
		uuid := ""
		for time.Now().Before(deadline) {
			uuid, e = observe()
			if e == nil && uuid != old && uuid != "AAAAAAAAAAAAAAAAAAAAAA" {
				break
			}
			if e != nil && !errors.Is(e, sarama.ErrUnknownTopicOrPartition) {
				return r, e
			}
			time.Sleep(50 * time.Millisecond)
		}
		if uuid == "" || uuid == old {
			return r, errors.New("new source UUID not observed")
		}
		newLo, e := c.GetOffset(q.Topic, 0, sarama.OffsetOldest)
		if e != nil {
			return r, e
		}
		newHi, e := c.GetOffset(q.Topic, 0, sarama.OffsetNewest)
		if e != nil {
			return r, e
		}
		if newLo != 0 || newHi != 0 {
			return r, errors.New("new source not empty")
		}
		r.Data = map[string]any{"topic": q.Topic, "oldUuid": old, "newUuid": uuid, "beginBefore": "0", "endBefore": "0", "beginAfter": "0", "endAfter": "0", "config": public, "adverse": q.Adverse}
	case "metadata":
		if len(q.Topics) == 0 {
			return r, errors.New("topics required")
		}
		broker, err := c.Controller()
		if err != nil {
			return r, err
		}
		m, err := broker.GetMetadata(&sarama.MetadataRequest{Version: 11, Topics: q.Topics, AllowAutoTopicCreation: false})
		if err != nil {
			return r, err
		}
		if m.ClusterID == nil || *m.ClusterID == "" {
			return r, errors.New("missing cluster identity")
		}
		if len(m.Topics) != len(q.Topics) {
			return r, errors.New("metadata count mismatch")
		}
		seen := map[string]bool{}
		out := []any{}
		for _, t := range m.Topics {
			requested := false
			for _, name := range q.Topics {
				if name == t.Name {
					requested = true
				}
			}
			if !requested || seen[t.Name] || t.Uuid.String() == "AAAAAAAAAAAAAAAAAAAAAA" {
				return r, errors.New("metadata identity mismatch")
			}
			seen[t.Name] = true
			if t.Err != sarama.ErrNoError {
				return r, t.Err
			}
			if len(t.Partitions) != 1 || t.Partitions[0].ID != 0 {
				return r, errors.New("not single partition")
			}
			lo, err := c.GetOffset(t.Name, 0, sarama.OffsetOldest)
			if err != nil {
				return r, err
			}
			hi, err := c.GetOffset(t.Name, 0, sarama.OffsetNewest)
			if err != nil {
				return r, err
			}
			conf, err := a.DescribeConfig(sarama.ConfigResource{Type: sarama.TopicResource, Name: t.Name})
			if err != nil {
				return r, err
			}
			public := map[string]string{}
			for _, v := range conf {
				if configKeys[v.Name] {
					public[v.Name] = v.Value
				}
			}
			out = append(out, map[string]any{"name": t.Name, "uuid": t.Uuid.String(), "partitions": 1, "lowOffset": strconv.FormatInt(lo, 10), "highWatermark": strconv.FormatInt(hi, 10), "config": public})
		}
		r.Data = map[string]any{"clusterId": *m.ClusterID, "topics": out}
	case "create-topics":
		if len(q.Topics) == 0 {
			return r, errors.New("topics required")
		}
		cfg := map[string]*string{}
		for k, v := range q.Config {
			value := v
			cfg[k] = &value
		}
		for _, topic := range q.Topics {
			if err := a.CreateTopic(topic, &sarama.TopicDetail{NumPartitions: 1, ReplicationFactor: 1, ConfigEntries: cfg}, false); err != nil {
				return r, err
			}
		}
		r.Data = map[string]any{"created": q.Topics}
	case "grant":
		for _, g := range q.Grants {
			pattern := sarama.AclPatternLiteral
			if g.Pattern == "prefixed" {
				pattern = sarama.AclPatternPrefixed
			}
			err := a.CreateACL(sarama.Resource{ResourceType: kinds[g.Kind], ResourceName: g.Name, ResourcePatternType: pattern}, sarama.Acl{Principal: g.Principal, Host: "*", Operation: operations[g.Operation], PermissionType: sarama.AclPermissionAllow})
			if err != nil {
				return r, err
			}
		}
		r.Data = map[string]any{"grants": q.Grants}
	case "acls":
		out, err := a.ListAcls(sarama.AclFilter{ResourceType: sarama.AclResourceAny, ResourcePatternTypeFilter: sarama.AclPatternAny, Operation: sarama.AclOperationAny, PermissionType: sarama.AclPermissionAny})
		if err != nil {
			return r, err
		}
		for _, v := range out {
			if !strings.HasPrefix(v.ResourceName, q.Prefix+"-") {
				return r, errors.New("outside fixture ACL")
			}
		}
		r.Data = out
	case "seed-group":
		if q.Offset == nil || *q.Offset != 0 {
			return r, errors.New("genesis offset0 only")
		}
		bounded, cancel := context.WithTimeout(ctx, 10*time.Second)
		defer cancel()
		r.Data, err = seedGenesis(bounded, q, seedOperations{
			bounds: func() (any, error) {
				lo, e := c.GetOffset(q.Topic, 0, sarama.OffsetOldest)
				if e != nil {
					return nil, e
				}
				hi, e := c.GetOffset(q.Topic, 0, sarama.OffsetNewest)
				return [2]int64{lo, hi}, e
			},
			fetch: func() (any, error) { return a.ListConsumerGroupOffsets(q.Group, map[string][]int32{q.Topic: {0}}) },
			commit: func() (any, error) {
				return a.AlterConsumerGroupOffsets(q.Group, map[string]map[int32]sarama.OffsetAndMetadata{q.Topic: {0: {Offset: 0}}}, nil)
			},
		}, 250*time.Millisecond)
		return r, err
	case "group-offset":
		resp, err := a.ListConsumerGroupOffsets(q.Group, map[string][]int32{q.Topic: {0}})
		if err != nil {
			return r, err
		}
		block := resp.GetBlock(q.Topic, 0)
		if block == nil {
			return r, errors.New("missing group offset")
		}
		if block.Err != sarama.ErrNoError {
			return r, block.Err
		}
		r.Data = map[string]any{"group": q.Group, "topic": q.Topic, "offset": strconv.FormatInt(block.Offset, 10)}
	case "publish":
		value, err := base64.StdEncoding.DecodeString(q.Value)
		if err != nil || len(value) > 8192 {
			return r, errors.New("value cap")
		}
		producer, err := sarama.NewSyncProducerFromClient(c)
		if err != nil {
			return r, err
		}
		defer producer.Close()
		partition, offset, err := producer.SendMessage(&sarama.ProducerMessage{Topic: q.Topic, Partition: 0, Key: sarama.StringEncoder(q.Key), Value: sarama.ByteEncoder(value)})
		if err != nil {
			return r, err
		}
		r.Data = map[string]any{"partition": partition, "offset": strconv.FormatInt(offset, 10), "payloadSha256": digest(value)}
	case "delete-canary":
		if err := a.DeleteTopic(q.Topic); err != nil {
			return r, err
		}
		r.Data = map[string]any{"deleted": q.Topic}
	case "alter-canary":
		value := "8388609"
		if err := a.AlterConfig(sarama.TopicResource, q.Topic, map[string]*string{"segment.bytes": &value}, false); err != nil {
			return r, err
		}
		r.Data = map[string]any{"altered": q.Topic}
	case "history":
		data, err := history(c, q)
		r.Data = data
		return r, err
	case "range":
		lo, e := c.GetOffset(q.Topic, 0, sarama.OffsetOldest)
		if e != nil {
			return r, e
		}
		broker, e := c.Leader(q.Topic, 0)
		if e != nil {
			return r, e
		}
		fr := &sarama.FetchRequest{Version: 11, MaxWaitTime: 250, MinBytes: 1, MaxBytes: 8 * 1024 * 1024, Isolation: sarama.ReadCommitted}
		fr.AddBlock(q.Topic, 0, lo, 8*1024*1024, -1)
		response, e := broker.Fetch(fr)
		if e != nil {
			return r, e
		}
		block := response.GetBlock(q.Topic, 0)
		if block == nil {
			return r, errors.New("missing fetch block")
		}
		if block.Err != sarama.ErrNoError {
			return r, block.Err
		}
		r.Data = map[string]any{"topic": q.Topic, "beginningOffset": strconv.FormatInt(lo, 10), "endOffset": strconv.FormatInt(block.HighWaterMarkOffset, 10), "readCommittedEndOffset": strconv.FormatInt(block.LastStableOffset, 10)}
	case "wire-fit":
		lo, e := c.GetOffset(q.Topic, 0, sarama.OffsetOldest)
		if e != nil {
			return r, e
		}
		hi, e := c.GetOffset(q.Topic, 0, sarama.OffsetNewest)
		if e != nil {
			return r, e
		}
		if lo != 0 || hi != 0 {
			return r, errors.New("fresh separate state-fit canary required")
		}
		value := make([]byte, 6307840)
		for i := range value {
			value[i] = byte(i % 251)
		}
		producer, e := sarama.NewSyncProducerFromClient(c)
		if e != nil {
			return r, e
		}
		defer producer.Close()
		if e = producer.BeginTxn(); e != nil {
			return r, e
		}
		partition, offset, e := producer.SendMessage(&sarama.ProducerMessage{Topic: q.Topic, Partition: 0, Value: sarama.ByteEncoder(value)})
		if e != nil {
			producer.AbortTxn()
			return r, e
		}
		if e = producer.CommitTxn(); e != nil {
			return r, e
		}
		broker, e := c.Leader(q.Topic, 0)
		if e != nil {
			return r, e
		}
		fr := &sarama.FetchRequest{Version: 11, MaxWaitTime: 1000, MinBytes: 1, MaxBytes: 6307840 + 1024, Isolation: sarama.ReadCommitted}
		fr.AddBlock(q.Topic, 0, offset, 6307840+1024, -1)
		response, e := broker.Fetch(fr)
		if e != nil {
			return r, e
		}
		block := response.GetBlock(q.Topic, 0)
		if block == nil || block.Err != sarama.ErrNoError || block.LastStableOffset <= offset {
			return r, errors.New("state-fit committed fetch missing")
		}
		found := false
		for _, set := range block.RecordsSet {
			if set.RecordBatch != nil && !set.RecordBatch.Control {
				for _, record := range set.RecordBatch.Records {
					if set.RecordBatch.FirstOffset+record.OffsetDelta == offset {
						if !bytes.Equal(value, record.Value) {
							return r, errors.New("state-fit bytes mismatch")
						}
						found = true
					}
				}
			}
		}
		if !found {
			return r, errors.New("state-fit record not visible")
		}
		r.Data = map[string]any{"topic": q.Topic, "partition": partition, "offset": strconv.FormatInt(offset, 10), "payloadBytes": len(value), "payloadSha256": digest(value), "readCommittedEndOffset": strconv.FormatInt(block.LastStableOffset, 10), "producerMaxMessageBytes": 6307840 + 1023, "maxRequestBytes": 6307840 + 1024, "fetchMaxBytes": 6307840 + 1024, "compression": "none", "transactionalId": q.Prefix + "-state-fit-tx", "scope": "Separate transport canary; not full managed state serialization or financial flow"}
	}
	return r, nil
}

type historyPhaseError struct {
	phase                                        string
	fetchCount, recordCount, payloadBytes, codec int
	position, end                                int64
	cause                                        error
}

func (e *historyPhaseError) Error() string { return "history phase refused" }
func (e *historyPhaseError) Unwrap() error { return e.cause }
func history(c sarama.Client, q request) (data any, err error) {
	diagnostic := historyPhaseError{phase: "offset_lookup", end: -1, codec: -1}
	defer func() {
		if err != nil {
			diagnostic.cause = err
			err = &diagnostic
		}
	}()
	lo, err := c.GetOffset(q.Topic, 0, sarama.OffsetOldest)
	if err != nil {
		return nil, err
	}
	if lo != 0 {
		diagnostic.phase = "lost_genesis"
		return nil, errors.New("lost genesis")
	}
	diagnostic.phase = "leader_lookup"
	broker, err := c.Leader(q.Topic, 0)
	if err != nil {
		return nil, err
	}
	position := int64(0)
	end := int64(-1)
	rows := []any{}
	aborted := map[int64]int64{}
	bytesRead := 0
	for fetch := 0; fetch < 64; fetch++ {
		fr := &sarama.FetchRequest{Version: 11, MaxWaitTime: 250, MinBytes: 1, MaxBytes: 8 * 1024 * 1024, Isolation: sarama.ReadCommitted}
		fr.AddBlock(q.Topic, 0, position, 8*1024*1024, -1)
		diagnostic.phase = "fetch_request"
		diagnostic.fetchCount = fetch + 1
		diagnostic.position = position
		diagnostic.end = end
		response, err := broker.Fetch(fr)
		if err != nil {
			return nil, err
		}
		block := response.GetBlock(q.Topic, 0)
		if block == nil {
			diagnostic.phase = "missing_block"
			return nil, errors.New("missing fetch block")
		}
		if block.Err != sarama.ErrNoError {
			diagnostic.phase = "kafka_error"
			return nil, block.Err
		}
		if end < 0 {
			end = block.LastStableOffset
			diagnostic.end = end
		} else if block.LastStableOffset != end {
			diagnostic.phase = "unstable_cut"
			return nil, errors.New("unstable history cut")
		}
		for _, t := range block.AbortedTransactions {
			if old, ok := aborted[t.ProducerID]; !ok || t.FirstOffset < old {
				aborted[t.ProducerID] = t.FirstOffset
			}
		}
		before := position
		for _, set := range block.RecordsSet {
			batch := set.RecordBatch
			diagnostic.phase = "missing_batch"
			if batch == nil {
				return nil, errors.New("unsupported/partial batch")
			}
			diagnostic.codec = int(batch.Codec)
			diagnostic.phase = "partial_batch"
			if batch.PartialTrailingRecord {
				return nil, errors.New("unsupported/partial batch")
			}
			diagnostic.phase = "unsupported_codec"
			if batch.Codec != sarama.CompressionNone {
				return nil, errors.New("unsupported/partial batch")
			}
			if batch.FirstOffset >= end {
				break
			}
			next := batch.LastOffset() + 1
			if next > position {
				position = next
			}
			if batch.Control {
				if first, ok := aborted[batch.ProducerID]; ok && batch.FirstOffset >= first {
					delete(aborted, batch.ProducerID)
				}
				continue
			}
			if first, ok := aborted[batch.ProducerID]; ok && batch.IsTransactional && batch.FirstOffset >= first {
				continue
			}
			for _, record := range batch.Records {
				offset := batch.FirstOffset + record.OffsetDelta
				if offset >= end || offset < before {
					continue
				}
				bytesRead += len(record.Value)
				diagnostic.recordCount = len(rows)
				diagnostic.payloadBytes = bytesRead
				if len(rows) >= 16 || len(record.Value) > 131072 || bytesRead > 2*1024*1024 {
					diagnostic.phase = "record_budget"
					return nil, errors.New("history budget")
				}
				headers := []map[string]string{}
				for _, h := range record.Headers {
					headers = append(headers, map[string]string{"keyBase64": base64.StdEncoding.EncodeToString(h.Key), "valueBase64": base64.StdEncoding.EncodeToString(h.Value)})
				}
				rows = append(rows, map[string]any{"offset": strconv.FormatInt(offset, 10), "keyBase64": base64.StdEncoding.EncodeToString(record.Key), "valueBase64": base64.StdEncoding.EncodeToString(record.Value), "payloadSha256": digest(record.Value), "headers": headers, "bytes": len(record.Value)})
			}
		}
		if position >= end {
			return map[string]any{"topic": q.Topic, "lowOffset": "0", "readCommittedEndOffset": strconv.FormatInt(end, 10), "scanNextOffset": strconv.FormatInt(position, 10), "records": rows, "payloadBytes": bytesRead}, nil
		}
		if position == before {
			diagnostic.phase = "no_progress"
			diagnostic.position = position
			diagnostic.recordCount = len(rows)
			diagnostic.payloadBytes = bytesRead
			return nil, errors.New("history scan no progress")
		}
	}
	diagnostic.phase = "fetch_budget"
	diagnostic.position = position
	diagnostic.recordCount = len(rows)
	diagnostic.payloadBytes = bytesRead
	return nil, errors.New("history fetch budget")
}
func encodeReceipt(r receipt, max int) ([]byte, error) {
	b, err := json.Marshal(r)
	if err != nil || len(b) > max {
		return nil, errors.New("public output cap")
	}
	return append(b, '\n'), nil
}
func main() {
	q, err := decode(os.Stdin)
	if err != nil {
		fmt.Fprintln(os.Stdout, `{"schema":"calcify-finite-probe-result-v1","status":"error","code":"invalid_input"}`)
		os.Exit(2)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	done := make(chan receipt, 1)
	go func() {
		r, e := execute(ctx, q)
		if e != nil {
			r = errorReceipt(q, e)
		}
		done <- r
	}()
	var r receipt
	select {
	case r = <-done:
	case <-ctx.Done():
		r = receipt{Schema: "calcify-finite-probe-result-v1", Mode: q.Mode, Status: "error", Code: "deadline"}
	}
	limit := ordinaryOutputLimit
	if q.Mode == "history" {
		limit = historyOutputLimit
	}
	b, err := encodeReceipt(r, limit)
	if err != nil {
		fmt.Fprintln(os.Stdout, `{"schema":"calcify-finite-probe-result-v1","status":"error","code":"output_cap"}`)
		os.Exit(2)
	}
	// A server must never trick the helper into disclosing its own credentials.
	secrets := []string{q.Auth.Password, q.Bearer}
	for _, u := range q.Users {
		secrets = append(secrets, u.Password)
	}
	sort.Strings(secrets)
	for _, s := range secrets {
		if s != "" && (bytes.Contains(b, []byte(s)) || bytes.Contains(b, []byte(base64.StdEncoding.EncodeToString([]byte(s))))) {
			fmt.Fprintln(os.Stdout, `{"schema":"calcify-finite-probe-result-v1","status":"error","code":"secret_spill"}`)
			os.Exit(2)
		}
	}
	os.Stdout.Write(b)
}
