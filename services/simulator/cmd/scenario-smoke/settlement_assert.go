package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strings"
)

type settlementFactsReport struct {
	ScenarioRunID       string                         `json:"scenarioRunId"`
	Obligations         []settlementObligation         `json:"obligations"`
	Allocations         []settlementAllocation         `json:"allocations"`
	Confirmations       []settlementConfirmation       `json:"confirmations"`
	Affirmations        []settlementAffirmation        `json:"affirmations"`
	ClearingSubmissions []settlementClearingSubmission `json:"clearingSubmissions"`
	ClearingAcceptances []settlementClearingAcceptance `json:"clearingAcceptances"`
	ClearingRejections  []settlementClearingRejection  `json:"clearingRejections"`
	Novations           []settlementNovation           `json:"novations"`
	Instructions        []settlementInstruction        `json:"instructions"`
	Attempts            []settlementAttempt            `json:"attempts"`
	LegOutcomes         []settlementLegOutcome         `json:"legOutcomes"`
	LedgerEntries       []settlementLedgerEntry        `json:"ledgerEntries"`
	Settlements         []settlementSettled            `json:"settlements"`
	Breaks              []settlementBreak              `json:"breaks"`
	Repairs             []settlementRepair             `json:"repairs"`
	Resolutions         []settlementResolution         `json:"resolutions"`
}

type settlementObligation struct {
	SettlementObligationID string `json:"settlementObligationId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	TradeID                string `json:"tradeId"`
	PostTradeProfileID     string `json:"postTradeProfileId"`
	State                  string `json:"state"`
}

type settlementAllocation struct {
	SettlementAllocationID string `json:"settlementAllocationId"`
	SettlementObligationID string `json:"settlementObligationId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	TradeID                string `json:"tradeId"`
	BuyOrderID             string `json:"buyOrderId"`
	SellOrderID            string `json:"sellOrderId"`
	State                  string `json:"state"`
}

type settlementConfirmation struct {
	SettlementConfirmationID string `json:"settlementConfirmationId"`
	SettlementAllocationID   string `json:"settlementAllocationId"`
	SettlementObligationID   string `json:"settlementObligationId"`
	ScenarioRunID            string `json:"scenarioRunId"`
	CorrelationID            string `json:"correlationId"`
	CausationID              string `json:"causationId"`
	TradeID                  string `json:"tradeId"`
	State                    string `json:"state"`
}

type settlementAffirmation struct {
	SettlementAffirmationID  string `json:"settlementAffirmationId"`
	SettlementConfirmationID string `json:"settlementConfirmationId"`
	SettlementAllocationID   string `json:"settlementAllocationId"`
	SettlementObligationID   string `json:"settlementObligationId"`
	ScenarioRunID            string `json:"scenarioRunId"`
	CorrelationID            string `json:"correlationId"`
	CausationID              string `json:"causationId"`
	TradeID                  string `json:"tradeId"`
	State                    string `json:"state"`
}

type settlementClearingSubmission struct {
	SettlementClearingSubmissionID string `json:"settlementClearingSubmissionId"`
	SettlementObligationID         string `json:"settlementObligationId"`
	SettlementAffirmationID        string `json:"settlementAffirmationId"`
	ScenarioRunID                  string `json:"scenarioRunId"`
	CorrelationID                  string `json:"correlationId"`
	CausationID                    string `json:"causationId"`
	State                          string `json:"state"`
}

type settlementClearingAcceptance struct {
	SettlementClearingAcceptanceID string `json:"settlementClearingAcceptanceId"`
	SettlementClearingSubmissionID string `json:"settlementClearingSubmissionId"`
	SettlementObligationID         string `json:"settlementObligationId"`
	ScenarioRunID                  string `json:"scenarioRunId"`
	CorrelationID                  string `json:"correlationId"`
	CausationID                    string `json:"causationId"`
	State                          string `json:"state"`
}

type settlementClearingRejection struct {
	SettlementClearingRejectionID  string `json:"settlementClearingRejectionId"`
	SettlementClearingSubmissionID string `json:"settlementClearingSubmissionId"`
	SettlementObligationID         string `json:"settlementObligationId"`
	ScenarioRunID                  string `json:"scenarioRunId"`
	CorrelationID                  string `json:"correlationId"`
	CausationID                    string `json:"causationId"`
	Reason                         string `json:"reason"`
	State                          string `json:"state"`
}

type settlementNovation struct {
	SettlementNovationID           string `json:"settlementNovationId"`
	SettlementClearingAcceptanceID string `json:"settlementClearingAcceptanceId"`
	SettlementObligationID         string `json:"settlementObligationId"`
	ScenarioRunID                  string `json:"scenarioRunId"`
	CorrelationID                  string `json:"correlationId"`
	CausationID                    string `json:"causationId"`
	State                          string `json:"state"`
}

type settlementInstruction struct {
	SettlementInstructionID string `json:"settlementInstructionId"`
	SettlementObligationID  string `json:"settlementObligationId"`
	ScenarioRunID           string `json:"scenarioRunId"`
	CorrelationID           string `json:"correlationId"`
	CausationID             string `json:"causationId"`
	State                   string `json:"state"`
}

type settlementAttempt struct {
	SettlementAttemptID     string `json:"settlementAttemptId"`
	SettlementObligationID  string `json:"settlementObligationId"`
	SettlementInstructionID string `json:"settlementInstructionId"`
	ScenarioRunID           string `json:"scenarioRunId"`
	CorrelationID           string `json:"correlationId"`
	CausationID             string `json:"causationId"`
	State                   string `json:"state"`
}

type settlementLegOutcome struct {
	SettlementLegOutcomeID string `json:"settlementLegOutcomeId"`
}

type settlementLedgerEntry struct {
	LedgerEntryID string `json:"ledgerEntryId"`
}

type settlementSettled struct {
	SettlementID            string `json:"settlementId"`
	SettlementObligationID  string `json:"settlementObligationId"`
	SettlementInstructionID string `json:"settlementInstructionId"`
	SettlementAttemptID     string `json:"settlementAttemptId"`
	ScenarioRunID           string `json:"scenarioRunId"`
	CorrelationID           string `json:"correlationId"`
	CausationID             string `json:"causationId"`
	SettlementState         string `json:"settlementState"`
}

type settlementBreak struct {
	SettlementBreakID      string `json:"settlementBreakId"`
	SettlementObligationID string `json:"settlementObligationId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	Reason                 string `json:"reason"`
	State                  string `json:"state"`
}

type settlementRepair struct {
	SettlementRepairID     string `json:"settlementRepairId"`
	SettlementBreakID      string `json:"settlementBreakId"`
	SettlementObligationID string `json:"settlementObligationId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	RepairAction           string `json:"repairAction"`
}

