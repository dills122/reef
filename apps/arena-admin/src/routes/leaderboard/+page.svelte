<script lang="ts">
	import { fetchLeaderboard, type LeaderboardEntry } from '$lib/api';
	import { GAME_MODES } from '$lib/config/game-modes';
	import { cn } from '$lib/utils';

	let selectedMode = $state(GAME_MODES[0].id);
	let entries = $state<LeaderboardEntry[]>([]);
	let loading = $state(true);

	$effect(() => {
		const mode = GAME_MODES.find((m) => m.id === selectedMode) ?? GAME_MODES[0];
		loading = true;
		fetchLeaderboard(mode.id, mode.scoringPolicyVersion).then((result) => {
			entries = result;
			loading = false;
		});
	});

	function signed(n: number) {
		return `${n > 0 ? '+' : ''}${n.toFixed(2)}`;
	}

	function pnlClass(n: number) {
		return cn(n > 0 && 'text-accent', n < 0 && 'text-destructive');
	}
</script>

<svelte:head>
	<title>Leaderboard — Bot Arena</title>
	<meta name="description" content="Bot Arena leaderboards by game type — current standings for every active bot." />
</svelte:head>

<h1 class="mb-6 text-3xl font-normal tracking-[-0.03em] lowercase">leaderboard</h1>

<div class="mb-6 flex flex-wrap gap-2">
	{#each GAME_MODES as mode (mode.id)}
		<button
			class={cn(
				'inline-flex min-h-[32px] items-center rounded border px-2.5 py-1.5 text-sm lowercase transition-colors',
				selectedMode === mode.id
					? 'border-accent bg-accent text-accent-ink font-bold'
					: 'border-rule-strong bg-accent-soft text-ink'
			)}
			onclick={() => (selectedMode = mode.id)}
		>
			{mode.name}
		</button>
	{/each}
</div>

{#if loading}
	<p class="border-t border-rule py-6 text-center text-muted">loading…</p>
{:else if entries.length === 0}
	<p class="border-t border-rule py-6 text-center text-muted">no scored runs yet for this game type.</p>
{:else}
	<!-- Mobile: stacked cards, bot + pnl are the two things worth a glance on a phone. -->
	<ul class="border-t border-rule sm:hidden">
		{#each entries as entry (entry.rank)}
			<li class="flex items-start justify-between gap-3 border-b border-rule py-3">
				<div class="min-w-0">
					<p class="text-xs text-muted">#{entry.rank}</p>
					<p class="truncate font-bold text-ink">{entry.botName}</p>
					<p class="truncate text-xs text-muted">{entry.ownerHandle}</p>
				</div>
				<div class="shrink-0 text-right">
					<p class={cn('font-bold', pnlClass(entry.realizedPnl))}>{signed(entry.realizedPnl)}</p>
					<p class="text-xs text-muted">eq {entry.finalEquity.toFixed(2)}</p>
					<p class="text-xs text-muted">dd {entry.maxDrawdown.toFixed(2)}</p>
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
				<th class="border-b border-rule py-3 pr-4 text-right font-normal">equity</th>
				<th class="border-b border-rule py-3 pr-4 text-right font-normal">pnl</th>
				<th class="border-b border-rule py-3 text-right font-normal">max dd</th>
			</tr>
		</thead>
		<tbody>
			{#each entries as entry (entry.rank)}
				<tr class="transition-colors hover:bg-accent-soft">
					<td class="border-b border-rule py-2.5 pr-4">{entry.rank}</td>
					<td class="border-b border-rule py-2.5 pr-4 font-bold text-ink">{entry.botName}</td>
					<td class="border-b border-rule py-2.5 pr-4 text-muted">{entry.ownerHandle}</td>
					<td class="border-b border-rule py-2.5 pr-4 text-right">{entry.finalEquity.toFixed(2)}</td>
					<td class={cn('border-b border-rule py-2.5 pr-4 text-right', pnlClass(entry.realizedPnl))}
						>{signed(entry.realizedPnl)}</td
					>
					<td class="border-b border-rule py-2.5 text-right text-muted">{entry.maxDrawdown.toFixed(2)}</td>
				</tr>
			{/each}
		</tbody>
	</table>
{/if}
