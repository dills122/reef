import { PUBLIC_ARENA_API_BASE_URL } from '$env/static/public';

export type LeaderboardEntry = {
	rank: number;
	botName: string;
	ownerHandle: string;
	finalEquity: number;
	realizedPnl: number;
	maxDrawdown: number;
};

export type SessionUser = {
	githubLogin: string;
	roles: string[];
};

export type ArenaBot = {
	botId: string;
	fileName: string;
	metadata: {
		name: string;
		publisher: string;
		email: string;
		description?: string;
		version?: string;
	};
	owners?: ArenaBotOwner[];
	createdAt: string;
};

export type ArenaBotOwner = {
	reefUserId: string;
	githubLogin: string;
	displayName: string;
	trustState: string;
	ownershipState: string;
	assignedAt: string;
};

export type ArenaRun = {
	runId: string;
	modeId: string;
	scenarioId: string;
	seed: number;
	policyVersion: string;
	botVersions: { botId: string; versionId: string }[];
	status: string;
	createdAt: string;
	completedAt?: string | null;
};

export type ArenaRunBotResult = {
	runId: string;
	botId: string;
	versionId: string;
	scoringPolicyVersion: string;
	finalEquity: number;
	realizedPnl: number;
	maxDrawdown: number;
	actionsProposed: number;
	orderActionsProposed: number;
	dataCalls: number;
	signalsGenerated: number;
	disqualified: boolean;
	scoreEligible: boolean;
	publicLeaderboard: boolean;
	createdAt: string;
};

export type ArenaRunEnforcementEvent = {
	runId: string;
	botId: string;
	versionId: string;
	decision: string;
	reasonCode: string;
	reason: string;
	policyVersion: string;
	countersJson: string;
	occurredAt: string;
};

export type AccountRiskControl = {
	scopeType: string;
	scopeId: string;
	decision: string;
	reason: string;
	maxQuantityUnits: string;
	maxNotional: string;
	currency: string;
	updatedAt?: string;
};

type ErrorBody = {
	error?: string;
};

async function fetchAdminJson<T>(path: string): Promise<T> {
	const res = await fetch(`${PUBLIC_ARENA_API_BASE_URL}${path}`, {
		credentials: 'include'
	});
	if (!res.ok) {
		let message = `${res.status} ${res.statusText}`.trim();
		try {
			const body = (await res.json()) as ErrorBody;
			if (body.error) message = body.error;
		} catch {
			// Keep HTTP status fallback.
		}
		throw new Error(message);
	}
	return (await res.json()) as T;
}

// Requires an X-Client-Id header per the venue-intake read boundary
// (ExternalApiBoundary.checkRead) — public/unauthenticated, but still
// client-identified for rate limiting. Returns [] on any failure so the
// page renders an empty state rather than an error.
export async function fetchLeaderboard(
	modeId: string,
	scoringPolicyVersion: string
): Promise<LeaderboardEntry[]> {
	try {
		const params = new URLSearchParams({ modeId, scoringPolicyVersion });
		const res = await fetch(`${PUBLIC_ARENA_API_BASE_URL}/api/v1/arena/leaderboard?${params}`, {
			headers: { 'X-Client-Id': 'arena-admin-web' }
		});
		if (!res.ok) return [];
		const body = (await res.json()) as { entries: LeaderboardEntry[] };
		return body.entries;
	} catch {
		return [];
	}
}

export async function fetchSession(): Promise<SessionUser | null> {
	try {
		const res = await fetch(`${PUBLIC_ARENA_API_BASE_URL}/admin/auth/session`, {
			credentials: 'include'
		});
		if (!res.ok) return null;
		return (await res.json()) as SessionUser;
	} catch {
		return null;
	}
}

export function githubLoginUrl(redirectPath: string): string {
	return `${PUBLIC_ARENA_API_BASE_URL}/admin/auth/github/start?redirectPath=${encodeURIComponent(redirectPath)}`;
}

export async function fetchAdminBots(limit = 100): Promise<ArenaBot[]> {
	const params = new URLSearchParams({ limit: String(limit) });
	const body = await fetchAdminJson<{ bots: ArenaBot[] }>(`/admin/v1/arena/bots?${params}`);
	return body.bots ?? [];
}

export async function fetchAdminRuns(limit = 20): Promise<ArenaRun[]> {
	const params = new URLSearchParams({ limit: String(limit) });
	const body = await fetchAdminJson<{ runs: ArenaRun[] }>(`/admin/v1/arena/runs?${params}`);
	return body.runs ?? [];
}

export async function fetchAdminRunResults(runId: string): Promise<ArenaRunBotResult[]> {
	const params = new URLSearchParams({ runId });
	const body = await fetchAdminJson<{ results: ArenaRunBotResult[] }>(
		`/admin/v1/arena/run-bot-results?${params}`
	);
	return body.results ?? [];
}

export async function fetchAdminRunEnforcementEvents(runId: string): Promise<ArenaRunEnforcementEvent[]> {
	const params = new URLSearchParams({ runId });
	const body = await fetchAdminJson<{ events: ArenaRunEnforcementEvent[] }>(
		`/admin/v1/arena/run-enforcement-events?${params}`
	);
	return body.events ?? [];
}

export async function fetchAdminRiskControls(): Promise<AccountRiskControl[]> {
	const body = await fetchAdminJson<{ controls: AccountRiskControl[] }>(
		'/admin/v1/risk/account-controls'
	);
	return body.controls ?? [];
}
