<script lang="ts">
	import '../app.css';
	import favicon from '$lib/assets/favicon.svg';
	import { page } from '$app/state';
	import {
		displayRoles,
		fetchSession,
		githubLoginUrl,
		hasBotAdminAccess,
		hasOperatorAccess,
		type SessionUser
	} from '$lib/api';

	let { children } = $props();
	let session = $state<SessionUser | null | 'loading'>('loading');
	let appShell = $derived(page.url.pathname === '/bot-admin' || page.url.pathname.startsWith('/admin'));

	const publicNavLinks = [
		{ href: '/', label: 'arena' },
		{ href: '/game-types', label: 'game types' },
		{ href: '/leaderboard', label: 'leaderboard' }
	];

	const navLinks = $derived([
		...publicNavLinks,
		...(session !== 'loading' && session && hasBotAdminAccess(session)
			? [{ href: '/bot-admin', label: 'my bots' }]
			: []),
		...(session !== 'loading' && session && hasOperatorAccess(session)
			? [{ href: '/admin', label: 'game admin' }]
			: [])
	]);

	$effect(() => {
		fetchSession().then((result) => {
			session = result;
		});
	});
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
</svelte:head>

<div
	class={appShell
		? 'mx-auto flex min-h-screen w-[min(1120px,calc(100%-32px))] flex-col pt-[clamp(28px,7vw,72px)]'
		: 'mx-auto flex min-h-screen w-[min(76ch,calc(100%-32px))] flex-col pt-[clamp(28px,7vw,72px)]'}
>
	<header
		class="mb-8 flex flex-col gap-3 border-t border-rule pt-4 sm:flex-row sm:items-baseline sm:justify-between sm:gap-6"
	>
		<a href="/" class="text-sm font-bold whitespace-nowrap text-ink no-underline">bot arena</a>
		<div class="flex flex-wrap items-baseline gap-x-4 gap-y-2 text-sm sm:justify-end">
			<nav class="flex flex-wrap gap-x-4 gap-y-1">
				{#each navLinks as link (link.href)}
					<a
						href={link.href}
						class="no-underline"
						class:text-ink={page.url.pathname === link.href ||
							(link.href !== '/' && page.url.pathname.startsWith(`${link.href}/`))}
						class:text-muted={page.url.pathname !== link.href &&
							(link.href === '/' || !page.url.pathname.startsWith(`${link.href}/`))}
					>
						{link.label}
					</a>
				{/each}
			</nav>
			{#if session === 'loading'}
				<span class="text-muted">checking session…</span>
			{:else if session}
				<div class="flex max-w-full flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted">
					<span class="max-w-[18ch] truncate text-ink" title={session.githubLogin}>
						{session.githubLogin}
					</span>
					<span class="border-l border-rule pl-2">{session.trustState || 'unknown'}</span>
					<span class="max-w-[28ch] truncate border-l border-rule pl-2" title={displayRoles(session.roles)}>
						{displayRoles(session.roles)}
					</span>
				</div>
			{:else}
				<a href={githubLoginUrl(page.url.pathname)} class="no-underline">sign in</a>
			{/if}
		</div>
	</header>

	<main class="flex-1 pb-16">
		{@render children()}
	</main>

	<footer class="flex justify-between gap-4 border-t border-rule py-4 text-sm text-muted">
		<span>reef bot arena — simulation-first, not financial advice.</span>
		<a href="https://github.com/dills122/reef">github</a>
	</footer>
</div>