type settlementResolution struct {
	SettlementResolutionID string `json:"settlementResolutionId"`
	SettlementObligationID string `json:"settlementObligationId"`
	SettlementBreakID      string `json:"settlementBreakId"`
	SettlementRepairID     string `json:"settlementRepairId"`
	ScenarioRunID          string `json:"scenarioRunId"`
	CorrelationID          string `json:"correlationId"`
	CausationID            string `json:"causationId"`
	SettlementState        string `json:"settlementState"`
	ExceptionState         string `json:"exceptionState"`
}

type settlementExceptionQueueReport struct {
	ScenarioRunID           string                `json:"scenarioRunId"`
	ExceptionsCount         int                   `json:"exceptionsCount"`
	OpenCount               int                   `json:"openCount"`
	RepairPostedCount       int                   `json:"repairPostedCount"`
	ResolvedCount           int                   `json:"resolvedCount"`
	ClearingRejectedCount   int                   `json:"clearingRejectedCount"`
	SettlementBreakCount    int                   `json:"settlementBreakCount"`
	SettlementExceptionRows []settlementException `json:"exceptions"`
}

type settlementException struct {
	SettlementExceptionID          string `json:"settlementExceptionId"`
	ExceptionType                  string `json:"exceptionType"`
	ExceptionState                 string `json:"exceptionState"`
	State                          string `json:"state"`
	Severity                       string `json:"severity"`
	OwnerRole                      string `json:"ownerRole"`
	ActionRequired                 string `json:"actionRequired"`
	Reason                         string `json:"reason"`
	SettlementObligationID         string `json:"settlementObligationId"`
	TradeID                        string `json:"tradeId"`
	SettlementClearingSubmissionID string `json:"settlementClearingSubmissionId"`
	SettlementClearingRejectionID  string `json:"settlementClearingRejectionId"`
	SettlementBreakID              string `json:"settlementBreakId"`
	SettlementRepairID             string `json:"settlementRepairId"`
	SettlementResolutionID         string `json:"settlementResolutionId"`
	RepairAction                   string `json:"repairAction"`
	ActorID                        string `json:"actorId"`
	CorrelationID                  string `json:"correlationId"`
	OpenedAt                       string `json:"openedAt"`
	LastUpdatedAt                  string `json:"lastUpdatedAt"`
	ResolvedAt                     string `json:"resolvedAt"`
}

func attachSettlementFactArtifactAssertions(cfg config, report *smokeReport) {
	body, err := os.ReadFile(cfg.settlementFactsReportPath)
	read := assertionRead{
		Endpoint:       cfg.settlementFactsReportPath,
		SourceType:     "settlement assertion artifact",
		FreshnessModel: "artifact snapshot",
		Body:           strings.TrimSpace(string(body)),
	}
	if err != nil {
		read.StatusCode = 0
		report.Reads = append(report.Reads, read)
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "readable settlement facts report", err.Error(), cfg.settlementFactsReportPath)
		return
	}
	read.StatusCode = 200
	report.Reads = append(report.Reads, read)
	var facts settlementFactsReport
	if err := json.Unmarshal(body, &facts); err != nil {
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "valid settlement facts JSON", err.Error(), cfg.settlementFactsReportPath)
		return
	}
	if report.PathID != "P2_SETTLEMENT_BREAK_REPAIR" {
		passAssertion(report, "settlement-facts-attached", "settlement facts artifact attached", "attached", cfg.settlementFactsReportPath)
		return
	}
	assertP2SettlementFacts(report, facts, cfg.settlementFactsReportPath)
}

func attachSettlementFactAPIAssertions(cfg config, client *http.Client, report *smokeReport) {
	endpoint := "/api/v1/settlement/facts/" + url.PathEscape(report.ScenarioRunID)
	read, body, err := executeRead(client, absoluteURL(cfg.readBaseURL, endpoint), endpoint, map[string]string{"scenarioRunId": report.ScenarioRunID})
	read.SourceType = "settlement facts"
	read.FreshnessModel = "append-only settlement fact store"
	if err != nil {
		report.Reads = append(report.Reads, read)
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "readable settlement facts API", err.Error(), endpoint)
		return
	}
	report.Reads = append(report.Reads, read)
	if read.StatusCode != 200 {
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "HTTP 200 settlement facts API", fmt.Sprint(read.StatusCode), endpoint)
		return
	}
	var facts settlementFactsReport
	if err := json.Unmarshal(body, &facts); err != nil {
		failAssertion(report, "p2-settlement-facts-readable", "settlement_fact_read", "valid settlement facts JSON", err.Error(), endpoint)
		return
	}
	assertP2SettlementFacts(report, facts, endpoint)
}

func attachSettlementExceptionAPIAssertions(cfg config, client *http.Client, report *smokeReport) {
	endpoint := "/api/v1/settlement/exceptions/" + url.PathEscape(report.ScenarioRunID)
	read, body, err := executeRead(client, absoluteURL(cfg.readBaseURL, endpoint), endpoint, map[string]string{"scenarioRunId": report.ScenarioRunID})
	read.SourceType = "settlement exception queue"
	read.FreshnessModel = "rebuildable exception queue over clearing rejections and settlement breaks"
	if err != nil {
		report.Reads = append(report.Reads, read)
		failAssertion(report, "p2-exception-queue-readable", "settlement_exception_read", "readable settlement exception queue API", err.Error(), endpoint)
		return
	}
	report.Reads = append(report.Reads, read)
	if read.StatusCode != 200 {
		failAssertion(report, "p2-exception-queue-readable", "settlement_exception_read", "HTTP 200 settlement exception queue API", fmt.Sprint(read.StatusCode), endpoint)
		return
	}
	var queue settlementExceptionQueueReport
	if err := json.Unmarshal(body, &queue); err != nil {
		failAssertion(report, "p2-exception-queue-readable", "settlement_exception_read", "valid settlement exception queue JSON", err.Error(), endpoint)
		return
	}
	assertP2SettlementExceptionQueue(report, queue, endpoint)
}

