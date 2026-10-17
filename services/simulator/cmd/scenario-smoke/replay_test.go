package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestScenarioSmokeLiveAssertionsAttachReplayChecksumEvidence(t *testing.T) {
	replayPath := filepath.Join(t.TempDir(), "replay-check.json")
	replayJSON := p1CleanReplayCheckJSON()
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"commandId":"` + commandID + `","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/history":
			participantID := r.URL.Query().Get("participantId")
			orderID := map[string]string{
				"HIDDEN_SELLER_A": "p1_golden_hidden_cross_t1-ord-001",
				"VISIBLE_BUYER_B": "p1_golden_hidden_cross_t1-ord-002",
				"VISIBLE_BUYER_C": "p1_golden_hidden_cross_t1-ord-003",
			}[participantID]
			if orderID == "" {
				http.NotFound(w, r)
				return
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSON(orderID)))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnFillsJSON(r.URL.Query().Get("participantId"))))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1DataAvailabilityJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"market data depth not found","instrumentId":"XYZ"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--scenario-run-id", "p1-replay-assert-live",
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--replay-check-report", replayPath,
		"--require-replay-check",
	}, &stdout, server.Client())
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}

	var report smokeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", err, stdout.String())
	}
	if !report.Pass || len(report.Failures) != 0 || len(report.Errors) != 0 {
		t.Fatalf("unexpected failed assertion report: %+v", report)
	}
	if len(report.ArtifactPaths) != 1 || report.ArtifactPaths[0] != replayPath {
		t.Fatalf("artifact paths: %+v", report.ArtifactPaths)
	}
	if report.ReplayChecksum["pass"] != true {
		t.Fatalf("missing replay checksum pass: %+v", report.ReplayChecksum)
	}
	if !hasAssertion(report, "p1-replay-checksum-clean", "pass") {
		t.Fatalf("missing replay checksum assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p1-replay-stored-command-count", "pass") {
		t.Fatalf("missing replay counter assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p1-replay-hidden-depth-timeline-proof", "pass") {
		t.Fatalf("missing hidden-depth replay timeline assertion: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsRequireHiddenDepthReplayProof(t *testing.T) {
	tempDir := t.TempDir()
	replayPath := filepath.Join(tempDir, "replay-check.json")
	replayJSON := `{"pass":true,"checkedAt":"2026-03-14T18:00:30Z","report":{"batchCount":1,"storedCommandCount":3,"payloadOutcomeCount":3,"canonicalOutcomeCount":3,"duplicateReplayInserted":0,"checksumMismatchCount":0,"batchCommandCountMismatchCount":0,"payloadHashMismatchCount":0,"missingOutcomeCount":0,"extraOutcomeCount":0,"streamGapCount":0,"streamOverlapCount":0,"watermarkLagCount":0},"failures":[]}`
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}
	reportPath := filepath.Join(tempDir, "scenario-report.json")
	reportJSON := `{"mode":"live","pass":true,"pathId":"P1_GOLDEN_HIDDEN_CROSS_T1","scenarioRunId":"p1-missing-timeline","seed":0,"requests":[],"results":[]}`
	if err := os.WriteFile(reportPath, []byte(reportJSON), 0o644); err != nil {
		t.Fatalf("write scenario report: %v", err)
	}

	var stdout bytes.Buffer
	err := run([]string{
		"--attach-replay-to-report", reportPath,
		"--replay-check-report", replayPath,
		"--require-replay-check",
	}, &stdout, nil)
	if err == nil {
		t.Fatal("expected hidden-depth replay proof failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p1-replay-hidden-depth-timeline-proof") {
		t.Fatalf("expected p1-replay-hidden-depth-timeline-proof failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeReplayAttachmentUsesReportVisibilityTimeline(t *testing.T) {
	tempDir := t.TempDir()
	replayPath := filepath.Join(tempDir, "replay-check.json")
	replayJSON := `{"pass":true,"checkedAt":"2026-03-14T18:00:30Z","report":{"batchCount":1,"storedCommandCount":3,"payloadOutcomeCount":3,"canonicalOutcomeCount":3,"duplicateReplayInserted":0,"checksumMismatchCount":0,"batchCommandCountMismatchCount":0,"payloadHashMismatchCount":0,"missingOutcomeCount":0,"extraOutcomeCount":0,"streamGapCount":0,"streamOverlapCount":0,"watermarkLagCount":0},"failures":[]}`
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}
	reportPath := filepath.Join(tempDir, "scenario-report.json")
	reportJSON := `{"mode":"live","pass":true,"pathId":"P1_GOLDEN_HIDDEN_CROSS_T1","scenarioRunId":"p1-report-timeline","seed":0,"requests":[],"results":[],"visibilityTimeline":{"scenarioId":"P1_GOLDEN_HIDDEN_CROSS_T1","scenarioRunId":"p1-report-timeline","publicDepthHiddenRestingExposed":false,"publicDepthChecks":[{"phase":"after-hidden-resting-before-first-execution","instrumentId":"XYZ","price":"100000000000","hiddenRestingQuantityVisible":false,"statusCode":404,"observed":"no public hidden resting sell level"}]}}`
	if err := os.WriteFile(reportPath, []byte(reportJSON), 0o644); err != nil {
		t.Fatalf("write scenario report: %v", err)
	}

	var stdout bytes.Buffer
	err := run([]string{
		"--attach-replay-to-report", reportPath,
		"--replay-check-report", replayPath,
		"--require-replay-check",
	}, &stdout, nil)
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasAssertion(report, "p1-replay-hidden-depth-timeline-proof", "pass") {
		t.Fatalf("missing replay hidden-depth timeline assertion: %+v", report.Assertions)
	}
	if _, ok := report.ReplayChecksum["visibilityTimeline"].(map[string]any); !ok {
		t.Fatalf("expected replay checksum to include attached visibility timeline: %+v", report.ReplayChecksum)
	}
}

func TestScenarioSmokeAttachReplayUsesP2AssertionPrefix(t *testing.T) {
	tempDir := t.TempDir()
	reportPath := filepath.Join(tempDir, "report.json")
	replayPath := filepath.Join(tempDir, "replay-check.json")
	reportJSON := `{"mode":"live","pass":true,"pathId":"P2_SETTLEMENT_BREAK_REPAIR","scenarioRunId":"p2-report-replay","seed":424243,"requests":[{"sequence":1,"command":"SubmitOrder"},{"sequence":2,"command":"SubmitOrder"}],"results":[],"assertions":[]}`
	replayJSON := `{"pass":true,"report":{"batchCount":2,"storedCommandCount":2,"payloadOutcomeCount":2,"canonicalOutcomeCount":2,"duplicateReplayInserted":0,"checksumMismatchCount":0,"batchCommandCountMismatchCount":0,"payloadHashMismatchCount":0,"missingOutcomeCount":0,"extraOutcomeCount":0,"streamGapCount":0,"streamOverlapCount":0,"watermarkLagCount":0},"failures":[]}`
	if err := os.WriteFile(reportPath, []byte(reportJSON), 0o644); err != nil {
		t.Fatalf("write report: %v", err)
	}
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}

	var stdout bytes.Buffer
	err := run([]string{
		"--attach-replay-to-report", reportPath,
		"--replay-check-report", replayPath,
		"--require-replay-check",
	}, &stdout, nil)
	if err != nil {
		t.Fatalf("run error: %v\n%s", err, stdout.String())
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasAssertion(report, "p2-replay-checksum-clean", "pass") {
		t.Fatalf("missing P2 replay checksum assertion: %+v", report.Assertions)
	}
	if !hasAssertion(report, "p2-replay-stored-command-count", "pass") {
		t.Fatalf("missing P2 replay counter assertion: %+v", report.Assertions)
	}
	if hasAssertion(report, "p1-replay-checksum-clean", "pass") {
		t.Fatalf("unexpected P1 replay assertion on P2 report: %+v", report.Assertions)
	}
}

func TestScenarioSmokeLiveAssertionsFailOnReplayChecksumEvidence(t *testing.T) {
	replayPath := filepath.Join(t.TempDir(), "replay-check.json")
	replayJSON := `{"pass":false,"report":{"batchCount":1,"checksumMismatchCount":1},"failures":["batch payload checksum mismatches: 1"]}`
	if err := os.WriteFile(replayPath, []byte(replayJSON), 0o644); err != nil {
		t.Fatalf("write replay report: %v", err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/readyz":
			writeReadyzOK(w)
		case r.Method == http.MethodPost:
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/api/v1/commands/"):
			commandID := strings.TrimPrefix(r.URL.Path, "/api/v1/commands/")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"commandId":"` + commandID + `","status":"COMPLETED","resultStatus":"accepted","source":"canonical_outcome"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/history":
			participantID := r.URL.Query().Get("participantId")
			orderID := map[string]string{
				"HIDDEN_SELLER_A": "p1_golden_hidden_cross_t1-ord-001",
				"VISIBLE_BUYER_B": "p1_golden_hidden_cross_t1-ord-002",
				"VISIBLE_BUYER_C": "p1_golden_hidden_cross_t1-ord-003",
			}[participantID]
			if orderID == "" {
				http.NotFound(w, r)
				return
			}
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnOrderHistoryJSON(orderID)))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/orders/fills":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1OwnFillsJSON(r.URL.Query().Get("participantId"))))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/data/availability":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1DataAvailabilityJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/trades/XYZ":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(p1TradeTapeJSON()))
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/market-data/depth/XYZ":
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"market data depth not found","instrumentId":"XYZ"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--replay-check-report", replayPath,
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected replay checksum assertion failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if !hasFailure(report, "p1-replay-checksum-clean") {
		t.Fatalf("expected replay checksum failure: %+v", report.Failures)
	}
}

func TestScenarioSmokeLiveAssertionsCanRequireReplayChecksumEvidence(t *testing.T) {
	server := p1AssertionServer(t, p1AssertionServerOptions{})
	defer server.Close()

	var stdout bytes.Buffer
	err := run([]string{
		"--scenario", filepath.Join(scenarioDefinitionsRoot(t), "P1_GOLDEN_HIDDEN_CROSS_T1.yaml"),
		"--base-url", server.URL,
		"--live",
		"--assertions",
		"--require-replay-check",
	}, &stdout, server.Client())
	if err == nil {
		t.Fatal("expected missing replay checksum assertion failure")
	}
	var report smokeReport
	if unmarshalErr := json.Unmarshal(stdout.Bytes(), &report); unmarshalErr != nil {
		t.Fatalf("assertion json did not unmarshal: %v\n%s", unmarshalErr, stdout.String())
	}
	if report.Pass {
		t.Fatalf("expected failed report: %+v", report)
	}
	if !hasFailure(report, "p1-replay-checksum-clean") {
		t.Fatalf("expected replay checksum required failure: %+v", report.Failures)
	}
}
