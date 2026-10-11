package streamdirect

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/IBM/sarama"
	"github.com/dills122/reef/services/matching-engine/internal/domain"
)

type FiniteBrokerObservation struct {
	ClusterID string
	Topics    []FiniteTopic
	Cuts      FiniteCuts
	Commands  []*sarama.ConsumerMessage `json:"-"`
}

func finiteChallenge() (string, error) {
	raw := make([]byte, 24)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func fetchFiniteWitness(ctx context.Context, origin, token, digest string) (*FiniteStartupWitness, error) {
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return nil, fmt.Errorf("finite binding endpoint must be HTTP(S) origin")
	}
	if _, err := finiteDigest(digest); err != nil {
		return nil, err
	}
	nonce, err := finiteChallenge()
	if err != nil {
		return nil, err
	}
	u.Path = "/internal/calcify/finite-bindings/" + digest
	u.RawQuery = url.Values{"nonce": []string{nonce}}.Encode()
	requestCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(requestCtx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("finite witness request failed")
	}
	req.Header.Set("Authorization", "Bearer "+token)
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return fmt.Errorf("finite witness redirects forbidden") }}
	defer transport.CloseIdleConnections()
	response, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("finite witness unavailable")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("finite witness refused status %d", response.StatusCode)
	}
	if response.Header.Get("Content-Type") != "application/json" && !strings.HasPrefix(response.Header.Get("Content-Type"), "application/json;") {
		return nil, fmt.Errorf("finite witness content type mismatch")
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, finiteStartupLimit+1))
	if err != nil {
		return nil, fmt.Errorf("finite witness body unavailable")
	}
	return ParseFiniteStartup(raw, nonce, digest)
}

func finiteKafkaConfig(config RuntimeConfig, auth *FiniteBrokerCredentials) KafkaConfig {
	return KafkaConfig{Finite: auth, BootstrapServers: config.KafkaBootstrap, CommandTopic: config.CommandStream, CommandSubjectPrefix: config.CommandSubjectPrefix, EventTopic: config.EventStream, EventSubjectPrefix: config.EventSubjectPrefix, OwnershipTopic: config.OwnershipTopic, OwnershipGroup: config.OwnershipGroup, PartitionCount: config.PartitionCount, FetchTimeout: config.FetchTimeout}
}

func validateFiniteEffectiveConfig(w *FiniteStartupWitness, config RuntimeConfig) error {
	o := w.Opening
	route := o.CommandSubjectPrefix + ".p00." + o.VenueSessionID + "." + o.InstrumentID + ".SubmitOrder"
	if venueSessionIDFromSubject(route) != o.VenueSessionID || instrumentIDFromSubject(route) != o.InstrumentID {
		return fmt.Errorf("finite opening scope cannot use canonical matching routing")
	}
	if !config.Enabled || !config.CalcifyLifecycleEnabled || config.LogProvider != "redpanda" || config.BatchSize != 1 || config.PartitionCount != 1 || len(config.Partitions) != 1 || config.Partitions[0] != 0 || config.ShardID != o.MatcherShardID || config.OwnershipGroup != o.OwnershipGroup || fmt.Sprintf("%s-%s", config.DurablePrefix, PartitionToken(1, 0)) != o.CommandConsumerGroup || config.CommandSubjectPrefix != o.CommandSubjectPrefix || config.EventSubjectPrefix != o.SourceSubjectPrefix || config.CommandStream != o.Topics[0].Name || config.EventStream != o.Topics[1].Name || config.OwnershipTopic != o.Topics[3].Name {
		return fmt.Errorf("finite effective routing/config mismatch")
	}
	if w.Run.AdmissionState != "STOPPED" || w.Run.UnresolvedPublications != 0 {
		return fmt.Errorf("finite startup requires stopped resolved publication journal")
	}
	for _, j := range w.Journal {
		if j.State != "PUBLISHED" && j.State != "DEFINITELY_NOT_SENT" {
			return fmt.Errorf("finite startup retains unresolved publication")
		}
	}
	return nil
}

