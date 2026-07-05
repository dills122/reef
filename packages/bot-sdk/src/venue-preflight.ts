import type { BotScenarioFixtureV1 } from "./runner";

export type BotVenuePreflightSeverityV1 = "error" | "warning";

export interface BotVenueSeedRequirementV1 {
  readonly kind: "instrument" | "participant" | "account" | "actor_role_binding" | "venue_session";
  readonly id: string;
  readonly description: string;
}

export interface BotVenuePreflightIssueV1 {
  readonly code: string;
  readonly message: string;
  readonly severity: BotVenuePreflightSeverityV1;
}

export interface BotVenuePreflightReportV1 {
  readonly status: "ready" | "not_ready";
  readonly requirements: readonly BotVenueSeedRequirementV1[];
  readonly issues: readonly BotVenuePreflightIssueV1[];
}

export function venueSeedRequirementsForFixtureV1(fixture: BotScenarioFixtureV1): readonly BotVenueSeedRequirementV1[] {
  const instrumentIds = new Set<string>();
  for (const tick of fixture.ticks) {
    for (const instrumentId of Object.keys(tick.marketSnapshots)) {
      instrumentIds.add(instrumentId);
    }
  }
  for (const order of fixture.initialOrders ?? []) {
    instrumentIds.add(order.instrumentId);
  }

  return [
    ...Array.from(instrumentIds)
      .sort()
      .map((instrumentId) => ({
        kind: "instrument" as const,
        id: instrumentId,
        description: `Instrument ${instrumentId} exists in Reef reference data.`,
      })),
    {
      kind: "participant",
      id: fixture.participantId,
      description: `Participant ${fixture.participantId} exists for bot-originated orders.`,
    },
    {
      kind: "account",
      id: fixture.accountId,
      description: `Account ${fixture.accountId} exists and belongs to participant ${fixture.participantId}.`,
    },
    {
      kind: "actor_role_binding",
      id: fixture.actorId,
      description: `Actor ${fixture.actorId} has order submit, cancel, and modify permissions.`,
    },
    {
      kind: "venue_session",
      id: fixture.venueSessionId,
      description: `Venue session ${fixture.venueSessionId} is valid for the run.`,
    },
  ];
}

export function validateVenuePreflightV1(fixture: BotScenarioFixtureV1): BotVenuePreflightReportV1 {
  const issues: BotVenuePreflightIssueV1[] = [];
  const requiredStringFields: readonly (keyof BotScenarioFixtureV1)[] = [
    "runId",
    "venueSessionId",
    "actorId",
    "participantId",
    "accountId",
    "botId",
    "botVersion",
    "correlationId",
  ];

  for (const field of requiredStringFields) {
    const value = fixture[field];
    if (typeof value !== "string" || value.trim().length === 0) {
      issues.push({
        code: "missing_fixture_field",
        message: `Fixture field ${field} is required for live venue submission.`,
        severity: "error",
      });
    }
  }

  if (fixture.ticks.length === 0) {
    issues.push({
      code: "missing_ticks",
      message: "Fixture must include at least one tick.",
      severity: "error",
    });
  }

  const requirements = venueSeedRequirementsForFixtureV1(fixture);
  if (!requirements.some((requirement) => requirement.kind === "instrument")) {
    issues.push({
      code: "missing_instrument_requirement",
      message: "Fixture must include at least one instrument through market snapshots or initial orders.",
      severity: "error",
    });
  }

  return {
    status: issues.some((issue) => issue.severity === "error") ? "not_ready" : "ready",
    requirements,
    issues,
  };
}
