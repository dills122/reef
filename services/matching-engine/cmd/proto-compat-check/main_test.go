package main

import (
	"testing"

	"google.golang.org/protobuf/types/descriptorpb"
)

func TestCompatibilityViolationsAcceptsAddedField(t *testing.T) {
	baseline := descriptorSet(field("order_id", 1, descriptorpb.FieldDescriptorProto_TYPE_STRING))
	current := descriptorSet(
		field("order_id", 1, descriptorpb.FieldDescriptorProto_TYPE_STRING),
		field("participant_id", 2, descriptorpb.FieldDescriptorProto_TYPE_STRING),
	)
	if violations := compatibilityViolations(baseline, current); len(violations) != 0 {
		t.Fatalf("expected additive descriptor to pass, got %v", violations)
	}
}

func TestCompatibilityViolationsRejectsRemovedAndRetypedFields(t *testing.T) {
	baseline := descriptorSet(
		field("order_id", 1, descriptorpb.FieldDescriptorProto_TYPE_STRING),
		field("quantity", 2, descriptorpb.FieldDescriptorProto_TYPE_STRING),
	)
	current := descriptorSet(field("order_id", 1, descriptorpb.FieldDescriptorProto_TYPE_INT64))
	violations := compatibilityViolations(baseline, current)
	if len(violations) != 2 {
		t.Fatalf("expected removed and changed field violations, got %v", violations)
	}
}

func descriptorSet(fields ...*descriptorpb.FieldDescriptorProto) *descriptorpb.FileDescriptorSet {
	return &descriptorpb.FileDescriptorSet{File: []*descriptorpb.FileDescriptorProto{{
		Name:    stringPointer("contracts/proto/test.proto"),
		Package: stringPointer("reef.test.v1"),
		MessageType: []*descriptorpb.DescriptorProto{{
			Name:  stringPointer("Order"),
			Field: fields,
		}},
	}}}
}

func field(name string, number int32, fieldType descriptorpb.FieldDescriptorProto_Type) *descriptorpb.FieldDescriptorProto {
	label := descriptorpb.FieldDescriptorProto_LABEL_OPTIONAL
	return &descriptorpb.FieldDescriptorProto{Name: &name, Number: &number, Type: &fieldType, Label: &label}
}

func stringPointer(value string) *string {
	return &value
}
