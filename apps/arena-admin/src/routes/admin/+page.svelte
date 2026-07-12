<script lang="ts">
	import { PUBLIC_ARENA_API_BASE_URL } from '$env/static/public';
	import {
		fetchAdminBots,
		fetchAdminRunEnforcementEvents,
		fetchAdminRiskControls,
		fetchAdminRunResults,
		fetchAdminRuns,
		fetchBotConfigStatus,
		fetchBotRuntimeConfigDescriptors,
		replaceBotConfig,
		deleteBotConfig,
		type AccountRiskControl,
		type ArenaBot,
		type ArenaRun,
		type ArenaRunEnforcementEvent,
		type ArenaRunBotResult,
		type BotConfigStatus,
		type BotRuntimeConfigDescriptor
	} from '$lib/api';
	import Button from '$lib/components/ui/Button.svelte';
	import Card from '$lib/components/ui/Card.svelte';
	import { cn } from '$lib/utils';

	type AdminTab = 'bots' | 'runs';
	type BotState = 'enabled' | 'disabled' | 'frozen';

	const adminTabs: { id: AdminTab; label: string; countLabel: string }[] = [
		{ id: 'bots', label: 'bots', countLabel: 'total' },
		{ id: 'runs', label: 'runs', countLabel: 'recent' }
	];

	let activeAdminTab = $state<AdminTab>('bots');
	let bots = $state<ArenaBot[]>([]);
	let runs = $state<ArenaRun[]>([]);
	let riskControls = $state<AccountRiskControl[]>([]);
	let resultsByRunId = $state<Record<string, ArenaRunBotResult[]>>({});
	let enforcementByRunId = $state<Record<string, ArenaRunEnforcementEvent[]>>({});
	let configByBotId = $state<Record<string, BotConfigStatus>>({});
	let configDraftByBotId = $state<Record<string, string>>({});
	let configErrorByBotId = $state<Record<string, string>>({});
	let configBusyByBotId = $state<Record<string, boolean>>({});
	let configDescriptorsByKey = $state<Record<string, BotRuntimeConfigDescriptor[]>>({});
	let configDescriptorErrorByKey = $state<Record<string, string>>({});
	let configDescriptorBusyByKey = $state<Record<string, boolean>>({});
	let selectedConfigBotId = $state('');
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

	async function openBotConfig(botId: string) {
		selectedConfigBotId = botId;
		if (!configDraftByBotId[botId]) {
			configDraftByBotId = { ...configDraftByBotId, [botId]: '{\n}' };
		}
		await Promise.all([
			!configByBotId[botId] ? loadBotConfig(botId) : Promise.resolve(),
			loadConfigDescriptors(botId)
		]);
	}

	function closeBotConfig() {
		selectedConfigBotId = '';
	}

	function updateConfigDraft(botId: string, value: string) {
		configDraftByBotId = { ...configDraftByBotId, [botId]: value };
	}

	function handleKeydown(event: KeyboardEvent) {
		if (event.key === 'Escape' && selectedConfigBotId) {
			closeBotConfig();
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

	function latestBotVersionId(botId: string): string {
		for (const run of runs) {
			const botVersion = run.botVersions.find((version) => version.botId === botId);
			if (botVersion?.versionId) return botVersion.versionId;
		}
		return '';
	}

	function descriptorKey(botId: string, versionId: string): string {
		return `${botId}:${versionId}`;
	}

	async function loadConfigDescriptors(botId: string, force = false) {
		const versionId = latestBotVersionId(botId);
		if (!versionId) return;
		const key = descriptorKey(botId, versionId);
		if (!force && configDescriptorsByKey[key]) return;

		configDescriptorBusyByKey = { ...configDescriptorBusyByKey, [key]: true };
		configDescriptorErrorByKey = { ...configDescriptorErrorByKey, [key]: '' };
		try {
			const descriptors = await fetchBotRuntimeConfigDescriptors(botId, versionId);
			configDescriptorsByKey = { ...configDescriptorsByKey, [key]: descriptors };
		} catch (err) {
			configDescriptorErrorByKey = {
				...configDescriptorErrorByKey,
				[key]: err instanceof Error ? err.message : 'config field guidance failed'
			};
		} finally {
			configDescriptorBusyByKey = { ...configDescriptorBusyByKey, [key]: false };
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

	function tabClass(tab: AdminTab) {
		const active = activeAdminTab === tab;
		return cn(
			'inline-flex min-h-10 items-center gap-2 border-b-2 px-3 py-2 text-sm font-bold leading-tight transition-colors',
			active
				? 'border-accent text-ink'
				: 'border-transparent text-muted hover:border-rule-strong hover:text-ink'
		);
	}

	function tabCount(tab: AdminTab) {
		return tab === 'bots' ? bots.length : runs.length;
	}

	function handleTabKeydown(event: KeyboardEvent, tab: AdminTab) {
		const currentIndex = adminTabs.findIndex((item) => item.id === tab);
		let nextIndex = currentIndex;

		if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
			nextIndex = (currentIndex + 1) % adminTabs.length;
		} else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
			nextIndex = (currentIndex - 1 + adminTabs.length) % adminTabs.length;
		} else if (event.key === 'Home') {
			nextIndex = 0;
		} else if (event.key === 'End') {
			nextIndex = adminTabs.length - 1;
		} else {
			return;
		}

		event.preventDefault();
		activeAdminTab = adminTabs[nextIndex].id;
		requestAnimationFrame(() => document.getElementById(`admin-${activeAdminTab}-tab`)?.focus());
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

<svelte:window onkeydown={handleKeydown} />

<h1 class="mb-6 text-3xl font-normal tracking-[-0.03em] lowercase">admin</h1>

{#if loading}
	<p class="border-t border-rule py-6 text-center text-muted">loading…</p>
{:else if error}
	<div class="border-t border-destructive py-6">
		<p class="font-bold text-destructive">admin data unavailable</p>
		<p class="mt-1 text-sm text-muted">{error}</p>
	</div>
{:else}
	<div class="mb-6 border-b border-rule">
		<div class="flex flex-wrap gap-2" role="tablist" aria-label="admin views">
			{#each adminTabs as tab (tab.id)}
				<button
					id={`admin-${tab.id}-tab`}
					class={tabClass(tab.id)}
					type="button"
					role="tab"
					aria-selected={activeAdminTab === tab.id}
					aria-controls={`admin-${tab.id}-panel`}
					tabindex={activeAdminTab === tab.id ? 0 : -1}
					onclick={() => (activeAdminTab = tab.id)}
					onkeydown={(event) => handleTabKeydown(event, tab.id)}
				>
					<span>{tab.label}</span>
					<span class="text-xs font-normal text-muted">{tabCount(tab.id)} {tab.countLabel}</span>
				</button>
			{/each}
		</div>
	</div>

	<div
		id="admin-bots-panel"
		role="tabpanel"
		aria-labelledby="admin-bots-tab"
		hidden={activeAdminTab !== 'bots'}
	>
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
						<li class="py-5">
							<div class="grid gap-4 lg:grid-cols-[minmax(0,1fr)_180px]">
								<div class="min-w-0">
									<div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
										<h3 class="min-w-0 break-words text-lg font-bold leading-tight text-ink">
											{bot.metadata.name}
										</h3>
										<div class="flex shrink-0 flex-wrap gap-1.5">
										<span class={cn('rounded-full border px-2 py-0.5 text-xs leading-tight', stateClass(state))}
											>{state}</span
										>
										{#if configStatus?.hasConfig}
											<span class="rounded-full border border-accent-2 px-2 py-0.5 text-xs leading-tight text-ink"
												>config</span
											>
										{/if}
										</div>
									</div>

									<dl class="mt-3 grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
										<div class="min-w-0">
											<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">bot id</dt>
											<dd class="mt-1 break-all text-muted">{bot.botId}</dd>
										</div>
										<div class="min-w-0">
											<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">source</dt>
											<dd class="mt-1 truncate text-muted" title={bot.fileName}>{bot.fileName}</dd>
										</div>
										<div class="min-w-0">
											<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">owner</dt>
											<dd class="mt-1 truncate text-muted">{owner?.githubLogin ?? bot.metadata.publisher}</dd>
										</div>
										{#if bot.metadata.email}
											<div class="min-w-0">
												<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">contact</dt>
												<dd class="mt-1 truncate text-muted" title={bot.metadata.email}>{bot.metadata.email}</dd>
											</div>
										{/if}
									</dl>

									{#if control?.reason}
										<p class="mt-4 border-l border-destructive/70 pl-3 text-xs text-muted">{control.reason}</p>
									{/if}
								</div>
								<div class="flex flex-col gap-4 text-left text-xs text-muted lg:items-end lg:text-right">
									<dl class="grid gap-3">
										<div>
											<dt class="font-bold uppercase tracking-normal">trust</dt>
											<dd class="mt-1 text-ink">{owner?.trustState ?? 'unlinked'}</dd>
										</div>
										{#if owner?.ownershipState}
											<div>
												<dt class="font-bold uppercase tracking-normal">ownership</dt>
												<dd class="mt-1 text-ink">{owner.ownershipState}</dd>
											</div>
										{/if}
										<div>
											<dt class="font-bold uppercase tracking-normal">created</dt>
											<dd class="mt-1 text-ink">{new Date(bot.createdAt).toLocaleString()}</dd>
										</div>
									</dl>
									<Button
										variant="secondary"
										class="min-h-8 w-full px-2.5 py-1.5 text-xs lg:w-auto"
										onclick={() => openBotConfig(bot.botId)}
									>
										configure
									</Button>
								</div>
							</div>
						</li>
					{/each}
				</ul>
			{/if}
		</Card>
	</div>

	<div
		id="admin-runs-panel"
		role="tabpanel"
		aria-labelledby="admin-runs-tab"
		hidden={activeAdminTab !== 'runs'}
	>
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
						<li class="grid gap-4 py-5 lg:grid-cols-[minmax(0,1fr)_180px]">
							<div class="min-w-0">
								<div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
									<h3 class="min-w-0 break-all text-lg font-bold leading-tight text-ink">{run.runId}</h3>
									<span class="rounded-full border border-rule-strong px-2 py-0.5 text-xs leading-tight text-muted">
										{run.status}
									</span>
								</div>

								<dl class="mt-3 grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
									<div class="min-w-0">
										<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">mode</dt>
										<dd class="mt-1 truncate text-muted">{run.modeId}</dd>
									</div>
									<div class="min-w-0">
										<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">scenario</dt>
										<dd class="mt-1 truncate text-muted" title={run.scenarioId}>{run.scenarioId}</dd>
									</div>
									<div>
										<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">seed</dt>
										<dd class="mt-1 text-muted">{run.seed}</dd>
									</div>
									<div>
										<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">coverage</dt>
										<dd class="mt-1 text-muted">
											{run.botVersions.length} bots, {results.length} results
											{#if enforcementEvents.length}
												, {enforcementEvents.length} enforcement
											{/if}
										</dd>
									</div>
								</dl>

								{#if winner}
									<p class="mt-4 border-l border-accent pl-3 text-xs text-muted">
										leader <span class="text-ink">{winner.botId}</span> {signed(winner.realizedPnl)}
									</p>
								{/if}
							</div>
							<div class="flex flex-col gap-4 text-sm lg:items-end lg:text-right">
								<dl class="grid gap-3 text-xs text-muted">
									<div>
										<dt class="font-bold uppercase tracking-normal">created</dt>
										<dd class="mt-1 text-ink">{new Date(run.createdAt).toLocaleString()}</dd>
									</div>
								</dl>
								<div class="flex flex-wrap gap-3 lg:justify-end">
								<a class="text-ink underline decoration-rule underline-offset-4" href={runResultsHref(run.runId)}
									>results</a
								>
								{#if leaderboardHref}
									<a class="text-ink underline decoration-rule underline-offset-4" href={leaderboardHref}
										>leaderboard data</a
									>
								{/if}
								</div>
							</div>
						</li>
					{/each}
				</ul>
			{/if}
		</Card>
	</div>
{/if}

{#if selectedConfigBotId}
	{@const selectedBot = bots.find((bot) => bot.botId === selectedConfigBotId)}
	{#if selectedBot}
		{@const selectedOwner = selectedBot.owners?.[0]}
		{@const selectedState = botState(selectedBot.botId)}
		{@const selectedStatus = configByBotId[selectedBot.botId]}
		{@const selectedBusy = configBusyByBotId[selectedBot.botId]}
		{@const selectedError = configErrorByBotId[selectedBot.botId]}
		{@const selectedVersionId = latestBotVersionId(selectedBot.botId)}
		{@const selectedDescriptorKey = selectedVersionId ? descriptorKey(selectedBot.botId, selectedVersionId) : ''}
		{@const selectedDescriptors = selectedDescriptorKey ? (configDescriptorsByKey[selectedDescriptorKey] ?? []) : []}
		{@const selectedDescriptorBusy = selectedDescriptorKey ? configDescriptorBusyByKey[selectedDescriptorKey] : false}
		{@const selectedDescriptorError = selectedDescriptorKey ? configDescriptorErrorByKey[selectedDescriptorKey] : ''}
		<div class="fixed inset-0 z-50">
			<button
				class="absolute inset-0 h-full w-full cursor-default bg-black/70"
				type="button"
				aria-label="Close config editor"
				onclick={closeBotConfig}
			></button>
			<div class="pointer-events-none absolute inset-0 flex items-center justify-center p-3 sm:p-4">
				<div
					class="pointer-events-auto relative grid h-[min(760px,calc(100dvh-2rem))] w-full max-w-5xl overflow-hidden border border-rule-strong bg-bg shadow-2xl md:grid-cols-[320px_minmax(0,1fr)]"
					role="dialog"
					aria-modal="true"
					aria-labelledby="bot-config-title"
				>
				<button
					class="absolute right-4 top-4 z-10 inline-flex h-9 w-9 shrink-0 items-center justify-center border border-rule-strong bg-bg text-lg leading-none text-muted transition-colors hover:border-accent-hover hover:text-ink"
					type="button"
					aria-label="Close config editor"
					onclick={closeBotConfig}
				>
					×
				</button>
				<aside class="flex min-h-0 flex-col overflow-y-auto border-b border-rule bg-[#0d1422] p-4 md:border-b-0 md:border-r md:p-5">
					<div class="min-h-0 flex-1">
						<div class="pr-12">
							<div class="min-w-0">
								<p class="text-xs text-muted">bot config</p>
								<h2 id="bot-config-title" class="mt-2 break-words text-2xl font-bold text-ink">
									{selectedBot.metadata.name}
								</h2>
							</div>
						</div>

						<div class="mt-5 flex flex-wrap gap-2">
							<span class={cn('rounded-full border px-2 py-0.5 text-xs leading-tight', stateClass(selectedState))}
								>{selectedState}</span
							>
							<span class="rounded-full border border-rule-strong px-2 py-0.5 text-xs leading-tight text-muted">
								{selectedBusy && !selectedStatus ? 'loading' : selectedStatus?.hasConfig ? 'configured' : 'empty'}
							</span>
						</div>

						<dl class="mt-6 space-y-4 text-sm">
							<div>
								<dt class="text-xs text-muted">bot id</dt>
								<dd class="mt-1 break-all text-ink">{selectedBot.botId}</dd>
							</div>
							<div>
								<dt class="text-xs text-muted">source</dt>
								<dd class="mt-1 break-all text-ink">{selectedBot.fileName}</dd>
							</div>
							<div>
								<dt class="text-xs text-muted">owner</dt>
								<dd class="mt-1 text-ink">{selectedOwner?.githubLogin ?? selectedBot.metadata.publisher}</dd>
								{#if selectedBot.metadata.email}
									<dd class="mt-1 break-all text-muted">{selectedBot.metadata.email}</dd>
								{/if}
							</div>
							<div>
								<dt class="text-xs text-muted">secret path</dt>
								<dd class="mt-1 break-all text-ink">{selectedStatus?.secretPath ?? 'unavailable'}</dd>
							</div>
						</dl>

						{#if selectedStatus?.keys?.length}
							<div class="mt-6">
								<p class="text-xs text-muted">stored keys</p>
								<div class="mt-2 flex flex-wrap gap-1.5">
									{#each selectedStatus.keys as key}
										<span class="max-w-full truncate border border-rule px-2 py-0.5 text-xs text-muted">{key}</span>
									{/each}
								</div>
							</div>
						{/if}

						{#if selectedError}
							<div class="mt-6 border border-destructive/60 bg-destructive/10 p-3 text-sm text-destructive">
								{selectedError}
							</div>
						{/if}
					</div>

					<div class="mt-6 shrink-0">
						<Button
							variant="secondary"
							class="min-h-9 px-3 py-2 text-xs"
							disabled={selectedBusy}
							onclick={() => {
								loadBotConfig(selectedBot.botId);
								loadConfigDescriptors(selectedBot.botId, true);
							}}
						>
							refresh
						</Button>
					</div>
				</aside>

				<div class="flex min-h-0 flex-col overflow-hidden p-4 pt-14 md:p-5 md:pt-5">
					<div class="shrink-0 border-b border-rule pb-4">
						<div class="min-w-0 flex-1">
							<h3 class="text-xl font-normal text-ink">json</h3>
							<p class="mt-1 max-w-[58ch] text-sm text-muted">
								values are hidden after save; saving replaces the stored object.
							</p>
						</div>
					</div>

					<div class="min-h-0 flex-1 overflow-y-auto py-4 pr-1">
						<section class="border border-rule bg-[#090f1a] p-4" aria-labelledby="config-guidance-title">
							<div class="flex flex-col gap-1 sm:flex-row sm:items-baseline sm:justify-between">
								<h4 id="config-guidance-title" class="text-sm font-bold text-ink">expected fields</h4>
								<p class="text-xs text-muted">
									{#if selectedVersionId}
										version {selectedVersionId}
									{:else}
										no recent version context
									{/if}
								</p>
							</div>

							{#if selectedDescriptorBusy}
								<p class="mt-3 text-sm text-muted">loading field guidance…</p>
							{:else if selectedDescriptorError}
								<p class="mt-3 border-l border-destructive/70 pl-3 text-sm text-muted">
									field guidance unavailable: {selectedDescriptorError}
								</p>
							{:else if selectedDescriptors.length}
								<ul class="mt-3 divide-y divide-rule border-t border-rule">
									{#each selectedDescriptors as descriptor (descriptor.key)}
										<li class="grid gap-3 py-3 text-sm sm:grid-cols-[minmax(0,1fr)_120px]">
											<div class="min-w-0">
												<div class="flex flex-wrap items-center gap-2">
													<code class="break-all text-ink">{descriptor.key}</code>
													<span
														class={cn(
															'rounded-full border px-2 py-0.5 text-xs leading-tight',
															descriptor.required
																? 'border-accent text-ink'
																: 'border-rule-strong text-muted'
														)}
													>
														{descriptor.required ? 'required' : 'optional'}
													</span>
												</div>
												{#if descriptor.description}
													<p class="mt-1 text-xs text-muted">{descriptor.description}</p>
												{/if}
												<p class="mt-1 truncate text-xs text-muted" title={descriptor.secretPath}>
													{descriptor.secretPath}
												</p>
											</div>
											<div class="text-xs text-muted sm:text-right">
												<p class="font-bold uppercase tracking-normal">{descriptor.provider}</p>
												<p class="mt-1">{descriptor.valueType ?? 'json value'}</p>
											</div>
										</li>
									{/each}
								</ul>
							{:else if selectedVersionId}
								<p class="mt-3 text-sm text-muted">
									no runtime config descriptors are registered for this bot version.
								</p>
							{:else}
								<p class="mt-3 text-sm text-muted">
									open a bot from a recent run to show version-specific config guidance.
								</p>
							{/if}
						</section>

						<label class="mt-4 block text-xs font-bold uppercase tracking-normal text-muted" for="bot-config-json">
							config object
						</label>
						<textarea
							id="bot-config-json"
							class="mt-2 h-[clamp(180px,32dvh,280px)] max-h-[45dvh] min-h-[180px] w-full resize-y border border-rule bg-[#070b13] p-4 text-sm leading-relaxed text-ink outline-none focus:border-accent-hover"
							spellcheck="false"
							disabled={selectedBusy}
							aria-describedby="bot-config-json-help"
							value={configDraftByBotId[selectedBot.botId] ?? '{\n}'}
							oninput={(event) =>
								updateConfigDraft(selectedBot.botId, (event.currentTarget as HTMLTextAreaElement).value)}
						></textarea>
						<p id="bot-config-json-help" class="mt-2 text-xs text-muted">
							Only object-shaped JSON is accepted. Top-level keys are validated by the config service.
						</p>
					</div>

					<div class="shrink-0 border-t border-rule bg-bg pt-4">
						<div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
							<p class="text-xs text-muted">
								save replaces the whole object.
							</p>
							<div class="flex flex-wrap gap-2 sm:justify-end">
								<Button
									variant="secondary"
									class="min-h-9 px-3 py-2 text-xs"
									disabled={selectedBusy}
									onclick={closeBotConfig}
								>
									cancel
								</Button>
								<Button
									class="min-h-9 px-3 py-2 text-xs"
									disabled={selectedBusy}
									onclick={() => saveBotConfig(selectedBot.botId)}
								>
									{selectedBusy ? 'saving' : 'save'}
								</Button>
							</div>
						</div>
					</div>
				</div>
				</div>
			</div>
		</div>
	{/if}
{/if}
