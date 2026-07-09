<script lang="ts">
	import { fetchSession, githubLoginUrl, type SessionUser } from '$lib/api';
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
{:else}
	<div class="mb-6 flex items-baseline justify-between border-b border-rule pb-4">
		<p class="text-sm text-muted">
			signed in as <span class="text-ink">{session.githubLogin}</span>
		</p>
		<p class="text-xs text-muted">{session.roles.join(', ')}</p>
	</div>
	{@render children()}
{/if}
