<script lang="ts">
	import { PUBLIC_ARENA_API_BASE_URL } from '$env/static/public';
	import {
		fetchAdminBots,
		fetchAdminRunEnforcementEvents,
		fetchAdminRiskControls,
		fetchAdminRunResults,
		fetchAdminRuns,
		fetchBotConfigStatus,
		replaceBotConfig,
		deleteBotConfig,
		type AccountRiskControl,
		type ArenaBot,
		type ArenaRun,
		type ArenaRunEnforcementEvent,
		type ArenaRunBotResult,
		type BotConfigStatus
	} from '$lib/api';
	import Button from '$lib/components/ui/Button.svelte';
	import Card from '$lib/components/ui/Card.svelte';
	import { cn } from '$lib/utils';

	type BotState = 'enabled' | 'disabled' | 'frozen';

	let bots = $state<ArenaBot[]>([]);
	let runs = $state<ArenaRun[]>([]);
	let riskControls = $state<AccountRiskControl[]>([]);
	let resultsByRunId = $state<Record<string, ArenaRunBotResult[]>>({});
	let enforcementByRunId = $state<Record<string, ArenaRunEnforcementEvent[]>>({});
	let configByBotId = $state<Record<string, BotConfigStatus>>({});
	let configDraftByBotId = $state<Record<string, string>>({});
	let configErrorByBotId = $state<Record<string, string>>({});
	let configBusyByBotId = $state<Record<string, boolean>>({});
	let expandedConfigBotId = $state('');
	let loading = $state(true);
	let error = $state('');
	let riskError = $state('');

	$effect(() => {
		loadAdminData();
	});

	async function loadAdminData() {
		loading = true;
		error = '';
		riskError = '';
		try {
			const [botList, runList, riskResult] = await Promise.all([
				fetchAdminBots(),
				fetchAdminRuns(),
				fetchAdminRiskControls().then(
					(controls) => ({ ok: true as const, controls }),
					(err: Error) => ({ ok: false as const, message: err.message })
				)
			]);

			bots = botList;
			runs = runList;
			if (riskResult.ok) {
				riskControls = riskResult.controls;
			} else {
				riskControls = [];
				riskError = riskResult.message;
			}

			const resultPairs = await Promise.all(
				runList.slice(0, 10).map(async (run) => [run.runId, await fetchAdminRunResults(run.runId)] as const)
			);
			const enforcementPairs = await Promise.all(
				runList
					.slice(0, 10)
					.map(async (run) => [run.runId, await fetchAdminRunEnforcementEvents(run.runId)] as const)
			);
			resultsByRunId = Object.fromEntries(resultPairs);
			enforcementByRunId = Object.fromEntries(enforcementPairs);
		} catch (err) {
			error = err instanceof Error ? err.message : 'admin data load failed';
		} finally {
			loading = false;
		}
	}

	async function toggleBotConfig(botId: string) {
		if (expandedConfigBotId === botId) {
			expandedConfigBotId = '';
			return;
		}
		expandedConfigBotId = botId;
		if (!configDraftByBotId[botId]) {
			configDraftByBotId = { ...configDraftByBotId, [botId]: '{\n}' };
		}
		if (!configByBotId[botId]) {
			await loadBotConfig(botId);
		}
	}

	async function loadBotConfig(botId: string) {
		configBusyByBotId = { ...configBusyByBotId, [botId]: true };
		configErrorByBotId = { ...configErrorByBotId, [botId]: '' };
		try {
			const status = await fetchBotConfigStatus(botId);
			configByBotId = { ...configByBotId, [botId]: status };
		} catch (err) {
			configErrorByBotId = {
				...configErrorByBotId,
				[botId]: err instanceof Error ? err.message : 'bot config status failed'
			};
		} finally {
			configBusyByBotId = { ...configBusyByBotId, [botId]: false };
		}
	}

	async function saveBotConfig(botId: string) {
		configBusyByBotId = { ...configBusyByBotId, [botId]: true };
		configErrorByBotId = { ...configErrorByBotId, [botId]: '' };
		try {
			const parsed = JSON.parse(configDraftByBotId[botId] || '{}');
			if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
				throw new Error('config must be a JSON object');
			}
			const status = await replaceBotConfig(botId, parsed);
			configByBotId = { ...configByBotId, [botId]: status };
			configDraftByBotId = { ...configDraftByBotId, [botId]: JSON.stringify(parsed, null, 2) };
		} catch (err) {
			configErrorByBotId = {
				...configErrorByBotId,
				[botId]: err instanceof Error ? err.message : 'bot config save failed'
			};
		} finally {
			configBusyByBotId = { ...configBusyByBotId, [botId]: false };
		}
	}

	async function clearBotConfig(botId: string) {
		if (!confirm(`Clear OpenBao config for ${botId}?`)) return;
		configBusyByBotId = { ...configBusyByBotId, [botId]: true };
		configErrorByBotId = { ...configErrorByBotId, [botId]: '' };
		try {
			const status = await deleteBotConfig(botId);
			configByBotId = { ...configByBotId, [botId]: status };
			configDraftByBotId = { ...configDraftByBotId, [botId]: '{\n}' };
		} catch (err) {
			configErrorByBotId = {
				...configErrorByBotId,
				[botId]: err instanceof Error ? err.message : 'bot config clear failed'
			};
		} finally {
			configBusyByBotId = { ...configBusyByBotId, [botId]: false };
		}
	}

	function botRiskControl(botId: string) {
		return riskControls.find((control) => control.scopeType === 'BOT' && control.scopeId === botId);
	}

	function botState(botId: string): BotState {
		const control = botRiskControl(botId);
		if (control?.decision === 'DISABLED_BOT') return 'disabled';
		const frozenByEvent = Object.values(enforcementByRunId)
			.flat()
			.some((event) => event.botId === botId && event.decision.toLowerCase() === 'freeze');
		const frozenByResult = Object.values(resultsByRunId)
			.flat()
			.some((result) => result.botId === botId && result.disqualified);
		return frozenByEvent || frozenByResult ? 'frozen' : 'enabled';
	}

	function stateClass(state: BotState) {
		return cn(
			state === 'enabled' && 'border-accent text-ink',
			state === 'disabled' && 'border-destructive text-destructive',
			state === 'frozen' && 'border-rule-strong text-muted'
		);
	}

	function runResults(runId: string) {
		return resultsByRunId[runId] ?? [];
	}

	function runEnforcementEvents(runId: string) {
		return enforcementByRunId[runId] ?? [];
	}

	function runLeaderboardHref(run: ArenaRun) {
		const policy = runResults(run.runId)[0]?.scoringPolicyVersion;
		if (!policy) return '';
		const params = new URLSearchParams({ modeId: run.modeId, scoringPolicyVersion: policy });
		return `${PUBLIC_ARENA_API_BASE_URL}/admin/v1/arena/leaderboard?${params}`;
	}

	function runResultsHref(runId: string) {
		const params = new URLSearchParams({ runId });
		return `${PUBLIC_ARENA_API_BASE_URL}/admin/v1/arena/run-bot-results?${params}`;
	}

	function signed(n: number) {
		return `${n > 0 ? '+' : ''}${n.toLocaleString()}`;
	}