// ObserveFiniteBroker reads actual broker identity/cuts/commands without topic creation.
// Passing an opening here supplies expectations only; this API cannot activate a runner.
func ObserveFiniteBroker(ctx context.Context, opening FiniteOpening, config KafkaConfig) (*FiniteBrokerObservation, error) {
	if err := opening.validate(); err != nil {
		return nil, err
	}
	if config.Finite == nil || config.Finite.Username == "" || config.Finite.Password == "" {
		return nil, fmt.Errorf("authenticated finite broker configuration required")
	}
	c := kafkaConfiguredClient(config, "reef-calcify-finite-observer")
	c.Version = sarama.V2_8_0_0
	client, err := sarama.NewClient(kafkaBrokers(config), c)
	if err != nil {
		return nil, fmt.Errorf("finite broker connection failed: %w", err)
	}
	defer client.Close()
	controller, err := client.Controller()
	if err != nil {
		return nil, err
	}
	names := make([]string, len(opening.Topics))
	for i, t := range opening.Topics {
		names[i] = t.Name
	}
	request := sarama.NewMetadataRequest(sarama.V2_8_0_0, names)
	request.AllowAutoTopicCreation = false
	metadata, err := controller.GetMetadata(request)
	if err != nil {
		return nil, fmt.Errorf("finite UUID metadata unavailable: %w", err)
	}
	if metadata.Version < 10 || metadata.ClusterID == nil || *metadata.ClusterID != opening.BrokerClusterID {
		return nil, fmt.Errorf("finite actual cluster identity mismatch")
	}
	admin, err := sarama.NewClusterAdminFromClient(client)
	if err != nil {
		return nil, err
	}
	// Admin shares client; client owns closure for this bounded observation.
	observation := &FiniteBrokerObservation{ClusterID: *metadata.ClusterID, Cuts: FiniteCuts{Ranges: make([]FiniteRange, 0, 5)}}
	for i, t := range opening.Topics {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		var actual *sarama.TopicMetadata
		for _, entry := range metadata.Topics {
			if entry.Name == t.Name {
				if actual != nil {
					return nil, fmt.Errorf("duplicate finite broker topic metadata")
				}
				actual = entry
			}
		}
		if actual == nil || actual.Err != sarama.ErrNoError || actual.Uuid.String() != t.TopicID || len(actual.Partitions) != 1 || actual.Partitions[0].ID != 0 || actual.Partitions[0].Err != sarama.ErrNoError || len(actual.Partitions[0].Replicas) != 1 {
			return nil, fmt.Errorf("finite actual topic identity/routing mismatch for role %s", t.Role)
		}
		properties, err := admin.DescribeConfig(sarama.ConfigResource{Type: sarama.TopicResource, Name: t.Name, ConfigNames: []string{"cleanup.policy", "max.message.bytes"}})
		if err != nil {
			return nil, fmt.Errorf("finite topic configuration unavailable for %s", t.Role)
		}
		values := map[string]string{}
		for _, p := range properties {
			values[p.Name] = p.Value
		}
		if i < 3 && values["cleanup.policy"] != "delete" {
			return nil, fmt.Errorf("finite canonical history must retain uncompacted records")
		}
		if i == 4 && values["cleanup.policy"] != "compact" {
			return nil, fmt.Errorf("finite managed changelog policy mismatch")
		}
		minimum := []int{8192, 65536, 131072, 1, 6307840}[i]
		maxBytes, err := strconv.Atoi(values["max.message.bytes"])
		if err != nil || maxBytes < minimum {
			return nil, fmt.Errorf("finite topic record limit too small for %s", t.Role)
		}
		begin, err := client.GetOffset(t.Name, 0, sarama.OffsetOldest)
		if err != nil {
			return nil, err
		}
		end, err := client.GetOffset(t.Name, 0, sarama.OffsetNewest)
		if err != nil {
			return nil, err
		}
		leader, err := client.Leader(t.Name, 0)
		if err != nil {
			return nil, err
		}
		stableEnd, err := finiteBrokerStableEnd(leader, t.Name)
		if err != nil {
			return nil, err
		}
		if begin < 0 || end < 0 || stableEnd < begin || stableEnd > end || uint64(end) > finiteOffsetLimit {
			return nil, fmt.Errorf("finite broker stable history unavailable for %s", t.Role)
		}
		if i < 3 && begin != 0 {
			return nil, fmt.Errorf("finite canonical history missing genesis prefix for %s", t.Role)
		}
		observation.Topics = append(observation.Topics, t)
		observation.Cuts.Ranges = append(observation.Cuts.Ranges, FiniteRange{Role: t.Role, Partition: 0, BeginningOffset: FiniteDecimal(begin), EndOffset: FiniteDecimal(end), ReadCommittedEndOffset: FiniteDecimal(stableEnd)})
	}
	commandCut, err := finiteActualGroupCut(client, opening.CommandConsumerGroup, opening.Topics[0].Name)
	if err != nil {
		return nil, err
	}
	captureCut, err := finiteActualGroupCut(client, opening.CaptureApplicationID, opening.Topics[1].Name)
	if err != nil {
		return nil, err
	}
	observation.Cuts.CommandGroup = FiniteGroupCut{GroupID: opening.CommandConsumerGroup, NextOffset: FiniteDecimal(commandCut)}
	observation.Cuts.CaptureSourceGroup = FiniteGroupCut{GroupID: opening.CaptureApplicationID, NextOffset: FiniteDecimal(captureCut)}
	commandRange := observation.Cuts.Ranges[0]
	if commandRange.BeginningOffset != 0 || commandRange.EndOffset != commandRange.ReadCommittedEndOffset || commandRange.EndOffset > opening.Budget.Publications || commandCut > int64(commandRange.EndOffset) {
		return nil, fmt.Errorf("finite command history violates retained nontransactional profile")
	}
	if commandRange.EndOffset > 0 {
		consumer, err := sarama.NewConsumerFromClient(client)
		if err != nil {
			return nil, err
		}
		defer consumer.Close()
		partition, err := consumer.ConsumePartition(opening.Topics[0].Name, 0, 0)
		if err != nil {
			return nil, err
		}
		defer partition.Close()
		for expected := int64(0); expected < int64(commandRange.EndOffset); expected++ {
			select {
			case <-ctx.Done():
				return nil, fmt.Errorf("finite command observation deadline")
			case failure := <-partition.Errors():
				return nil, fmt.Errorf("finite command observation failed: %v", failure)
			case message, ok := <-partition.Messages():
				if !ok || message.Offset != expected || message.Partition != 0 || len(message.Value) > int(opening.Budget.CommandBytes) {
					return nil, fmt.Errorf("finite command history missing/oversized")
				}
				observation.Commands = append(observation.Commands, message)
			}
		}
	}
	return observation, nil
}

