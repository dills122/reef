<script lang="ts">
	import {
		fetchArenaAdmissionWindow,
		fetchArenaEligibilityDecisions,
		fetchArenaRoster,
		previewArenaRoster,
		removeArenaRosterEntry,
		type ArenaAdmissionWindow,
		type ArenaEligibilityDecision,
		type ArenaRoster,
		type ArenaRosterPreview,
		type ArenaRosterRemoval
	} from '$lib/api';
	import Badge from '$lib/components/ui/Badge.svelte';
	import Button from '$lib/components/ui/Button.svelte';
	import Card from '$lib/components/ui/Card.svelte';
	import PageHeader from '$lib/components/ui/PageHeader.svelte';
	import StateMessage from '$lib/components/ui/StateMessage.svelte';
	import TextInput from '$lib/components/ui/TextInput.svelte';

	let windowId = $state('');
	let maxBots = $state(8);
	let admissionWindow = $state<ArenaAdmissionWindow | null>(null);
	let decisions = $state<ArenaEligibilityDecision[]>([]);
	let priorities = $state<Record<string, number>>({});
	let preview = $state<ArenaRosterPreview | null>(null);
	let roster = $state<ArenaRoster | null>(null);
	let loading = $state(false);
	let previewing = $state(false);
	let removing = $state(false);
	let error = $state('');
	let success = $state('');
	let selectedEntry = $state('');
	let removalReason = $state<ArenaRosterRemoval['reasonCode']>('availability');
	let removalDetail = $state('');
	let removalRequestId = $state('');

	type PreviewGroup = {
		label: string;
		values: ArenaEligibilityDecision[];
		variant: 'success' | 'muted' | 'danger';
	};

	let eligibleDecisions = $derived(
		decisions.filter((decision) => decision.outcome === 'eligible_for_roster')
	);
	let previewGroups = $derived.by((): PreviewGroup[] =>
		preview
			? [
					{ label: 'included', values: preview.included.map((item) => item.decision), variant: 'success' },
					{ label: 'capacity overflow', values: preview.capacityOverflow.map((item) => item.decision), variant: 'muted' },
					{ label: 'rolled', values: preview.rolled, variant: 'muted' },
					{ label: 'excluded', values: preview.excluded, variant: 'danger' }
				]
			: []
	);

	async function loadWindow(event: SubmitEvent) {
		event.preventDefault();
		const requestedWindowId = windowId.trim();
		if (!requestedWindowId) return;
		loading = true;
		error = '';
		success = '';
		preview = null;
		try {
			const [windowResult, decisionResult] = await Promise.all([
				fetchArenaAdmissionWindow(requestedWindowId),
				fetchArenaEligibilityDecisions(requestedWindowId)
			]);
			admissionWindow = windowResult;
			decisions = decisionResult;
			priorities = Object.fromEntries(
				decisionResult
					.filter((decision) => decision.outcome === 'eligible_for_roster')
					.map((decision) => [decision.evaluationId, priorities[decision.evaluationId] ?? 0])
			);
			await loadRoster(requestedWindowId);
		} catch (err) {
			error = err instanceof Error ? err.message : 'admission window load failed';
			admissionWindow = null;
			decisions = [];
			roster = null;
		} finally {
			loading = false;
		}
	}

	async function loadRoster(requestedWindowId: string) {
		try {
			roster = await fetchArenaRoster(requestedWindowId);
			selectedEntry = roster.effectiveEntries[0]
				? `${roster.effectiveEntries[0].botId}|${roster.effectiveEntries[0].versionId}`
				: '';
		} catch (err) {
			if (err instanceof Error && err.message === 'roster not found') {
				roster = null;
				selectedEntry = '';
				return;
			}
			throw err;
		}
	}

	async function buildPreview() {
		if (!admissionWindow) return;
		if (!Number.isInteger(maxBots) || maxBots < 1) {
			error = 'max bots must be a positive integer';
			return;
		}
		if (eligibleDecisions.some((decision) => !Number.isInteger(priorities[decision.evaluationId]) || priorities[decision.evaluationId] < 0)) {
			error = 'every eligible priority must be a nonnegative integer';
			return;
		}
		previewing = true;
		error = '';
		success = '';
		try {
			preview = await previewArenaRoster(
				admissionWindow.windowId,
				maxBots,
				eligibleDecisions.map((decision) => ({
					evaluationId: decision.evaluationId,
					priority: priorities[decision.evaluationId] ?? 0
				}))
			);
		} catch (err) {
			error = err instanceof Error ? err.message : 'roster preview failed';
		} finally {
			previewing = false;
		}
	}

	async function removeEntry(event: SubmitEvent) {
		event.preventDefault();
		if (!admissionWindow || !selectedEntry || !removalDetail.trim()) return;
		const [botId, versionId] = selectedEntry.split('|');
		removing = true;
		error = '';
		success = '';
		try {
			if (!removalRequestId) removalRequestId = `removal-${crypto.randomUUID()}`;
			await removeArenaRosterEntry({
				removalId: removalRequestId,
				windowId: admissionWindow.windowId,
				botId,
				versionId,
				reasonCode: removalReason,
				detail: removalDetail.trim()
			});
			await loadRoster(admissionWindow.windowId);
			removalDetail = '';
			removalRequestId = '';
			success = `${botId} ${versionId} removed from the effective roster; the locked snapshot was not changed.`;
		} catch (err) {
			error = err instanceof Error ? err.message : 'roster removal failed';
		} finally {
			removing = false;
		}
	}

	function formatTime(value: string) {
		return new Date(value).toLocaleString();
	}

	function outcomeVariant(outcome: ArenaEligibilityDecision['outcome']) {
		if (outcome === 'eligible_for_roster') return 'success' as const;
		if (outcome === 'excluded') return 'danger' as const;
		return 'muted' as const;
	}
