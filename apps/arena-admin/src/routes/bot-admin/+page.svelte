<script lang="ts">
	import {
		displayRoles,
		fetchSession,
		fetchBotConfigStatus,
		fetchOwnedArenaBots,
		githubLoginUrl,
		hasBotAdminAccess,
		hasOperatorAccess,
		replaceBotConfig,
		deleteBotConfig,
		type ArenaBot,
		type ArenaBotOwner,
		type BotConfigStatus,
		type SessionUser
	} from '$lib/api';
	import ActionBar from '$lib/components/ui/ActionBar.svelte';
	import Badge from '$lib/components/ui/Badge.svelte';
	import Button from '$lib/components/ui/Button.svelte';
	import Card from '$lib/components/ui/Card.svelte';
	import PageHeader from '$lib/components/ui/PageHeader.svelte';
	import SegmentButton from '$lib/components/ui/SegmentButton.svelte';
	import StateMessage from '$lib/components/ui/StateMessage.svelte';
	import TextInput from '$lib/components/ui/TextInput.svelte';

	let session = $state<SessionUser | null | 'loading'>('loading');
	let ownedBots = $state<ArenaBot[]>([]);
	let loadingBots = $state(false);
	let botError = $state('');
	let configByBotId = $state<Record<string, BotConfigStatus>>({});
	let configErrorByBotId = $state<Record<string, string>>({});
	let selectedConfigBotId = $state('');
	let configDraftByBotId = $state<Record<string, string>>({});
	let configBaselineByBotId = $state<Record<string, string>>({});
	let configSaveErrorByBotId = $state<Record<string, string>>({});
	let configBusyByBotId = $state<Record<string, boolean>>({});
	let configNoticeByBotId = $state<Record<string, string>>({});
	let botFilter = $state<'all' | 'attention' | 'configured' | 'empty'>('all');
	let botSearch = $state('');
	let copyNoticeByKey = $state<Record<string, string>>({});
	let confirmAction = $state<{ kind: 'close' | 'clear'; botId: string } | null>(null);

	let attentionBots = $derived(
		ownedBots.filter((bot) => configErrorByBotId[bot.botId] || !configByBotId[bot.botId]?.hasConfig)
	);
	let filteredOwnedBots = $derived(
		ownedBots.filter((bot) => {
			const query = botSearch.trim().toLowerCase();
			const matchesQuery =
				!query ||
				bot.botId.toLowerCase().includes(query) ||
				bot.metadata.name.toLowerCase().includes(query) ||
				bot.fileName.toLowerCase().includes(query) ||
				bot.metadata.publisher.toLowerCase().includes(query);
			if (!matchesQuery) return false;
			const hasConfig = configByBotId[bot.botId]?.hasConfig;
			if (botFilter === 'attention') return attentionBots.some((item) => item.botId === bot.botId);
			if (botFilter === 'configured') return Boolean(hasConfig);
			if (botFilter === 'empty') return !hasConfig;
			return true;
		})
	);

	$effect(() => {
		fetchSession().then((result) => {
			session = result;
		});
	});

	$effect(() => {
		if (session === 'loading' || session === null || !hasBotAdminAccess(session)) return;
		loadingBots = true;
		botError = '';
		fetchOwnedArenaBots().then(
			async (result) => {
				ownedBots = result.bots;
				loadingBots = false;
				const pairs = await Promise.all(
					result.bots.map(async (bot) => {
						try {
							return { botId: bot.botId, status: await fetchBotConfigStatus(bot.botId) };
						} catch (err) {
							return {
								botId: bot.botId,
								error: err instanceof Error ? err.message : 'config status unavailable'
							};
						}
					})
				);
				const nextConfig: Record<string, BotConfigStatus> = {};
				const nextErrors: Record<string, string> = {};
				for (const pair of pairs) {
					if (pair.status) nextConfig[pair.botId] = pair.status;
					if (pair.error) nextErrors[pair.botId] = pair.error;
				}
				configByBotId = nextConfig;
				configErrorByBotId = nextErrors;
			},
			(err: Error) => {
				ownedBots = [];
				loadingBots = false;
				botError = err.message || 'owned bot lookup failed';
			}
		);
	});

	function ownerFor(bot: ArenaBot): ArenaBotOwner | undefined {
		const currentSession = session;
		if (currentSession === 'loading' || currentSession === null) return bot.owners?.[0];
		return bot.owners?.find((item) => item.reefUserId === currentSession.reefUserId) ?? bot.owners?.[0];
	}

	function openConfigEditor(botId: string) {
		selectedConfigBotId = botId;
		const draft = configDraftByBotId[botId] ?? draftFromConfigStatus(configByBotId[botId]);
		configDraftByBotId = { ...configDraftByBotId, [botId]: draft };
		configBaselineByBotId = { ...configBaselineByBotId, [botId]: draft };
		configSaveErrorByBotId = { ...configSaveErrorByBotId, [botId]: '' };
		configNoticeByBotId = { ...configNoticeByBotId, [botId]: '' };
	}

	function closeConfigEditor() {
		selectedConfigBotId = '';
		confirmAction = null;
	}

	function requestCloseConfigEditor() {
		if (selectedConfigBotId && configDraftIsDirty(selectedConfigBotId)) {
			confirmAction = { kind: 'close', botId: selectedConfigBotId };
			return;
		}
		closeConfigEditor();
	}

	function updateConfigDraft(botId: string, value: string) {
		configDraftByBotId = { ...configDraftByBotId, [botId]: value };
	}

	function draftFromConfigStatus(status: BotConfigStatus | undefined): string {
		if (status?.config && typeof status.config === 'object' && !Array.isArray(status.config)) {
			return JSON.stringify(status.config, null, 2);
		}
		if (!status?.keys?.length) return '{\n}';
		return JSON.stringify(Object.fromEntries(status.keys.map((key) => [key, ''])), null, 2);
	}

	function handleKeydown(event: KeyboardEvent) {
		if (event.key === 'Escape' && selectedConfigBotId) {
			requestCloseConfigEditor();
		}
	}

	function canWriteBotConfig(): boolean {
		const currentSession = session;
		if (currentSession === 'loading' || currentSession === null) return false;
		const trustState = (currentSession.trustState ?? '').toLowerCase();
		return trustState !== 'limited' && trustState !== 'banned';
	}

	function configDraftIsDirty(botId: string): boolean {
		return (configDraftByBotId[botId] ?? '{\n}') !== (configBaselineByBotId[botId] ?? '{\n}');
	}

	async function copyText(key: string, value: string) {
		if (!value || value === 'unavailable') return;
		try {
			await navigator.clipboard.writeText(value);
			copyNoticeByKey = { ...copyNoticeByKey, [key]: 'copied' };
		} catch {
			copyNoticeByKey = { ...copyNoticeByKey, [key]: 'copy failed' };
		}
	}

	async function refreshConfigStatus(botId: string) {
		configBusyByBotId = { ...configBusyByBotId, [botId]: true };
		configErrorByBotId = { ...configErrorByBotId, [botId]: '' };
		try {
			const status = await fetchBotConfigStatus(botId);
			configByBotId = { ...configByBotId, [botId]: status };
		} catch (err) {
			configErrorByBotId = {
				...configErrorByBotId,
				[botId]: err instanceof Error ? err.message : 'config status unavailable'
			};
		} finally {
			configBusyByBotId = { ...configBusyByBotId, [botId]: false };
		}
	}

	async function saveBotConfig(botId: string) {
		if (!canWriteBotConfig()) {
			configSaveErrorByBotId = { ...configSaveErrorByBotId, [botId]: 'config writes are disabled for this trust state' };
			return;
		}
		configBusyByBotId = { ...configBusyByBotId, [botId]: true };
		configSaveErrorByBotId = { ...configSaveErrorByBotId, [botId]: '' };
		configNoticeByBotId = { ...configNoticeByBotId, [botId]: '' };
		try {
			const parsed = parseConfigDraft(botId);
			if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
				throw new Error('config must be a JSON object');
			}
			const status = await replaceBotConfig(botId, parsed);
			configByBotId = { ...configByBotId, [botId]: status };
			configErrorByBotId = { ...configErrorByBotId, [botId]: '' };
			configDraftByBotId = { ...configDraftByBotId, [botId]: draftFromConfigStatus(status) };
			configBaselineByBotId = { ...configBaselineByBotId, [botId]: draftFromConfigStatus(status) };
			configNoticeByBotId = {
				...configNoticeByBotId,
				[botId]: 'config saved'
			};
		} catch (err) {
			configSaveErrorByBotId = {
				...configSaveErrorByBotId,
				[botId]: err instanceof Error ? err.message : 'config save failed'
			};
		} finally {
			configBusyByBotId = { ...configBusyByBotId, [botId]: false };
		}
	}

	function parseConfigDraft(botId: string): Record<string, unknown> {
		try {
			const parsed = JSON.parse(configDraftByBotId[botId] || '{}');
			if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
				throw new Error('config must be a JSON object');
			}
			return parsed as Record<string, unknown>;
		} catch (err) {
			if (err instanceof SyntaxError) {
				throw new Error(`invalid JSON: ${err.message}`);
			}
			throw err;
		}
	}

	function requestClearBotConfig(botId: string) {
		if (!canWriteBotConfig()) {
			configSaveErrorByBotId = { ...configSaveErrorByBotId, [botId]: 'config clears are disabled for this trust state' };
			return;
		}
		confirmAction = { kind: 'clear', botId };
	}

	async function clearBotConfig(botId: string) {
		confirmAction = null;
		configBusyByBotId = { ...configBusyByBotId, [botId]: true };
		configSaveErrorByBotId = { ...configSaveErrorByBotId, [botId]: '' };
		configNoticeByBotId = { ...configNoticeByBotId, [botId]: '' };
		try {
			const status = await deleteBotConfig(botId);
			configByBotId = { ...configByBotId, [botId]: status };
			configErrorByBotId = { ...configErrorByBotId, [botId]: '' };
			configDraftByBotId = { ...configDraftByBotId, [botId]: draftFromConfigStatus(status) };
			configBaselineByBotId = { ...configBaselineByBotId, [botId]: draftFromConfigStatus(status) };
			configNoticeByBotId = { ...configNoticeByBotId, [botId]: 'config cleared' };
		} catch (err) {
			configSaveErrorByBotId = {
				...configSaveErrorByBotId,
				[botId]: err instanceof Error ? err.message : 'config clear failed'
			};
		} finally {
			configBusyByBotId = { ...configBusyByBotId, [botId]: false };
		}
	}

	function confirmPendingAction() {
		const action = confirmAction;
		if (!action) return;
		if (action.kind === 'close') {
			closeConfigEditor();
			return;
		}
		void clearBotConfig(action.botId);
	}