</script>

<svelte:head>
	<title>Admin — Bot Arena</title>
</svelte:head>

<h1 class="mb-6 text-3xl font-normal tracking-[-0.03em] lowercase">admin</h1>

{#if loading}
	<p class="border-t border-rule py-6 text-center text-muted">loading…</p>
{:else if error}
	<div class="border-t border-destructive py-6">
		<p class="font-bold text-destructive">admin data unavailable</p>
		<p class="mt-1 text-sm text-muted">{error}</p>
	</div>
{:else}
	<div class="grid gap-8 xl:grid-cols-[1fr_1.2fr]">
		<Card>
			<div class="mb-4 flex items-baseline justify-between gap-4">
				<h2 class="text-xl font-normal lowercase">bots</h2>
				<p class="text-xs text-muted">{bots.length} total</p>
			</div>

			{#if riskError}
				<p class="mb-4 border-l border-rule pl-3 text-xs text-muted">risk controls unavailable: {riskError}</p>
			{/if}

			{#if bots.length === 0}
				<p class="border-t border-rule py-5 text-center text-muted">no bots registered.</p>
			{:else}
				<ul class="divide-y divide-rule border-t border-rule">
					{#each bots as bot (bot.botId)}
						{@const state = botState(bot.botId)}
						{@const control = botRiskControl(bot.botId)}
						{@const owner = bot.owners?.[0]}
						{@const configStatus = configByBotId[bot.botId]}
						{@const configBusy = configBusyByBotId[bot.botId]}
						<li class="py-4">
							<div class="grid gap-3 md:grid-cols-[1fr_auto]">
								<div class="min-w-0">
									<div class="flex flex-wrap items-center gap-2">
										<p class="truncate font-bold text-ink">{bot.metadata.name}</p>
										<span class={cn('rounded-full border px-2 py-0.5 text-xs leading-tight', stateClass(state))}
											>{state}</span
										>
										{#if configStatus?.hasConfig}
											<span class="rounded-full border border-accent-2 px-2 py-0.5 text-xs leading-tight text-ink"
												>config</span
											>
										{/if}
									</div>
									<p class="mt-1 truncate text-sm text-muted">{bot.botId} · {bot.fileName}</p>
									<p class="mt-1 text-sm text-muted">
										owner {owner?.githubLogin ?? bot.metadata.publisher}
										{#if bot.metadata.email}
											· {bot.metadata.email}
										{/if}
									</p>
									{#if control?.reason}
										<p class="mt-1 text-xs text-muted">{control.reason}</p>
									{/if}
								</div>
								<div class="flex flex-col items-start gap-2 text-left text-xs text-muted md:items-end md:text-right">
									<p>
										trust {owner?.trustState ?? 'unlinked'}
										{#if owner?.ownershipState}
											· {owner.ownershipState}
										{/if}
									</p>
									<p>created {new Date(bot.createdAt).toLocaleString()}</p>
									<Button
										variant="secondary"
										class="min-h-8 px-2.5 py-1.5 text-xs"
										onclick={() => toggleBotConfig(bot.botId)}
									>
										config
									</Button>
								</div>
							</div>

							{#if expandedConfigBotId === bot.botId}
								<div class="mt-4 border-t border-rule pt-4">
									<div class="grid gap-3 lg:grid-cols-[1fr_1.2fr]">
										<div class="min-w-0 text-sm">
											<p class="truncate text-ink">{configStatus?.secretPath ?? 'secret path unavailable'}</p>
											<p class="mt-1 text-xs text-muted">
												{configStatus?.hasConfig ? 'configured' : 'empty'} · values hidden · save replaces blob
											</p>
											{#if configStatus?.keys?.length}
												<div class="mt-3 flex flex-wrap gap-1.5">
													{#each configStatus.keys as key}
														<span class="max-w-full truncate border border-rule px-2 py-0.5 text-xs text-muted">{key}</span>
													{/each}
												</div>
											{/if}
											{#if configErrorByBotId[bot.botId]}
												<p class="mt-3 text-xs text-destructive">{configErrorByBotId[bot.botId]}</p>
											{/if}
										</div>
										<div class="min-w-0">
											<textarea
												class="min-h-40 w-full resize-y border border-rule bg-bg p-3 text-sm leading-relaxed text-ink outline-none focus:border-accent-hover"
												spellcheck="false"
												disabled={configBusy}
												bind:value={configDraftByBotId[bot.botId]}
											></textarea>
											<div class="mt-3 flex flex-wrap justify-end gap-2">
												<Button
													variant="secondary"
													class="min-h-8 px-2.5 py-1.5 text-xs"
													disabled={configBusy}
													onclick={() => loadBotConfig(bot.botId)}
												>
													refresh
												</Button>
												<Button
													variant="secondary"
													class="min-h-8 border-destructive px-2.5 py-1.5 text-xs text-destructive"
													disabled={configBusy}
													onclick={() => clearBotConfig(bot.botId)}
												>
													clear
												</Button>
												<Button
													class="min-h-8 px-2.5 py-1.5 text-xs"
													disabled={configBusy}
													onclick={() => saveBotConfig(bot.botId)}
												>
													save
												</Button>
											</div>
										</div>
									</div>
								</div>
							{/if}
						</li>
					{/each}
				</ul>
			{/if}
		</Card>

		<Card>
			<div class="mb-4 flex items-baseline justify-between gap-4">
				<h2 class="text-xl font-normal lowercase">runs</h2>
				<p class="text-xs text-muted">{runs.length} recent</p>
			</div>

			{#if runs.length === 0}
				<p class="border-t border-rule py-5 text-center text-muted">no arena runs recorded.</p>
			{:else}
				<ul class="divide-y divide-rule border-t border-rule">
					{#each runs as run (run.runId)}
						{@const results = runResults(run.runId)}
						{@const enforcementEvents = runEnforcementEvents(run.runId)}
						{@const winner = results.filter((result) => !result.disqualified).sort((a, b) => b.finalEquity - a.finalEquity)[0]}
						{@const leaderboardHref = runLeaderboardHref(run)}
						<li class="grid gap-3 py-4 lg:grid-cols-[1fr_auto]">
							<div class="min-w-0">
								<div class="flex flex-wrap items-center gap-2">
									<p class="truncate font-bold text-ink">{run.runId}</p>
									<span class="rounded-full border border-rule-strong px-2 py-0.5 text-xs leading-tight text-muted">
										{run.status}
									</span>
								</div>
								<p class="mt-1 text-sm text-muted">
									{run.modeId} · {run.scenarioId} · seed {run.seed}
								</p>
								<p class="mt-1 text-sm text-muted">
									{run.botVersions.length} bots · {results.length} results
									{#if enforcementEvents.length}
										· {enforcementEvents.length} enforcement
									{/if}
									{#if winner}
										· leader {winner.botId} {signed(winner.realizedPnl)}
									{/if}
								</p>
							</div>
							<div class="flex flex-wrap items-start gap-3 text-sm lg:justify-end">
								<a class="text-ink underline decoration-rule underline-offset-4" href={runResultsHref(run.runId)}
									>results</a
								>
								{#if leaderboardHref}
									<a class="text-ink underline decoration-rule underline-offset-4" href={leaderboardHref}
										>leaderboard data</a
									>
								{/if}
								<p class="basis-full text-xs text-muted lg:text-right">
									created {new Date(run.createdAt).toLocaleString()}
								</p>
							</div>
						</li>
					{/each}
				</ul>
			{/if}
		</Card>
	</div>
{/if}
