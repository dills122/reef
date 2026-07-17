<script lang="ts">
	import {
		ARENA_API_BASE_URL,
		fetchAdminRunEnforcementEvents,
		fetchAdminRunResults,
		fetchAdminRuns,
		type ArenaRun,
		type ArenaRunBotResult,
		type ArenaRunEnforcementEvent
	} from '$lib/api';
	import ActionBar from '$lib/components/ui/ActionBar.svelte';
	import Badge from '$lib/components/ui/Badge.svelte';
	import Button from '$lib/components/ui/Button.svelte';
	import Card from '$lib/components/ui/Card.svelte';
	import PageHeader from '$lib/components/ui/PageHeader.svelte';
	import SegmentButton from '$lib/components/ui/SegmentButton.svelte';
	import StateMessage from '$lib/components/ui/StateMessage.svelte';
	import TextInput from '$lib/components/ui/TextInput.svelte';

	let runs = $state<ArenaRun[]>([]);
	let resultsByRunId = $state<Record<string, ArenaRunBotResult[]>>({});
	let enforcementByRunId = $state<Record<string, ArenaRunEnforcementEvent[]>>({});
	let loading = $state(true);
	let error = $state('');
	let runFilter = $state<'all' | 'attention' | 'completed' | 'open'>('all');
	let runSearch = $state('');

	let attentionRuns = $derived(
		runs.filter((run) => {
			const results = runResults(run.runId);
			return run.status !== 'COMPLETED' || results.length < run.botVersions.length || runEnforcementEvents(run.runId).length > 0;
		})
	);
	let filteredRuns = $derived(
		runs.filter((run) => {
			const query = runSearch.trim().toLowerCase();
			const matchesQuery =
				!query ||
				run.runId.toLowerCase().includes(query) ||
				run.modeId.toLowerCase().includes(query) ||
				run.scenarioId.toLowerCase().includes(query);
			if (!matchesQuery) return false;
			if (runFilter === 'completed') return run.status === 'COMPLETED';
			if (runFilter === 'open') return run.status !== 'COMPLETED';
			if (runFilter === 'attention') return attentionRuns.some((item) => item.runId === run.runId);
			return true;
		})
	);

	$effect(() => {
		loadAdminData();
	});

	async function loadAdminData() {
		loading = true;
		error = '';
		try {
			const runList = await fetchAdminRuns();
			runs = runList;

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
		return `${ARENA_API_BASE_URL}/admin/v1/arena/leaderboard?${params}`;
	}

	function runResultsHref(runId: string) {
		const params = new URLSearchParams({ runId });
		return `${ARENA_API_BASE_URL}/admin/v1/arena/run-bot-results?${params}`;
	}

	function runDetailHref(runId: string) {
		const params = new URLSearchParams({ runId });
		return `/admin/run?${params}`;
	}

	function signed(n: number) {
		return `${n > 0 ? '+' : ''}${n.toLocaleString()}`;
	}
</script>

<svelte:head>
	<title>Game Admin — Bot Arena</title>
</svelte:head>

<PageHeader title="game admin">
	{#snippet meta()}
		<p class="text-sm text-muted">{runs.length} recent runs</p>
	{/snippet}
	{#snippet actions()}
		<Button variant="secondary" href="/bot-admin">bot admin</Button>
	{/snippet}
</PageHeader>

{#if loading}
	<StateMessage variant="loading" message="loading…" />
{:else if error}
	<StateMessage variant="error" title="game admin unavailable" message={error} />
{:else}
	<Card>
		<div class="mb-4 flex items-baseline justify-between gap-4">
			<h2 class="text-xl font-normal lowercase">runs</h2>
			<p class="text-xs text-muted">{filteredRuns.length} shown / {runs.length} recent</p>
		</div>

		<div class="mb-4 grid gap-3 border-t border-rule pt-4 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-center">
			<TextInput
				type="search"
				placeholder="filter by run, mode, or scenario"
				aria-label="Filter runs"
				value={runSearch}
				oninput={(event) => (runSearch = event.currentTarget.value)}
			/>
			<div class="flex flex-wrap gap-2">
				<SegmentButton selected={runFilter === 'all'} onclick={() => (runFilter = 'all')}>all</SegmentButton>
				<SegmentButton selected={runFilter === 'attention'} onclick={() => (runFilter = 'attention')}>
					attention {attentionRuns.length}
				</SegmentButton>
				<SegmentButton selected={runFilter === 'completed'} onclick={() => (runFilter = 'completed')}>completed</SegmentButton>
				<SegmentButton selected={runFilter === 'open'} onclick={() => (runFilter = 'open')}>open</SegmentButton>
			</div>
		</div>

		{#if runs.length === 0}
			<StateMessage variant="empty" message="no arena runs recorded." />
		{:else if filteredRuns.length === 0}
			<StateMessage variant="empty" message="no runs match the current filters." />
		{:else}
			<ul class="divide-y divide-rule border-t border-rule">
				{#each filteredRuns as run (run.runId)}
					{@const results = runResults(run.runId)}
					{@const enforcementEvents = runEnforcementEvents(run.runId)}
					{@const winner = results.filter((result) => !result.disqualified).sort((a, b) => b.finalEquity - a.finalEquity)[0]}
					{@const leaderboardHref = runLeaderboardHref(run)}
					<li class="grid gap-4 py-5 lg:grid-cols-[minmax(0,1fr)_190px]">
						<div class="min-w-0">
							<div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
								<h3 class="min-w-0 break-all text-lg font-bold leading-tight text-ink">{run.runId}</h3>
								<div class="flex flex-wrap gap-1.5">
									<Badge variant={run.status === 'COMPLETED' ? 'success' : 'muted'}>{run.status}</Badge>
									{#if enforcementEvents.length}
										<Badge variant="danger">enforcement</Badge>
									{/if}
									{#if results.length < run.botVersions.length}
										<Badge variant="muted">partial results</Badge>
									{/if}
								</div>
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
						</div>
						<ActionBar label="run actions" class="lg:col-span-2">
							<Button size="sm" href={runDetailHref(run.runId)}>details</Button>
							<Button size="sm" variant="secondary" href={runResultsHref(run.runId)}>results json</Button>
							{#if leaderboardHref}
								<Button size="sm" variant="secondary" href={leaderboardHref}>leaderboard json</Button>
							{/if}
						</ActionBar>
					</li>
				{/each}
			</ul>
		{/if}
	</Card>
{/if}
