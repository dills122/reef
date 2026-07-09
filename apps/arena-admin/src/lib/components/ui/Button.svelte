<script lang="ts">
	import { cn } from '$lib/utils';
	import type { HTMLAnchorAttributes, HTMLButtonAttributes } from 'svelte/elements';

	type Variant = 'primary' | 'secondary';

	let {
		variant = 'primary',
		class: className,
		href,
		children,
		...rest
	}: (HTMLButtonAttributes | HTMLAnchorAttributes) & { variant?: Variant; href?: string } =
		$props();

	const variants: Record<Variant, string> = {
		primary: 'border-accent bg-accent text-accent-ink',
		secondary: 'border-rule-strong bg-accent-soft text-ink'
	};

	const classes = $derived(
		cn(
			'inline-flex min-h-[42px] w-fit items-center justify-center gap-2 rounded border px-3.5 py-2.5 text-sm font-bold leading-tight no-underline transition-colors hover:border-accent-hover hover:bg-accent-hover hover:text-accent-ink disabled:pointer-events-none disabled:opacity-50',
			variants[variant],
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
