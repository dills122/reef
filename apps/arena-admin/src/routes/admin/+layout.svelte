<script lang="ts">
	import { page } from '$app/state';
	import {
		displayRoles,
		fetchSession,
		githubLoginUrl,
		hasOperatorAccess,
		type SessionUser
	} from '$lib/api';
	import Button from '$lib/components/ui/Button.svelte';

	let { children } = $props();
	let session = $state<SessionUser | null | 'loading'>('loading');
	const adminNavLinks = [
		{ href: '/admin', label: 'runs' },
		{ href: '/admin/admission', label: 'admission' },
		{ href: '/admin/access', label: 'access' }
	];

	$effect(() => {
		fetchSession().then((result) => {
			session = result;
		});
	});

</script>

{#if session === 'loading'}
	<p class="text-muted">checking session…</p>
{:else if session === null}
	<div class="flex flex-col items-start gap-4">
		<h1 class="text-3xl font-normal tracking-[-0.03em] lowercase">admin sign-in required</h1>
		<p class="max-w-[52ch] text-muted">
			This area is gated to Reef operators. Sign in with the GitHub account tied to your admin
			role.
		</p>
		<Button href={githubLoginUrl(page.url.pathname)}>sign in with github</Button>
	</div>
{:else if !hasOperatorAccess(session)}
	<div class="flex flex-col items-start gap-4">
		<h1 class="text-3xl font-normal tracking-[-0.03em] lowercase">operator role required</h1>
		<p class="max-w-[58ch] text-muted">
			You are signed in as <span class="text-ink">{session.githubLogin}</span>, but this surface is for
			operator control-plane access. Bot owner config belongs on a narrower participant surface, not this
			all-bots admin view.
		</p>
		<div class="border-t border-rule pt-4 text-sm text-muted">
			<p>
				<span class="font-bold uppercase tracking-normal">trust</span><br />
				{session.trustState || 'unknown'}
			</p>
			<p class="mt-3">
				<span class="font-bold uppercase tracking-normal">roles</span><br />
				{displayRoles(session.roles)}
			</p>
		</div>
		<Button href="/">back to arena</Button>
	</div>
{:else}
	<nav class="mb-6 flex flex-wrap gap-2 border-b border-rule pb-3 text-sm">
		{#each adminNavLinks as link (link.href)}
			<a
				href={link.href}
				class="border px-3 py-2 font-bold no-underline"
				class:border-accent={page.url.pathname === link.href}
				class:bg-accent-soft={page.url.pathname === link.href}
				class:text-ink={page.url.pathname === link.href}
				class:border-rule={page.url.pathname !== link.href}
				class:text-muted={page.url.pathname !== link.href}
			>
				{link.label}
			</a>
		{/each}
	</nav>
	{@render children()}
{/if}
