import { env } from '$env/dynamic/public';
import { resolveFixture } from './dev-fixtures';

export const ARENA_API_BASE_URL = env.PUBLIC_ARENA_API_BASE_URL?.trim() ?? '';

export type LeaderboardEntry = {
	rank: number;
	runId: string;
	botId: string;
	botName: string;
	ownerHandle: string;
	versionId: string;
	scoringPolicyVersion: string;
	finalEquity: number;
	realizedPnl: number;
	maxDrawdown: number;
	disqualified: boolean;
};

export type LeaderboardResponse = {
	modeId: string;
	scoringPolicyVersion: string;
	entries: LeaderboardEntry[];
	fetchedAt: string;
};

export type SessionUser = {
	reefUserId: string;
	githubLogin: string;
	displayName?: string;
	trustState?: string;
	roles: string[];
};

export type AdminAccessUserRole = {
	reefUserId: string;
	roleId: string;
	assignedBy: string;
	assignedAt: string;
};

export type AdminAccessBotOwnership = {
	reefUserId: string;
	botId: string;
	ownershipState: string;
	assignedBy: string;
	assignedAt: string;
};

export type AdminAccessUser = {
	reefUserId: string;
	githubUserId: number;
	githubLogin: string;
	displayName: string;
	trustState: string;
	roles: AdminAccessUserRole[];
	botOwnerships: AdminAccessBotOwnership[];
	createdAt: string;
	lastSeenAt: string;
	updatedAt: string;
};

export type AdminAccessRole = {
	roleId: string;
	description: string;
	createdAt: string;
};

const operatorRoles = new Set([
	'operator',
	'site-operator',
	'game-admin',
	'secret-admin',
	'platform-admin',
	'arena-operator'
]);
const botAdminRoles = new Set([
	'participant',
	'reviewer',
	'operator',
	'site-operator',
	'game-admin',
	'secret-admin',
	'platform-admin',
	'arena-operator'
]);
const roleDisplayAliases = new Map([
	['site-operator', 'operator'],
	['game-admin', 'operator'],
	['arena-operator', 'operator']
]);

function normalizedRole(role: string): string {
	return role.trim().toLowerCase().replaceAll('_', '-');
}

function canonicalDisplayRole(role: string): string {
	const normalized = normalizedRole(role);
	return roleDisplayAliases.get(normalized) ?? normalized;
}

export function displayRoles(roles: string[]): string {
	const display = Array.from(
		new Set(roles.map(canonicalDisplayRole).filter((role) => role.length > 0))
	).sort();
	return display.length ? display.join(', ') : 'none';
}

export function hasOperatorAccess(user: SessionUser): boolean {
	const trustState = (user.trustState ?? '').toLowerCase();
	if (trustState && trustState !== 'trusted') return false;
	return user.roles.some((role) => operatorRoles.has(normalizedRole(role)));
}

export function hasBotAdminAccess(user: SessionUser): boolean {
	const trustState = (user.trustState ?? '').toLowerCase();
	if (trustState === 'banned') return false;
	return user.roles.some((role) => botAdminRoles.has(normalizedRole(role)));
}

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

