<script lang="ts">
	import {
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
	import Button from '$lib/components/ui/Button.svelte';
	import Card from '$lib/components/ui/Card.svelte';

	let session = $state<SessionUser | null | 'loading'>('loading');
	let ownedBots = $state<ArenaBot[]>([]);
	let loadingBots = $state(false);
	let botError = $state('');
	let configByBotId = $state<Record<string, BotConfigStatus>>({});
	let configErrorByBotId = $state<Record<string, string>>({});
	let selectedConfigBotId = $state('');
	let configDraftByBotId = $state<Record<string, string>>({});
	let configSaveErrorByBotId = $state<Record<string, string>>({});
	let configBusyByBotId = $state<Record<string, boolean>>({});
	let configNoticeByBotId = $state<Record<string, string>>({});

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
		selectedConfigBotId = selectedConfigBotId === botId ? '' : botId;
		if (!configDraftByBotId[botId]) {
			configDraftByBotId = { ...configDraftByBotId, [botId]: '{\n}' };
		}
		configSaveErrorByBotId = { ...configSaveErrorByBotId, [botId]: '' };
		configNoticeByBotId = { ...configNoticeByBotId, [botId]: '' };
	}

	function updateConfigDraft(botId: string, value: string) {
		configDraftByBotId = { ...configDraftByBotId, [botId]: value };
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
		configBusyByBotId = { ...configBusyByBotId, [botId]: true };
		configSaveErrorByBotId = { ...configSaveErrorByBotId, [botId]: '' };
		configNoticeByBotId = { ...configNoticeByBotId, [botId]: '' };
		try {
			const parsed = JSON.parse(configDraftByBotId[botId] || '{}');
			if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
				throw new Error('config must be a JSON object');
			}
			const status = await replaceBotConfig(botId, parsed);
			configByBotId = { ...configByBotId, [botId]: status };
			configErrorByBotId = { ...configErrorByBotId, [botId]: '' };
			configDraftByBotId = { ...configDraftByBotId, [botId]: JSON.stringify(parsed, null, 2) };
			configNoticeByBotId = { ...configNoticeByBotId, [botId]: 'config saved' };
		} catch (err) {
			configSaveErrorByBotId = {
				...configSaveErrorByBotId,
				[botId]: err instanceof Error ? err.message : 'config save failed'
			};
		} finally {
			configBusyByBotId = { ...configBusyByBotId, [botId]: false };
		}
	}

	async function clearBotConfig(botId: string) {
		if (!confirm(`Clear config for ${botId}?`)) return;
		configBusyByBotId = { ...configBusyByBotId, [botId]: true };
		configSaveErrorByBotId = { ...configSaveErrorByBotId, [botId]: '' };
		configNoticeByBotId = { ...configNoticeByBotId, [botId]: '' };
		try {
			const status = await deleteBotConfig(botId);
			configByBotId = { ...configByBotId, [botId]: status };
			configErrorByBotId = { ...configErrorByBotId, [botId]: '' };
			configDraftByBotId = { ...configDraftByBotId, [botId]: '{\n}' };
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
</script>

<svelte:head>
	<title>Bot Admin — Bot Arena</title>
	<meta
		name="description"
		content="Bot Arena participant administration for owned bots, runtime config, and submitted bot status."
	/>
</svelte:head>

{#if session === 'loading'}
	<p class="border-t border-rule py-6 text-center text-muted">checking session…</p>
{:else if session === null}
	<div class="flex flex-col items-start gap-4">
		<h1 class="text-3xl font-normal tracking-[-0.03em] lowercase">bot admin sign-in required</h1>
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
				{session.roles.length ? session.roles.join(', ') : 'none'}
			</p>
		</div>
		<Button href="/">back to arena</Button>
	</div>
{:else}
	<div class="mb-6 flex flex-col gap-2 border-b border-rule pb-4 sm:flex-row sm:items-baseline sm:justify-between">
		<div>
			<h1 class="text-3xl font-normal tracking-[-0.03em] lowercase">bot admin</h1>
			<p class="mt-2 text-sm text-muted">
				signed in as <span class="text-ink">{session.githubLogin}</span>
			</p>
		</div>
		<p class="text-xs text-muted">{session.roles.join(', ')}</p>
	</div>

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
				{#if hasOperatorAccess(session)}
					<Button variant="secondary" href="/admin">open app admin</Button>
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
					<dd class="mt-1 break-words text-ink">{session.roles.join(', ')}</dd>
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
				<span class="w-fit rounded-full border border-rule-strong px-2 py-0.5 text-xs leading-tight text-muted">
					{ownedBots.length} owned
				</span>
			</div>

			{#if loadingBots}
				<p class="mt-5 border-t border-rule py-5 text-center text-muted">loading owned bots…</p>
			{:else if botError}
				<div class="mt-5 border-t border-destructive py-5">
					<p class="font-bold text-destructive">owned bots unavailable</p>
					<p class="mt-1 text-sm text-muted">{botError}</p>
				</div>
			{:else if ownedBots.length === 0}
				<p class="mt-5 border-t border-rule py-5 text-center text-muted">
					no bots are linked to this account yet.
				</p>
			{:else}
				<ul class="mt-5 divide-y divide-rule border-t border-rule">
					{#each ownedBots as bot (bot.botId)}
						{@const owner = ownerFor(bot)}
						{@const configStatus = configByBotId[bot.botId]}
						{@const configError = configErrorByBotId[bot.botId]}
						{@const configBusy = configBusyByBotId[bot.botId]}
						{@const configSaveError = configSaveErrorByBotId[bot.botId]}
						{@const configNotice = configNoticeByBotId[bot.botId]}
						<li class="grid gap-4 py-5 lg:grid-cols-[minmax(0,1fr)_180px]">
							<div class="min-w-0">
								<div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
									<h3 class="min-w-0 break-words text-lg font-bold leading-tight text-ink">
										{bot.metadata.name}
									</h3>
									<div class="flex shrink-0 flex-wrap gap-1.5">
										<span class="rounded-full border border-accent px-2 py-0.5 text-xs leading-tight text-ink">
											{owner?.ownershipState ?? 'linked'}
										</span>
										<span class="rounded-full border border-rule-strong px-2 py-0.5 text-xs leading-tight text-muted">
											{configStatus?.hasConfig ? 'configured' : configError ? 'config unavailable' : 'empty config'}
										</span>
									</div>
								</div>

								<dl class="mt-3 grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
									<div class="min-w-0">
										<dt class="text-xs font-bold uppercase tracking-normal text-muted">bot id</dt>
										<dd class="mt-1 break-all text-muted">{bot.botId}</dd>
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

							<div class="flex flex-wrap gap-2 lg:col-span-2 lg:justify-end">
								<Button
									variant="secondary"
									class="min-h-8 px-2.5 py-1.5 text-xs"
									disabled={configBusy}
									onclick={() => refreshConfigStatus(bot.botId)}
								>
									refresh config
								</Button>
								<Button
									class="min-h-8 px-2.5 py-1.5 text-xs"
									disabled={configBusy}
									onclick={() => openConfigEditor(bot.botId)}
								>
									{selectedConfigBotId === bot.botId ? 'close config' : 'edit config'}
								</Button>
							</div>

							{#if selectedConfigBotId === bot.botId}
								<section class="border-t border-rule pt-4 lg:col-span-2" aria-labelledby={`bot-config-${bot.botId}`}>
									<div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
										<div class="min-w-0">
											<h4 id={`bot-config-${bot.botId}`} class="text-sm font-bold text-ink">runtime config</h4>
											<p class="mt-1 max-w-[62ch] text-xs text-muted">
												Values are hidden after save. Saving replaces the whole object for this bot.
											</p>
										</div>
										{#if configStatus?.secretPath}
											<p class="max-w-full break-all text-xs text-muted sm:max-w-[32ch] sm:text-right">
												{configStatus.secretPath}
											</p>
										{/if}
									</div>

									<label
										class="mt-4 block text-xs font-bold uppercase tracking-normal text-muted"
										for={`bot-config-json-${bot.botId}`}
									>
										config object
									</label>
									<textarea
										id={`bot-config-json-${bot.botId}`}
										class="mt-2 h-[clamp(180px,32dvh,280px)] max-h-[45dvh] min-h-[180px] w-full resize-y border border-rule bg-[#070b13] p-4 text-sm leading-relaxed text-ink outline-none focus:border-accent-hover"
										spellcheck="false"
										disabled={configBusy}
										value={configDraftByBotId[bot.botId] ?? '{\n}'}
										oninput={(event) =>
											updateConfigDraft(bot.botId, (event.currentTarget as HTMLTextAreaElement).value)}
									></textarea>

									{#if configSaveError}
										<p class="mt-3 border-l border-destructive pl-3 text-sm text-destructive">{configSaveError}</p>
									{:else if configNotice}
										<p class="mt-3 border-l border-accent pl-3 text-sm text-muted">{configNotice}</p>
									{:else}
										<p class="mt-3 text-xs text-muted">
											Only object-shaped JSON is accepted. Top-level keys are validated by the config service.
										</p>
									{/if}

									<div class="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
										<p class="text-xs text-muted">
											Current stored keys: {configStatus?.keys?.length ? configStatus.keys.join(', ') : 'none'}
										</p>
										<div class="flex flex-wrap gap-2 sm:justify-end">
											<Button
												variant="secondary"
												class="min-h-9 px-3 py-2 text-xs"
												disabled={configBusy}
												onclick={() => clearBotConfig(bot.botId)}
											>
												clear
											</Button>
											<Button
												class="min-h-9 px-3 py-2 text-xs"
												disabled={configBusy}
												onclick={() => saveBotConfig(bot.botId)}
											>
												{configBusy ? 'saving' : 'save'}
											</Button>
										</div>
									</div>
								</section>
							{/if}
						</li>
					{/each}
				</ul>
			{/if}
		</Card>
	</div>
{/if}
