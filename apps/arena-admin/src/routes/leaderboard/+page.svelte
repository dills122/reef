<script lang="ts">
	import { fetchLeaderboard, type LeaderboardResponse } from '$lib/api';
	import PageHeader from '$lib/components/ui/PageHeader.svelte';
	import SegmentButton from '$lib/components/ui/SegmentButton.svelte';
	import StateMessage from '$lib/components/ui/StateMessage.svelte';
	import { GAME_MODES, type GameMode } from '$lib/config/game-modes';
	import { cn } from '$lib/utils';

	let selectedMode = $state(GAME_MODES[0].id);
	let selectedModeConfig = $state<GameMode>(GAME_MODES[0]);
	let leaderboard = $state<LeaderboardResponse | null>(null);
	let loading = $state(true);
	let error = $state('');

	$effect(() => {
		const mode = GAME_MODES.find((m) => m.id === selectedMode) ?? GAME_MODES[0];
		selectedModeConfig = mode;
		const controller = new AbortController();
		loading = true;
		error = '';
		leaderboard = null;
		fetchLeaderboard(mode.id, mode.scoringPolicyVersion, { signal: controller.signal }).then(
			(result) => {
				leaderboard = result;
				loading = false;
			},
			(err: Error) => {
				if (err.name === 'AbortError') return;
				error = err.message || 'leaderboard unavailable';
				loading = false;
			}
		);
		return () => controller.abort();
	});

	function signed(n: number) {
		return `${n > 0 ? '+' : ''}${n.toLocaleString()}`;
	}

	function pnlClass(n: number) {
		return cn(n > 0 && 'text-accent', n < 0 && 'text-destructive');
	}

	function formatNumber(n: number) {
		return n.toLocaleString();
	}
</script>

<svelte:head>
	<title>Leaderboard — Bot Arena</title>
	<meta name="description" content="Bot Arena leaderboards by game type — current standings for every active bot." />
</svelte:head>

<PageHeader title="leaderboard" class="mb-6" />

<div class="mb-6 flex flex-wrap gap-2">
	{#each GAME_MODES as mode (mode.id)}
		<SegmentButton selected={selectedMode === mode.id} onclick={() => (selectedMode = mode.id)}>
			{mode.name}
		</SegmentButton>
	{/each}
</div>

<section class="mb-6 border-y border-rule py-4">
	<div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
		<div class="min-w-0">
			<p class="text-xs tracking-[0.08em] text-muted uppercase">selected game type</p>
			<h2 class="mt-1 text-xl font-bold text-ink">{selectedModeConfig.name}</h2>
			<p class="mt-2 max-w-[60ch] text-sm text-muted">{selectedModeConfig.description}</p>
		</div>
		<div class="grid shrink-0 gap-2 text-xs text-muted sm:text-right">
			<p>
				<span class="font-bold uppercase tracking-normal">entry</span><br />
				{selectedModeConfig.mandatory ? 'mandatory' : 'optional'}
			</p>
			<p>
				<span class="font-bold uppercase tracking-normal">scoring policy</span><br />
				{leaderboard?.scoringPolicyVersion ?? selectedModeConfig.scoringPolicyVersion}
			</p>
			{#if leaderboard}
				<p>
					<span class="font-bold uppercase tracking-normal">source</span><br />
					/api/v1/arena/leaderboard
				</p>
			{/if}
		</div>
	</div>
</section>

{#if loading}
	<StateMessage variant="loading" message="loading leaderboard…" />
{:else if error}
	<StateMessage variant="error" title="leaderboard unavailable" message={error}>
		<p class="mt-3 text-xs text-muted">
			This is the public `/api/v1/arena/leaderboard` read. An unavailable board is not treated as an
			empty scored run set.
		</p>
	</StateMessage>
{:else if !leaderboard || leaderboard.entries.length === 0}
	<StateMessage variant="empty" message="no public scored runs yet for this game type.">
		<p class="mt-2 text-xs text-muted">
			Only eligible, non-disqualified results marked for the public leaderboard appear here.
		</p>
	</StateMessage>
{:else}
	<!-- Mobile: stacked cards, bot + pnl are the two things worth a glance on a phone. -->
	<ul class="border-t border-rule sm:hidden">
		{#each leaderboard.entries as entry (entry.runId + entry.botId + entry.versionId)}
			<li class="flex items-start justify-between gap-3 border-b border-rule py-3">
				<div class="min-w-0">
					<p class="text-xs text-muted">#{entry.rank}</p>
					<p class="truncate font-bold text-ink">{entry.botName}</p>
					<p class="truncate text-xs text-muted">{entry.ownerHandle} / {entry.versionId}</p>
					<p class="truncate text-xs text-muted" title={entry.runId}>{entry.runId}</p>
				</div>
				<div class="shrink-0 text-right">
					<p class={cn('font-bold', pnlClass(entry.realizedPnl))}>{signed(entry.realizedPnl)}</p>
					<p class="text-xs text-muted">eq {formatNumber(entry.finalEquity)}</p>
					<p class="text-xs text-muted">dd {formatNumber(entry.maxDrawdown)}</p>
				</div>
			</li>
		{/each}
	</ul>

	<!-- sm+: full table -->
	<table class="hidden w-full border-t border-rule text-sm sm:table">
		<thead>
			<tr class="text-xs tracking-[0.08em] text-muted uppercase">
				<th class="border-b border-rule py-3 pr-4 text-left font-normal">rank</th>
				<th class="border-b border-rule py-3 pr-4 text-left font-normal">bot</th>
				<th class="border-b border-rule py-3 pr-4 text-left font-normal">owner</th>
				<th class="border-b border-rule py-3 pr-4 text-left font-normal">run</th>
				<th class="border-b border-rule py-3 pr-4 text-right font-normal">equity</th>
				<th class="border-b border-rule py-3 pr-4 text-right font-normal">pnl</th>
				<th class="border-b border-rule py-3 text-right font-normal">max dd</th>
			</tr>
		</thead>
		<tbody>
			{#each leaderboard.entries as entry (entry.runId + entry.botId + entry.versionId)}
				<tr class="transition-colors hover:bg-accent-soft">
					<td class="border-b border-rule py-2.5 pr-4">{entry.rank}</td>
					<td class="border-b border-rule py-2.5 pr-4">
						<p class="font-bold text-ink">{entry.botName}</p>
						<p class="text-xs text-muted">{entry.botId} / {entry.versionId}</p>
					</td>
					<td class="border-b border-rule py-2.5 pr-4 text-muted">{entry.ownerHandle}</td>
					<td class="max-w-[16ch] truncate border-b border-rule py-2.5 pr-4 text-muted" title={entry.runId}>
						{entry.runId}
					</td>
					<td class="border-b border-rule py-2.5 pr-4 text-right">{formatNumber(entry.finalEquity)}</td>
					<td class={cn('border-b border-rule py-2.5 pr-4 text-right', pnlClass(entry.realizedPnl))}
						>{signed(entry.realizedPnl)}</td
					>
					<td class="border-b border-rule py-2.5 text-right text-muted">{formatNumber(entry.maxDrawdown)}</td>
				</tr>
			{/each}
		</tbody>
	</table>
{/if}