func assertP2SettlementExceptionQueue(report *smokeReport, queue settlementExceptionQueueReport, proofSource string) {
	if queue.ScenarioRunID == report.ScenarioRunID {
		passAssertion(report, "p2-exception-queue-scenario-run", report.ScenarioRunID, queue.ScenarioRunID, proofSource)
	} else {
		failAssertion(report, "p2-exception-queue-scenario-run", "settlement_exception_scope", report.ScenarioRunID, queue.ScenarioRunID, proofSource)
	}
	if queue.ExceptionsCount == len(queue.SettlementExceptionRows) {
		passAssertion(report, "p2-exception-queue-count-matches", fmt.Sprint(queue.ExceptionsCount), fmt.Sprint(len(queue.SettlementExceptionRows)), proofSource)
	} else {
		failAssertion(report, "p2-exception-queue-count-matches", "settlement_exception_count", fmt.Sprint(queue.ExceptionsCount), fmt.Sprint(len(queue.SettlementExceptionRows)), proofSource)
	}
	if queue.ExceptionsCount == 0 {
		passAssertion(report, "p2-exception-queue-empty", "0 exceptions", "0 exceptions", proofSource)
		return
	}
	assertCount(report, "p2-one-settlement-break-exception", "settlement_exception_count", queue.SettlementBreakCount, 1, proofSource)
	assertCount(report, "p2-zero-clearing-rejected-exceptions", "settlement_exception_count", queue.ClearingRejectedCount, 0, proofSource)
	assertCount(report, "p2-zero-open-exceptions", "settlement_exception_count", queue.OpenCount, 0, proofSource)
	assertCount(report, "p2-zero-repair-posted-exceptions", "settlement_exception_count", queue.RepairPostedCount, 0, proofSource)
	assertCount(report, "p2-one-resolved-exception", "settlement_exception_count", queue.ResolvedCount, 1, proofSource)
	if len(queue.SettlementExceptionRows) != 1 {
		failAssertion(report, "p2-exception-queue-single-row", "settlement_exception_count", "1", fmt.Sprint(len(queue.SettlementExceptionRows)), proofSource)
		return
	}
	exception := queue.SettlementExceptionRows[0]
	assertSettlementExceptionField(report, "p2-exception-type-settlement-break", exception.ExceptionType, "SETTLEMENT_BREAK", proofSource)
	assertSettlementExceptionField(report, "p2-exception-state-resolved", exception.ExceptionState, "RESOLVED", proofSource)
	assertSettlementExceptionField(report, "p2-exception-owner-settlement-ops", exception.OwnerRole, "SETTLEMENT_OPS", proofSource)
	assertSettlementExceptionField(report, "p2-exception-action-none-after-resolution", exception.ActionRequired, "NONE", proofSource)
	expectedRepairAction := expectedSettlementRepairAction(exception.Reason)
	if expectedRepairAction == "" {
		failAssertion(report, "p2-exception-repair-action-matches-reason", "settlement_exception_repair", "known settlement break reason", exception.Reason, proofSource)
	} else {
		assertSettlementExceptionField(report, "p2-exception-repair-action-matches-reason", exception.RepairAction, expectedRepairAction, proofSource)
	}
	if exception.SettlementBreakID != "" && exception.SettlementRepairID != "" && exception.SettlementResolutionID != "" &&
		exception.ResolvedAt != "" && exception.ActorID != "" && exception.CorrelationID != "" {
		passAssertion(report, "p2-exception-resolution-linked", "break, repair, resolution, actor, correlation, resolvedAt", "all links present", proofSource)
	} else {
		failAssertion(report, "p2-exception-resolution-linked", "settlement_exception_causation", "break, repair, resolution, actor, correlation, resolvedAt", settlementExceptionObserved(exception), proofSource)
	}
}

func assertSettlementExceptionField(report *smokeReport, id string, observed string, expected string, proofSource string) {
	if observed == expected {
		passAssertion(report, id, expected, observed, proofSource)
		return
	}
	failAssertion(report, id, "settlement_exception_field", expected, observed, proofSource)
}

func expectedSettlementRepairAction(reason string) string {
	switch reason {
	case "CASH_LEG_FAILED":
		return "POST_CASH_LEG_REPAIR"
	case "SECURITY_LEG_FAILED":
		return "POST_SECURITY_LEG_REPAIR"
	default:
		return ""
	}
}

func settlementBreakReasonAssertionID(reason string) string {
	switch reason {
	case "CASH_LEG_FAILED":
		return "p2-break-reason-cash-leg-failed"
	case "SECURITY_LEG_FAILED":
		return "p2-break-reason-security-leg-failed"
	default:
		return "p2-break-reason-known"
	}
}

func settlementExceptionObserved(exception settlementException) string {
	return fmt.Sprintf(
		"break=%s repair=%s resolution=%s actor=%s correlation=%s resolvedAt=%s",
		exception.SettlementBreakID,
		exception.SettlementRepairID,
		exception.SettlementResolutionID,
		exception.ActorID,
		exception.CorrelationID,
		exception.ResolvedAt,
	)
}

