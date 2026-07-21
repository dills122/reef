<script lang="ts">
	import { cn } from '$lib/utils';
	import type { Snippet } from 'svelte';

	type Variant = 'loading' | 'empty' | 'error';

	let {
		variant,
		title,
		message,
		center = variant !== 'error',
		class: className,
		children
	}: {
		variant: Variant;
		title?: string;
		message?: string;
		center?: boolean;
		class?: string;
		children?: Snippet;
	} = $props();

	const classes: Record<Variant, string> = {
		loading: 'border-rule',
		empty: 'border-rule',
		error: 'border-destructive'
	};
</script>

<div
	class={cn('border-t py-6', classes[variant], center && 'text-center', className)}
	role={variant === 'error' ? 'alert' : 'status'}
	aria-live={variant === 'error' ? 'assertive' : 'polite'}
>
	{#if title}
		<p class={cn('font-bold', variant === 'error' ? 'text-destructive' : 'text-ink')}>{title}</p>
	{/if}
	{#if message}
		<p class={cn(title && 'mt-1', 'text-sm text-muted')}>{message}</p>
	{/if}
	{@render children?.()}
</div>
