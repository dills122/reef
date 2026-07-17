<script lang="ts">
	import { page } from '$app/state';
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
	import StateMessage from '$lib/components/ui/StateMessage.svelte';
	import { cn } from '$lib/utils';

	let run = $state<ArenaRun | null>(null);
	let results = $state<ArenaRunBotResult[]>([]);
	let enforcementEvents = $state<ArenaRunEnforcementEvent[]>([]);
	let loading = $state(true);
	let error = $state('');
	let lastLoadedRunId = $state('');

	let runId = $derived(page.url.searchParams.get('runId')?.trim() ?? '');
	let winner = $derived(
		results.filter((result) => !result.disqualified).sort((a, b) => b.finalEquity - a.finalEquity)[0]
	);
	let leaderboardHref = $derived(run && results[0]?.scoringPolicyVersion ? runLeaderboardHref(run, results[0]) : '');

	$effect(() => {
		if (runId === lastLoadedRunId) return;
		lastLoadedRunId = runId;
		loadRun(runId);
	});

	async function loadRun(nextRunId: string) {
		run = null;
		results = [];
		enforcementEvents = [];
		error = '';

		if (!nextRunId) {
			loading = false;
			return;
		}

		loading = true;
		try {
			const [runList, nextResults, nextEnforcementEvents] = await Promise.all([
				fetchAdminRuns(100),
				fetchAdminRunResults(nextRunId),
				fetchAdminRunEnforcementEvents(nextRunId)
			]);
			run = runList.find((item) => item.runId === nextRunId) ?? null;
			results = nextResults;
			enforcementEvents = nextEnforcementEvents;
		} catch (err) {
			error = err instanceof Error ? err.message : 'run detail load failed';
		} finally {
			loading = false;
		}
	}

	function runLeaderboardHref(selectedRun: ArenaRun, selectedResult: ArenaRunBotResult) {
		const params = new URLSearchParams({
			modeId: selectedRun.modeId,
			scoringPolicyVersion: selectedResult.scoringPolicyVersion
		});
		return `${ARENA_API_BASE_URL}/admin/v1/arena/leaderboard?${params}`;
	}

	function runResultsHref(selectedRunId: string) {
		const params = new URLSearchParams({ runId: selectedRunId });
		return `${ARENA_API_BASE_URL}/admin/v1/arena/run-bot-results?${params}`;
	}

	function enforcementHref(selectedRunId: string) {
		const params = new URLSearchParams({ runId: selectedRunId });
		return `${ARENA_API_BASE_URL}/admin/v1/arena/run-enforcement-events?${params}`;
	}

	function signed(n: number) {
		return `${n > 0 ? '+' : ''}${n.toLocaleString()}`;
	}

	function pnlClass(n: number) {
		return cn(n > 0 && 'text-accent', n < 0 && 'text-destructive');
	}
</script>

<svelte:head>
	<title>Run Detail — Bot Arena</title>
</svelte:head>

