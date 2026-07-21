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
		const admissionWindow: ArenaAdmissionWindow = {
			windowId: 'weekly-2026-07-25',
			policyVersion: 'admission-v1',
			scheduledStart: '2026-07-25T00:00:00Z',
			inviteDecisionCutoff: '2026-07-22T00:00:00Z',
			mergeReadinessCutoff: '2026-07-23T00:00:00Z',
			rosterLockAt: '2026-07-24T00:00:00Z',
			operationalRecheckAt: '2026-07-24T22:00:00Z',
			runInstantiationAt: '2026-07-24T23:30:00Z',
			displayTimeZone: 'America/Toronto',
			createdAt: '2026-07-20T00:00:00Z'
		};
		const eligibilityDecisions: ArenaEligibilityDecision[] = [
			{
				evaluationId: 'eval-maker-v1',
				windowId: admissionWindow.windowId,
				botId: 'dsteele-spread-maker',
				versionId: 'v1',
				outcome: 'eligible_for_roster',
				reasonCodes: [],
				sourceHash: 'sha256:maker-source',
				artifactHash: 'sha256:maker-artifact',
				configHash: 'sha256:maker-config',
				evaluatedAt: '2026-07-23T20:00:00Z',
				correlationId: 'fixture-admission'
			},
			{
				evaluationId: 'eval-latency-v2',
				windowId: admissionWindow.windowId,
				botId: 'latency-arb-fixture',
				versionId: 'v2',
				outcome: 'rolled_to_next_window',
				reasonCodes: ['config_not_ready'],
				sourceHash: 'sha256:latency-source',
				artifactHash: 'sha256:latency-artifact',
				configHash: '',
				evaluatedAt: '2026-07-23T20:00:00Z',
				correlationId: 'fixture-admission'
			}
		];
		const roster: ArenaRoster = {
			snapshotId: 'roster-weekly-2026-07-25',
			windowId: admissionWindow.windowId,
			snapshotHash: 'sha256:fixture-roster',
			maxBots: 8,
			lockedAt: admissionWindow.rosterLockAt,
			lockedBy: 'local-dev-admin',
			correlationId: 'fixture-admission',
			policy: {
				modeId: 'hosted-sim',
				scenarioId: 'opening-auction-smoke',
				actorProfileVersion: 'actors-v1',
				riskPolicyVersion: 'risk-v1',
				scoringPolicyVersion: 'score-v2',
				economicPolicyVersion: 'preview-zero-fee-v1'
			},
			entries: [
				{
					botOrder: 0,
					botId: 'dsteele-spread-maker',
					versionId: 'v1',
					priority: 100,
					sourceHash: 'sha256:maker-source',
					artifactHash: 'sha256:maker-artifact',
					configHash: 'sha256:maker-config',
					eligibilityEvaluationId: 'eval-maker-v1'
				}
			],
			effectiveEntries: [],
			removals: [
				{
					removalId: 'removal-fixture-1',
					windowId: admissionWindow.windowId,
					snapshotId: 'roster-weekly-2026-07-25',
					botId: 'dsteele-spread-maker',
					versionId: 'v1',
					reasonCode: 'availability',
					detail: 'fixture runner unavailable',
					removedAt: '2026-07-24T01:00:00Z',
					removedBy: 'local-dev-admin',
					correlationId: 'fixture-admission'
				}
			]
		};
		const accessRoles: AdminAccessRole[] = [
			{
				roleId: 'participant',
				description: 'Can own accepted bots and manage own bot config',
				createdAt: '2026-07-10T15:00:00Z'
			},
			{
				roleId: 'reviewer',
				description: 'Can review bot submissions',
				createdAt: '2026-07-10T15:00:00Z'
			},
			{
				roleId: 'operator',
				description: 'Can operate arena runs and game settings',
				createdAt: '2026-07-10T15:00:00Z'
			},
			{
				roleId: 'secret-admin',
				description: 'Can perform explicit secret repair and rotation actions',
				createdAt: '2026-07-10T15:00:00Z'
			},
			{
				roleId: 'platform-admin',
				description: 'Can administer the Reef control plane',
				createdAt: '2026-07-10T15:00:00Z'
			}
		];
		const accessUsers: AdminAccessUser[] = [
			{
				reefUserId: 'user-gh-15662762',
				githubUserId: 15662762,
				githubLogin: 'dills122',
				displayName: 'Dill Steele',
				trustState: 'trusted',
				roles: [
					{
						reefUserId: 'user-gh-15662762',
						roleId: 'participant',
						assignedBy: 'github-oauth',
						assignedAt: '2026-07-10T15:00:00Z'
					},
					{
						reefUserId: 'user-gh-15662762',
						roleId: 'operator',
						assignedBy: 'bootstrap',
						assignedAt: '2026-07-10T15:02:00Z'
					}
				],
				botOwnerships: [
					{
						reefUserId: 'user-gh-15662762',
						botId: 'dsteele-spread-maker',
						ownershipState: 'owner',
						assignedBy: 'admin-cli',
						assignedAt: '2026-07-10T15:44:01Z'
					}
				],
				createdAt: '2026-07-10T15:00:00Z',
				lastSeenAt: now,
				updatedAt: now
			},
			{
				reefUserId: 'user-gh-424242',
				githubUserId: 424242,
				githubLogin: 'reef-reviewer',
				displayName: 'Reef Reviewer',
				trustState: 'limited',
				roles: [
					{
						reefUserId: 'user-gh-424242',
						roleId: 'participant',
						assignedBy: 'github-oauth',
						assignedAt: '2026-07-11T16:00:00Z'
					},
					{
						reefUserId: 'user-gh-424242',
						roleId: 'reviewer',
						assignedBy: 'user-gh-15662762',
						assignedAt: '2026-07-11T16:15:00Z'
					}
				],
				botOwnerships: [],
				createdAt: '2026-07-11T16:00:00Z',
				lastSeenAt: '2026-07-11T18:03:00Z',
				updatedAt: '2026-07-11T18:04:00Z'
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
		if (method === 'GET' && url.pathname === '/admin/v1/access/users') return { users: accessUsers } as T;
		if (method === 'GET' && url.pathname === '/admin/v1/access/roles') return { roles: accessRoles } as T;
		if (method === 'POST' && url.pathname === '/admin/v1/access/users/trust-state') {
			const payload = JSON.parse(String(init.body ?? '{}')) as Partial<AdminAccessUser>;
			return {
				status: 'ok',
				user: {
					...accessUsers[0],
					reefUserId: payload.reefUserId ?? accessUsers[0].reefUserId,
					trustState: payload.trustState ?? 'trusted'
				}
			} as T;
		}
		if (method === 'POST' && url.pathname === '/admin/v1/access/users/roles') {
			const payload = JSON.parse(String(init.body ?? '{}')) as Partial<AdminAccessUserRole>;
			return {
				status: 'ok',
				role: {
					reefUserId: payload.reefUserId ?? accessUsers[0].reefUserId,
					roleId: payload.roleId ?? 'reviewer',
					assignedBy: 'user-gh-15662762',
					assignedAt: now
				}
			} as T;
		}
		if (method === 'POST' && url.pathname === '/admin/v1/access/users/roles/revoke') {
			const payload = JSON.parse(String(init.body ?? '{}')) as Partial<AdminAccessUserRole>;
			return {
				status: 'ok',
				reefUserId: payload.reefUserId ?? accessUsers[0].reefUserId,
				roleId: payload.roleId ?? 'reviewer'
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
		if (method === 'GET' && url.pathname === '/admin/v1/arena/admission-windows') {
			return { status: 'ok', window: admissionWindow } as T;
		}
		if (method === 'GET' && url.pathname === '/admin/v1/arena/eligibility-decisions') {
			return { status: 'ok', decisions: eligibilityDecisions } as T;
		}
		if (method === 'POST' && url.pathname === '/admin/v1/arena/roster-previews') {
			const payload = JSON.parse(String(init.body ?? '{}')) as {
				maxBots?: number;
				candidates?: { evaluationId: string; priority: number }[];
			};
			const priorities = new Map((payload.candidates ?? []).map((item) => [item.evaluationId, item.priority]));
			const ranked = eligibilityDecisions
				.filter((decision) => decision.outcome === 'eligible_for_roster' && priorities.has(decision.evaluationId))
				.map((decision) => ({ decision, priority: priorities.get(decision.evaluationId) ?? 0 }))
				.sort((a, b) => b.priority - a.priority || a.decision.botId.localeCompare(b.decision.botId));
			const maxBots = payload.maxBots ?? 8;
			return {
				status: 'ok',
				preview: {
					windowId: admissionWindow.windowId,
					maxBots,
					included: ranked.slice(0, maxBots),
					capacityOverflow: ranked.slice(maxBots),
					awaitingPriority: eligibilityDecisions.filter(
						(decision) => decision.outcome === 'eligible_for_roster' && !priorities.has(decision.evaluationId)
					),
					rolled: eligibilityDecisions.filter((decision) => decision.outcome === 'rolled_to_next_window'),
					excluded: eligibilityDecisions.filter((decision) => decision.outcome === 'excluded')
				}
			} as T;
		}
		if (method === 'GET' && url.pathname === '/admin/v1/arena/rosters') {
			return { status: 'ok', roster } as T;
		}
		if (method === 'POST' && url.pathname === '/admin/v1/arena/roster-removals') {
			const payload = JSON.parse(String(init.body ?? '{}')) as Partial<ArenaRosterRemoval>;
			return {
				status: 'ok',
				removal: {
					...roster.removals[0],
					...payload,
					removedAt: now,
					removedBy: 'local-dev-admin'
				}
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
				secretPath: `secret/bots/admin-cli/${botId}`,
				hasConfig: botId === 'dsteele-spread-maker',
				config:
					botId === 'dsteele-spread-maker'
						? { instrumentId: 'AAPL', orderSize: 100, spread: 0.02 }
						: null,
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
					roles: ['operator', 'participant']
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
				roles: ['operator', 'participant']
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