func assertP2SettlementFacts(report *smokeReport, facts settlementFactsReport, proofSource string) {
	if facts.ScenarioRunID == report.ScenarioRunID {
		passAssertion(report, "p2-settlement-scenario-run", report.ScenarioRunID, facts.ScenarioRunID, proofSource)
	} else {
		failAssertion(report, "p2-settlement-scenario-run", "settlement_fact_scope", report.ScenarioRunID, facts.ScenarioRunID, proofSource)
	}
	if hasPostTradeSettlementChainFacts(facts) {
		assertPostTradeSettlementChain(report, facts, proofSource)
		if settlementFactsHaveCausation(facts) {
			passAssertion(report, "p2-settlement-causation-fields", "scenarioRunId/correlationId/causationId on every fact", "all facts carry causation fields", proofSource)
		} else {
			failAssertion(report, "p2-settlement-causation-fields", "settlement_fact_causation", "scenarioRunId/correlationId/causationId on every fact", "missing causation field", proofSource)
		}
		if settlementFactsScopedToScenarioRun(facts, report.ScenarioRunID) {
			passAssertion(report, "p2-settlement-scope-consistent", report.ScenarioRunID, "all facts match scenarioRunId", proofSource)
		} else {
			failAssertion(report, "p2-settlement-scope-consistent", "settlement_fact_scope", report.ScenarioRunID, settlementScenarioRunIDs(facts), proofSource)
		}
		return
	}
	if hasOpsRealisticPendingFacts(facts) {
		assertOpsRealisticPendingSettlementFacts(report, facts, proofSource)
		if settlementFactsHaveCausation(facts) {
			passAssertion(report, "p2-settlement-causation-fields", "scenarioRunId/correlationId/causationId on every fact", "all facts carry causation fields", proofSource)
		} else {
			failAssertion(report, "p2-settlement-causation-fields", "settlement_fact_causation", "scenarioRunId/correlationId/causationId on every fact", "missing causation field", proofSource)
		}
		if settlementFactsScopedToScenarioRun(facts, report.ScenarioRunID) {
			passAssertion(report, "p2-settlement-scope-consistent", report.ScenarioRunID, "all facts match scenarioRunId", proofSource)
		} else {
			failAssertion(report, "p2-settlement-scope-consistent", "settlement_fact_scope", report.ScenarioRunID, settlementScenarioRunIDs(facts), proofSource)
		}
		return
	}
	assertCount(report, "p2-one-settlement-obligation", "settlement_fact_count", len(facts.Obligations), 1, proofSource)
	assertCount(report, "p2-one-settlement-break", "settlement_fact_count", len(facts.Breaks), 1, proofSource)
	assertCount(report, "p2-one-manual-repair", "settlement_fact_count", len(facts.Repairs), 1, proofSource)
	assertCount(report, "p2-one-settlement-resolution", "settlement_fact_count", len(facts.Resolutions), 1, proofSource)
	if len(facts.Obligations) == 1 && strings.TrimSpace(facts.Obligations[0].TradeID) != "" {
		passAssertion(report, "p2-obligation-references-trade", "non-empty tradeId", facts.Obligations[0].TradeID, proofSource)
	} else {
		failAssertion(report, "p2-obligation-references-trade", "settlement_fact_causation", "one obligation with tradeId", obligationTradeIDs(facts.Obligations), proofSource)
	}
	if len(facts.Obligations) == 1 && facts.Obligations[0].State == "OBLIGATION_CREATED" {
		passAssertion(report, "p2-obligation-state-created", "OBLIGATION_CREATED", facts.Obligations[0].State, proofSource)
	} else {
		failAssertion(report, "p2-obligation-state-created", "settlement_fact_state", "OBLIGATION_CREATED", obligationStates(facts.Obligations), proofSource)
	}
	expectedRepairAction := ""
	if len(facts.Breaks) == 1 {
		expectedRepairAction = expectedSettlementRepairAction(facts.Breaks[0].Reason)
	}
	if len(facts.Breaks) == 1 && expectedRepairAction != "" {
		passAssertion(report, settlementBreakReasonAssertionID(facts.Breaks[0].Reason), facts.Breaks[0].Reason, facts.Breaks[0].Reason, proofSource)
	} else {
		failAssertion(report, "p2-break-reason-known", "settlement_fact_reason", "CASH_LEG_FAILED or SECURITY_LEG_FAILED", breakReasons(facts.Breaks), proofSource)
	}
	if len(facts.Breaks) == 1 && facts.Breaks[0].State == "BROKEN" {
		passAssertion(report, "p2-break-state-broken", "BROKEN", facts.Breaks[0].State, proofSource)
	} else {
		failAssertion(report, "p2-break-state-broken", "settlement_fact_state", "BROKEN", breakStates(facts.Breaks), proofSource)
	}
	if len(facts.Repairs) == 1 && expectedRepairAction != "" && facts.Repairs[0].RepairAction == expectedRepairAction {
		passAssertion(report, "p2-repair-action-posted", expectedRepairAction, facts.Repairs[0].RepairAction, proofSource)
	} else {
		failAssertion(report, "p2-repair-action-posted", "settlement_fact_repair", expectedRepairAction, repairActions(facts.Repairs), proofSource)
	}
	if len(facts.Resolutions) == 1 && facts.Resolutions[0].SettlementState == "RESOLVED" {
		passAssertion(report, "p2-settlement-final-resolved", "RESOLVED", facts.Resolutions[0].SettlementState, proofSource)
	} else {
		failAssertion(report, "p2-settlement-final-resolved", "settlement_fact_state", "RESOLVED", settlementStates(facts.Resolutions), proofSource)
	}
	if len(facts.Resolutions) == 1 && facts.Resolutions[0].ExceptionState == "RESOLVED" {
		passAssertion(report, "p2-exception-final-resolved", "RESOLVED", facts.Resolutions[0].ExceptionState, proofSource)
	} else {
		failAssertion(report, "p2-exception-final-resolved", "settlement_fact_state", "RESOLVED", exceptionStates(facts.Resolutions), proofSource)
	}
	if p2ResolutionReferencesRepair(facts) {
		passAssertion(report, "p2-no-direct-resolution-without-repair", "resolution references repair", "resolution references repair", proofSource)
	} else {
		failAssertion(report, "p2-no-direct-resolution-without-repair", "settlement_fact_causation", "resolution references repair", "missing repair linkage", proofSource)
	}
	if p2SettlementChainLinked(facts) {
		passAssertion(report, "p2-settlement-chain-linked", "obligation -> break -> repair -> resolution", "all fact references linked", proofSource)
	} else {
		failAssertion(report, "p2-settlement-chain-linked", "settlement_fact_causation", "obligation -> break -> repair -> resolution", p2SettlementChainObserved(facts), proofSource)
	}
	if settlementFactsHaveCausation(facts) {
		passAssertion(report, "p2-settlement-causation-fields", "scenarioRunId/correlationId/causationId on every fact", "all facts carry causation fields", proofSource)
	} else {
		failAssertion(report, "p2-settlement-causation-fields", "settlement_fact_causation", "scenarioRunId/correlationId/causationId on every fact", "missing causation field", proofSource)
	}
	if settlementFactsScopedToScenarioRun(facts, report.ScenarioRunID) {
		passAssertion(report, "p2-settlement-scope-consistent", report.ScenarioRunID, "all facts match scenarioRunId", proofSource)
	} else {
		failAssertion(report, "p2-settlement-scope-consistent", "settlement_fact_scope", report.ScenarioRunID, settlementScenarioRunIDs(facts), proofSource)
	}
}

func hasPostTradeSettlementChainFacts(facts settlementFactsReport) bool {
	return len(facts.Allocations) > 0 || len(facts.Confirmations) > 0 || len(facts.Affirmations) > 0 ||
		len(facts.ClearingSubmissions) > 0 || len(facts.ClearingAcceptances) > 0 || len(facts.Novations) > 0 ||
		len(facts.Settlements) > 0
}

func hasOpsRealisticPendingFacts(facts settlementFactsReport) bool {
	return len(facts.Obligations) == 1 &&
		len(facts.Allocations) == 0 &&
		len(facts.Confirmations) == 0 &&
		len(facts.Affirmations) == 0 &&
		len(facts.ClearingSubmissions) == 0 &&
		len(facts.ClearingAcceptances) == 0 &&
		len(facts.ClearingRejections) == 0 &&
		len(facts.Novations) == 0 &&
		len(facts.Instructions) == 0 &&
		len(facts.Attempts) == 0 &&
		len(facts.LegOutcomes) == 0 &&
		len(facts.LedgerEntries) == 0 &&
		len(facts.Settlements) == 0 &&
		len(facts.Breaks) == 0 &&
		len(facts.Repairs) == 0 &&
		len(facts.Resolutions) == 0
}

