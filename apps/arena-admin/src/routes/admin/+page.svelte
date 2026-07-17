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
	import Button from '$lib/components/ui/Button.svelte';
	import Card from '$lib/components/ui/Card.svelte';

	let runs = $state<ArenaRun[]>([]);
	let resultsByRunId = $state<Record<string, ArenaRunBotResult[]>>({});
	let enforcementByRunId = $state<Record<string, ArenaRunEnforcementEvent[]>>({});
	let loading = $state(true);
	let error = $state('');

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

	function signed(n: number) {
		return `${n > 0 ? '+' : ''}${n.toLocaleString()}`;
	}
</script>

<svelte:head>
	<title>Game Admin — Bot Arena</title>
</svelte:head>

<div class="mb-6 flex flex-col gap-3 border-b border-rule pb-5 sm:flex-row sm:items-end sm:justify-between">
	<div>
		<h1 class="text-3xl font-normal tracking-[-0.03em] lowercase">game admin</h1>
		<p class="mt-2 text-sm text-muted">{runs.length} recent runs</p>
	</div>
	<Button variant="secondary" href="/bot-admin">bot admin</Button>
</div>

{#if loading}
	<p class="border-t border-rule py-6 text-center text-muted">loading…</p>
{:else if error}
	<div class="border-t border-destructive py-6">
		<p class="font-bold text-destructive">game admin unavailable</p>
		<p class="mt-1 text-sm text-muted">{error}</p>
	</div>
{:else}
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
								<a class="text-ink underline decoration-rule underline-offset-4" href={runResultsHref(run.runId)}>
									results
								</a>
								{#if leaderboardHref}
									<a class="text-ink underline decoration-rule underline-offset-4" href={leaderboardHref}>
										leaderboard data
									</a>
								{/if}
							</div>
						</div>
					</li>
				{/each}
			</ul>
		{/if}
	</Card>
{/if}