</script>

<svelte:window onkeydown={handleKeydown} />

<svelte:head>
	<title>My Bots — Bot Arena</title>
	<meta
		name="description"
		content="Bot Arena participant administration for owned bots, runtime config, and submitted bot status."
	/>
</svelte:head>

{#if session === 'loading'}
	<StateMessage variant="loading" message="checking session…" />
{:else if session === null}
	<div class="flex flex-col items-start gap-4">
		<h1 class="text-3xl font-normal tracking-[-0.03em] lowercase">my bots sign-in required</h1>
		<p class="max-w-[58ch] text-muted">
			Sign in with the GitHub account you use for Bot Arena submissions to manage bot-owned
			control-plane data.
		</p>
		<Button href={githubLoginUrl('/bot-admin')}>sign in with github</Button>
	</div>
{:else if !hasBotAdminAccess(session)}
	<div class="flex flex-col items-start gap-4">
		<h1 class="text-3xl font-normal tracking-[-0.03em] lowercase">bot role required</h1>
		<p class="max-w-[58ch] text-muted">
			You are signed in as <span class="text-ink">{session.githubLogin}</span>, but this surface requires a
			Bot Arena participant, reviewer, or operator role.
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
	{@const activeSession = session as SessionUser}
	<PageHeader title="bot admin" class="pb-4">
		{#snippet meta()}
			<p class="text-sm text-muted">
				signed in as <span class="text-ink">{activeSession.githubLogin}</span>
			</p>
			<p class="text-xs text-muted">{activeSession.roles.join(', ')}</p>
		{/snippet}
	</PageHeader>

	<div class="grid gap-6">
		<Card>
			<div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
				<div>
					<h2 class="text-xl font-normal lowercase">account</h2>
					<p class="mt-2 max-w-[58ch] text-sm text-muted">
						Your Reef user, GitHub identity, trust state, and role set decide which Bot Arena
						controls are shown.
					</p>
				</div>
				{#if hasOperatorAccess(activeSession)}
					<Button variant="secondary" href="/admin">open game admin</Button>
				{/if}
			</div>

			<dl class="mt-5 grid gap-x-6 gap-y-4 text-sm sm:grid-cols-2">
				<div class="min-w-0">
					<dt class="text-xs font-bold uppercase tracking-normal text-muted">reef user</dt>
					<dd class="mt-1 break-all text-ink">{session.reefUserId}</dd>
				</div>
				<div class="min-w-0">
					<dt class="text-xs font-bold uppercase tracking-normal text-muted">github</dt>
					<dd class="mt-1 break-all text-ink">{session.githubLogin}</dd>
				</div>
				<div>
					<dt class="text-xs font-bold uppercase tracking-normal text-muted">trust</dt>
					<dd class="mt-1 text-ink">{session.trustState || 'unknown'}</dd>
				</div>
				<div class="min-w-0">
					<dt class="text-xs font-bold uppercase tracking-normal text-muted">roles</dt>
					<dd class="mt-1 break-words text-ink">{displayRoles(session.roles)}</dd>
				</div>
			</dl>
		</Card>

		<Card>
			<div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
				<div>
					<h2 class="text-xl font-normal lowercase">owned bots</h2>
					<p class="mt-2 max-w-[62ch] text-sm text-muted">
						Only bots linked to your session identity are shown here. This page uses the
						owner-scoped arena route, not the all-bots operator roster.
					</p>
				</div>
				<Badge variant="muted">{filteredOwnedBots.length} shown / {ownedBots.length} owned</Badge>
			</div>

			<div class="mt-5 grid gap-3 border-t border-rule pt-4 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-center">
				<TextInput
					type="search"
					placeholder="filter by bot, source, or publisher"
					aria-label="Filter owned bots"
					value={botSearch}
					oninput={(event) => (botSearch = event.currentTarget.value)}
				/>
				<div class="flex flex-wrap gap-2">
					<SegmentButton selected={botFilter === 'all'} onclick={() => (botFilter = 'all')}>all</SegmentButton>
					<SegmentButton selected={botFilter === 'attention'} onclick={() => (botFilter = 'attention')}>
						attention {attentionBots.length}
					</SegmentButton>
					<SegmentButton selected={botFilter === 'configured'} onclick={() => (botFilter = 'configured')}>
						configured
					</SegmentButton>
					<SegmentButton selected={botFilter === 'empty'} onclick={() => (botFilter = 'empty')}>empty</SegmentButton>
				</div>
			</div>

			{#if loadingBots}
				<StateMessage variant="loading" message="loading owned bots…" />
			{:else if botError}
				<StateMessage variant="error" title="owned bots unavailable" message={botError} />
			{:else if ownedBots.length === 0}
				<StateMessage variant="empty" message="no bots are linked to this account yet." />
			{:else if filteredOwnedBots.length === 0}
				<StateMessage variant="empty" message="no bots match the current filters." />
			{:else}
				<ul class="mt-5 divide-y divide-rule border-t border-rule">
					{#each filteredOwnedBots as bot (bot.botId)}
						{@const owner = ownerFor(bot)}
						{@const configStatus = configByBotId[bot.botId]}
						{@const configError = configErrorByBotId[bot.botId]}
						{@const configBusy = configBusyByBotId[bot.botId]}
						<li class="grid gap-4 py-5 lg:grid-cols-[minmax(0,1fr)_180px]">
							<div class="min-w-0">
								<div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
									<h3 class="min-w-0 break-words text-lg font-bold leading-tight text-ink">
										{bot.metadata.name}
									</h3>
									<div class="flex shrink-0 flex-wrap gap-1.5">
										<Badge variant="accent">{owner?.ownershipState ?? 'linked'}</Badge>
										<Badge variant={configStatus?.hasConfig ? 'success' : configError ? 'danger' : 'muted'}>
											{configStatus?.hasConfig ? 'configured' : configError ? 'config unavailable' : 'empty config'}
										</Badge>
									</div>
								</div>

								<dl class="mt-3 grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
									<div class="min-w-0">
										<dt class="text-xs font-bold uppercase tracking-normal text-muted">bot id</dt>
										<dd class="mt-1 flex min-w-0 flex-wrap items-center gap-2 text-muted">
											<span class="break-all">{bot.botId}</span>
											<button
												class="border border-rule px-2 py-0.5 text-xs text-ink transition-colors hover:border-accent-hover hover:bg-accent-hover hover:text-accent-ink"
												type="button"
												onclick={() => copyText(`bot:${bot.botId}`, bot.botId)}
											>
												copy
											</button>
											{#if copyNoticeByKey[`bot:${bot.botId}`]}
												<span class="text-xs text-muted">{copyNoticeByKey[`bot:${bot.botId}`]}</span>
											{/if}
										</dd>
									</div>
									<div class="min-w-0">
										<dt class="text-xs font-bold uppercase tracking-normal text-muted">source</dt>
										<dd class="mt-1 truncate text-muted" title={bot.fileName}>{bot.fileName}</dd>
									</div>
									<div class="min-w-0">
										<dt class="text-xs font-bold uppercase tracking-normal text-muted">publisher</dt>
										<dd class="mt-1 truncate text-muted">{bot.metadata.publisher}</dd>
									</div>
									<div class="min-w-0">
										<dt class="text-xs font-bold uppercase tracking-normal text-muted">contact</dt>
										<dd class="mt-1 truncate text-muted" title={bot.metadata.email}>{bot.metadata.email}</dd>
									</div>
								</dl>

								{#if configStatus?.keys?.length}
									<div class="mt-4 flex flex-wrap gap-1.5">
										{#each configStatus.keys as key}
											<span class="max-w-full truncate border border-rule px-2 py-0.5 text-xs text-muted">{key}</span>
										{/each}
									</div>
								{:else if configError}
									<p class="mt-4 border-l border-rule pl-3 text-xs text-muted">
										config status unavailable: {configError}
									</p>
								{/if}
							</div>

							<dl class="grid gap-3 text-xs text-muted lg:text-right">
								<div>
									<dt class="font-bold uppercase tracking-normal">created</dt>
									<dd class="mt-1 text-ink">{new Date(bot.createdAt).toLocaleString()}</dd>
								</div>
								{#if configStatus?.updatedAt}
									<div>
										<dt class="font-bold uppercase tracking-normal">config updated</dt>
										<dd class="mt-1 text-ink">{new Date(configStatus.updatedAt).toLocaleString()}</dd>
									</div>
								{/if}
							</dl>

							<ActionBar label="bot actions" class="lg:col-span-2">
								<Button
									variant="secondary"
									size="sm"
									disabled={configBusy}
									onclick={() => refreshConfigStatus(bot.botId)}
								>
									refresh config
								</Button>
								<Button
									size="sm"
									disabled={configBusy || !canWriteBotConfig()}
									onclick={() => openConfigEditor(bot.botId)}
								>
									edit config
								</Button>
							</ActionBar>
						</li>
					{/each}
				</ul>
			{/if}
		</Card>
	</div>
{/if}

{#if selectedConfigBotId}
	{@const selectedBot = ownedBots.find((bot) => bot.botId === selectedConfigBotId)}
	{#if selectedBot}
		{@const selectedOwner = ownerFor(selectedBot)}
		{@const selectedStatus = configByBotId[selectedBot.botId]}
		{@const selectedBusy = configBusyByBotId[selectedBot.botId]}
		{@const selectedError = configErrorByBotId[selectedBot.botId]}
		{@const selectedSaveError = configSaveErrorByBotId[selectedBot.botId]}
		{@const selectedNotice = configNoticeByBotId[selectedBot.botId]}
		{@const selectedSecretPath = selectedStatus?.secretPath ?? 'unavailable'}
		{@const writeAllowed = canWriteBotConfig()}
		<div class="fixed inset-0 z-50">
			<button
				class="absolute inset-0 h-full w-full cursor-default bg-black/70"
				type="button"
				aria-label="Close config editor"
				onclick={requestCloseConfigEditor}
			></button>
			<div class="pointer-events-none absolute inset-0 flex items-center justify-center p-3 sm:p-4">
				<div
					class="pointer-events-auto relative grid h-[min(720px,calc(100dvh-2rem))] w-full max-w-5xl overflow-hidden border border-rule-strong bg-bg shadow-2xl md:grid-cols-[320px_minmax(0,1fr)]"
					role="dialog"
					aria-modal="true"
					aria-labelledby="bot-config-title"
				>
					<button
						class="absolute right-4 top-4 z-10 inline-flex h-9 w-9 shrink-0 items-center justify-center border border-rule-strong bg-bg text-lg leading-none text-muted transition-colors hover:border-accent-hover hover:text-ink"
						type="button"
						aria-label="Close config editor"
						onclick={requestCloseConfigEditor}
					>
						×
					</button>
					<aside class="flex min-h-0 flex-col overflow-y-auto border-b border-rule bg-[#0d1422] p-4 md:border-b-0 md:border-r md:p-5">
						<div class="min-h-0 flex-1">
							<div class="pr-12">
								<p class="text-xs text-muted">runtime config</p>
								<h2 id="bot-config-title" class="mt-2 break-words text-2xl font-bold text-ink">
									{selectedBot.metadata.name}
								</h2>
							</div>

							<div class="mt-5 flex flex-wrap gap-2">
								<Badge variant="muted">{selectedOwner?.ownershipState ?? 'linked'}</Badge>
								<Badge variant={selectedStatus?.hasConfig ? 'success' : 'muted'}>
									{selectedBusy && !selectedStatus ? 'loading' : selectedStatus?.hasConfig ? 'configured' : 'empty'}
								</Badge>
							</div>

							<dl class="mt-6 space-y-4 text-sm">
								<div>
									<dt class="text-xs text-muted">bot id</dt>
									<dd class="mt-1 flex min-w-0 flex-wrap items-center gap-2 text-ink">
										<span class="break-all">{selectedBot.botId}</span>
										<button
											class="border border-rule px-2 py-0.5 text-xs text-ink transition-colors hover:border-accent-hover hover:bg-accent-hover hover:text-accent-ink"
											type="button"
											onclick={() => copyText(`modal-bot:${selectedBot.botId}`, selectedBot.botId)}
										>
											copy
										</button>
										{#if copyNoticeByKey[`modal-bot:${selectedBot.botId}`]}
											<span class="text-xs text-muted">{copyNoticeByKey[`modal-bot:${selectedBot.botId}`]}</span>
										{/if}
									</dd>
								</div>
								<div>
									<dt class="text-xs text-muted">source</dt>
									<dd class="mt-1 break-all text-ink">{selectedBot.fileName}</dd>
								</div>
								<div>
									<dt class="text-xs text-muted">owner</dt>
									<dd class="mt-1 text-ink">{selectedOwner?.githubLogin ?? selectedBot.metadata.publisher}</dd>
									{#if selectedBot.metadata.email}
										<dd class="mt-1 break-all text-muted">{selectedBot.metadata.email}</dd>
									{/if}
								</div>
								<div>
									<dt class="text-xs text-muted">secret path</dt>
									<dd class="mt-1 flex min-w-0 flex-wrap items-center gap-2 text-ink">
										<span class="break-all">{selectedSecretPath}</span>
										<button
											class="border border-rule px-2 py-0.5 text-xs text-ink transition-colors hover:border-accent-hover hover:bg-accent-hover hover:text-accent-ink disabled:pointer-events-none disabled:opacity-50"
											type="button"
											disabled={selectedSecretPath === 'unavailable'}
											onclick={() => copyText(`secret:${selectedBot.botId}`, selectedSecretPath)}
										>
											copy
										</button>
										{#if copyNoticeByKey[`secret:${selectedBot.botId}`]}
											<span class="text-xs text-muted">{copyNoticeByKey[`secret:${selectedBot.botId}`]}</span>
										{/if}
									</dd>
								</div>
							</dl>

							{#if selectedStatus?.keys?.length}
								<div class="mt-6">
									<p class="text-xs text-muted">stored keys</p>
									<div class="mt-2 flex flex-wrap gap-1.5">
										{#each selectedStatus.keys as key}
											<span class="max-w-full truncate border border-rule px-2 py-0.5 text-xs text-muted">{key}</span>
										{/each}
									</div>
								</div>
							{/if}

							{#if selectedError}
								<div class="mt-6 border border-destructive/60 bg-destructive/10 p-3 text-sm text-destructive">
									{selectedError}
								</div>
							{/if}
						</div>

						<div class="mt-6 shrink-0">
							<Button
								variant="secondary"
								size="sm"
								disabled={selectedBusy}
								onclick={() => refreshConfigStatus(selectedBot.botId)}
							>
								refresh
							</Button>
						</div>
					</aside>

					<div class="flex min-h-0 flex-col overflow-hidden p-4 pt-14 md:p-5 md:pt-5">
						<div class="shrink-0 border-b border-rule pb-4">
							<h3 class="text-xl font-normal text-ink">json</h3>
							<p class="mt-1 max-w-[58ch] text-sm text-muted">
								saved values reload for authorized bot owners and operators.
							</p>
							{#if !writeAllowed}
								<p class="mt-2 border-l border-destructive pl-3 text-sm text-destructive">
									config writes are disabled for this trust state.
								</p>
							{/if}
						</div>

						<div class="min-h-0 flex-1 overflow-y-auto py-4 pr-1">
							<label class="block text-xs font-bold uppercase tracking-normal text-muted" for="bot-config-json">
								config object
							</label>
							<textarea
								id="bot-config-json"
								class="mt-2 h-[clamp(220px,46dvh,420px)] min-h-[220px] w-full resize-y border border-rule bg-[#070b13] p-4 text-sm leading-relaxed text-ink outline-none focus:border-accent-hover"
								spellcheck="false"
								disabled={selectedBusy}
								aria-describedby="bot-config-json-help"
								value={configDraftByBotId[selectedBot.botId] ?? '{\n}'}
								oninput={(event) =>
									updateConfigDraft(selectedBot.botId, (event.currentTarget as HTMLTextAreaElement).value)}
							></textarea>

							{#if selectedSaveError}
								<p class="mt-3 border-l border-destructive pl-3 text-sm text-destructive">{selectedSaveError}</p>
							{:else if selectedNotice}
								<p class="mt-3 border-l border-accent pl-3 text-sm text-muted">{selectedNotice}</p>
							{/if}
							<p id="bot-config-json-help" class="mt-3 text-xs text-muted">
								Only object-shaped JSON is accepted. Save replaces the whole stored object.
							</p>
						</div>

						<div class="shrink-0 border-t border-rule bg-bg pt-4">
							<div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
								<p class="text-xs text-muted">save replaces the whole object.</p>
								<div class="flex flex-wrap gap-2 sm:justify-end">
									<Button
										variant="secondary"
										size="sm"
										disabled={selectedBusy || !writeAllowed}
										onclick={() => requestClearBotConfig(selectedBot.botId)}
									>
										clear
									</Button>
									<Button
										variant="secondary"
										size="sm"
										disabled={selectedBusy}
										onclick={requestCloseConfigEditor}
									>
										cancel
									</Button>
									<Button
										size="sm"
										disabled={selectedBusy || !writeAllowed}
										onclick={() => saveBotConfig(selectedBot.botId)}
									>
										{selectedBusy ? 'saving' : 'save'}
									</Button>
								</div>
							</div>
						</div>
					</div>
				</div>
			</div>
		</div>
		{#if confirmAction?.botId === selectedBot.botId}
			<div class="fixed inset-0 z-[60]">
				<div class="absolute inset-0 bg-black/75"></div>
				<div class="pointer-events-none absolute inset-0 flex items-center justify-center p-4">
					<div
						class="pointer-events-auto w-full max-w-sm border border-rule-strong bg-bg p-5 shadow-2xl"
						role="alertdialog"
						aria-modal="true"
						aria-labelledby="confirm-config-action-title"
					>
						<h2 id="confirm-config-action-title" class="text-xl font-bold text-ink">
							{confirmAction.kind === 'clear' ? 'clear config?' : 'discard changes?'}
						</h2>
						<p class="mt-3 text-sm text-muted">
							{confirmAction.kind === 'clear'
								? 'Stored config metadata remains visible, but the current config object will be removed.'
								: 'Unsaved JSON changes in this editor will be lost.'}
						</p>
						<div class="mt-5 flex flex-wrap justify-end gap-2">
							<Button
								variant="secondary"
								class="min-h-9 px-3 py-2 text-xs"
								onclick={() => (confirmAction = null)}
							>
								cancel
							</Button>
							<Button class="min-h-9 px-3 py-2 text-xs" onclick={confirmPendingAction}>
								{confirmAction.kind === 'clear' ? 'clear config' : 'discard'}
							</Button>
						</div>
					</div>
				</div>
			</div>
		{/if}
	{/if}
{/if}