export type OwnedArenaBotsResponse = {
	reefUserId: string;
	bots: ArenaBot[];
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

export type ArenaAdmissionWindow = {
	windowId: string;
	policyVersion: string;
	scheduledStart: string;
	inviteDecisionCutoff: string;
	mergeReadinessCutoff: string;
	rosterLockAt: string;
	operationalRecheckAt: string;
	runInstantiationAt: string;
	displayTimeZone: string;
	createdAt: string;
};

export type ArenaEligibilityDecision = {
	evaluationId: string;
	windowId: string;
	botId: string;
	versionId: string;
	outcome: 'eligible_for_roster' | 'rolled_to_next_window' | 'excluded';
	reasonCodes: string[];
	sourceHash: string;
	artifactHash: string;
	configHash: string;
	evaluatedAt: string;
	correlationId: string;
};

export type ArenaRosterPreviewEntry = {
	priority: number;
	decision: ArenaEligibilityDecision;
};

export type ArenaRosterPreview = {
	windowId: string;
	maxBots: number;
	included: ArenaRosterPreviewEntry[];
	capacityOverflow: ArenaRosterPreviewEntry[];
	awaitingPriority: ArenaEligibilityDecision[];
	rolled: ArenaEligibilityDecision[];
	excluded: ArenaEligibilityDecision[];
};

export type ArenaRosterEntry = {
	botOrder: number;
	botId: string;
	versionId: string;
	priority: number;
	sourceHash: string;
	artifactHash: string;
	configHash: string;
	eligibilityEvaluationId: string;
};

export type ArenaRosterRemoval = {
	removalId: string;
	windowId: string;
	snapshotId: string;
	botId: string;
	versionId: string;
	reasonCode: 'security' | 'trust' | 'config' | 'availability';
	detail: string;
	removedAt: string;
	removedBy: string;
	correlationId: string;
};

export type ArenaRoster = {
	snapshotId: string;
	windowId: string;
	snapshotHash: string;
	maxBots: number;
	lockedAt: string;
	lockedBy: string;
	correlationId: string;
	policy: Record<string, string>;
	entries: ArenaRosterEntry[];
	effectiveEntries: ArenaRosterEntry[];
	removals: ArenaRosterRemoval[];
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

export type BotConfigStatus = {
	botId: string;
	ownerIdentity: string;
	secretPath: string;
	hasConfig: boolean;
	config?: Record<string, unknown> | null;
	keys: string[];
	updatedAt?: string;
	updatedBy?: string;
	version?: number | null;
};

export type BotRuntimeConfigDescriptor = {
	botId: string;
	versionId: string;
	key: string;
	provider: string;
	secretPath: string;
	required: boolean;
	description?: string;
	valueType?: string;
};

type ErrorBody = {
	error?: string;
	code?: string;
	message?: string;
};

function localDevFakeAdminEnabled(): boolean {
	if (!import.meta.env.DEV) return false;
	const value = (env.PUBLIC_ARENA_LOCAL_DEV_FAKE_ADMIN ?? '').trim().toLowerCase();
	return value === '1' || value === 'true' || value === 'yes' || value === 'on';
}

function localDevFixturesEnabled(): boolean {
	if (!import.meta.env.DEV) return false;
	const value = (env.PUBLIC_ARENA_LOCAL_DEV_FIXTURES ?? '').trim().toLowerCase();
	return value === '1' || value === 'true' || value === 'yes' || value === 'on';
}

async function fetchAdminJson<T>(path: string, init: RequestInit = {}): Promise<T> {
	if (import.meta.env.DEV && localDevFixturesEnabled()) {
		const method = (init.method ?? 'GET').toUpperCase();
		const url = new URL(path, 'http://arena-admin.local');
		const fixture = resolveFixture(method, url, init);
		if (fixture !== undefined) return fixture as T;
	}

	const res = await fetch(`${ARENA_API_BASE_URL}${path}`, {
		...init,
		credentials: 'include',
		headers: {
			...(init.body ? { 'content-type': 'application/json' } : {}),
			...(init.headers ?? {})
		}
	});
	if (!res.ok) {
		throw new Error(await responseErrorMessage(res));
	}
	return (await res.json()) as T;
}

async function responseErrorMessage(res: Response): Promise<string> {
	let message = `${res.status} ${res.statusText}`.trim();
	try {
		const body = (await res.json()) as ErrorBody;
		message = body.error || body.message || body.code || message;
	} catch {
		// Keep HTTP status fallback.
	}
	return message;
}

// Requires an X-Client-Id header per the venue-intake read boundary
// (ExternalApiBoundary.checkRead) — public/unauthenticated, but still
// client-identified for rate limiting.
export async function fetchLeaderboard(
	modeId: string,
	scoringPolicyVersion: string,
	options: { limit?: number; signal?: AbortSignal } = {}
): Promise<LeaderboardResponse> {
	const params = new URLSearchParams({
		modeId,
		scoringPolicyVersion,
		limit: String(options.limit ?? 50)
	});
	const res = await fetch(`${ARENA_API_BASE_URL}/api/v1/arena/leaderboard?${params}`, {
		headers: { 'X-Client-Id': 'arena-admin-web' },
		signal: options.signal
	});
	if (!res.ok) {
		throw new Error(await responseErrorMessage(res));
	}
	const body = (await res.json()) as Partial<LeaderboardResponse>;
	return {
		modeId: body.modeId ?? modeId,
		scoringPolicyVersion: body.scoringPolicyVersion ?? scoringPolicyVersion,
		entries: Array.isArray(body.entries) ? body.entries : [],
		fetchedAt: new Date().toISOString()
	};
}

function localDevFakeAdminSession(): SessionUser {
	return {
		reefUserId: 'admin-cli',
		githubLogin: 'local-dev-admin',
		displayName: 'Local Dev Admin',
		trustState: 'trusted',
		roles: ['operator', 'participant']
	};
}

export async function fetchSession(): Promise<SessionUser | null> {
	try {
		const res = await fetch(`${ARENA_API_BASE_URL}/admin/auth/session`, {
			credentials: 'include'
		});
		if (!res.ok) {
			if (import.meta.env.DEV && localDevFakeAdminEnabled()) {
				return localDevFakeAdminSession();
			}
			return null;
		}
		const body = (await res.json()) as Partial<SessionUser>;
		return {
			reefUserId: body.reefUserId ?? '',
			githubLogin: body.githubLogin || body.reefUserId || 'admin',
			displayName: body.displayName ?? '',
			trustState: body.trustState ?? '',
			roles: Array.isArray(body.roles) ? body.roles : []
		};
	} catch {
		if (import.meta.env.DEV && localDevFakeAdminEnabled()) {
			return localDevFakeAdminSession();
		}
		return null;
	}
}

export function githubLoginUrl(redirectPath: string): string {
	return `${ARENA_API_BASE_URL}/admin/auth/github/start?redirectPath=${encodeURIComponent(redirectPath)}`;
}

export async function fetchAdminBots(limit = 100): Promise<ArenaBot[]> {
	const params = new URLSearchParams({ limit: String(limit) });
	const body = await fetchAdminJson<{ bots: ArenaBot[] }>(`/admin/v1/arena/bots?${params}`);
	return body.bots ?? [];
}

export async function fetchOwnedArenaBots(limit = 100): Promise<OwnedArenaBotsResponse> {
	const params = new URLSearchParams({ limit: String(limit) });
	const body = await fetchAdminJson<Partial<OwnedArenaBotsResponse>>(
		`/admin/v1/arena/my/bots?${params}`
	);
	return {
		reefUserId: body.reefUserId ?? '',
		bots: Array.isArray(body.bots) ? body.bots : []
	};
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

export async function fetchArenaAdmissionWindow(windowId: string): Promise<ArenaAdmissionWindow> {
	const params = new URLSearchParams({ windowId });
	const body = await fetchAdminJson<{ window: ArenaAdmissionWindow }>(
		`/admin/v1/arena/admission-windows?${params}`
	);
	return body.window;
}

export async function fetchArenaEligibilityDecisions(windowId: string): Promise<ArenaEligibilityDecision[]> {
	const params = new URLSearchParams({ windowId });
	const body = await fetchAdminJson<{ decisions: ArenaEligibilityDecision[] }>(
		`/admin/v1/arena/eligibility-decisions?${params}`
	);
	return body.decisions ?? [];
}

export async function previewArenaRoster(
	windowId: string,
	maxBots: number,
	candidates: { evaluationId: string; priority: number }[]
): Promise<ArenaRosterPreview> {
	const body = await fetchAdminJson<{ preview: ArenaRosterPreview }>('/admin/v1/arena/roster-previews', {
		method: 'POST',
		body: JSON.stringify({ windowId, maxBots, candidates })
	});
	return body.preview;
}

export async function fetchArenaRoster(windowId: string): Promise<ArenaRoster> {
	const params = new URLSearchParams({ windowId });
	const body = await fetchAdminJson<{ roster: ArenaRoster }>(`/admin/v1/arena/rosters?${params}`);
	return body.roster;
}

export async function removeArenaRosterEntry(input: {
	removalId: string;
	windowId: string;
	botId: string;
	versionId: string;
	reasonCode: ArenaRosterRemoval['reasonCode'];
	detail: string;
}): Promise<ArenaRosterRemoval> {
	const body = await fetchAdminJson<{ removal: ArenaRosterRemoval }>('/admin/v1/arena/roster-removals', {
		method: 'POST',
		body: JSON.stringify(input)
	});
	return body.removal;
}

export async function fetchAdminRiskControls(): Promise<AccountRiskControl[]> {
	const body = await fetchAdminJson<{ controls: AccountRiskControl[] }>(
		'/admin/v1/risk/account-controls'
	);
	return body.controls ?? [];
}

export async function fetchAdminAccessUsers(limit = 100): Promise<AdminAccessUser[]> {
	const params = new URLSearchParams({ limit: String(limit) });
	const body = await fetchAdminJson<{ users: AdminAccessUser[] }>(`/admin/v1/access/users?${params}`);
	return body.users ?? [];
}

export async function fetchAdminAccessRoles(): Promise<AdminAccessRole[]> {
	const body = await fetchAdminJson<{ roles: AdminAccessRole[] }>('/admin/v1/access/roles');
	return body.roles ?? [];
}

export async function updateAdminUserTrustState(
	reefUserId: string,
	trustState: string,
	reason: string
): Promise<void> {
	await fetchAdminJson('/admin/v1/access/users/trust-state', {
		method: 'POST',
		body: JSON.stringify({ reefUserId, trustState, reason })
	});
}

export async function assignAdminAccessRole(
	reefUserId: string,
	roleId: string,
	reason: string
): Promise<void> {
	await fetchAdminJson('/admin/v1/access/users/roles', {
		method: 'POST',
		body: JSON.stringify({ reefUserId, roleId, reason })
	});
}

export async function revokeAdminAccessRole(
	reefUserId: string,
	roleId: string,
	reason: string
): Promise<void> {
	await fetchAdminJson('/admin/v1/access/users/roles/revoke', {
		method: 'POST',
		body: JSON.stringify({ reefUserId, roleId, reason })
	});
}

export async function fetchBotConfigStatus(botId: string): Promise<BotConfigStatus> {
	const params = new URLSearchParams({ botId });
	return await fetchAdminJson<BotConfigStatus>(`/admin/v1/arena/bots/config?${params}`);
}

export async function fetchBotRuntimeConfigDescriptors(
	botId: string,
	versionId: string
): Promise<BotRuntimeConfigDescriptor[]> {
	const params = new URLSearchParams({ botId, versionId });
	const body = await fetchAdminJson<{ descriptors: BotRuntimeConfigDescriptor[] }>(
		`/admin/v1/arena/runtime-config-descriptors?${params}`
	);
	return body.descriptors ?? [];
}

export async function replaceBotConfig(botId: string, config: unknown): Promise<BotConfigStatus> {
	if (import.meta.env.DEV && localDevFixturesEnabled()) {
		throw new Error(
			'local fixture mode does not persist bot config; use http://localhost:5174 with PUBLIC_ARENA_API_BASE_URL=http://localhost:8080 and disable PUBLIC_ARENA_LOCAL_DEV_FIXTURES to test writes'
		);
	}
	return await fetchAdminJson<BotConfigStatus>('/admin/v1/arena/bots/config', {
		method: 'PUT',
		body: JSON.stringify({ botId, config })
	});
}

export async function deleteBotConfig(botId: string): Promise<BotConfigStatus> {
	if (import.meta.env.DEV && localDevFixturesEnabled()) {
		throw new Error(
			'local fixture mode does not persist bot config; use http://localhost:5174 with PUBLIC_ARENA_API_BASE_URL=http://localhost:8080 and disable PUBLIC_ARENA_LOCAL_DEV_FIXTURES to test writes'
		);
	}
	const params = new URLSearchParams({ botId });
	return await fetchAdminJson<BotConfigStatus>(`/admin/v1/arena/bots/config?${params}`, {
		method: 'DELETE'
	});
}
