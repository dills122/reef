package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/IBM/sarama"
)

const validInput = `{"schema":"calcify-finite-probe-v1","mode":"metadata","prefix":"calcify-o2-test","auth":{"username":"calcify-o2-test-denied","password":"synthetic-test-secret"},"topics":["calcify-o2-test-commands"]}`

func TestRejectUnboundedAmbiguousInput(t *testing.T) {
	if _, err := decode(strings.NewReader(validInput)); err != nil {
		t.Fatal(err)
	}
	bad := []string{
		strings.Replace(validInput, `"mode":"metadata"`, `"mode":"metadata","mode":"publish"`, 1),
		strings.Replace(validInput, `"username":`, `"password":"duplicate","username":`, 1),
		strings.Replace(validInput, `"topics":`, `"unknown":1,"topics":`, 1),
		validInput + `{}`, validInput + strings.Repeat(" ", inputLimit),
		strings.Replace(validInput, "calcify-o2-test-commands", "outside-commands", 1),
		strings.Replace(validInput, `"mode":"metadata"`, `"mode":"shell"`, 1),
	}
	for i, input := range bad {
		if _, err := decode(strings.NewReader(input)); err == nil {
			t.Fatalf("accepted bad input%d", i)
		}
	}
	if _, err := decode(bytes.NewReader(append([]byte(validInput), 255))); err == nil {
		t.Fatal("invalid UTF8 accepted")
	}
}
func TestCanaryAndACLContainment(t *testing.T) {
	q, err := decode(strings.NewReader(validInput))
	if err != nil {
		t.Fatal(err)
	}
	q.Mode = "delete-canary"
	q.Topic = q.Prefix + "-commands"
	if validate(q) == nil {
		t.Fatal("canonical topic delete permitted")
	}
	q.Topic = q.Prefix + "-canary"
	if err := validate(q); err != nil {
		t.Fatal(err)
	}
	q.Mode = "grant"
	q.Grants = []grant{{Principal: "User:*", Kind: "topic", Name: q.Topic, Operation: "write"}}
	if validate(q) == nil {
		t.Fatal("wildcard principal allowed")
	}
	q.Grants = []grant{{Principal: "User:" + q.Prefix + "-denied", Kind: "topic", Name: q.Topic, Operation: "all"}}
	if validate(q) == nil {
		t.Fatal("all operation allowed")
	}
	q.Grants[0].Operation = "describe"
	if err := validate(q); err != nil {
		t.Fatal(err)
	}
	q.Grants[0].Pattern = "prefixed"
	if validate(q) == nil {
		t.Fatal("broad topic prefix allowed")
	}
	q.Grants[0].Kind = "transactional-id"
	q.Grants[0].Name = q.Prefix + "-capture-"
	if err := validate(q); err != nil {
		t.Fatal(err)
	}
	q.Grants[0].Name = q.Prefix + "-"
	if validate(q) == nil {
		t.Fatal("non-application transaction prefix allowed")
	}
}
func TestFinitePLAINConfigNeverAutoCreates(t *testing.T) {
	q, _ := decode(strings.NewReader(validInput))
	c := kafkaConfig(q)
	if !c.Net.SASL.Enable || c.Net.SASL.Mechanism != sarama.SASLTypePlaintext || c.Metadata.AllowAutoTopicCreation {
		t.Fatal("wrong security/config")
	}
	if c.Consumer.IsolationLevel != sarama.ReadCommitted || c.Producer.RequiredAcks != sarama.WaitForAll || c.Producer.Retry.Max != 0 {
		t.Fatal("wrong observation/negative send configuration")
	}
	q.Auth.Username = ""
	if !kafkaConfig(q).Net.SASL.Enable {
		t.Fatal("empty principal must attempt actual SASL handshake for authentication denial")
	}
}
func TestErrorsDoNotClaimDenialOrEchoSecrets(t *testing.T) {
	q, _ := decode(strings.NewReader(validInput))
	for _, k := range []sarama.KError{sarama.ErrTopicAuthorizationFailed, sarama.ErrGroupAuthorizationFailed, sarama.ErrClusterAuthorizationFailed, sarama.ErrTransactionalIDAuthorizationFailed} {
		r := errorReceipt(q, k)
		if r.Status != "denied" {
			t.Fatal(r)
		}
	}
	r := errorReceipt(q, errors.New("timeout synthetic-test-secret"))
	b, _ := json.Marshal(r)
	if r.Status != "error" || bytes.Contains(b, []byte(q.Auth.Password)) {
		t.Fatal("timeout falsely denied or secret echoed")
	}
	if errorReceipt(q, sarama.ErrSASLAuthenticationFailed).Status != "authentication" {
		t.Fatal("auth distinction")
	}
}
func TestHTTPNoRedirectCredentialForwarding(t *testing.T) {
	forwarded := false
	sink := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { forwarded = true }))
	defer sink.Close()
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, sink.URL, 302) }))
	defer origin.Close()
	r, _ := http.NewRequest("GET", origin.URL, nil)
	r.Header.Set("Authorization", "Bearer test-secret")
	if _, err := httpClient().Do(r); err == nil || forwarded {
		t.Fatal("redirect forwarded authorization")
	}
}
func TestHTTPBodyBoundAndErrorNotFalseDenial(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, strings.Repeat("x", 32769)) }))
	defer s.Close()
	q, _ := decode(strings.NewReader(validInput))
	q.Method = "GET"
	if _, err := requestHTTP(context.Background(), q, s.URL, nil, false); err == nil {
		t.Fatal("oversized HTTP body accepted")
	}
	if _, err := encodeReceipt(receipt{Data: strings.Repeat("x", ordinaryOutputLimit)}, ordinaryOutputLimit); err == nil {
		t.Fatal("output cap absent")
	}
}

