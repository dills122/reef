package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"net"

	"github.com/IBM/sarama"
)

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
