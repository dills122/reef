<script lang="ts">
	import { fetchSession, githubLoginUrl, hasOperatorAccess, type SessionUser } from '$lib/api';
	import Button from '$lib/components/ui/Button.svelte';

	let { children } = $props();
	let session = $state<SessionUser | null | 'loading'>('loading');

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
		<Button href={githubLoginUrl('/admin')}>sign in with github</Button>
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
				{session.roles.length ? session.roles.join(', ') : 'none'}
			</p>
		</div>
		<Button href="/">back to arena</Button>
	</div>
{:else}
	<div class="mb-6 flex items-baseline justify-between border-b border-rule pb-4">
		<p class="text-sm text-muted">
			signed in as <span class="text-ink">{session.githubLogin}</span>
		</p>
		<p class="text-xs text-muted">{session.roles.join(', ')}</p>
	</div>
	{@render children()}
{/if}
