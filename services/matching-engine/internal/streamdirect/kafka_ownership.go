package streamdirect

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"sync"

	"github.com/IBM/sarama"
)

const kafkaOwnershipStrategyName = "reef-static-lanes-v1"

type kafkaOwnershipMember struct {
	ShardID    string  `json:"shardId"`
	Partitions []int32 `json:"partitions"`
}

// KafkaOwnershipLease uses static consumer-group membership as the control
// plane fence for an engine shard. Kafka transactions protect each batch;
// this lease prevents an old process from repeatedly reacquiring the producer
// epoch between batches after a replacement has started.
type KafkaOwnershipLease struct {
	group      sarama.ConsumerGroup
	cancel     context.CancelFunc
	done       chan struct{}
	state      chan struct{}
	partitions map[int32]bool

	mu       sync.RWMutex
	owned    map[int32]bool
	terminal error
	once     sync.Once
}

func AcquireKafkaOwnershipLease(parent context.Context, config KafkaConfig, shardID string, partitions []int) (*KafkaOwnershipLease, error) {
	requested := make([]int32, 0, len(partitions))
	requestedSet := make(map[int32]bool, len(partitions))
	for _, partition := range partitions {
		value := int32(partition)
		if requestedSet[value] {
			continue
		}
		requestedSet[value] = true
		requested = append(requested, value)
	}
	sort.Slice(requested, func(i, j int) bool { return requested[i] < requested[j] })
	memberData, err := json.Marshal(kafkaOwnershipMember{ShardID: shardID, Partitions: requested})
	if err != nil {
		return nil, err
	}
	clientConfig := newKafkaClientConfig("reef-matching-engine-ownership-" + shardID)
	clientConfig.Version = sarama.V2_3_0_0
	clientConfig.Consumer.Group.InstanceId = shardID
	clientConfig.Consumer.Group.Member.UserData = memberData
	clientConfig.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{&kafkaOwnershipBalanceStrategy{}}
	clientConfig.Consumer.Offsets.AutoCommit.Enable = false
	groupID := config.OwnershipGroup
	if groupID == "" {
		groupID = "reef-matching-engine-ownership-v1"
	}
	group, err := sarama.NewConsumerGroup(kafkaBrokers(config), groupID, clientConfig)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(parent)
	lease := &KafkaOwnershipLease{
		group:      group,
		cancel:     cancel,
		done:       make(chan struct{}),
		state:      make(chan struct{}, 1),
		partitions: requestedSet,
		owned:      make(map[int32]bool),
	}
	go lease.run(ctx, config.OwnershipTopic)
	for {
		select {
		case <-parent.Done():
			_ = lease.Close()
			return nil, parent.Err()
		case <-lease.done:
			return nil, lease.terminalError()
		case <-lease.state:
			if lease.ownsAllRequested() {
				return lease, nil
			}
		}
	}
}

func (l *KafkaOwnershipLease) run(ctx context.Context, topic string) {
	var err error
	for ctx.Err() == nil {
		err = l.group.Consume(ctx, []string{topic}, l)
		if err != nil {
			break
		}
	}
	l.mu.Lock()
	l.owned = make(map[int32]bool)
	if ctx.Err() == nil && err != nil {
		l.terminal = err
	}
	l.mu.Unlock()
	l.signalState()
	l.once.Do(func() { close(l.done) })
}

func (l *KafkaOwnershipLease) Setup(session sarama.ConsumerGroupSession) error {
	claimed := make(map[int32]bool)
	for _, partitions := range session.Claims() {
		for _, partition := range partitions {
			claimed[partition] = true
		}
	}
	for partition := range l.partitions {
		if !claimed[partition] {
			return fmt.Errorf("Kafka ownership shard missing configured partition %d; overlapping shard ownership is not allowed", partition)
		}
	}
	l.mu.Lock()
	l.owned = claimed
	l.mu.Unlock()
	l.signalState()
	return nil
}

func (l *KafkaOwnershipLease) Cleanup(sarama.ConsumerGroupSession) error {
	l.mu.Lock()
	l.owned = make(map[int32]bool)
	l.mu.Unlock()
	l.signalState()
	return nil
}

func (l *KafkaOwnershipLease) ConsumeClaim(session sarama.ConsumerGroupSession, claim sarama.ConsumerGroupClaim) error {
	for {
		select {
		case <-session.Context().Done():
			return session.Context().Err()
		case _, ok := <-claim.Messages():
			if !ok {
				return nil
			}
		}
	}
}

func (l *KafkaOwnershipLease) AssertOwned(partition int32) error {
	l.mu.RLock()
	defer l.mu.RUnlock()
	if l.terminal != nil {
		return &fatalProcessorError{cause: fmt.Errorf("Kafka ownership lost for partition %d: %w", partition, l.terminal)}
	}
	select {
	case <-l.done:
		return &fatalProcessorError{cause: fmt.Errorf("Kafka ownership ended for partition %d", partition)}
	default:
	}
	if !l.owned[partition] {
		return fmt.Errorf("Kafka ownership temporarily unavailable for partition %d", partition)
	}
	return nil
}

func (l *KafkaOwnershipLease) Close() error {
	if l == nil {
		return nil
	}
	l.cancel()
	err := l.group.Close()
	<-l.done
	return err
}

func (l *KafkaOwnershipLease) ownsAllRequested() bool {
	l.mu.RLock()
	defer l.mu.RUnlock()
	for partition := range l.partitions {
		if !l.owned[partition] {
			return false
		}
	}
	return true
}

func (l *KafkaOwnershipLease) terminalError() error {
	l.mu.RLock()
	defer l.mu.RUnlock()
	if l.terminal != nil {
		return l.terminal
	}
	return fmt.Errorf("Kafka ownership lease closed")
}

func (l *KafkaOwnershipLease) signalState() {
	select {
	case l.state <- struct{}{}:
	default:
	}
}

type kafkaOwnershipBalanceStrategy struct{}

func (*kafkaOwnershipBalanceStrategy) Name() string { return kafkaOwnershipStrategyName }

func (*kafkaOwnershipBalanceStrategy) AssignmentData(string, map[string][]int32, int32) ([]byte, error) {
	return nil, nil
}

func (*kafkaOwnershipBalanceStrategy) Plan(members map[string]sarama.ConsumerGroupMemberMetadata, topics map[string][]int32) (sarama.BalanceStrategyPlan, error) {
	memberIDs := make([]string, 0, len(members))
	for memberID := range members {
		memberIDs = append(memberIDs, memberID)
	}
	sort.Strings(memberIDs)
	plan := make(sarama.BalanceStrategyPlan, len(members))
	owners := make(map[string]map[int32]string)
	for _, memberID := range memberIDs {
		metadata := members[memberID]
		var request kafkaOwnershipMember
		if err := json.Unmarshal(metadata.UserData, &request); err != nil {
			return nil, fmt.Errorf("decode Kafka ownership member %s: %w", memberID, err)
		}
		for _, topic := range metadata.Topics {
			available := make(map[int32]bool, len(topics[topic]))
			for _, partition := range topics[topic] {
				available[partition] = true
			}
			if owners[topic] == nil {
				owners[topic] = make(map[int32]string)
			}
			for _, partition := range request.Partitions {
				if !available[partition] || owners[topic][partition] != "" {
					continue
				}
				owners[topic][partition] = memberID
				plan.Add(memberID, topic, partition)
			}
		}
	}
	return plan, nil
}