<PageHeader title="run detail" description={runId || 'Select a run from game admin.'}>
	{#snippet actions()}
		<Button variant="secondary" href="/admin">back to runs</Button>
	{/snippet}
</PageHeader>

{#if !runId}
	<StateMessage variant="empty" title="no run selected" message="Open a run from game admin to inspect its results and enforcement events." />
{:else if loading}
	<StateMessage variant="loading" message="loading run detail…" />
{:else if error}
	<StateMessage variant="error" title="run detail unavailable" message={error} />
{:else if !run}
	<Card>
		<StateMessage
			variant="empty"
			title="run metadata unavailable"
			message="Results may still load, but this run was not present in the recent admin run list."
		/>
	</Card>
{:else}
	<div class="grid gap-6">
		<Card>
			<div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_260px]">
				<div class="min-w-0">
					<div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
						<h2 class="break-all text-2xl font-bold text-ink">{run.runId}</h2>
						<div class="flex flex-wrap gap-1.5">
							<Badge variant={run.status === 'COMPLETED' ? 'success' : 'muted'}>{run.status}</Badge>
							<Badge variant="muted">{run.botVersions.length} bots</Badge>
							<Badge variant="muted">{results.length} results</Badge>
							{#if enforcementEvents.length}
								<Badge variant="danger">{enforcementEvents.length} enforcement</Badge>
							{/if}
						</div>
					</div>

					<dl class="mt-5 grid gap-x-6 gap-y-4 text-sm sm:grid-cols-2">
						<div class="min-w-0">
							<dt class="text-xs font-bold uppercase tracking-normal text-muted">mode</dt>
							<dd class="mt-1 break-words text-ink">{run.modeId}</dd>
						</div>
						<div class="min-w-0">
							<dt class="text-xs font-bold uppercase tracking-normal text-muted">scenario</dt>
							<dd class="mt-1 break-words text-ink">{run.scenarioId}</dd>
						</div>
						<div>
							<dt class="text-xs font-bold uppercase tracking-normal text-muted">seed</dt>
							<dd class="mt-1 text-ink">{run.seed}</dd>
						</div>
						<div class="min-w-0">
							<dt class="text-xs font-bold uppercase tracking-normal text-muted">policy</dt>
							<dd class="mt-1 break-words text-ink">{run.policyVersion}</dd>
						</div>
						<div>
							<dt class="text-xs font-bold uppercase tracking-normal text-muted">created</dt>
							<dd class="mt-1 text-ink">{new Date(run.createdAt).toLocaleString()}</dd>
						</div>
						<div>
							<dt class="text-xs font-bold uppercase tracking-normal text-muted">completed</dt>
							<dd class="mt-1 text-ink">{run.completedAt ? new Date(run.completedAt).toLocaleString() : 'not complete'}</dd>
						</div>
					</dl>
				</div>

				<div class="grid gap-4 text-sm lg:border-l lg:border-rule lg:pl-5">
					<div>
						<p class="text-xs font-bold uppercase tracking-normal text-muted">leader</p>
						<p class="mt-1 truncate text-ink" title={winner?.botId}>{winner?.botId ?? 'none'}</p>
						<p class={cn('mt-1 text-xs', winner ? pnlClass(winner.realizedPnl) : 'text-muted')}>
							{winner ? signed(winner.realizedPnl) : 'n/a'}
						</p>
					</div>
					<div>
						<p class="text-xs font-bold uppercase tracking-normal text-muted">visibility</p>
						<p class="mt-1 text-ink">{results.filter((result) => result.publicLeaderboard).length} public results</p>
					</div>
				</div>
			</div>

			<ActionBar label="raw data" class="mt-5">
				<Button size="sm" variant="secondary" href={runResultsHref(run.runId)}>results json</Button>
				<Button size="sm" variant="secondary" href={enforcementHref(run.runId)}>enforcement json</Button>
				{#if leaderboardHref}
					<Button size="sm" variant="secondary" href={leaderboardHref}>leaderboard json</Button>
				{/if}
			</ActionBar>
		</Card>

		<Card>
			<div class="mb-4 flex items-baseline justify-between gap-4">
				<h2 class="text-xl font-normal lowercase">bot versions</h2>
				<p class="text-xs text-muted">{run.botVersions.length}</p>
			</div>
			<div class="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
				{#each run.botVersions as botVersion (botVersion.botId + botVersion.versionId)}
					<div class="min-w-0 border border-rule p-3 text-sm">
						<p class="truncate font-bold text-ink" title={botVersion.botId}>{botVersion.botId}</p>
						<p class="mt-1 truncate text-xs text-muted" title={botVersion.versionId}>{botVersion.versionId}</p>
					</div>
				{/each}
			</div>
		</Card>

		<Card>
			<div class="mb-4 flex items-baseline justify-between gap-4">
				<h2 class="text-xl font-normal lowercase">results</h2>
				<p class="text-xs text-muted">{results.length}</p>
			</div>
			{#if results.length}
				<div class="overflow-x-auto border-t border-rule">
					<table class="w-full min-w-[860px] border-collapse text-sm">
						<thead class="text-xs text-muted">
							<tr>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-left font-normal">bot</th>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-left font-normal">version</th>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-right font-normal">equity</th>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-right font-normal">pnl</th>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-right font-normal">max dd</th>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-right font-normal">orders</th>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-right font-normal">data</th>
								<th scope="col" class="border-b border-rule py-3 text-left font-normal">state</th>
							</tr>
						</thead>
						<tbody>
							{#each results as result (result.botId + result.versionId)}
								<tr class="transition-colors hover:bg-accent-soft">
									<td class="max-w-[22ch] truncate border-b border-rule py-2.5 pr-4 text-ink" title={result.botId}>
										{result.botId}
									</td>
									<td class="max-w-[16ch] truncate border-b border-rule py-2.5 pr-4 text-muted" title={result.versionId}>
										{result.versionId}
									</td>
									<td class="border-b border-rule py-2.5 pr-4 text-right text-muted">{result.finalEquity.toLocaleString()}</td>
									<td class={cn('border-b border-rule py-2.5 pr-4 text-right', pnlClass(result.realizedPnl))}>
										{signed(result.realizedPnl)}
									</td>
									<td class="border-b border-rule py-2.5 pr-4 text-right text-muted">{result.maxDrawdown.toLocaleString()}</td>
									<td class="border-b border-rule py-2.5 pr-4 text-right text-muted">{result.orderActionsProposed.toLocaleString()}</td>
									<td class="border-b border-rule py-2.5 pr-4 text-right text-muted">{result.dataCalls.toLocaleString()}</td>
									<td class="border-b border-rule py-2.5">
										<div class="flex flex-wrap gap-1.5">
											<Badge variant={result.disqualified ? 'danger' : result.scoreEligible ? 'success' : 'muted'}>
												{result.disqualified ? 'disqualified' : result.scoreEligible ? 'eligible' : 'ineligible'}
											</Badge>
											{#if result.publicLeaderboard}
												<Badge variant="muted">public</Badge>
											{/if}
										</div>
									</td>
								</tr>
							{/each}
						</tbody>
					</table>
				</div>
			{:else}
				<StateMessage variant="empty" message="no results loaded for this run." />
			{/if}
		</Card>

		<Card>
			<div class="mb-4 flex items-baseline justify-between gap-4">
				<h2 class="text-xl font-normal lowercase">enforcement</h2>
				<p class="text-xs text-muted">{enforcementEvents.length}</p>
			</div>
			{#if enforcementEvents.length}
				<div class="overflow-x-auto border-t border-rule">
					<table class="w-full min-w-[760px] border-collapse text-sm">
						<thead class="text-xs text-muted">
							<tr>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-left font-normal">bot</th>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-left font-normal">decision</th>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-left font-normal">reason</th>
								<th scope="col" class="border-b border-rule py-3 pr-4 text-left font-normal">policy</th>
								<th scope="col" class="border-b border-rule py-3 text-left font-normal">time</th>
							</tr>
						</thead>
						<tbody>
							{#each enforcementEvents as event (event.botId + event.versionId + event.occurredAt)}
								<tr class="transition-colors hover:bg-accent-soft">
									<td class="max-w-[22ch] truncate border-b border-rule py-2.5 pr-4 text-ink" title={event.botId}>
										{event.botId}
										<p class="truncate text-xs text-muted" title={event.versionId}>{event.versionId}</p>
									</td>
									<td class="border-b border-rule py-2.5 pr-4">
										<Badge variant="danger">{event.decision}</Badge>
									</td>
									<td class="border-b border-rule py-2.5 pr-4 text-muted">
										<p class="font-bold text-ink">{event.reasonCode}</p>
										<p class="mt-1 max-w-[36ch] break-words">{event.reason}</p>
									</td>
									<td class="max-w-[16ch] truncate border-b border-rule py-2.5 pr-4 text-muted" title={event.policyVersion}>
										{event.policyVersion}
									</td>
									<td class="border-b border-rule py-2.5 text-muted">{new Date(event.occurredAt).toLocaleString()}</td>
								</tr>
							{/each}
						</tbody>
					</table>
				</div>
			{:else}
				<StateMessage variant="empty" message="no enforcement events loaded for this run." />
			{/if}
		</Card>
	</div>
{/if}
