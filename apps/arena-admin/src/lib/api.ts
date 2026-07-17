import { env } from '$env/dynamic/public';

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

function normalizedRole(role: string): string {
	return role.trim().toLowerCase().replaceAll('_', '-');
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
		const now = '2026-07-11T21:30:00Z';
		const bots = [
			{
				botId: 'dsteele-spread-maker',
				fileName: 'packages/bot-sdk/examples/simple-market-maker.ts',
				metadata: {
					name: 'dsteele-spread-maker',
					publisher: 'dills122',
					email: '15662762+dills122@users.noreply.github.com',
					description: 'local visual fixture',
					version: '0.1.0'
				},
				owners: [
					{
						reefUserId: 'admin-cli',
						githubLogin: 'local-dev-admin',
						displayName: 'Local Dev Admin',
						trustState: 'trusted',
						ownershipState: 'owner',
						assignedAt: now
					}
				],
				createdAt: '2026-07-10T15:44:01Z'
			},
			{
				botId: 'latency-arb-fixture',
				fileName: 'packages/bot-sdk/examples/latency-arb.ts',
				metadata: {
					name: 'latency-arb-fixture',
					publisher: 'reef-labs',
					email: 'arena@example.test',
					description: 'frozen fixture bot',
					version: '0.2.0'
				},
				owners: [],
				createdAt: '2026-07-10T16:12:18Z'
			}
		];
		const runs = [
			{
				runId: 'local-visual-run-001',
				modeId: 'hosted-sim',
				scenarioId: 'opening-auction-smoke',
				seed: 424242,
				policyVersion: 'arena-policy-v1',
				botVersions: [
					{ botId: 'dsteele-spread-maker', versionId: 'v1' },
					{ botId: 'latency-arb-fixture', versionId: 'v2' }
				],
				status: 'COMPLETED',
				createdAt: '2026-07-11T21:10:00Z',
				completedAt: '2026-07-11T21:13:22Z'
			}
		];
		const results = [
			{
				runId: 'local-visual-run-001',
				botId: 'dsteele-spread-maker',
				versionId: 'v1',
				scoringPolicyVersion: 'score-v2',
				finalEquity: 104820,
				realizedPnl: 4820,
				maxDrawdown: 410,
				actionsProposed: 118,
				orderActionsProposed: 86,
				dataCalls: 42,
				signalsGenerated: 37,
				disqualified: false,
				scoreEligible: true,
				publicLeaderboard: true,
				createdAt: '2026-07-11T21:13:24Z'
			},
			{
				runId: 'local-visual-run-001',
				botId: 'latency-arb-fixture',
				versionId: 'v2',
				scoringPolicyVersion: 'score-v2',
				finalEquity: 98640,
				realizedPnl: -1360,
				maxDrawdown: 2250,
				actionsProposed: 480,
				orderActionsProposed: 420,
				dataCalls: 91,
				signalsGenerated: 66,
				disqualified: true,
				scoreEligible: false,
				publicLeaderboard: false,
				createdAt: '2026-07-11T21:13:24Z'
			}
		];

		if (method === 'GET' && url.pathname === '/admin/v1/arena/my/bots') {
			return {
				reefUserId: 'admin-cli',
				bots: bots.filter((bot) => bot.botId === 'dsteele-spread-maker')
			} as T;
		}
		if (method === 'GET' && url.pathname === '/admin/v1/arena/bots') return { bots } as T;
		if (method === 'GET' && url.pathname === '/admin/v1/arena/runs') return { runs } as T;
		if (method === 'GET' && url.pathname === '/admin/v1/arena/run-bot-results') return { results } as T;
		if (method === 'GET' && url.pathname === '/admin/v1/arena/run-enforcement-events') {
			return {
				events: [
					{
						runId: 'local-visual-run-001',
						botId: 'latency-arb-fixture',
						versionId: 'v2',
						decision: 'FREEZE',
						reasonCode: 'ORDER_RATE_LIMIT',
						reason: 'fixture freeze for local admin layout testing',
						policyVersion: 'arena-policy-v1',
						countersJson: '{"orders":420}',
						occurredAt: '2026-07-11T21:12:45Z'
					}
				]
			} as T;
		}
		if (method === 'GET' && url.pathname === '/admin/v1/risk/account-controls') {
			return {
				controls: [
					{
						scopeType: 'BOT',
						scopeId: 'latency-arb-fixture',
						decision: 'DISABLED_BOT',
						reason: 'local fixture risk control',
						maxQuantityUnits: '0',
						maxNotional: '0',
						currency: 'USD',
						updatedAt: now
					}
				]
			} as T;
		}
		if (method === 'GET' && url.pathname === '/admin/v1/arena/bots/config') {
			const botId = url.searchParams.get('botId') ?? 'dsteele-spread-maker';
			return {
				botId,
				ownerIdentity: 'admin-cli',
				secretPath: `secret/data/bots/admin-cli/${botId}`,
				hasConfig: botId === 'dsteele-spread-maker',
				keys: botId === 'dsteele-spread-maker' ? ['instrumentId', 'orderSize', 'spread'] : [],
				updatedAt: now,
				updatedBy: 'local-dev-admin',
				version: 1
			} as T;
		}
		if (method === 'GET' && url.pathname === '/admin/v1/arena/runtime-config-descriptors') {
			const botId = url.searchParams.get('botId') ?? 'dsteele-spread-maker';
			const versionId = url.searchParams.get('versionId') ?? 'v1';
			const descriptors =
				botId === 'dsteele-spread-maker'
					? [
							{
								botId,
								versionId,
								key: 'instrumentId',
								provider: 'OpenBao',
								secretPath: `secret/bots/admin-cli/${botId}`,
								required: true,
								description: 'Instrument symbol used for public market snapshots.'
							},
							{
								botId,
								versionId,
								key: 'orderSize',
								provider: 'OpenBao',
								secretPath: `secret/bots/admin-cli/${botId}`,
								required: true,
								description: 'Order quantity for each generated quote.'
							},
							{
								botId,
								versionId,
								key: 'spread',
								provider: 'OpenBao',
								secretPath: `secret/bots/admin-cli/${botId}`,
								required: true,
								description: 'Price spread around the current midpoint.'
							}
						]
					: [
							{
								botId,
								versionId,
								key: 'API_KEY',
								provider: 'OpenBao',
								secretPath: `secret/bots/reef-labs/${botId}`,
								required: true,
								description: 'External signal feed credential.'
							},
							{
								botId,
								versionId,
								key: 'MODEL_MODE',
								provider: 'OpenBao',
								secretPath: `secret/bots/reef-labs/${botId}`,
								required: false,
								description: 'Optional local strategy mode.'
							}
						];
			return { descriptors } as T;
		}
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

export async function fetchSession(): Promise<SessionUser | null> {
	try {
		const res = await fetch(`${ARENA_API_BASE_URL}/admin/auth/session`, {
			credentials: 'include'
		});
		if (!res.ok) {
			if (import.meta.env.DEV && localDevFakeAdminEnabled()) {
				return {
					reefUserId: 'admin-cli',
					githubLogin: 'local-dev-admin',
					displayName: 'Local Dev Admin',
					trustState: 'trusted',
					roles: ['arena-operator', 'participant', 'local-dev']
				};
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
			return {
				reefUserId: 'admin-cli',
				githubLogin: 'local-dev-admin',
				displayName: 'Local Dev Admin',
				trustState: 'trusted',
				roles: ['arena-operator', 'participant', 'local-dev']
			};
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

export async function fetchAdminRiskControls(): Promise<AccountRiskControl[]> {
	const body = await fetchAdminJson<{ controls: AccountRiskControl[] }>(
		'/admin/v1/risk/account-controls'
	);
	return body.controls ?? [];
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
			'local fixture mode does not persist bot config; disable PUBLIC_ARENA_LOCAL_DEV_FIXTURES to test writes'
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
			'local fixture mode does not persist bot config; disable PUBLIC_ARENA_LOCAL_DEV_FIXTURES to test writes'
		);
	}
	const params = new URLSearchParams({ botId });
	return await fetchAdminJson<BotConfigStatus>(`/admin/v1/arena/bots/config?${params}`, {
		method: 'DELETE'
	});
}
