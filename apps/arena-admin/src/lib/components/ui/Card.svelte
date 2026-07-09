<script lang="ts">
	import { cn } from '$lib/utils';
	import type { Snippet } from 'svelte';

	let {
		class: className,
		children
	}: { class?: string; children?: Snippet } = $props();
</script>

<div class={cn('border-t border-rule pt-4', className)}>
	{@render children?.()}
</div>
