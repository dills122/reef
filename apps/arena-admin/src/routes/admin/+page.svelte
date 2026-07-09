<script lang="ts">
	import Card from '$lib/components/ui/Card.svelte';
</script>

<svelte:head>
	<title>Admin — Bot Arena</title>
</svelte:head>

<h1 class="mb-6 text-3xl font-normal tracking-[-0.03em] lowercase">admin</h1>

<div class="grid gap-6 sm:grid-cols-2">
	<Card>
		<p class="font-bold text-ink">bots</p>
		<p class="mt-1 text-sm text-muted">Ownership, limits, ban state. Not wired yet.</p>
	</Card>
	<Card>
		<p class="font-bold text-ink">runs</p>
		<p class="mt-1 text-sm text-muted">Run/replay control-plane operations. Not wired yet.</p>
	</Card>
</div>
