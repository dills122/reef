import type { BotActionV1, BotResultV1 } from "./index";
import type { VenueCommandRequestV1 } from "./venue-adapter";

export interface VenueCommandResponseV1 {
  readonly route: VenueCommandRequestV1["route"];
  readonly status: number;
  readonly body: string;
  readonly commandId: string;
}

export interface VenueCommandTransportV1 {
  send(request: VenueCommandRequestV1): Promise<VenueCommandResponseV1>;
}

export interface VenueHttpClientOptionsV1 {
  readonly baseUrl: string;
  readonly fetch?: VenueFetchV1;
}

export type VenueFetchV1 = (
  url: string,
  init: {
    readonly method: string;
    readonly headers: Readonly<Record<string, string>>;
    readonly body: string;
  },
) => Promise<{ readonly status: number; text(): Promise<string> }>;

/** Outcomes describe intake evidence, never matching completion. */
export type VenueCommandOutcomeV1 =
  | { readonly status: "accepted" | "rejected"; readonly request: VenueCommandRequestV1;
      readonly response: VenueCommandResponseV1 }
  | { readonly status: "unknown"; readonly request: VenueCommandRequestV1; readonly message: string }
  | { readonly status: "not_sent"; readonly request: VenueCommandRequestV1 };

export type VenueCommandBatchResultV1 = BotResultV1<readonly VenueCommandResponseV1[]> & {
  readonly acceptedResponses: readonly VenueCommandResponseV1[];
  readonly outcomes: readonly VenueCommandOutcomeV1[];
};

export async function sendVenueCommandRequestsV1(
  requests: readonly VenueCommandRequestV1[],
  transport: VenueCommandTransportV1,
): Promise<VenueCommandBatchResultV1> {
  const acceptedResponses: VenueCommandResponseV1[] = [];
  const outcomes: VenueCommandOutcomeV1[] = requests.map((request) => ({ status: "not_sent", request }));
  for (const [index, request] of requests.entries()) {
    let response: VenueCommandResponseV1;
    try {
      response = await transport.send(request);
    } catch {
      // A lost response cannot prove whether durable intake accepted the command.
      const message = `Venue command ${request.body.commandId ?? "unknown"} has an unknown transport outcome.`;
      outcomes[index] = { status: "unknown", request, message };
      return { ok: false, denial: { code: "TEMPORARILY_UNAVAILABLE", message }, acceptedResponses, outcomes };
    }
    if (response.status < 200 || response.status >= 300) {
      outcomes[index] = { status: "rejected", request, response };
      return {
        ok: false,
        denial: {
          code: "TEMPORARILY_UNAVAILABLE",
          message: `Venue command ${request.body.commandId ?? "unknown"} failed with HTTP ${response.status}.`,
        },
        acceptedResponses,
        outcomes,
      };
    }
    outcomes[index] = { status: "accepted", request, response };
    acceptedResponses.push(response);
  }
  return { ok: true, value: acceptedResponses, acceptedResponses, outcomes };
}

/** Preserve action positions (including noops) when applying an accepted command prefix. */
export function acceptedVenueActionPrefixV1(
  actions: readonly BotActionV1[],
  acceptedCommandCount: number,
): readonly BotActionV1[] {
  let commandIndex = 0;
  const firstUnacceptedIndex = actions.findIndex((action) =>
    action.type !== "noop" && commandIndex++ >= acceptedCommandCount);
  return firstUnacceptedIndex < 0 ? actions : actions.slice(0, firstUnacceptedIndex);
}

export function createVenueHttpTransportV1(options: VenueHttpClientOptionsV1): VenueCommandTransportV1 {
  const fetchImpl = options.fetch ?? (globalThis as { fetch?: VenueFetchV1 }).fetch;
  if (fetchImpl === undefined) {
    throw new Error("No fetch implementation available for venue HTTP transport.");
  }

  return {
    async send(request) {
      const response = await fetchImpl(`${options.baseUrl.replace(/\/$/, "")}${request.route}`, {
        method: request.method,
        headers: request.headers,
        body: JSON.stringify(request.body),
      });
      return {
        route: request.route,
        status: response.status,
        body: await response.text(),
        commandId: request.body.commandId ?? "",
      };
    },
  };
}

export function createRecordingVenueTransportV1(
  responseStatus = 202,
): VenueCommandTransportV1 & { readonly requests: readonly VenueCommandRequestV1[] } {
  const requests: VenueCommandRequestV1[] = [];
  return {
    requests,
    async send(request) {
      requests.push(request);
      return {
        route: request.route,
        status: responseStatus,
        body: JSON.stringify({ commandId: request.body.commandId, accepted: responseStatus >= 200 && responseStatus < 300 }),
        commandId: request.body.commandId ?? "",
      };
    },
  };
}
