package main

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"google.golang.org/protobuf/proto"
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

func TestCompatibilityViolationsRejectsRemovedFilesAndPackageChanges(t *testing.T) {
	baseline := &descriptorpb.FileDescriptorSet{File: []*descriptorpb.FileDescriptorProto{
		fileDescriptor("contracts/proto/orders.proto", "reef.orders.v1"),
		fileDescriptor("contracts/proto/removed.proto", "reef.removed.v1"),
	}}
	current := &descriptorpb.FileDescriptorSet{File: []*descriptorpb.FileDescriptorProto{
		fileDescriptor("contracts/proto/orders.proto", "reef.orders.v2"),
	}}

	want := []string{
		"file removed: contracts/proto/removed.proto",
		"package changed in contracts/proto/orders.proto: reef.orders.v1 -> reef.orders.v2",
	}
	if got := compatibilityViolations(baseline, current); !reflect.DeepEqual(got, want) {
		t.Fatalf("violations = %v, want %v", got, want)
	}
}

func TestCompatibilityViolationsRejectsEnumAndServiceChanges(t *testing.T) {
	baselineFile := fileDescriptor("contracts/proto/orders.proto", "reef.orders.v1")
	baselineFile.EnumType = []*descriptorpb.EnumDescriptorProto{
		enumDescriptor("OrderSide", enumValue("BUY", 0), enumValue("SELL", 1)),
	}
	baselineFile.Service = []*descriptorpb.ServiceDescriptorProto{
		serviceDescriptor("OrderService", methodDescriptor("Submit", ".reef.orders.v1.SubmitOrder", ".reef.orders.v1.SubmitResult")),
		serviceDescriptor("RemovedService", methodDescriptor("Ping", ".reef.orders.v1.Ping", ".reef.orders.v1.Pong")),
	}

	currentFile := fileDescriptor("contracts/proto/orders.proto", "reef.orders.v1")
	currentFile.EnumType = []*descriptorpb.EnumDescriptorProto{
		enumDescriptor("OrderSide", enumValue("BUY", 0), enumValue("ASK", 1)),
	}
	currentFile.Service = []*descriptorpb.ServiceDescriptorProto{
		serviceDescriptor("OrderService", methodDescriptor("Submit", ".reef.orders.v1.ChangedSubmit", ".reef.orders.v1.SubmitResult")),
	}

	want := []string{
		"enum value removed or changed: .reef.orders.v1.OrderSide.SELL = 1",
		"method contract changed: OrderService.Submit",
		"service removed: reef.orders.v1.RemovedService",
	}
	if got := compatibilityViolations(
		&descriptorpb.FileDescriptorSet{File: []*descriptorpb.FileDescriptorProto{baselineFile}},
		&descriptorpb.FileDescriptorSet{File: []*descriptorpb.FileDescriptorProto{currentFile}},
	); !reflect.DeepEqual(got, want) {
		t.Fatalf("violations = %v, want %v", got, want)
	}
}

func TestCompatibilityViolationsRejectsOneofChanges(t *testing.T) {
	baseline := descriptorSet(fieldInOneof("accepted", 1, descriptorpb.FieldDescriptorProto_TYPE_STRING, 0), fieldInOneof("rejected", 2, descriptorpb.FieldDescriptorProto_TYPE_STRING, 0))
	baseline.File[0].MessageType[0].OneofDecl = []*descriptorpb.OneofDescriptorProto{{Name: stringPointer("outcome")}}
	current := descriptorSet(field("accepted", 1, descriptorpb.FieldDescriptorProto_TYPE_STRING), field("rejected", 2, descriptorpb.FieldDescriptorProto_TYPE_STRING))

	violations := compatibilityViolations(baseline, current)
	if len(violations) != 2 {
		t.Fatalf("violations = %v, want both fields to reject oneof removal", violations)
	}
}

func TestReadDescriptorSetRoundTripsAndRejectsMalformedInput(t *testing.T) {
	want := descriptorSet(field("order_id", 1, descriptorpb.FieldDescriptorProto_TYPE_STRING))
	encoded, err := proto.Marshal(want)
	if err != nil {
		t.Fatalf("marshal descriptor set: %v", err)
	}
	path := filepath.Join(t.TempDir(), "descriptor.pb")
	if err := os.WriteFile(path, encoded, 0o600); err != nil {
		t.Fatalf("write descriptor set: %v", err)
	}

	got, err := readDescriptorSet(path)
	if err != nil {
		t.Fatalf("read descriptor set: %v", err)
	}
	if !proto.Equal(got, want) {
		t.Fatalf("descriptor set = %v, want %v", got, want)
	}

	malformedPath := filepath.Join(t.TempDir(), "malformed.pb")
	if err := os.WriteFile(malformedPath, []byte("not-a-descriptor"), 0o600); err != nil {
		t.Fatalf("write malformed descriptor set: %v", err)
	}
	if _, err := readDescriptorSet(malformedPath); err == nil {
		t.Fatal("readDescriptorSet accepted malformed protobuf bytes")
	}
	if _, err := readDescriptorSet(filepath.Join(t.TempDir(), "missing.pb")); err == nil {
		t.Fatal("readDescriptorSet accepted a missing file")
	}
}

func descriptorSet(fields ...*descriptorpb.FieldDescriptorProto) *descriptorpb.FileDescriptorSet {
	file := fileDescriptor("contracts/proto/test.proto", "reef.test.v1")
	file.MessageType = []*descriptorpb.DescriptorProto{{
		Name:  stringPointer("Order"),
		Field: fields,
	}}
	return &descriptorpb.FileDescriptorSet{File: []*descriptorpb.FileDescriptorProto{file}}
}

func field(name string, number int32, fieldType descriptorpb.FieldDescriptorProto_Type) *descriptorpb.FieldDescriptorProto {
	label := descriptorpb.FieldDescriptorProto_LABEL_OPTIONAL
	return &descriptorpb.FieldDescriptorProto{Name: &name, Number: &number, Type: &fieldType, Label: &label}
}

func fieldInOneof(name string, number int32, fieldType descriptorpb.FieldDescriptorProto_Type, oneofIndex int32) *descriptorpb.FieldDescriptorProto {
	value := field(name, number, fieldType)
	value.OneofIndex = &oneofIndex
	return value
}

func fileDescriptor(name string, packageName string) *descriptorpb.FileDescriptorProto {
	return &descriptorpb.FileDescriptorProto{Name: &name, Package: &packageName}
}

func enumDescriptor(name string, values ...*descriptorpb.EnumValueDescriptorProto) *descriptorpb.EnumDescriptorProto {
	return &descriptorpb.EnumDescriptorProto{Name: &name, Value: values}
}

func enumValue(name string, number int32) *descriptorpb.EnumValueDescriptorProto {
	return &descriptorpb.EnumValueDescriptorProto{Name: &name, Number: &number}
}

func serviceDescriptor(name string, methods ...*descriptorpb.MethodDescriptorProto) *descriptorpb.ServiceDescriptorProto {
	return &descriptorpb.ServiceDescriptorProto{Name: &name, Method: methods}
}

func methodDescriptor(name string, inputType string, outputType string) *descriptorpb.MethodDescriptorProto {
	return &descriptorpb.MethodDescriptorProto{Name: &name, InputType: &inputType, OutputType: &outputType}
}

func stringPointer(value string) *string {
	return &value
}
