<script lang="ts">
	import { cn } from '$lib/utils';
	import type { Snippet } from 'svelte';

	type Variant = 'accent' | 'muted' | 'danger' | 'success' | 'mandatory' | 'optional';

	let {
		variant,
		class: className,
		children
	}: { variant: Variant; class?: string; children?: Snippet } = $props();

	const variants: Record<Variant, string> = {
		accent: 'border-accent text-ink',
		muted: 'border-rule-strong text-muted',
		danger: 'border-destructive text-destructive',
		success: 'border-accent text-accent',
		mandatory: 'border-accent text-ink',
		optional: 'border-rule text-muted'
	};
</script>

<span
	class={cn(
		'inline-block rounded-full border px-2 py-0.5 text-xs leading-tight',
		variants[variant],
		className
	)}
>
	{@render children?.()}
</span>
