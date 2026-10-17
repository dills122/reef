// calcify-finite-probe is a fixture-only, bounded stdin/stdout client. Secrets
// never belong in argv or public receipts. It cannot authorize finite startup.
package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
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
