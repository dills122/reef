package streamdirect

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/IBM/sarama"
)

type FiniteRuntimeConfig struct {
	BindingEndpoint   string
	ServiceTokenFile  string
	KafkaUsername     string
	KafkaPasswordFile string
	KafkaTLSEnabled   bool
	KafkaCAFile       string
}

// FiniteBrokerCredentials is startup transport configuration, never binding proof.
// Secret fields have no JSON representation and must never be logged.
type FiniteBrokerCredentials struct {
	Username string      `json:"-"`
	Password string      `json:"-"`
	TLS      *tls.Config `json:"-"`
}

func readFiniteSecret(path string) (string, error) {
	if path == "" {
		return "", fmt.Errorf("finite secret file required")
	}
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
		return "", fmt.Errorf("finite secret file must be regular and private")
	}
	f, err := os.Open(path)
	if err != nil {
		return "", fmt.Errorf("finite secret file unavailable")
	}
	defer f.Close()
	opened, err := f.Stat()
	if err != nil || !os.SameFile(info, opened) || !opened.Mode().IsRegular() || opened.Mode().Perm()&0077 != 0 {
		return "", fmt.Errorf("finite secret file changed while opening")
	}
	raw, err := io.ReadAll(io.LimitReader(f, 4097))
	if err != nil || len(raw) > 4096 {
		return "", fmt.Errorf("finite secret file exceeds limit")
	}
	secret := strings.TrimSuffix(string(raw), "\n")
	if secret == "" || strings.TrimSpace(secret) != secret || strings.ContainsAny(secret, "\r\n\x00") {
		return "", fmt.Errorf("invalid finite secret file")
	}
	return secret, nil
}

func (f *FiniteRuntimeConfig) credentials() (string, *FiniteBrokerCredentials, error) {
	if f == nil {
		return "", nil, fmt.Errorf("finite startup configuration required")
	}
	if os.Getenv("MATCHING_ENGINE_CALCIFY_SERVICE_TOKEN") != "" || os.Getenv("MATCHING_ENGINE_CALCIFY_KAFKA_PASSWORD") != "" {
		return "", nil, fmt.Errorf("finite secrets require file inputs, not direct environment")
	}
	token, err := readFiniteSecret(f.ServiceTokenFile)
	if err != nil {
		return "", nil, err
	}
	password, err := readFiniteSecret(f.KafkaPasswordFile)
	if err != nil {
		return "", nil, err
	}
	if !finiteText(f.KafkaUsername, 128) {
		return "", nil, fmt.Errorf("finite broker username required")
	}
	auth := &FiniteBrokerCredentials{Username: f.KafkaUsername, Password: password}
	if f.KafkaTLSEnabled {
		roots, err := x509.SystemCertPool()
		if err != nil {
			return "", nil, fmt.Errorf("finite TLS roots unavailable")
		}
		if f.KafkaCAFile != "" {
			ca, err := os.Open(f.KafkaCAFile)
			if err != nil {
				return "", nil, fmt.Errorf("invalid finite TLS CA file")
			}
			raw, readErr := io.ReadAll(io.LimitReader(ca, 65537))
			_ = ca.Close()
			if readErr != nil || len(raw) > 65536 || !roots.AppendCertsFromPEM(raw) {
				return "", nil, fmt.Errorf("invalid finite TLS CA file")
			}
		}
		auth.TLS = &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots}
	} else if f.KafkaCAFile != "" {
		return "", nil, fmt.Errorf("finite CA requires TLS")
	}
	return token, auth, nil
}

func kafkaConfiguredClient(config KafkaConfig, id string) *sarama.Config {
	c := newKafkaClientConfig(id)
	if config.Finite == nil {
		return c
	}
	c.Net.SASL.Enable = true
	c.Net.SASL.Mechanism = sarama.SASLTypePlaintext
	c.Net.SASL.User = config.Finite.Username
	c.Net.SASL.Password = config.Finite.Password
	c.Net.TLS.Enable = config.Finite.TLS != nil
	c.Net.TLS.Config = config.Finite.TLS
	c.Metadata.AllowAutoTopicCreation = false
	c.Consumer.IsolationLevel = sarama.ReadCommitted
	c.Consumer.Fetch.Default = 65536
	c.Consumer.Fetch.Max = 131072
	c.Producer.MaxMessageBytes = 65536 + 1024
	c.Producer.Compression = sarama.CompressionNone
	c.Net.DialTimeout = 10 * time.Second
	c.Net.ReadTimeout = 10 * time.Second
	c.Net.WriteTimeout = 10 * time.Second
	return c
}