func assertOpsRealisticPendingSettlementFacts(report *smokeReport, facts settlementFactsReport, proofSource string) {
	assertCount(report, "p2-one-settlement-obligation", "settlement_fact_count", len(facts.Obligations), 1, proofSource)
	assertCount(report, "p2-ops-pending-zero-allocations", "settlement_fact_count", len(facts.Allocations), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-confirmations", "settlement_fact_count", len(facts.Confirmations), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-affirmations", "settlement_fact_count", len(facts.Affirmations), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-clearing-submissions", "settlement_fact_count", len(facts.ClearingSubmissions), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-clearing-acceptances", "settlement_fact_count", len(facts.ClearingAcceptances), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-novations", "settlement_fact_count", len(facts.Novations), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-instructions", "settlement_fact_count", len(facts.Instructions), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-attempts", "settlement_fact_count", len(facts.Attempts), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-leg-outcomes", "settlement_fact_count", len(facts.LegOutcomes), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-ledger-entries", "settlement_fact_count", len(facts.LedgerEntries), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-settlements", "settlement_fact_count", len(facts.Settlements), 0, proofSource)
	assertCount(report, "p2-ops-pending-zero-breaks", "settlement_fact_count", len(facts.Breaks), 0, proofSource)
	if len(facts.Obligations) == 1 && facts.Obligations[0].State == "OBLIGATION_CREATED" {
		passAssertion(report, "p2-ops-pending-obligation-created", "OBLIGATION_CREATED", facts.Obligations[0].State, proofSource)
	} else {
		failAssertion(report, "p2-ops-pending-obligation-created", "settlement_fact_state", "OBLIGATION_CREATED", obligationStates(facts.Obligations), proofSource)
	}
	if len(facts.Obligations) == 1 && facts.Obligations[0].PostTradeProfileID == "ops-realistic-v1" {
		passAssertion(report, "p2-ops-pending-profile", "ops-realistic-v1", facts.Obligations[0].PostTradeProfileID, proofSource)
	} else {
		failAssertion(report, "p2-ops-pending-profile", "settlement_fact_profile", "ops-realistic-v1", obligationProfiles(facts.Obligations), proofSource)
	}
	if len(facts.Obligations) == 1 && strings.TrimSpace(facts.Obligations[0].TradeID) != "" {
		passAssertion(report, "p2-obligation-references-trade", "non-empty tradeId", facts.Obligations[0].TradeID, proofSource)
	} else {
		failAssertion(report, "p2-obligation-references-trade", "settlement_fact_causation", "one obligation with tradeId", obligationTradeIDs(facts.Obligations), proofSource)
	}
}

func assertPostTradeSettlementChain(report *smokeReport, facts settlementFactsReport, proofSource string) {
	assertCount(report, "p2-one-settlement-obligation", "settlement_fact_count", len(facts.Obligations), 1, proofSource)
	assertCount(report, "p2-one-allocation-proposed", "settlement_fact_count", len(facts.Allocations), 1, proofSource)
	assertCount(report, "p2-one-confirmation-generated", "settlement_fact_count", len(facts.Confirmations), 1, proofSource)
	assertCount(report, "p2-one-affirmation-accepted", "settlement_fact_count", len(facts.Affirmations), 1, proofSource)
	assertCount(report, "p2-one-clearing-submitted", "settlement_fact_count", len(facts.ClearingSubmissions), 1, proofSource)
	assertCount(report, "p2-one-clearing-accepted", "settlement_fact_count", len(facts.ClearingAcceptances), 1, proofSource)
	assertCount(report, "p2-zero-clearing-rejections", "settlement_fact_count", len(facts.ClearingRejections), 0, proofSource)
	assertCount(report, "p2-one-novation-recorded", "settlement_fact_count", len(facts.Novations), 1, proofSource)
	assertCount(report, "p2-one-settlement-instruction", "settlement_fact_count", len(facts.Instructions), 1, proofSource)
	assertCount(report, "p2-one-settlement-attempt", "settlement_fact_count", len(facts.Attempts), 1, proofSource)
	assertCount(report, "p2-one-settled-finality", "settlement_fact_count", len(facts.Settlements), 1, proofSource)
	if len(facts.Obligations) == 1 && strings.TrimSpace(facts.Obligations[0].TradeID) != "" {
		passAssertion(report, "p2-obligation-references-trade", "non-empty tradeId", facts.Obligations[0].TradeID, proofSource)
	} else {
		failAssertion(report, "p2-obligation-references-trade", "settlement_fact_causation", "one obligation with tradeId", obligationTradeIDs(facts.Obligations), proofSource)
	}
	assertSettlementFactState(report, "p2-obligation-state-created", stateFromObligation(facts.Obligations), "OBLIGATION_CREATED", proofSource)
	assertSettlementFactState(report, "p2-allocation-state-proposed", stateFromAllocation(facts.Allocations), "ALLOCATION_PROPOSED", proofSource)
	assertSettlementFactState(report, "p2-confirmation-state-generated", stateFromConfirmation(facts.Confirmations), "CONFIRMATION_GENERATED", proofSource)
	assertSettlementFactState(report, "p2-affirmation-state-accepted", stateFromAffirmation(facts.Affirmations), "AFFIRMATION_ACCEPTED", proofSource)
	assertSettlementFactState(report, "p2-clearing-state-submitted", stateFromClearingSubmission(facts.ClearingSubmissions), "CLEARING_SUBMITTED", proofSource)
	assertSettlementFactState(report, "p2-clearing-state-accepted", stateFromClearingAcceptance(facts.ClearingAcceptances), "CLEARING_ACCEPTED", proofSource)
	assertSettlementFactState(report, "p2-novation-state-recorded", stateFromNovation(facts.Novations), "NOVATION_RECORDED", proofSource)
	assertSettlementFactState(report, "p2-instruction-state-created", stateFromInstruction(facts.Instructions), "INSTRUCTION_CREATED", proofSource)
	assertSettlementFactState(report, "p2-attempt-state-started", stateFromAttempt(facts.Attempts), "ATTEMPT_STARTED", proofSource)
	assertSettlementFactState(report, "p2-settlement-state-settled", stateFromSettlement(facts.Settlements), "SETTLED", proofSource)
	if postTradeSettlementChainLinked(facts) {
		passAssertion(report, "p2-post-trade-chain-linked", "trade -> obligation -> allocation -> confirmation -> affirmation -> clearing -> novation -> settlement", "all fact references linked", proofSource)
	} else {
		failAssertion(report, "p2-post-trade-chain-linked", "settlement_fact_causation", "trade -> obligation -> allocation -> confirmation -> affirmation -> clearing -> novation -> settlement", postTradeSettlementChainObserved(facts), proofSource)
	}
}