// ListOffsets at read_committed returns actual LSO using DESCRIBE permission;
// unlike Fetch-at-end it grants no read access to capture/changelog records.
func finiteBrokerStableEnd(leader *sarama.Broker, topic string) (int64, error) {
	request := sarama.NewOffsetRequest(sarama.V2_8_0_0)
	request.IsolationLevel = sarama.ReadCommitted
	request.AddBlock(topic, 0, sarama.OffsetNewest, 1)
	response, err := leader.GetAvailableOffsets(request)
	if err != nil {
		return 0, err
	}
	if response.Version < 2 {
		return 0, fmt.Errorf("finite broker lacks read-committed offset observation")
	}
	block := response.GetBlock(topic, 0)
	if block == nil || block.Err != sarama.ErrNoError || block.Offset < 0 || uint64(block.Offset) > finiteOffsetLimit {
		return 0, fmt.Errorf("finite actual read-committed end unavailable")
	}
	return block.Offset, nil
}

func finiteActualGroupCut(client sarama.Client, group, topic string) (int64, error) {
	coordinator, err := client.Coordinator(group)
	if err != nil {
		return 0, err
	}
	req := sarama.NewOffsetFetchRequest(sarama.V2_8_0_0, group, map[string][]int32{topic: []int32{0}})
	req.RequireStable = true
	response, err := coordinator.FetchOffset(req)
	if err != nil {
		return 0, err
	}
	block := response.GetBlock(topic, 0)
	if response.Err != sarama.ErrNoError || block == nil || block.Err != sarama.ErrNoError || block.Offset < 0 || uint64(block.Offset) > finiteOffsetLimit {
		return 0, fmt.Errorf("finite group requires actual seeded/retained cut")
	}
	return block.Offset, nil
}

func verifyFiniteObservation(w *FiniteStartupWitness, actual *FiniteBrokerObservation) error {
	if actual.ClusterID != w.Opening.BrokerClusterID || len(actual.Topics) != len(w.Opening.Topics) || len(actual.Cuts.Ranges) != 5 {
		return fmt.Errorf("finite broker observation mismatch")
	}
	for i, t := range actual.Topics {
		if i < 3 && actual.Cuts.Ranges[i].BeginningOffset != 0 {
			return fmt.Errorf("finite canonical history missing genesis prefix")
		}
		if t != w.Opening.Topics[i] || actual.Cuts.Ranges[i] != w.Cuts.Ranges[i] {
			return fmt.Errorf("finite UUID/range changed during startup")
		}
	}
	if actual.Cuts.CommandGroup != w.Cuts.CommandGroup || actual.Cuts.CaptureSourceGroup != w.Cuts.CaptureSourceGroup {
		return fmt.Errorf("finite group cut changed during startup")
	}
	published := map[int64]FiniteJournalEntry{}
	for _, entry := range w.Journal {
		if entry.State == "PUBLISHED" {
			offset := int64(*entry.Offset)
			if _, exists := published[offset]; exists {
				return fmt.Errorf("duplicate finite publication position")
			}
			published[offset] = entry
		}
	}
	if len(published) != len(actual.Commands) {
		return fmt.Errorf("finite journal/retained command membership mismatch")
	}
	for _, message := range actual.Commands {
		entry, ok := published[message.Offset]
		if !ok {
			return fmt.Errorf("unregistered finite retained command")
		}
		if err := verifyFiniteCommand(w.Opening, message, entry); err != nil {
			return err
		}
	}
	return nil
}

