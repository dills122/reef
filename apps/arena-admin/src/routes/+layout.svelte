<script lang="ts">
	import '../app.css';
	import favicon from '$lib/assets/favicon.svg';
	import { page } from '$app/state';

	let { children } = $props();

	const navLinks = [
		{ href: '/', label: 'arena' },
		{ href: '/game-types', label: 'game types' },
		{ href: '/leaderboard', label: 'leaderboard' },
		{ href: '/admin', label: 'admin' }
	];
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
</svelte:head>

<div
	class="mx-auto flex min-h-screen w-[min(76ch,calc(100%-32px))] flex-col pt-[clamp(28px,7vw,72px)]"
>
	<header
		class="mb-8 flex flex-col gap-3 border-t border-rule pt-4 sm:flex-row sm:items-baseline sm:justify-between sm:gap-6"
	>
		<a href="/" class="text-sm font-bold whitespace-nowrap text-ink no-underline">bot arena</a>
		<nav class="flex flex-wrap gap-x-4 gap-y-1 text-sm">
			{#each navLinks as link (link.href)}
				<a
					href={link.href}
					class="no-underline"
					class:text-ink={page.url.pathname === link.href}
					class:text-muted={page.url.pathname !== link.href}
				>
					{link.label}
				</a>
			{/each}
		</nav>
	</header>

	<main class="flex-1 pb-16">
		{@render children()}
	</main>

	<footer class="flex justify-between gap-4 border-t border-rule py-4 text-sm text-muted">
		<span>reef bot arena — simulation-first, not financial advice.</span>
		<a href="https://github.com/dills122/reef">github</a>
	</footer>
</div>
