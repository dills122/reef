package streamdirect

import (
	"encoding/json"
	"testing"

	"github.com/IBM/sarama"
)

func TestKafkaOwnershipBalanceStrategyRejectsOverlappingLaneClaims(t *testing.T) {
	first, _ := json.Marshal(kafkaOwnershipMember{ShardID: "engine-a", Partitions: []int32{0, 1}})
	second, _ := json.Marshal(kafkaOwnershipMember{ShardID: "engine-b", Partitions: []int32{1, 2}})
	plan, err := (&kafkaOwnershipBalanceStrategy{}).Plan(
		map[string]sarama.ConsumerGroupMemberMetadata{
			"member-a": {Topics: []string{"ownership"}, UserData: first},
			"member-b": {Topics: []string{"ownership"}, UserData: second},
		},
		map[string][]int32{"ownership": {0, 1, 2}},
	)
	if err != nil {
		t.Fatalf("Plan failed: %v", err)
	}
	if got := plan["member-a"]["ownership"]; len(got) != 2 || got[0] != 0 || got[1] != 1 {
		t.Fatalf("unexpected first-owner assignment %v", got)
	}
	if got := plan["member-b"]["ownership"]; len(got) != 1 || got[0] != 2 {
		t.Fatalf("overlapping partition must not be assigned twice, got %v", got)
	}
}

func TestKafkaOwnershipBalanceStrategyRejectsMalformedMemberData(t *testing.T) {
	_, err := (&kafkaOwnershipBalanceStrategy{}).Plan(
		map[string]sarama.ConsumerGroupMemberMetadata{
			"member-a": {Topics: []string{"ownership"}, UserData: []byte("not-json")},
		},
		map[string][]int32{"ownership": {0}},
	)
	if err == nil {
		t.Fatal("expected malformed ownership metadata to fail")
	}
}
