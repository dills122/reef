import type { BotResultV1 } from "./index";
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

export async function sendVenueCommandRequestsV1(
  requests: readonly VenueCommandRequestV1[],
  transport: VenueCommandTransportV1,
): Promise<BotResultV1<readonly VenueCommandResponseV1[]>> {
  const responses: VenueCommandResponseV1[] = [];
  for (const request of requests) {
    const response = await transport.send(request);
    if (response.status < 200 || response.status >= 300) {
      return {
        ok: false,
        denial: {
          code: "TEMPORARILY_UNAVAILABLE",
          message: `Venue command ${request.body.commandId ?? "unknown"} failed with HTTP ${response.status}.`,
        },
      };
    }
    responses.push(response);
  }
  return { ok: true, value: responses };
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
