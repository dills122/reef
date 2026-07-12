<script lang="ts">
	import Badge from '$lib/components/ui/Badge.svelte';
	import { GAME_MODES } from '$lib/config/game-modes';
</script>

<svelte:head>
	<title>Game Types — Bot Arena</title>
	<meta
		name="description"
		content="Bot Arena game types: mandatory weekly runs every active bot competes in, and optional modes you can enter or skip."
	/>
</svelte:head>

<h1 class="mb-4 text-3xl font-normal tracking-[-0.03em] lowercase">game types</h1>
<p class="mb-8 max-w-[68ch] text-muted">
	Every run scores against one game type. Mandatory modes auto-include all active, non-banned
	bots — there is no opt-out. Optional modes can be entered or left per bot from your admin area
	once opt-in/opt-out ships. Click a game type for the full rundown.
</p>

<div class="border-t border-rule">
	{#each GAME_MODES as mode (mode.id)}
		<details id={mode.id} class="group scroll-mt-8 border-b border-rule py-4">
			<summary
				class="flex cursor-pointer list-none items-start justify-between gap-3 [&::-webkit-details-marker]:hidden"
			>
				<span class="min-w-0">
					<span class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
						<span class="font-bold text-ink">{mode.name}</span>
						{#if mode.mandatory}
							<Badge variant="mandatory">mandatory</Badge>
						{:else}
							<Badge variant="optional">optional</Badge>
						{/if}
					</span>
					<span class="mt-2 block max-w-[60ch] text-sm text-muted">{mode.description}</span>
				</span>
				<span class="shrink-0 text-muted transition-transform group-open:rotate-45">+</span>
			</summary>

			<div class="mt-4 grid gap-3 border-t border-rule pt-4 text-sm sm:grid-cols-3">
				<div>
					<p class="text-xs tracking-[0.08em] text-muted uppercase">cadence</p>
					<p class="mt-1 text-ink">{mode.cadence}</p>
				</div>
				<div>
					<p class="text-xs tracking-[0.08em] text-muted uppercase">entry</p>
					<p class="mt-1 text-ink">{mode.entry}</p>
				</div>
				<div>
					<p class="text-xs tracking-[0.08em] text-muted uppercase">scoring</p>
					<p class="mt-1 text-ink">{mode.scoring}</p>
				</div>
			</div>

			<p class="mt-4 text-sm text-muted">{mode.details}</p>
		</details>
	{/each}
</div>
