<script lang="ts">
	import { cn } from '$lib/utils';
	import type { Snippet } from 'svelte';
	import type { HTMLButtonAttributes } from 'svelte/elements';

	let {
		selected = false,
		class: className,
		children,
		...rest
	}: HTMLButtonAttributes & { selected?: boolean; class?: string; children?: Snippet } = $props();
</script>

<button
	class={cn(
		'inline-flex min-h-[32px] items-center rounded border px-2.5 py-1.5 text-sm lowercase transition-colors',
		selected
			? 'border-accent bg-accent text-accent-ink font-bold'
			: 'border-rule-strong bg-accent-soft text-ink hover:border-accent-hover hover:bg-accent-hover hover:text-accent-ink',
		className
	)}
	aria-pressed={selected}
	{...rest}
>
	{@render children?.()}
</button>
