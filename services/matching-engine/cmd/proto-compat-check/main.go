package main

import (
	"flag"
	"fmt"
	"os"
	"sort"

	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/descriptorpb"
)

func main() {
	baselinePath := flag.String("baseline", "", "baseline FileDescriptorSet")
	currentPath := flag.String("current", "", "current FileDescriptorSet")
	flag.Parse()
	if *baselinePath == "" || *currentPath == "" {
		fmt.Fprintln(os.Stderr, "usage: proto-compat-check --baseline BASE.pb --current CURRENT.pb")
		os.Exit(2)
	}
	baseline, err := readDescriptorSet(*baselinePath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	current, err := readDescriptorSet(*currentPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	violations := compatibilityViolations(baseline, current)
	if len(violations) == 0 {
		fmt.Println("Protobuf descriptor compatibility check passed.")
		return
	}
	for _, violation := range violations {
		fmt.Printf("- %s\n", violation)
	}
	os.Exit(1)
}

func readDescriptorSet(path string) (*descriptorpb.FileDescriptorSet, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read descriptor set %s: %w", path, err)
	}
	set := &descriptorpb.FileDescriptorSet{}
	if err := proto.Unmarshal(data, set); err != nil {
		return nil, fmt.Errorf("decode descriptor set %s: %w", path, err)
	}
	return set, nil
}

func compatibilityViolations(baseline *descriptorpb.FileDescriptorSet, current *descriptorpb.FileDescriptorSet) []string {
	violations := make([]string, 0)
	currentFiles := fileIndex(current)
	for _, oldFile := range baseline.GetFile() {
		newFile := currentFiles[oldFile.GetName()]
		if newFile == nil {
			violations = append(violations, fmt.Sprintf("file removed: %s", oldFile.GetName()))
			continue
		}
		if oldFile.GetPackage() != newFile.GetPackage() {
			violations = append(violations, fmt.Sprintf("package changed in %s: %s -> %s", oldFile.GetName(), oldFile.GetPackage(), newFile.GetPackage()))
		}
		violations = append(violations, compareMessages(oldFile, newFile)...)
		violations = append(violations, compareEnums(oldFile, newFile)...)
		violations = append(violations, compareServices(oldFile, newFile)...)
	}
	sort.Strings(violations)
	return violations
}

func fileIndex(set *descriptorpb.FileDescriptorSet) map[string]*descriptorpb.FileDescriptorProto {
	files := make(map[string]*descriptorpb.FileDescriptorProto, len(set.GetFile()))
	for _, file := range set.GetFile() {
		files[file.GetName()] = file
	}
	return files
}

func compareMessages(oldFile *descriptorpb.FileDescriptorProto, newFile *descriptorpb.FileDescriptorProto) []string {
	oldMessages := messageIndex(oldFile)
	newMessages := messageIndex(newFile)
	violations := make([]string, 0)
	for name, oldMessage := range oldMessages {
		newMessage := newMessages[name]
		if newMessage == nil {
			violations = append(violations, fmt.Sprintf("message removed: %s", name))
			continue
		}
		newByNumber := make(map[int32]*descriptorpb.FieldDescriptorProto, len(newMessage.GetField()))
		newByName := make(map[string]*descriptorpb.FieldDescriptorProto, len(newMessage.GetField()))
		for _, field := range newMessage.GetField() {
			newByNumber[field.GetNumber()] = field
			newByName[field.GetName()] = field
		}
		for _, oldField := range oldMessage.GetField() {
			newField := newByNumber[oldField.GetNumber()]
			if newField == nil {
				violations = append(violations, fmt.Sprintf("field removed or renumbered: %s.%s = %d", name, oldField.GetName(), oldField.GetNumber()))
				continue
			}
			if oldField.GetName() != newField.GetName() ||
				oldField.GetType() != newField.GetType() ||
				oldField.GetTypeName() != newField.GetTypeName() ||
				oldField.GetLabel() != newField.GetLabel() ||
				oldField.GetProto3Optional() != newField.GetProto3Optional() ||
				oneofName(oldMessage, oldField) != oneofName(newMessage, newField) {
				violations = append(violations, fmt.Sprintf("field contract changed: %s.%s = %d", name, oldField.GetName(), oldField.GetNumber()))
			}
			if sameName := newByName[oldField.GetName()]; sameName != nil && sameName.GetNumber() != oldField.GetNumber() {
				violations = append(violations, fmt.Sprintf("field number changed: %s.%s %d -> %d", name, oldField.GetName(), oldField.GetNumber(), sameName.GetNumber()))
			}
		}
	}
	return violations
}

func messageIndex(file *descriptorpb.FileDescriptorProto) map[string]*descriptorpb.DescriptorProto {
	messages := make(map[string]*descriptorpb.DescriptorProto)
	var visit func(string, []*descriptorpb.DescriptorProto)
	visit = func(prefix string, values []*descriptorpb.DescriptorProto) {
		for _, message := range values {
			name := prefix + "." + message.GetName()
			messages[name] = message
			visit(name, message.GetNestedType())
		}
	}
	visit("."+file.GetPackage(), file.GetMessageType())
	return messages
}

func oneofName(message *descriptorpb.DescriptorProto, field *descriptorpb.FieldDescriptorProto) string {
	if field.OneofIndex == nil {
		return ""
	}
	index := int(field.GetOneofIndex())
	if index < 0 || index >= len(message.GetOneofDecl()) {
		return "<invalid>"
	}
	return message.GetOneofDecl()[index].GetName()
}

func compareEnums(oldFile *descriptorpb.FileDescriptorProto, newFile *descriptorpb.FileDescriptorProto) []string {
	oldEnums := enumIndex(oldFile)
	newEnums := enumIndex(newFile)
	violations := make([]string, 0)
	for name, oldEnum := range oldEnums {
		newEnum := newEnums[name]
		if newEnum == nil {
			violations = append(violations, fmt.Sprintf("enum removed: %s", name))
			continue
		}
		values := make(map[int32]string, len(newEnum.GetValue()))
		for _, value := range newEnum.GetValue() {
			values[value.GetNumber()] = value.GetName()
		}
		for _, oldValue := range oldEnum.GetValue() {
			if values[oldValue.GetNumber()] != oldValue.GetName() {
				violations = append(violations, fmt.Sprintf("enum value removed or changed: %s.%s = %d", name, oldValue.GetName(), oldValue.GetNumber()))
			}
		}
	}
	return violations
}

func enumIndex(file *descriptorpb.FileDescriptorProto) map[string]*descriptorpb.EnumDescriptorProto {
	enums := make(map[string]*descriptorpb.EnumDescriptorProto)
	prefix := "." + file.GetPackage()
	for _, enum := range file.GetEnumType() {
		enums[prefix+"."+enum.GetName()] = enum
	}
	var visit func(string, []*descriptorpb.DescriptorProto)
	visit = func(parent string, messages []*descriptorpb.DescriptorProto) {
		for _, message := range messages {
			name := parent + "." + message.GetName()
			for _, enum := range message.GetEnumType() {
				enums[name+"."+enum.GetName()] = enum
			}
			visit(name, message.GetNestedType())
		}
	}
	visit(prefix, file.GetMessageType())
	return enums
}

func compareServices(oldFile *descriptorpb.FileDescriptorProto, newFile *descriptorpb.FileDescriptorProto) []string {
	services := make(map[string]*descriptorpb.ServiceDescriptorProto, len(newFile.GetService()))
	for _, service := range newFile.GetService() {
		services[service.GetName()] = service
	}
	violations := make([]string, 0)
	for _, oldService := range oldFile.GetService() {
		newService := services[oldService.GetName()]
		if newService == nil {
			violations = append(violations, fmt.Sprintf("service removed: %s.%s", oldFile.GetPackage(), oldService.GetName()))
			continue
		}
		methods := make(map[string]*descriptorpb.MethodDescriptorProto, len(newService.GetMethod()))
		for _, method := range newService.GetMethod() {
			methods[method.GetName()] = method
		}
		for _, oldMethod := range oldService.GetMethod() {
			newMethod := methods[oldMethod.GetName()]
			if newMethod == nil {
				violations = append(violations, fmt.Sprintf("method removed: %s.%s", oldService.GetName(), oldMethod.GetName()))
				continue
			}
			if oldMethod.GetInputType() != newMethod.GetInputType() ||
				oldMethod.GetOutputType() != newMethod.GetOutputType() ||
				oldMethod.GetClientStreaming() != newMethod.GetClientStreaming() ||
				oldMethod.GetServerStreaming() != newMethod.GetServerStreaming() {
				violations = append(violations, fmt.Sprintf("method contract changed: %s.%s", oldService.GetName(), oldMethod.GetName()))
			}
		}
	}
	return violations
}