</script>

<svelte:head>
	<title>Admission Control — Bot Arena</title>
</svelte:head>

<PageHeader title="admission control">
	{#snippet meta()}
		<p class="max-w-[64ch] text-sm text-muted">
			Preview deterministic capacity ordering before lock and record emergency removals without mutating the locked roster.
		</p>
	{/snippet}
	{#snippet actions()}
		<Button variant="secondary" href="/admin">runs</Button>
	{/snippet}
</PageHeader>

<Card>
	<form class="grid gap-4 md:grid-cols-[minmax(0,1fr)_8rem_auto] md:items-end" onsubmit={loadWindow}>
		<label class="grid gap-2 text-sm" for="admission-window-id">
			<span class="font-bold">window id</span>
			<TextInput id="admission-window-id" required value={windowId} oninput={(event) => (windowId = event.currentTarget.value)} placeholder="weekly-2026-07-25" />
		</label>
		<label class="grid gap-2 text-sm" for="admission-max-bots">
			<span class="font-bold">max bots</span>
			<TextInput id="admission-max-bots" type="number" min="1" required value={maxBots} oninput={(event) => (maxBots = event.currentTarget.valueAsNumber)} />
		</label>
		<Button type="submit" disabled={loading}>{loading ? 'loading…' : 'load window'}</Button>
	</form>
</Card>

{#if error}
	<StateMessage variant="error" title="admission action failed" message={error} />
{/if}
{#if success}
	<StateMessage variant="empty" title="roster updated" message={success} />
{/if}

{#if admissionWindow}
	<Card>
		<div class="flex flex-wrap items-start justify-between gap-4">
			<div>
				<h2 class="text-xl font-normal lowercase">window {admissionWindow.windowId}</h2>
				<p class="mt-1 text-xs text-muted">{admissionWindow.policyVersion} · {admissionWindow.displayTimeZone}</p>
			</div>
			<Badge variant={roster ? 'success' : 'muted'}>{roster ? 'locked' : 'pre-lock'}</Badge>
		</div>
		<dl class="mt-5 grid gap-4 border-t border-rule pt-4 text-sm sm:grid-cols-2 lg:grid-cols-5">
			<div><dt class="font-bold text-muted">invite cutoff</dt><dd class="mt-1">{formatTime(admissionWindow.inviteDecisionCutoff)}</dd></div>
			<div><dt class="font-bold text-muted">readiness cutoff</dt><dd class="mt-1">{formatTime(admissionWindow.mergeReadinessCutoff)}</dd></div>
			<div><dt class="font-bold text-muted">roster lock</dt><dd class="mt-1">{formatTime(admissionWindow.rosterLockAt)}</dd></div>
			<div><dt class="font-bold text-muted">recheck</dt><dd class="mt-1">{formatTime(admissionWindow.operationalRecheckAt)}</dd></div>
			<div><dt class="font-bold text-muted">game start</dt><dd class="mt-1">{formatTime(admissionWindow.scheduledStart)}</dd></div>
		</dl>
	</Card>

	<Card>
		<div class="flex flex-wrap items-baseline justify-between gap-3">
			<h2 class="text-xl font-normal lowercase">eligibility decisions</h2>
			<p class="text-xs text-muted">{decisions.length} persisted decisions</p>
		</div>
		{#if decisions.length === 0}
			<StateMessage variant="empty" message="no eligibility decisions have been recorded for this window." />
		{:else}
			<div class="mt-4 overflow-x-auto">
				<table class="w-full min-w-[760px] border-collapse text-left text-sm">
					<thead class="border-y border-rule text-xs text-muted">
						<tr>
							<th scope="col" class="px-2 py-3">bot version</th>
							<th scope="col" class="px-2 py-3">outcome</th>
							<th scope="col" class="px-2 py-3">reason codes</th>
							<th scope="col" class="px-2 py-3">priority</th>
						</tr>
					</thead>
					<tbody class="divide-y divide-rule">
						{#each decisions as decision (decision.evaluationId)}
							<tr>
								<td class="px-2 py-3"><span class="font-bold">{decision.botId}</span><br /><span class="text-xs text-muted">{decision.versionId}</span></td>
								<td class="px-2 py-3"><Badge variant={outcomeVariant(decision.outcome)}>{decision.outcome}</Badge></td>
								<td class="px-2 py-3 text-xs text-muted">{decision.reasonCodes.join(', ') || 'none'}</td>
								<td class="w-32 px-2 py-3">
									{#if decision.outcome === 'eligible_for_roster'}
										<label class="sr-only" for={`priority-${decision.evaluationId}`}>Priority for {decision.botId} {decision.versionId}</label>
										<TextInput id={`priority-${decision.evaluationId}`} type="number" min="0" value={priorities[decision.evaluationId] ?? 0} oninput={(event) => (priorities[decision.evaluationId] = event.currentTarget.valueAsNumber)} />
									{:else}
										<span class="text-muted">—</span>
									{/if}
								</td>
							</tr>
						{/each}
					</tbody>
				</table>
			</div>
		{/if}
		<div class="mt-5 border-t border-rule pt-4">
			<Button type="button" onclick={buildPreview} disabled={previewing || eligibleDecisions.length === 0}>
				{previewing ? 'building preview…' : 'build deterministic preview'}
			</Button>
		</div>
	</Card>

	{#if preview}
		<Card>
			<h2 class="text-xl font-normal lowercase">pre-lock preview</h2>
			<p class="mt-1 text-sm text-muted">Priority descends first; bot id and version id provide deterministic tie-breaks.</p>
			<div class="mt-5 grid gap-4 md:grid-cols-2 xl:grid-cols-4">
				{#each previewGroups as group (group.label)}
					<section class="border border-rule p-4" aria-labelledby={`preview-${group.label.replaceAll(' ', '-')}`}>
						<div class="flex items-center justify-between gap-2">
							<h3 id={`preview-${group.label.replaceAll(' ', '-')}`} class="font-bold lowercase">{group.label}</h3>
							<Badge variant={group.variant}>{group.values.length}</Badge>
						</div>
						{#if group.values.length}
							<ul class="mt-3 space-y-2 text-sm">
								{#each group.values as decision (decision.evaluationId)}
									<li><span class="text-ink">{decision.botId}</span> <span class="text-muted">{decision.versionId}</span></li>
								{/each}
							</ul>
						{:else}
							<p class="mt-3 text-xs text-muted">none</p>
						{/if}
					</section>
				{/each}
			</div>
			{#if preview.awaitingPriority.length}
				<StateMessage variant="error" title="eligible decisions are not ranked" message={`${preview.awaitingPriority.length} eligible decisions must receive priority before lock.`} />
			{/if}
		</Card>
	{/if}

	{#if roster}
		<Card>
			<div class="flex flex-wrap items-start justify-between gap-4">
				<div>
					<h2 class="text-xl font-normal lowercase">locked roster</h2>
					<p class="mt-1 break-all text-xs text-muted">{roster.snapshotHash}</p>
				</div>
				<Badge variant="success">{roster.effectiveEntries.length} effective / {roster.entries.length} locked</Badge>
			</div>
			<p class="mt-4 border-l border-destructive pl-3 text-sm text-muted">
				Emergency removal records an immutable overlay. It never edits the snapshot or inserts a replacement after lock.
			</p>
			<dl class="mt-5 grid gap-4 border-y border-rule py-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
				<div><dt class="font-bold text-muted">mode</dt><dd class="mt-1">{roster.policy.modeId}</dd></div>
				<div><dt class="font-bold text-muted">scenario</dt><dd class="mt-1">{roster.policy.scenarioId}</dd></div>
				<div><dt class="font-bold text-muted">actor profile</dt><dd class="mt-1">{roster.policy.actorProfileVersion}</dd></div>
				<div><dt class="font-bold text-muted">risk policy</dt><dd class="mt-1">{roster.policy.riskPolicyVersion}</dd></div>
				<div><dt class="font-bold text-muted">scoring policy</dt><dd class="mt-1">{roster.policy.scoringPolicyVersion}</dd></div>
				<div><dt class="font-bold text-muted">economic policy</dt><dd class="mt-1">{roster.policy.economicPolicyVersion}</dd></div>
			</dl>
			<div class="mt-4 overflow-x-auto">
				<table class="w-full min-w-[680px] border-collapse text-left text-sm">
					<thead class="border-y border-rule text-xs text-muted"><tr><th scope="col" class="px-2 py-3">order</th><th scope="col" class="px-2 py-3">bot version</th><th scope="col" class="px-2 py-3">priority</th><th scope="col" class="px-2 py-3">effective state</th></tr></thead>
					<tbody class="divide-y divide-rule">
						{#each roster.entries as entry (entry.eligibilityEvaluationId)}
							{@const removal = roster.removals.find((item) => item.botId === entry.botId && item.versionId === entry.versionId)}
							<tr><td class="px-2 py-3">{entry.botOrder}</td><td class="px-2 py-3"><span class="font-bold">{entry.botId}</span> <span class="text-muted">{entry.versionId}</span></td><td class="px-2 py-3">{entry.priority}</td><td class="px-2 py-3">{#if removal}<Badge variant="danger">removed · {removal.reasonCode}</Badge>{:else}<Badge variant="success">included</Badge>{/if}</td></tr>
						{/each}
					</tbody>
				</table>
			</div>

			{#if roster.effectiveEntries.length}
				<form class="mt-6 grid gap-4 border-t border-rule pt-5 lg:grid-cols-2" onsubmit={removeEntry}>
					<label class="grid gap-2 text-sm" for="removal-entry"><span class="font-bold">roster entry</span><select id="removal-entry" required bind:value={selectedEntry} class="min-h-9 border border-rule bg-bg px-3 py-2 text-sm text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-hover">{#each roster.effectiveEntries as entry (entry.eligibilityEvaluationId)}<option value={`${entry.botId}|${entry.versionId}`}>{entry.botId} · {entry.versionId}</option>{/each}</select></label>
					<label class="grid gap-2 text-sm" for="removal-reason"><span class="font-bold">reason</span><select id="removal-reason" required bind:value={removalReason} class="min-h-9 border border-rule bg-bg px-3 py-2 text-sm text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-hover"><option value="security">security</option><option value="trust">trust</option><option value="config">config</option><option value="availability">availability</option></select></label>
					<label class="grid gap-2 text-sm lg:col-span-2" for="removal-detail"><span class="font-bold">audited detail</span><TextInput id="removal-detail" required value={removalDetail} oninput={(event) => (removalDetail = event.currentTarget.value)} placeholder="why this bot cannot start" /></label>
					<div class="lg:col-span-2"><Button type="submit" disabled={removing}>{removing ? 'recording removal…' : 'record emergency removal'}</Button></div>
				</form>
			{/if}
		</Card>
	{/if}
{/if}
