<script lang="ts">
	import { cn } from '$lib/utils';
	import type { Snippet } from 'svelte';

	let {
		label = 'actions',
		class: className,
		children
	}: { label?: string; class?: string; children?: Snippet } = $props();
</script>

<div class={cn('border-t border-rule pt-3', className)}>
	<div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
		<p class="text-xs text-muted">{label}</p>
		<div class="grid gap-2 sm:flex sm:flex-wrap sm:justify-end">
			{@render children?.()}
		</div>
	</div>
</div>