func assertSettlementFactState(report *smokeReport, id string, observed string, expected string, proofSource string) {
	if observed == expected {
		passAssertion(report, id, expected, observed, proofSource)
		return
	}
	failAssertion(report, id, "settlement_fact_state", expected, observed, proofSource)
}

func stateFromObligation(facts []settlementObligation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromAllocation(facts []settlementAllocation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromConfirmation(facts []settlementConfirmation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromAffirmation(facts []settlementAffirmation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromClearingSubmission(facts []settlementClearingSubmission) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromClearingAcceptance(facts []settlementClearingAcceptance) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromNovation(facts []settlementNovation) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromInstruction(facts []settlementInstruction) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromAttempt(facts []settlementAttempt) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].State
}

func stateFromSettlement(facts []settlementSettled) string {
	if len(facts) != 1 {
		return fmt.Sprintf("count=%d", len(facts))
	}
	return facts[0].SettlementState
}

func assertCount(report *smokeReport, id string, category string, observed int, expected int, proofSource string) {
	if observed == expected {
		passAssertion(report, id, fmt.Sprint(expected), fmt.Sprint(observed), proofSource)
		return
	}
	failAssertion(report, id, category, fmt.Sprint(expected), fmt.Sprint(observed), proofSource)
}

func p2ResolutionReferencesRepair(facts settlementFactsReport) bool {
	if len(facts.Resolutions) != 1 || len(facts.Repairs) != 1 {
		return false
	}
	resolution := facts.Resolutions[0]
	repair := facts.Repairs[0]
	return resolution.SettlementRepairID != "" &&
		resolution.SettlementRepairID == repair.SettlementRepairID &&
		resolution.SettlementBreakID == repair.SettlementBreakID &&
		resolution.SettlementObligationID == repair.SettlementObligationID
}

func p2SettlementChainLinked(facts settlementFactsReport) bool {
	if len(facts.Obligations) != 1 || len(facts.Breaks) != 1 || len(facts.Repairs) != 1 || len(facts.Resolutions) != 1 {
		return false
	}
	obligation := facts.Obligations[0]
	breakFact := facts.Breaks[0]
	repair := facts.Repairs[0]
	resolution := facts.Resolutions[0]
	return obligation.SettlementObligationID != "" &&
		breakFact.SettlementBreakID != "" &&
		repair.SettlementRepairID != "" &&
		resolution.SettlementResolutionID != "" &&
		breakFact.SettlementObligationID == obligation.SettlementObligationID &&
		repair.SettlementBreakID == breakFact.SettlementBreakID &&
		repair.SettlementObligationID == obligation.SettlementObligationID &&
		resolution.SettlementRepairID == repair.SettlementRepairID &&
		resolution.SettlementBreakID == breakFact.SettlementBreakID &&
		resolution.SettlementObligationID == obligation.SettlementObligationID
}

func p2SettlementChainObserved(facts settlementFactsReport) string {
	if len(facts.Obligations) != 1 || len(facts.Breaks) != 1 || len(facts.Repairs) != 1 || len(facts.Resolutions) != 1 {
		return fmt.Sprintf("counts obligations=%d breaks=%d repairs=%d resolutions=%d", len(facts.Obligations), len(facts.Breaks), len(facts.Repairs), len(facts.Resolutions))
	}
	obligation := facts.Obligations[0]
	breakFact := facts.Breaks[0]
	repair := facts.Repairs[0]
	resolution := facts.Resolutions[0]
	return fmt.Sprintf(
		"obligation=%s break(obligation=%s,id=%s) repair(obligation=%s,break=%s,id=%s) resolution(obligation=%s,break=%s,repair=%s,id=%s)",
		obligation.SettlementObligationID,
		breakFact.SettlementObligationID,
		breakFact.SettlementBreakID,
		repair.SettlementObligationID,
		repair.SettlementBreakID,
		repair.SettlementRepairID,
		resolution.SettlementObligationID,
		resolution.SettlementBreakID,
		resolution.SettlementRepairID,
		resolution.SettlementResolutionID,
	)
}

func postTradeSettlementChainLinked(facts settlementFactsReport) bool {
	if len(facts.Obligations) != 1 || len(facts.Allocations) != 1 || len(facts.Confirmations) != 1 ||
		len(facts.Affirmations) != 1 || len(facts.ClearingSubmissions) != 1 || len(facts.ClearingAcceptances) != 1 ||
		len(facts.ClearingRejections) != 0 || len(facts.Novations) != 1 || len(facts.Instructions) != 1 ||
		len(facts.Attempts) != 1 || len(facts.Settlements) != 1 {
		return false
	}
	obligation := facts.Obligations[0]
	allocation := facts.Allocations[0]
	confirmation := facts.Confirmations[0]
	affirmation := facts.Affirmations[0]
	clearingSubmission := facts.ClearingSubmissions[0]
	clearingAcceptance := facts.ClearingAcceptances[0]
	novation := facts.Novations[0]
	instruction := facts.Instructions[0]
	attempt := facts.Attempts[0]
	settlement := facts.Settlements[0]
	return obligation.SettlementObligationID != "" &&
		obligation.TradeID != "" &&
		allocation.SettlementAllocationID != "" &&
		confirmation.SettlementConfirmationID != "" &&
		affirmation.SettlementAffirmationID != "" &&
		clearingSubmission.SettlementClearingSubmissionID != "" &&
		clearingAcceptance.SettlementClearingAcceptanceID != "" &&
		novation.SettlementNovationID != "" &&
		instruction.SettlementInstructionID != "" &&
		attempt.SettlementAttemptID != "" &&
		settlement.SettlementID != "" &&
		allocation.SettlementObligationID == obligation.SettlementObligationID &&
		allocation.TradeID == obligation.TradeID &&
		allocation.CausationID == obligation.SettlementObligationID &&
		allocation.BuyOrderID != "" &&
		allocation.SellOrderID != "" &&
		confirmation.SettlementAllocationID == allocation.SettlementAllocationID &&
		confirmation.SettlementObligationID == obligation.SettlementObligationID &&
		confirmation.TradeID == obligation.TradeID &&
		confirmation.CausationID == allocation.SettlementAllocationID &&
		affirmation.SettlementConfirmationID == confirmation.SettlementConfirmationID &&
		affirmation.SettlementAllocationID == allocation.SettlementAllocationID &&
		affirmation.SettlementObligationID == obligation.SettlementObligationID &&
		affirmation.TradeID == obligation.TradeID &&
		affirmation.CausationID == confirmation.SettlementConfirmationID &&
		clearingSubmission.SettlementObligationID == obligation.SettlementObligationID &&
		clearingSubmission.SettlementAffirmationID == affirmation.SettlementAffirmationID &&
		clearingSubmission.CausationID == affirmation.SettlementAffirmationID &&
		clearingSubmission.State == "CLEARING_SUBMITTED" &&
		clearingAcceptance.SettlementClearingSubmissionID == clearingSubmission.SettlementClearingSubmissionID &&
		clearingAcceptance.SettlementObligationID == obligation.SettlementObligationID &&
		clearingAcceptance.CausationID == clearingSubmission.SettlementClearingSubmissionID &&
		clearingAcceptance.State == "CLEARING_ACCEPTED" &&
		novation.SettlementClearingAcceptanceID == clearingAcceptance.SettlementClearingAcceptanceID &&
		novation.SettlementObligationID == obligation.SettlementObligationID &&
		novation.CausationID == clearingAcceptance.SettlementClearingAcceptanceID &&
		novation.State == "NOVATION_RECORDED" &&
		instruction.SettlementObligationID == obligation.SettlementObligationID &&
		instruction.CausationID == novation.SettlementNovationID &&
		instruction.State == "INSTRUCTION_CREATED" &&
		attempt.SettlementObligationID == obligation.SettlementObligationID &&
		attempt.SettlementInstructionID == instruction.SettlementInstructionID &&
		attempt.CausationID == instruction.SettlementInstructionID &&
		attempt.State == "ATTEMPT_STARTED" &&
		settlement.SettlementObligationID == obligation.SettlementObligationID &&
		settlement.SettlementInstructionID == instruction.SettlementInstructionID &&
		settlement.SettlementAttemptID == attempt.SettlementAttemptID &&
		settlement.CausationID == attempt.SettlementAttemptID &&
		settlement.SettlementState == "SETTLED"
}

func postTradeSettlementChainObserved(facts settlementFactsReport) string {
	if len(facts.Obligations) != 1 || len(facts.Allocations) != 1 || len(facts.Confirmations) != 1 ||
		len(facts.Affirmations) != 1 || len(facts.ClearingSubmissions) != 1 || len(facts.ClearingAcceptances) != 1 ||
		len(facts.ClearingRejections) != 0 || len(facts.Novations) != 1 || len(facts.Instructions) != 1 ||
		len(facts.Attempts) != 1 || len(facts.Settlements) != 1 {
		return fmt.Sprintf(
			"counts obligations=%d allocations=%d confirmations=%d affirmations=%d clearingSubmissions=%d clearingAcceptances=%d clearingRejections=%d novations=%d instructions=%d attempts=%d settlements=%d",
			len(facts.Obligations),
			len(facts.Allocations),
			len(facts.Confirmations),
			len(facts.Affirmations),
			len(facts.ClearingSubmissions),
			len(facts.ClearingAcceptances),
			len(facts.ClearingRejections),
			len(facts.Novations),
			len(facts.Instructions),
			len(facts.Attempts),
			len(facts.Settlements),
		)
	}
	obligation := facts.Obligations[0]
	allocation := facts.Allocations[0]
	confirmation := facts.Confirmations[0]
	affirmation := facts.Affirmations[0]
	clearingSubmission := facts.ClearingSubmissions[0]
	clearingAcceptance := facts.ClearingAcceptances[0]
	novation := facts.Novations[0]
	instruction := facts.Instructions[0]
	attempt := facts.Attempts[0]
	settlement := facts.Settlements[0]
	return fmt.Sprintf(
		"obligation(id=%s,trade=%s) allocation(id=%s,obligation=%s,trade=%s,cause=%s,buyOrder=%s,sellOrder=%s) confirmation(id=%s,allocation=%s,cause=%s) affirmation(id=%s,confirmation=%s,cause=%s) clearingSubmission(id=%s,affirmation=%s,cause=%s) clearingAcceptance(id=%s,submission=%s,cause=%s) novation(id=%s,acceptance=%s,cause=%s) instruction(id=%s,obligation=%s,cause=%s) attempt(id=%s,instruction=%s,cause=%s) settlement(id=%s,attempt=%s,cause=%s,state=%s)",
		obligation.SettlementObligationID,
		obligation.TradeID,
		allocation.SettlementAllocationID,
		allocation.SettlementObligationID,
		allocation.TradeID,
		allocation.CausationID,
		allocation.BuyOrderID,
		allocation.SellOrderID,
		confirmation.SettlementConfirmationID,
		confirmation.SettlementAllocationID,
		confirmation.CausationID,
		affirmation.SettlementAffirmationID,
		affirmation.SettlementConfirmationID,
		affirmation.CausationID,
		clearingSubmission.SettlementClearingSubmissionID,
		clearingSubmission.SettlementAffirmationID,
		clearingSubmission.CausationID,
		clearingAcceptance.SettlementClearingAcceptanceID,
		clearingAcceptance.SettlementClearingSubmissionID,
		clearingAcceptance.CausationID,
		novation.SettlementNovationID,
		novation.SettlementClearingAcceptanceID,
		novation.CausationID,
		instruction.SettlementInstructionID,
		instruction.SettlementObligationID,
		instruction.CausationID,
		attempt.SettlementAttemptID,
		attempt.SettlementInstructionID,
		attempt.CausationID,
		settlement.SettlementID,
		settlement.SettlementAttemptID,
		settlement.CausationID,
		settlement.SettlementState,
	)
}

func settlementFactsHaveCausation(facts settlementFactsReport) bool {
	for _, obligation := range facts.Obligations {
		if !hasCausationFields(obligation.ScenarioRunID, obligation.CorrelationID, obligation.CausationID) {
			return false
		}
	}
	for _, allocation := range facts.Allocations {
		if !hasCausationFields(allocation.ScenarioRunID, allocation.CorrelationID, allocation.CausationID) {
			return false
		}
	}
	for _, confirmation := range facts.Confirmations {
		if !hasCausationFields(confirmation.ScenarioRunID, confirmation.CorrelationID, confirmation.CausationID) {
			return false
		}
	}
	for _, affirmation := range facts.Affirmations {
		if !hasCausationFields(affirmation.ScenarioRunID, affirmation.CorrelationID, affirmation.CausationID) {
			return false
		}
	}
	for _, clearingSubmission := range facts.ClearingSubmissions {
		if !hasCausationFields(clearingSubmission.ScenarioRunID, clearingSubmission.CorrelationID, clearingSubmission.CausationID) {
			return false
		}
	}
	for _, clearingAcceptance := range facts.ClearingAcceptances {
		if !hasCausationFields(clearingAcceptance.ScenarioRunID, clearingAcceptance.CorrelationID, clearingAcceptance.CausationID) {
			return false
		}
	}
	for _, clearingRejection := range facts.ClearingRejections {
		if !hasCausationFields(clearingRejection.ScenarioRunID, clearingRejection.CorrelationID, clearingRejection.CausationID) {
			return false
		}
	}
	for _, novation := range facts.Novations {
		if !hasCausationFields(novation.ScenarioRunID, novation.CorrelationID, novation.CausationID) {
			return false
		}
	}
	for _, instruction := range facts.Instructions {
		if !hasCausationFields(instruction.ScenarioRunID, instruction.CorrelationID, instruction.CausationID) {
			return false
		}
	}
	for _, attempt := range facts.Attempts {
		if !hasCausationFields(attempt.ScenarioRunID, attempt.CorrelationID, attempt.CausationID) {
			return false
		}
	}
	for _, settlement := range facts.Settlements {
		if !hasCausationFields(settlement.ScenarioRunID, settlement.CorrelationID, settlement.CausationID) {
			return false
		}
	}
	for _, breakFact := range facts.Breaks {
		if !hasCausationFields(breakFact.ScenarioRunID, breakFact.CorrelationID, breakFact.CausationID) {
			return false
		}
	}
	for _, repair := range facts.Repairs {
		if !hasCausationFields(repair.ScenarioRunID, repair.CorrelationID, repair.CausationID) {
			return false
		}
	}
	for _, resolution := range facts.Resolutions {
		if !hasCausationFields(resolution.ScenarioRunID, resolution.CorrelationID, resolution.CausationID) {
			return false
		}
	}
	return true
}

func hasCausationFields(scenarioRunID string, correlationID string, causationID string) bool {
	return strings.TrimSpace(scenarioRunID) != "" &&
		strings.TrimSpace(correlationID) != "" &&
		strings.TrimSpace(causationID) != ""
}

func settlementFactsScopedToScenarioRun(facts settlementFactsReport, expectedScenarioRunID string) bool {
	if facts.ScenarioRunID != expectedScenarioRunID {
		return false
	}
	for _, obligation := range facts.Obligations {
		if obligation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, allocation := range facts.Allocations {
		if allocation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, confirmation := range facts.Confirmations {
		if confirmation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, affirmation := range facts.Affirmations {
		if affirmation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, clearingSubmission := range facts.ClearingSubmissions {
		if clearingSubmission.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, clearingAcceptance := range facts.ClearingAcceptances {
		if clearingAcceptance.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, clearingRejection := range facts.ClearingRejections {
		if clearingRejection.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, novation := range facts.Novations {
		if novation.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, instruction := range facts.Instructions {
		if instruction.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, attempt := range facts.Attempts {
		if attempt.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, settlement := range facts.Settlements {
		if settlement.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, breakFact := range facts.Breaks {
		if breakFact.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, repair := range facts.Repairs {
		if repair.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	for _, resolution := range facts.Resolutions {
		if resolution.ScenarioRunID != expectedScenarioRunID {
			return false
		}
	}
	return true
}

func settlementScenarioRunIDs(facts settlementFactsReport) string {
	values := []string{"bundle:" + facts.ScenarioRunID}
	for _, obligation := range facts.Obligations {
		values = append(values, "obligation:"+obligation.ScenarioRunID)
	}
	for _, allocation := range facts.Allocations {
		values = append(values, "allocation:"+allocation.ScenarioRunID)
	}
	for _, confirmation := range facts.Confirmations {
		values = append(values, "confirmation:"+confirmation.ScenarioRunID)
	}
	for _, affirmation := range facts.Affirmations {
		values = append(values, "affirmation:"+affirmation.ScenarioRunID)
	}
	for _, clearingSubmission := range facts.ClearingSubmissions {
		values = append(values, "clearingSubmission:"+clearingSubmission.ScenarioRunID)
	}
	for _, clearingAcceptance := range facts.ClearingAcceptances {
		values = append(values, "clearingAcceptance:"+clearingAcceptance.ScenarioRunID)
	}
	for _, clearingRejection := range facts.ClearingRejections {
		values = append(values, "clearingRejection:"+clearingRejection.ScenarioRunID)
	}
	for _, novation := range facts.Novations {
		values = append(values, "novation:"+novation.ScenarioRunID)
	}
	for _, instruction := range facts.Instructions {
		values = append(values, "instruction:"+instruction.ScenarioRunID)
	}
	for _, attempt := range facts.Attempts {
		values = append(values, "attempt:"+attempt.ScenarioRunID)
	}
	for _, settlement := range facts.Settlements {
		values = append(values, "settlement:"+settlement.ScenarioRunID)
	}
	for _, breakFact := range facts.Breaks {
		values = append(values, "break:"+breakFact.ScenarioRunID)
	}
	for _, repair := range facts.Repairs {
		values = append(values, "repair:"+repair.ScenarioRunID)
	}
	for _, resolution := range facts.Resolutions {
		values = append(values, "resolution:"+resolution.ScenarioRunID)
	}
	return strings.Join(values, ",")
}

func obligationTradeIDs(obligations []settlementObligation) string {
	values := make([]string, 0, len(obligations))
	for _, obligation := range obligations {
		values = append(values, obligation.TradeID)
	}
	return strings.Join(values, ",")
}

func obligationStates(obligations []settlementObligation) string {
	values := make([]string, 0, len(obligations))
	for _, obligation := range obligations {
		values = append(values, obligation.State)
	}
	return strings.Join(values, ",")
}

func obligationProfiles(obligations []settlementObligation) string {
	values := make([]string, 0, len(obligations))
	for _, obligation := range obligations {
		values = append(values, obligation.PostTradeProfileID)
	}
	return strings.Join(values, ",")
}

func breakReasons(breaks []settlementBreak) string {
	values := make([]string, 0, len(breaks))
	for _, breakFact := range breaks {
		values = append(values, breakFact.Reason)
	}
	return strings.Join(values, ",")
}

func breakStates(breaks []settlementBreak) string {
	values := make([]string, 0, len(breaks))
	for _, breakFact := range breaks {
		values = append(values, breakFact.State)
	}
	return strings.Join(values, ",")
}

func repairActions(repairs []settlementRepair) string {
	values := make([]string, 0, len(repairs))
	for _, repair := range repairs {
		values = append(values, repair.RepairAction)
	}
	return strings.Join(values, ",")
}

func settlementStates(resolutions []settlementResolution) string {
	values := make([]string, 0, len(resolutions))
	for _, resolution := range resolutions {
		values = append(values, resolution.SettlementState)
	}
	return strings.Join(values, ",")
}

func exceptionStates(resolutions []settlementResolution) string {
	values := make([]string, 0, len(resolutions))
	for _, resolution := range resolutions {
		values = append(values, resolution.ExceptionState)
	}
	return strings.Join(values, ",")
}
