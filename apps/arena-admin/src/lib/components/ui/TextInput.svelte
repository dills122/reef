<script lang="ts">
	import { cn } from '$lib/utils';
	import type { HTMLInputAttributes } from 'svelte/elements';

	let {
		class: className,
		...rest
	}: HTMLInputAttributes & { class?: string } = $props();
</script>

<input
	class={cn(
		'min-h-9 w-full border border-rule bg-[#070b13] px-3 py-2 text-sm text-ink outline-none transition-colors placeholder:text-muted focus:border-accent-hover',
		className
	)}
	{...rest}
/>