func TestAdverseModesRequireExactOperatorTopicUUIDAndTrim(t *testing.T) {
	q, _ := decode(strings.NewReader(validInput))
	q.Mode = "recreate-source"
	q.Auth.Username = q.Prefix + "-operator"
	q.Topic = q.Prefix + "-source"
	q.TopicID = "AQIDBAUGBwgJCgsMDQ4PEA"
	q.Adverse = "topic-identity"
	if err := validate(q); err != nil {
		t.Fatal(err)
	}
	for _, change := range []func(*request){func(q *request) { q.Auth.Username = q.Prefix + "-matcher" }, func(q *request) { q.Topic = q.Prefix + "-commands" }, func(q *request) { q.TopicID = "AAAAAAAAAAAAAAAAAAAAAA" }, func(q *request) { q.Adverse = "command-history" }} {
		bad := q
		change(&bad)
		if validate(bad) == nil {
			t.Fatal("unscoped recreate accepted")
		}
	}
	q.Mode = "truncate-command-genesis"
	q.Adverse = "command-history"
	q.Topic = q.Prefix + "-commands"
	one := int64(1)
	q.Offset = &one
	if err := validate(q); err != nil {
		t.Fatal(err)
	}
	zero := int64(0)
	q.Offset = &zero
	if validate(q) == nil {
		t.Fatal("arbitrary trim accepted")
	}
}
func TestHTTPTransportCostMeasuresThroughBodyRead(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(202); io.WriteString(w, "receipt") }))
	defer s.Close()
	q, _ := decode(strings.NewReader(validInput))
	q.Method = "GET"
	v, e := requestHTTP(context.Background(), q, s.URL, nil, false)
	if e != nil {
		t.Fatal(e)
	}
	m := v.(map[string]any)
	if m["httpStatus"] != 202 || m["bytes"] != 7 || m["transportDurationNanos"] == "0" || m["transportStartedAt"] == "" || m["transportCompletedAt"] == "" {
		t.Fatal(m)
	}
}
func TestWireFitConfigUsesCaptureEquivalentBound(t *testing.T) {
	q, _ := decode(strings.NewReader(validInput))
	q.Mode = "wire-fit"
	c := kafkaConfig(q)
	if c.Producer.MaxMessageBytes != 6308863 || c.Consumer.Fetch.Max != 6308864 || !c.Producer.Idempotent || c.Producer.Compression != sarama.CompressionNone {
		t.Fatal("wirefit config differs from capture bound")
	}
}
func TestAdminReadRequestsDefaultsAndProjectsOnlySafetyFields(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/cluster_config" || r.URL.Query().Get("include_defaults") != "true" {
			t.Error("defaults not explicitly requested")
		}
		io.WriteString(w, `{"write_caching_default":"false","auto_create_topics_enabled":false,"admin_api_require_auth":true,"group_topic_partitions":1,"transaction_coordinator_partitions":1,"compacted_log_segment_size":8388608,"cloud_storage_secret_key":"nonpublic-sentinel"}`)
	}))
	defer s.Close()
	q, _ := decode(strings.NewReader(validInput))
	q.Mode = "admin-read"
	q.Method = "GET"
	v, err := requestHTTP(context.Background(), q, s.URL+adminReadPath, nil, true)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := base64.StdEncoding.DecodeString(v.(map[string]any)["bodyBase64"].(string))
	if err != nil {
		t.Fatal(err)
	}
	public, err := projectAdminConfig(raw)
	if err != nil {
		t.Fatal(err)
	}
	if string(public["write_caching_default"]) != `"false"` || string(public["auto_create_topics_enabled"]) != "false" {
		t.Fatal("explicitfalse lost")
	}
	b, _ := json.Marshal(public)
	if string(public["group_topic_partitions"]) != "1" || string(public["transaction_coordinator_partitions"]) != "1" || string(public["compacted_log_segment_size"]) != "8388608" {
		t.Fatal("geometry primitives missing")
	}
	if bytes.Contains(b, []byte("nonpublic-sentinel")) {
		t.Fatal("nonpublic config leaked")
	}
	missing, err := projectAdminConfig([]byte(`{"admin_api_require_auth":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := missing["write_caching_default"]; ok {
		t.Fatal("missing config falsely assertedfalse")
	}
}
func TestLargeAdminConfigBoundDoesNotExpandPublicAPIOrPublishUnknownFields(t *testing.T) {
	content := `{"write_caching_default":"false","unused":"` + strings.Repeat("x", 40000) + `"}`
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, content) }))
	defer s.Close()
	q, _ := decode(strings.NewReader(validInput))
	q.Method = "GET"
	q.Mode = "http"
	if _, e := requestHTTP(context.Background(), q, s.URL, nil, false); e == nil {
		t.Fatal("generalHTTP cap expanded")
	}
	q.Mode = "admin-read"
	v, e := requestHTTP(context.Background(), q, s.URL+adminReadPath, nil, true)
	if e != nil {
		t.Fatal(e)
	}
	row := v.(map[string]any)
	raw, _ := base64.StdEncoding.DecodeString(row["bodyBase64"].(string))
	public, e := projectAdminConfig(raw)
	if e != nil || len(public) != 1 {
		t.Fatal("adminprojection", e)
	}
	content = strings.Repeat("x", 262145)
	if _, e := requestHTTP(context.Background(), q, s.URL+adminReadPath, nil, true); e == nil {
		t.Fatal("oversizedadmin configaccepted")
	}
}
func TestTruncatedAdminConfigCannotPassProjection(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "100")
		io.WriteString(w, "{}")
	}))
	defer s.Close()
	q, _ := decode(strings.NewReader(validInput))
	q.Method = "GET"
	q.Mode = "admin-read"
	if _, e := requestHTTP(context.Background(), q, s.URL+adminReadPath, nil, true); e == nil {
		t.Fatal("truncatedAdmin configaccepted")
	}
}

type adminRoundTrip func(*http.Request) (*http.Response, error)

func (f adminRoundTrip) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestAdminMutationsUseValidWriteCachingEnum(t *testing.T) {
	original := http.DefaultTransport
	defer func() { http.DefaultTransport = original }()
	for _, mode := range []string{"admin-auth", "admin-denied"} {
		q, _ := decode(strings.NewReader(validInput))
		q.Mode = mode
		if mode == "admin-auth" {
			q.Auth.Username = q.Prefix + "-operator"
		}
		http.DefaultTransport = adminRoundTrip(func(r *http.Request) (*http.Response, error) {
			if r.Method != "PUT" || r.URL.Path != "/v1/cluster_config" {
				t.Fatal("wrong mutation route")
			}
			var body struct {
				Upsert map[string]json.RawMessage `json:"upsert"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			expected := `"true"`
			if mode == "admin-auth" {
				expected = `"false"`
				for key, want := range map[string]string{"group_topic_partitions": "1", "transaction_coordinator_partitions": "1", "compacted_log_segment_size": "8388608"} {
					if string(body.Upsert[key]) != want {
						t.Fatal("geometry mutation not exact integer")
					}
				}
			}
			if string(body.Upsert["write_caching_default"]) != expected {
				t.Fatal("write caching mutation not exact enum string")
			}
			return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{}`))}, nil
		})
		if _, err := execute(context.Background(), q); err != nil {
			t.Fatal(err)
		}
	}
}

func TestRawSaslProbeActualCodeShapes(t *testing.T) {
	for _, mode := range []string{"sasl-empty", "unauthenticated-state"} {
		t.Run(mode, func(t *testing.T) {
			client, server := net.Pipe()
			defer client.Close()
			defer server.Close()
			client.SetDeadline(time.Now().Add(time.Second))
			server.SetDeadline(time.Now().Add(time.Second))
			done := make(chan error, 1)
			go func() {
				apis := []int16{36}
				versions := []int16{0}
				correlations := []int32{2}
				payloads := [][]byte{{0, 0, 0, 2, 0, 0}}
				replies := []string{"0022ffff00000000"}
				if mode == "sasl-empty" {
					apis = []int16{17, 36}
					versions = []int16{1, 0}
					correlations = []int32{1, 2}
					payloads = [][]byte{append([]byte{0, 5}, []byte("PLAIN")...), {0, 0, 0, 2, 0, 0}}
					replies = []string{"0000000000010005504c41494e", "003affff00000000"}
				}
				for i, api := range apis {
					var header [4]byte
					if _, err := io.ReadFull(server, header[:]); err != nil {
						done <- err
						return
					}
					size := binary.BigEndian.Uint32(header[:])
					if size > 1024 {
						done <- errors.New("unexpected request cap")
						return
					}
					raw := make([]byte, size)
					if _, err := io.ReadFull(server, raw); err != nil {
						done <- err
						return
					}
					if len(raw) < 10 || int16(binary.BigEndian.Uint16(raw[:2])) != api || int16(binary.BigEndian.Uint16(raw[2:4])) != versions[i] || int32(binary.BigEndian.Uint32(raw[4:8])) != correlations[i] {
						done <- errors.New("protocol header differs")
						return
					}
					idlen := int(binary.BigEndian.Uint16(raw[8:10]))
					if 10+idlen > len(raw) || string(raw[10:10+idlen]) != "calcify-o2-test-probe" || !bytes.Equal(raw[10+idlen:], payloads[i]) {
						done <- errors.New("protocol body differs")
						return
					}
					body, _ := hex.DecodeString(replies[i])
					response := make([]byte, 8+len(body))
					binary.BigEndian.PutUint32(response[:4], uint32(4+len(body)))
					binary.BigEndian.PutUint32(response[4:8], uint32(correlations[i]))
					copy(response[8:], body)
					if _, err := server.Write(response); err != nil {
						done <- err
						return
					}
				}
				done <- nil
			}()
			q := request{Mode: mode, Prefix: "calcify-o2-test"}
			code, data, err := rawSaslProbe(client, q)
			if err != nil {
				t.Fatal(err)
			}
			expected := int16(34)
			if mode == "sasl-empty" {
				expected = 58
			}
			if code != expected {
				t.Fatal("wrong typed broker code", code)
			}
			if err := <-done; err != nil {
				t.Fatal(err)
			}
			public, _ := json.Marshal(data)
			if bytes.Contains(public, []byte("errorMessage")) || bytes.Contains(public, []byte("authBytes")) {
				t.Fatal("raw server fields published")
			}
		})
	}
}
func TestSaslExchangeRejectsOversizeCorrelationTruncationTransport(t *testing.T) {
	for name, response := range map[string][]byte{
		"oversize": {0, 0, 128, 1}, "correlation": {0, 0, 0, 4, 0, 0, 0, 9}, "truncated": {0, 0, 0, 8, 0, 0, 0, 2, 0}, "close": {},
	} {
		t.Run(name, func(t *testing.T) {
			client, server := net.Pipe()
			defer client.Close()
			client.SetDeadline(time.Now().Add(time.Second))
			go func() {
				defer server.Close()
				var header [4]byte
				io.ReadFull(server, header[:])
				n := binary.BigEndian.Uint32(header[:])
				io.CopyN(io.Discard, server, int64(n))
				server.Write(response)
			}()
			if _, err := saslExchange(client, 36, 0, 2, "test", []byte{0, 0, 0, 2, 0, 0}); err == nil {
				t.Fatal("inconclusive transport/frame passed")
			}
		})
	}
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	client.SetDeadline(time.Now().Add(5 * time.Millisecond))
	if _, err := saslExchange(client, 36, 0, 2, "test", []byte{}); err == nil {
		t.Fatal("timeout accepted")
	}
	if _, err := saslExchange(client, 36, 0, 2, strings.Repeat("x", 129), nil); err == nil {
		t.Fatal("request clientID cap accepted")
	}
}
func TestSaslResponseCodecsRejectMalformedBodies(t *testing.T) {
	handshake, _ := hex.DecodeString("0000000000010005504c41494e")
	if code, err := decodeSaslHandshake(handshake); err != nil || code != 0 {
		t.Fatal("valid handshake", err)
	}
	for _, raw := range [][]byte{nil, handshake[:len(handshake)-1], append(append([]byte{}, handshake...), 0), {0, 0, 255, 255, 255, 255}, {0, 0, 0, 0, 0, 0}} {
		if _, err := decodeSaslHandshake(raw); err == nil {
			t.Fatal("malformed handshake accepted")
		}
	}
	for _, code := range []int16{0, 34, 58} {
		raw := []byte{byte(code >> 8), byte(code), 255, 255, 0, 0, 0, 0}
		if actual, err := decodeSaslAuthenticate(raw); err != nil || actual != code {
			t.Fatal("valid typed auth", err)
		}
	}
	for _, raw := range [][]byte{nil, {0, 58, 255, 254, 0, 0, 0, 0}, {0, 58, 0, 5, 0}, {0, 58, 255, 255, 255, 255, 255, 255}, {0, 58, 255, 255, 0, 0, 0, 1}, {0, 58, 255, 255, 0, 0, 0, 0, 0}} {
		if _, err := decodeSaslAuthenticate(raw); err == nil {
			t.Fatal("malformed auth accepted")
		}
	}
}
func TestRawSaslRequestGuard(t *testing.T) {
	for _, mode := range []string{"sasl-empty", "unauthenticated-state"} {
		q := request{Schema: "calcify-finite-probe-v1", Mode: mode, Prefix: "calcify-o2-test"}
		if err := validate(q); err != nil {
			t.Fatal(err)
		}
		q.Auth.Username = "nonempty"
		if validate(q) == nil {
			t.Fatal("raw scope broadened")
		}
		q.Auth = credential{}
		q.Topics = []string{"calcify-o2-test-commands"}
		if validate(q) == nil {
			t.Fatal("raw scope canonical topic accepted")
		}
	}
}
func TestSaramaEmptyCredentialsFailBeforeDial(t *testing.T) {
	q := request{Prefix: "calcify-o2-test", Mode: "authenticate"}
	broker := sarama.NewBroker("invalid.fixture:9092")
	err := broker.Open(kafkaConfig(q))
	var configErr sarama.ConfigurationError
	if !errors.As(err, &configErr) {
		t.Fatal("expected local configuration refusal", err)
	}
	r := errorReceipt(q, err)
	if r.Status != "error" || r.Code != "operation_failed" {
		t.Fatal("local config failure classified as broker proof")
	}
}

func seedFetch(topic string, offset int64) *sarama.OffsetFetchResponse {
	r := &sarama.OffsetFetchResponse{}
	r.AddBlock(topic, 0, &sarama.OffsetFetchResponseBlock{Offset: offset})
	return r
}
func seedCommit(topic string, code sarama.KError) *sarama.OffsetCommitResponse {
	return &sarama.OffsetCommitResponse{Errors: map[string]map[int32]sarama.KError{topic: {0: code}}}
}
func seedTestQuery() request {
	return request{Mode: "seed-group", Prefix: "calcify-o2-test", Topic: "calcify-o2-test-commands", Group: "calcify-o2-test-engine-p00"}
}
func TestSeedRetriesOnlyCoordinatorReadsAndVerifiesActualCut(t *testing.T) {
	q := seedTestQuery()
	reads, commits, bounds := 0, 0, 0
	ops := seedOperations{bounds: func() (any, error) { bounds++; return [2]int64{0, 0}, nil }, fetch: func() (any, error) {
		reads++
		switch reads {
		case 1:
			return nil, sarama.ErrConsumerCoordinatorNotAvailable
		case 2:
			return seedFetch(q.Topic, -1), nil
		case 3:
			return nil, sarama.ErrNotCoordinatorForConsumer
		default:
			return seedFetch(q.Topic, 0), nil
		}
	}, commit: func() (any, error) { commits++; return seedCommit(q.Topic, sarama.ErrNoError), nil }}
	v, e := seedGenesis(context.Background(), q, ops, time.Nanosecond)
	if e != nil {
		t.Fatal(e)
	}
	r := v.(map[string]any)
	if commits != 1 || bounds != 2 || reads != 4 || r["offset"] != "0" || r["previousOffset"] != "-1" || r["beforeReadAttempts"] != 2 || r["afterReadAttempts"] != 2 {
		t.Fatal("actual seed observations not preserved")
	}
}
func TestSeedRefusesMissingExistingErrorAndChangedGenesisWithoutCommit(t *testing.T) {
	q := seedTestQuery()
	for _, kind := range []string{"nil-response", "missing-block", "existing-zero", "existing-positive", "offset-auth", "offset-coordinator-block", "nonempty", "changed"} {
		t.Run(kind, func(t *testing.T) {
			reads, commits, bounds := 0, 0, 0
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
			defer cancel()
			ops := seedOperations{bounds: func() (any, error) {
				bounds++
				if kind == "nonempty" || kind == "changed" && bounds == 2 {
					return [2]int64{0, 1}, nil
				}
				return [2]int64{0, 0}, nil
			}, fetch: func() (any, error) {
				reads++
				switch kind {
				case "nil-response":
					return (*sarama.OffsetFetchResponse)(nil), nil
				case "missing-block":
					return &sarama.OffsetFetchResponse{}, nil
				case "existing-zero":
					return seedFetch(q.Topic, 0), nil
				case "existing-positive":
					return seedFetch(q.Topic, 4), nil
				case "offset-auth":
					r := seedFetch(q.Topic, -1)
					r.GetBlock(q.Topic, 0).Err = sarama.ErrGroupAuthorizationFailed
					return r, nil
				case "offset-coordinator-block":
					r := seedFetch(q.Topic, -1)
					r.GetBlock(q.Topic, 0).Err = sarama.ErrConsumerCoordinatorNotAvailable
					return r, nil
				}
				return seedFetch(q.Topic, -1), nil
			}, commit: func() (any, error) { commits++; return seedCommit(q.Topic, sarama.ErrNoError), nil }}
			if _, e := seedGenesis(ctx, q, ops, time.Millisecond); e == nil || commits != 0 {
				t.Fatal("unsafe commit")
			}
			if kind == "offset-auth" && reads != 1 {
				t.Fatal("auth retried")
			}
		})
	}
}
func TestSeedGenericReadAndUncertainCommitNeverRetry(t *testing.T) {
	q := seedTestQuery()
	for _, cause := range []error{io.EOF, context.DeadlineExceeded, sarama.ErrGroupAuthorizationFailed, sarama.ErrSASLAuthenticationFailed, errors.New("nonpublic-secret-sentinel")} {
		reads, commits := 0, 0
		ops := seedOperations{bounds: func() (any, error) { return [2]int64{0, 0}, nil }, fetch: func() (any, error) { reads++; return nil, cause }, commit: func() (any, error) { commits++; return nil, nil }}
		_, e := seedGenesis(context.Background(), q, ops, time.Nanosecond)
		if e == nil || reads != 1 || commits != 0 {
			t.Fatal("generic/auth read retry")
		}
		encoded, _ := json.Marshal(errorReceipt(q, e))
		if bytes.Contains(encoded, []byte("nonpublic-secret-sentinel")) {
			t.Fatal("error text leaked")
		}
	}
	for _, cause := range []error{io.EOF, sarama.ErrConsumerCoordinatorNotAvailable, sarama.ErrNotCoordinatorForConsumer} {
		reads, commits := 0, 0
		ops := seedOperations{bounds: func() (any, error) { return [2]int64{0, 0}, nil }, fetch: func() (any, error) { reads++; return seedFetch(q.Topic, -1), nil }, commit: func() (any, error) { commits++; return nil, cause }}
		_, e := seedGenesis(context.Background(), q, ops, time.Nanosecond)
		if e == nil || reads != 1 || commits != 1 {
			t.Fatal("uncertain commit retried")
		}
		r := errorReceipt(q, e)
		if r.Data.(map[string]any)["phase"] != "commit" || r.Data.(map[string]any)["commitMayHaveStarted"] != true {
			t.Fatal("uncertainty missing")
		}
		if k, ok := cause.(sarama.KError); ok && r.Code != fmt.Sprint(int(k)) {
			t.Fatal("numeric coordinator diagnostics missing")
		}
	}
}
func TestSeedCommitAndReadbackRequirePresentSuccessfulBlocks(t *testing.T) {
	q := seedTestQuery()
	for _, kind := range []string{"nil-commit", "missing-topic", "missing-partition", "commit-error", "readback-missing", "readback-negative", "readback-positive", "readback-auth"} {
		t.Run(kind, func(t *testing.T) {
			reads, commits := 0, 0
			ops := seedOperations{bounds: func() (any, error) { return [2]int64{0, 0}, nil }, fetch: func() (any, error) {
				reads++
				if reads == 1 {
					return seedFetch(q.Topic, -1), nil
				}
				switch kind {
				case "readback-missing":
					return &sarama.OffsetFetchResponse{}, nil
				case "readback-negative":
					return seedFetch(q.Topic, -1), nil
				case "readback-positive":
					return seedFetch(q.Topic, 2), nil
				case "readback-auth":
					return nil, sarama.ErrGroupAuthorizationFailed
				}
				return seedFetch(q.Topic, 0), nil
			}, commit: func() (any, error) {
				commits++
				switch kind {
				case "nil-commit":
					return (*sarama.OffsetCommitResponse)(nil), nil
				case "missing-topic":
					return &sarama.OffsetCommitResponse{}, nil
				case "missing-partition":
					return &sarama.OffsetCommitResponse{Errors: map[string]map[int32]sarama.KError{q.Topic: {}}}, nil
				case "commit-error":
					return seedCommit(q.Topic, sarama.ErrNotCoordinatorForConsumer), nil
				}
				return seedCommit(q.Topic, sarama.ErrNoError), nil
			}}
			if _, e := seedGenesis(context.Background(), q, ops, time.Nanosecond); e == nil || commits != 1 {
				t.Fatal("missing/error actualack or readback accepted")
			}
		})
	}
}
func TestSeedDeadlineEndsBlockedReadWithoutMutation(t *testing.T) {
	q := seedTestQuery()
	release := make(chan struct{})
	defer close(release)
	commits := 0
	ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond)
	defer cancel()
	ops := seedOperations{bounds: func() (any, error) { <-release; return [2]int64{0, 0}, nil }, fetch: func() (any, error) { t.Error("fetch afterdeadline"); return nil, nil }, commit: func() (any, error) { commits++; return nil, nil }}
	_, e := seedGenesis(ctx, q, ops, time.Millisecond)
	if !errors.Is(e, context.DeadlineExceeded) || commits != 0 {
		t.Fatal("deadline permits mutation")
	}
}

type historyTestClient struct {
	sarama.Client
	low                  int64
	offsetErr, leaderErr error
	broker               *sarama.Broker
}

func (c historyTestClient) GetOffset(string, int32, int64) (int64, error) { return c.low, c.offsetErr }
func (c historyTestClient) Leader(string, int32) (*sarama.Broker, error) {
	return c.broker, c.leaderErr
}
func TestHistoryDiagnosticEarlyFailuresHideServerText(t *testing.T) {
	q := request{Mode: "history", Topic: "calcify-o2-test-source"}
	for _, tc := range []struct {
		name string
		c    historyTestClient
		code string
	}{{"offset", historyTestClient{offsetErr: errors.New("synthetic-private-password")}, "history_offset_lookup"}, {"genesis", historyTestClient{low: 1}, "history_lost_genesis"}, {"leader", historyTestClient{leaderErr: errors.New("synthetic-private-password")}, "history_leader_lookup"}} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := history(tc.c, q)
			if err == nil {
				t.Fatal("failure accepted")
			}
			r := errorReceipt(q, err)
			raw, _ := encodeReceipt(r, 32768)
			if r.Status != "error" || r.Code != tc.code || bytes.Contains(raw, []byte("synthetic-private-password")) {
				t.Fatal("unsafe or missing typed refusal", string(raw))
			}
		})
	}
}
func TestHistoryActualDecodedCompressedBatchStillRefuses(t *testing.T) {
	q := request{Mode: "history", Topic: "calcify-o2-test-source"}
	mock := sarama.NewMockBroker(t, 1)
	defer mock.Close()
	response := &sarama.FetchResponse{Version: 11, Blocks: map[string]map[int32]*sarama.FetchResponseBlock{q.Topic: {0: {LastStableOffset: 1, HighWaterMarkOffset: 1, RecordsSet: []*sarama.Records{{RecordBatch: &sarama.RecordBatch{Version: 2, Codec: sarama.CompressionLZ4, Records: []*sarama.Record{{Value: []byte("finite-public-record")}}}}}}}}}
	mock.Returns(response)
	b := sarama.NewBroker(mock.Addr())
	conf := sarama.NewConfig()
	conf.Version = sarama.V2_8_0_0
	conf.ApiVersionsRequest = false
	if err := b.Open(conf); err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	_, err := history(historyTestClient{broker: b}, q)
	if err == nil {
		t.Fatal("codec refusal weakened")
	}
	r := errorReceipt(q, err)
	raw, _ := encodeReceipt(r, 32768)
	if r.Code != "history_unsupported_codec" {
		t.Fatal(string(raw))
	}
	data := r.Data.(map[string]any)
	if data["codec"] != int(sarama.CompressionLZ4) || data["fetchCount"] != 1 || data["recordCount"] != 0 {
		t.Fatal(string(raw))
	}
	if bytes.Contains(raw, []byte("finite-public-record")) {
		t.Fatal("record bytes leaked")
	}
}
func TestHistoryDiagnosticEnumsMetricsAndWrappedDenial(t *testing.T) {
	q := request{Mode: "history"}
	phases := []string{"offset_lookup", "lost_genesis", "leader_lookup", "fetch_request", "missing_block", "kafka_error", "unstable_cut", "missing_batch", "partial_batch", "unsupported_codec", "record_budget", "no_progress", "fetch_budget"}
	for _, phase := range phases {
		t.Run(phase, func(t *testing.T) {
			err := &historyPhaseError{phase: phase, fetchCount: 2, position: 7, end: 9, recordCount: 12, payloadBytes: 1234, codec: 4, cause: errors.New("synthetic-private-password")}
			r := errorReceipt(q, err)
			raw, e := encodeReceipt(r, 32768)
			if e != nil || r.Code != "history_"+phase || bytes.Contains(raw, []byte("synthetic-private-password")) {
				t.Fatal("unsafe typed refusal", string(raw))
			}
			d := r.Data.(map[string]any)
			if d["position"] != "7" || d["readCommittedEndOffset"] != "9" || d["codec"] != 4 {
				t.Fatal("counter mismatch")
			}
		})
	}
	r := errorReceipt(q, &historyPhaseError{phase: "kafka_error", cause: sarama.ErrTopicAuthorizationFailed})
	if r.Status != "denied" || r.Code != "29" || r.Data.(map[string]any)["kafkaErrorCode"] != 29 {
		t.Fatal("denial classification lost")
	}
	r = errorReceipt(q, &historyPhaseError{phase: "synthetic-private-password", cause: errors.New("synthetic-private-password")})
	raw, _ := encodeReceipt(r, 32768)
	if r.Code != "operation_failed" || r.Data != nil || bytes.Contains(raw, []byte("synthetic-private-password")) {
		t.Fatal("unrecognized diagnostic text leaked")
	}
}