func verifyFiniteCommand(opening FiniteOpening, message *sarama.ConsumerMessage, entry FiniteJournalEntry) error {
	if message.Topic != opening.Topics[0].Name || message.Partition != 0 || message.Key == nil || finiteHash(message.Value) != entry.PayloadHash || finiteHash(message.Key) != entry.KeyHash || len(message.Value) != int(entry.PayloadBytes) || message.Offset != int64(*entry.Offset) || kafkaStreamSequence(0, message.Offset) != uint64(*entry.StreamSequence) {
		return fmt.Errorf("finite retained command payload/key/position mismatch")
	}
	count := 0
	subject := ""
	for _, header := range message.Headers {
		if string(header.Key) == kafkaStreamSubjectHeader {
			count++
			subject = string(header.Value)
		}
	}
	expected := opening.CommandSubjectPrefix + ".p00." + opening.VenueSessionID + "." + opening.InstrumentID + "." + entry.CommandType
	if count != 1 || subject != expected || finiteHash([]byte(subject)) != entry.SubjectHash {
		return fmt.Errorf("finite retained command subject mismatch")
	}
	route := map[string]string{"SubmitOrder": "/api/v1/orders/submit", "ModifyOrder": "/api/v1/orders/modify", "CancelOrder": "/api/v1/orders/cancel"}[entry.CommandType]
	if route == "" || finiteHash([]byte(route)) != entry.RouteHash {
		return fmt.Errorf("finite retained command canonical route mismatch")
	}
	return validateFiniteCommandScope(opening, message.Value, entry.CommandID, entry.CommandType)
}

func validateFiniteCommandScope(opening FiniteOpening, raw []byte, commandID, commandType string) error {
	var payload map[string]json.RawMessage
	if err := finiteStrictJSON(raw, &payload); err != nil {
		return fmt.Errorf("finite command JSON invalid")
	}
	var typed any
	switch commandType {
	case "SubmitOrder":
		typed = &domain.SubmitOrder{}
	case "ModifyOrder":
		typed = &domain.ModifyOrder{}
	case "CancelOrder":
		typed = &domain.CancelOrder{}
	default:
		return fmt.Errorf("finite command type invalid")
	}
	if err := json.Unmarshal(raw, typed); err != nil {
		return fmt.Errorf("finite typed command shape invalid")
	}
	read := func(key string) string { var v string; _ = json.Unmarshal(payload[key], &v); return v }
	if read("commandId") != commandID || read("runId") != opening.RunID || read("venueSessionId") != opening.VenueSessionID || read("instrumentId") != opening.InstrumentID {
		return fmt.Errorf("finite command scope differs from opening")
	}
	orderID := read("orderId")
	ordinal, err := strconv.Atoi(strings.TrimPrefix(orderID, "p0-"))
	if !finiteText(orderID, 128) || err != nil || ordinal < 1 || ordinal > opening.Profile.MaxOrderIDs || orderID != "p0-"+strconv.Itoa(ordinal) {
		return fmt.Errorf("finite command order identity outside source profile")
	}
	// Capture retains these exact strings. Bound their representation without
	// normalizing economics or inventing missing optional command metadata.
	required := []string{"commandId", "runId", "venueSessionId", "instrumentId", "participantId", "accountId", "occurredAt"}
	optional := []string{"traceId", "causationId", "correlationId", "actorId"}
	switch commandType {
	case "SubmitOrder":
		required = append(required, "quantityUnits", "limitPrice")
		optional = append(optional, "clientOrderId")
	case "ModifyOrder":
		required = append(required, "quantityUnits", "limitPrice")
	case "CancelOrder":
		optional = append(optional, "reason")
	}
	for _, name := range required {
		value := read(name)
		if len(value) > 128 || strings.TrimSpace(value) == "" {
			return fmt.Errorf("finite required command text unavailable/oversized")
		}
	}
	for _, name := range optional {
		rawValue, present := payload[name]
		if present && (len(rawValue) == 0 || rawValue[0] != '"' || len(read(name)) > 128) {
			return fmt.Errorf("finite optional command text invalid/oversized")
		}
	}
	if _, err := time.Parse(time.RFC3339Nano, read("occurredAt")); err != nil {
		return fmt.Errorf("finite command requires retained deterministic timestamp")
	}
	for _, party := range opening.Parties {
		if read("participantId") == party.ParticipantID && read("accountId") == party.AccountID {
			// Submit has canonical side/currency/policy fields. Modify/cancel
			// inherit immutable asset mapping; never infer it from book state.
			if commandType == "SubmitOrder" {
				if read("side") != party.Side || read("currency") != opening.QuoteCurrency || read("timeInForce") != "DAY" || read("orderType") != "LIMIT" {
					return fmt.Errorf("finite submit differs from bound party/asset/policy")
				}
			}
			return nil
		}
	}
	return fmt.Errorf("finite command party not bound")
}
