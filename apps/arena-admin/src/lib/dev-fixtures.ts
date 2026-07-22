import type {
	AdminAccessRole,
	AdminAccessUser,
	AdminAccessUserRole,
	ArenaAdmissionWindow,
	ArenaEligibilityDecision,
	ArenaRoster,
	ArenaRosterRemoval
} from './api';

// Local-dev-only fixture data, used when PUBLIC_ARENA_LOCAL_DEV_FIXTURES is
// enabled (see localDevFixturesEnabled in api.ts). Never reachable in a
// production build — assert-production-build-no-local-fixtures.mjs enforces
// that these markers do not leak into the built output.
export function resolveFixture(method: string, url: URL, init: RequestInit): unknown | undefined {
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
		};
	}
	if (method === 'GET' && url.pathname === '/admin/v1/access/users') return { users: accessUsers };
	if (method === 'GET' && url.pathname === '/admin/v1/access/roles') return { roles: accessRoles };
	if (method === 'POST' && url.pathname === '/admin/v1/access/users/trust-state') {
		const payload = JSON.parse(String(init.body ?? '{}')) as Partial<AdminAccessUser>;
		return {
			status: 'ok',
			user: {
				...accessUsers[0],
				reefUserId: payload.reefUserId ?? accessUsers[0].reefUserId,
				trustState: payload.trustState ?? 'trusted'
			}
		};
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
		};
	}
	if (method === 'POST' && url.pathname === '/admin/v1/access/users/roles/revoke') {
		const payload = JSON.parse(String(init.body ?? '{}')) as Partial<AdminAccessUserRole>;
		return {
			status: 'ok',
			reefUserId: payload.reefUserId ?? accessUsers[0].reefUserId,
			roleId: payload.roleId ?? 'reviewer'
		};
	}
	if (method === 'GET' && url.pathname === '/admin/v1/arena/bots') return { bots };
	if (method === 'GET' && url.pathname === '/admin/v1/arena/runs') return { runs };
	if (method === 'GET' && url.pathname === '/admin/v1/arena/run-bot-results') return { results };
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
		};
	}
	if (method === 'GET' && url.pathname === '/admin/v1/arena/admission-windows') {
		return { status: 'ok', window: admissionWindow };
	}
	if (method === 'GET' && url.pathname === '/admin/v1/arena/eligibility-decisions') {
		return { status: 'ok', decisions: eligibilityDecisions };
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
		};
	}
	if (method === 'GET' && url.pathname === '/admin/v1/arena/rosters') {
		return { status: 'ok', roster };
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
		};
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
		};
	}
	if (method === 'GET' && url.pathname === '/admin/v1/arena/bots/config') {
		const botId = url.searchParams.get('botId') ?? 'dsteele-spread-maker';
		return {
			botId,
			ownerIdentity: 'admin-cli',
			secretPath: `secret/bots/admin-cli/${botId}`,
			hasConfig: botId === 'dsteele-spread-maker',
			config: botId === 'dsteele-spread-maker' ? { instrumentId: 'AAPL', orderSize: 100, spread: 0.02 } : null,
			keys: botId === 'dsteele-spread-maker' ? ['instrumentId', 'orderSize', 'spread'] : [],
			updatedAt: now,
			updatedBy: 'local-dev-admin',
			version: 1
		};
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
		return { descriptors };
	}

	return undefined;
}
