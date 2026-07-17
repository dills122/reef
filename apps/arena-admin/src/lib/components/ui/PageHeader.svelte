<script lang="ts">
	import { cn } from '$lib/utils';
	import type { Snippet } from 'svelte';

	let {
		title,
		eyebrow,
		description,
		meta,
		actions,
		class: className
	}: {
		title: string;
		eyebrow?: string;
		description?: string;
		meta?: Snippet;
		actions?: Snippet;
		class?: string;
	} = $props();
</script>

<div
	class={cn(
		'mb-6 flex flex-col gap-3 border-b border-rule pb-5 sm:flex-row sm:items-end sm:justify-between',
		className
	)}
>
	<div class="min-w-0">
		{#if eyebrow}
			<p class="mb-2 text-xs tracking-[0.12em] text-muted uppercase">{eyebrow}</p>
		{/if}
		<h1 class="text-3xl font-normal tracking-[-0.03em] lowercase">{title}</h1>
		{#if description}
			<p class="mt-2 max-w-[68ch] text-sm text-muted">{description}</p>
		{/if}
	</div>
	{#if meta || actions}
		<div class="flex shrink-0 flex-col gap-2 sm:items-end">
			{@render meta?.()}
			{@render actions?.()}
		</div>
	{/if}
</div>
