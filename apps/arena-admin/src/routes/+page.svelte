<script lang="ts">
	import Button from '$lib/components/ui/Button.svelte';
	import { GAME_MODES } from '$lib/config/game-modes';
</script>

<svelte:head>
	<title>Bot Arena</title>
	<meta
		name="description"
		content="Bot Arena runs trading bots against Reef's simulation-first venue under real order lifecycle, matching, and risk rules. Submit a bot, get scored, climb the board."
	/>
</svelte:head>

<section>
	<p class="mb-4 text-xs tracking-[0.12em] text-muted uppercase">reef / bot arena</p>
	<h1
		class="mb-4 bg-gradient-to-r from-accent via-accent-2 to-accent-3 bg-clip-text text-[clamp(2.35rem,8vw,5rem)] leading-[0.98] font-normal tracking-[-0.04em] text-transparent lowercase"
	>
		trading bots. real market rules. one leaderboard.
	</h1>
	<p class="mb-6 max-w-[68ch] text-[1.05rem] text-muted">
		Bot Arena runs your trading bot against Reef's simulation-first venue under real order
		lifecycle, matching, and risk rules. Submit a bot, get scored, climb the board.
	</p>
	<div class="flex flex-wrap gap-2">
		<Button href="/leaderboard">view leaderboard</Button>
		<Button variant="secondary" href="/game-types">see game types</Button>
	</div>
</section>

<section class="mt-12 border-t border-rule pt-4">
	<h2 class="mb-3 text-sm font-bold tracking-[0.02em] uppercase">game types</h2>
	<div class="grid gap-x-6 gap-y-3 sm:grid-cols-3">
		{#each GAME_MODES as mode (mode.id)}
			<a href="/game-types#{mode.id}" class="group border-t border-rule pt-3 no-underline">
				<span class="flex items-baseline justify-between gap-2">
					<span class="font-bold text-ink group-hover:text-accent-hover">{mode.name}</span>
					<span class="text-muted group-hover:text-accent-hover">&rarr;</span>
				</span>
				<span class="mt-1 block text-sm text-muted">{mode.description}</span>
			</a>
		{/each}
	</div>
</section>
