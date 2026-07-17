<script lang="ts">
	import { cn } from '$lib/utils';
	import type { HTMLAnchorAttributes, HTMLButtonAttributes } from 'svelte/elements';

	type Variant = 'primary' | 'secondary';
	type Size = 'md' | 'sm';

	let {
		variant = 'primary',
		size = 'md',
		class: className,
		href,
		children,
		...rest
	}: (HTMLButtonAttributes | HTMLAnchorAttributes) & { variant?: Variant; size?: Size; href?: string } =
		$props();

	const variants: Record<Variant, string> = {
		primary: 'border-accent bg-accent text-accent-ink',
		secondary: 'border-rule-strong bg-accent-soft text-ink'
	};

	const sizes: Record<Size, string> = {
		md: 'min-h-[42px] px-3.5 py-2.5 text-sm',
		sm: 'min-h-9 px-3 py-2 text-xs'
	};

	const classes = $derived(
		cn(
			'inline-flex w-fit items-center justify-center gap-2 rounded border font-bold leading-tight no-underline transition-colors hover:border-accent-hover hover:bg-accent-hover hover:text-accent-ink disabled:pointer-events-none disabled:opacity-50',
			variants[variant],
			sizes[size],
			className
		)
	);
</script>

{#if href}
	<a {href} class={classes} {...rest as HTMLAnchorAttributes}>
		{@render children?.()}
	</a>
{:else}
	<button class={classes} {...rest as HTMLButtonAttributes}>
		{@render children?.()}
	</button>
{/if}
