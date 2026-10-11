package app

import (
	"encoding/hex"
	"strings"
)

// WithCalcifyFiniteBindingDigest sets snapshot compatibility identity only.
// It cannot authorize listeners, command intake or durable replay activation.
func WithCalcifyFiniteBindingDigest(digest string) Option {
	return func(s *Service) { s.calcifyFiniteBindingDigest = digest }
}

func (s *Service) CalcifyFiniteBindingDigest() string { return s.calcifyFiniteBindingDigest }

func (s *Service) validCalcifyFiniteSnapshot(snapshot Snapshot) bool {
	digest := s.calcifyFiniteBindingDigest
	if snapshot.Metadata.CalcifyFiniteBindingDigest != digest {
		return false
	}
	if digest == "" {
		return true
	}
	raw, err := hex.DecodeString(digest)
	return err == nil && len(raw) == 32 && len(digest) == 64 && strings.ToLower(digest) == digest && s.calcifySourceProfileHash() != "" && s.ValidateCalcifySourceProfile() == nil
}
