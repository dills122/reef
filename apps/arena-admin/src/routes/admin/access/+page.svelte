<script lang="ts">
	import {
		assignAdminAccessRole,
		fetchAdminAccessRoles,
		fetchAdminAccessUsers,
		revokeAdminAccessRole,
		updateAdminUserTrustState,
		type AdminAccessRole,
		type AdminAccessUser
	} from '$lib/api';
	import Button from '$lib/components/ui/Button.svelte';
	import Card from '$lib/components/ui/Card.svelte';

	const trustStates = ['new', 'trusted', 'limited', 'banned'];

	let users = $state<AdminAccessUser[]>([]);
	let roles = $state<AdminAccessRole[]>([]);
	let loading = $state(true);
	let error = $state('');
	let notice = $state('');
	let selectedTrust = $state<Record<string, string>>({});
	let selectedRole = $state<Record<string, string>>({});
	let reasons = $state<Record<string, string>>({});
	let busyByUser = $state<Record<string, boolean>>({});

	const roleOptions = $derived(roles.filter((role) => role.roleId !== 'participant'));
	const roleDescriptions = $derived(Object.fromEntries(roles.map((role) => [role.roleId, role.description])));
	const trustedCount = $derived(users.filter((user) => user.trustState === 'trusted').length);
	const operatorCount = $derived(
		users.filter((user) => user.roles.some((role) => role.roleId === 'operator')).length
	);

	$effect(() => {
		loadAccessData();
	});

	async function loadAccessData(showSpinner = true) {
		if (showSpinner) loading = true;
		error = '';
		try {
			const [userList, roleList] = await Promise.all([
				fetchAdminAccessUsers(),
				fetchAdminAccessRoles()
			]);
			users = userList;
			roles = roleList;
			selectedTrust = {
				...Object.fromEntries(userList.map((user) => [user.reefUserId, user.trustState])),
				...selectedTrust
			};
			const defaultRole = roleList.find((role) => role.roleId === 'reviewer')?.roleId ?? roleList[0]?.roleId ?? '';
			selectedRole = {
				...Object.fromEntries(userList.map((user) => [user.reefUserId, defaultRole])),
				...selectedRole
			};
		} catch (err) {
			error = err instanceof Error ? err.message : 'access data load failed';
		} finally {
			loading = false;
		}
	}

	async function updateTrust(user: AdminAccessUser) {
		const trustState = selectedTrust[user.reefUserId] ?? user.trustState;
		const reason = requiredReason(user.reefUserId);
		if (!reason) return;
		await mutate(user.reefUserId, async () => {
			await updateAdminUserTrustState(user.reefUserId, trustState, reason);
			notice = `${user.githubLogin} trust set to ${trustState}`;
		});
	}

	async function assignRole(user: AdminAccessUser) {
		const roleId = selectedRole[user.reefUserId] ?? '';
		const reason = requiredReason(user.reefUserId);
		if (!roleId || !reason) return;
		await mutate(user.reefUserId, async () => {
			await assignAdminAccessRole(user.reefUserId, roleId, reason);
			notice = `${roleId} assigned to ${user.githubLogin}`;
		});
	}

	async function revokeRole(user: AdminAccessUser, roleId: string) {
		const reason = requiredReason(user.reefUserId);
		if (!reason) return;
		await mutate(user.reefUserId, async () => {
			await revokeAdminAccessRole(user.reefUserId, roleId, reason);
			notice = `${roleId} revoked from ${user.githubLogin}`;
		});
	}

	async function mutate(reefUserId: string, action: () => Promise<void>) {
		busyByUser = { ...busyByUser, [reefUserId]: true };
		error = '';
		notice = '';
		try {
			await action();
			reasons = { ...reasons, [reefUserId]: '' };
			await loadAccessData(false);
		} catch (err) {
			error = err instanceof Error ? err.message : 'access update failed';
		} finally {
			busyByUser = { ...busyByUser, [reefUserId]: false };
		}
	}

	function requiredReason(reefUserId: string): string {
		const reason = (reasons[reefUserId] ?? '').trim();
		if (!reason) {
			error = 'reason is required for access changes';
			return '';
		}
		return reason;
	}

	function formatTimestamp(value?: string | null): string {
		if (!value) return 'none';
		return new Date(value).toLocaleString();
	}
</script>

<svelte:head>
	<title>Access Admin - Bot Arena</title>
</svelte:head>

<div class="mb-6 flex flex-col gap-3 border-b border-rule pb-5 sm:flex-row sm:items-end sm:justify-between">
	<div>
		<h1 class="text-3xl font-normal tracking-normal lowercase">access</h1>
		<p class="mt-2 text-sm text-muted">{users.length} admin users</p>
	</div>
	<div class="flex flex-wrap gap-2">
		<Button variant="secondary" href="/admin">runs</Button>
		<Button variant="secondary" href="/bot-admin">my bots</Button>
	</div>
</div>

{#if loading}
	<p class="border-t border-rule py-6 text-center text-muted">loading...</p>
{:else if error && users.length === 0}
	<div class="border-t border-destructive py-6">
		<p class="font-bold text-destructive">access admin unavailable</p>
		<p class="mt-1 text-sm text-muted">{error}</p>
	</div>
{:else}
	{#if error}
		<p class="mb-4 border-l-4 border-destructive pl-3 text-sm text-destructive">{error}</p>
	{/if}
	{#if notice}
		<p class="mb-4 border-l-4 border-accent pl-3 text-sm text-muted">{notice}</p>
	{/if}

	<div class="mb-6 grid gap-3 sm:grid-cols-3">
		<div class="border border-rule p-3">
			<p class="text-xs font-bold uppercase tracking-normal text-muted">trusted</p>
			<p class="mt-2 text-2xl text-ink">{trustedCount}</p>
		</div>
		<div class="border border-rule p-3">
			<p class="text-xs font-bold uppercase tracking-normal text-muted">operators</p>
			<p class="mt-2 text-2xl text-ink">{operatorCount}</p>
		</div>
		<div class="border border-rule p-3">
			<p class="text-xs font-bold uppercase tracking-normal text-muted">roles</p>
			<p class="mt-2 text-2xl text-ink">{roles.length}</p>
		</div>
	</div>

	<Card>
		<div class="mb-4 flex items-baseline justify-between gap-4">
			<h2 class="text-xl font-normal lowercase">users</h2>
			<Button variant="secondary" onclick={() => loadAccessData()}>refresh</Button>
		</div>

		{#if users.length === 0}
			<p class="border-t border-rule py-5 text-center text-muted">no admin users recorded.</p>
		{:else}
			<ul class="divide-y divide-rule border-t border-rule">
				{#each users as user (user.reefUserId)}
					{@const userRoleIds = user.roles.map((role) => role.roleId)}
					{@const busy = busyByUser[user.reefUserId]}
					<li class="grid gap-5 py-5 lg:grid-cols-[minmax(0,1fr)_260px]">
						<div class="min-w-0">
							<div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
								<div class="min-w-0">
									<h3 class="break-all text-lg font-bold leading-tight text-ink">{user.githubLogin}</h3>
									<p class="mt-1 break-all text-sm text-muted">{user.reefUserId}</p>
								</div>
								<span class="w-fit border border-rule-strong px-2 py-0.5 text-xs text-muted">
									{user.trustState}
								</span>
							</div>

							<dl class="mt-4 grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
								<div>
									<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">display</dt>
									<dd class="mt-1 text-muted">{user.displayName || 'none'}</dd>
								</div>
								<div>
									<dt class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">last seen</dt>
									<dd class="mt-1 text-muted">{formatTimestamp(user.lastSeenAt)}</dd>
								</div>
							</dl>

							<div class="mt-4">
								<p class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">roles</p>
								<div class="mt-2 flex flex-wrap gap-2">
									{#each user.roles as role (role.roleId)}
										<span class="inline-flex items-center gap-2 border border-rule px-2 py-1 text-xs text-muted">
											<span>{role.roleId}</span>
											{#if role.roleId !== 'participant'}
												<button
													type="button"
													class="text-ink underline disabled:text-muted"
													disabled={busy}
													title={roleDescriptions[role.roleId] ?? role.roleId}
													onclick={() => revokeRole(user, role.roleId)}
												>
													revoke
												</button>
											{/if}
										</span>
									{/each}
								</div>
							</div>

							<div class="mt-4">
								<p class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">bots</p>
								{#if user.botOwnerships.length === 0}
									<p class="mt-2 text-sm text-muted">none</p>
								{:else}
									<ul class="mt-2 grid gap-1 text-sm text-muted">
										{#each user.botOwnerships as ownership (`${ownership.botId}-${ownership.ownershipState}`)}
											<li class="break-all">{ownership.botId} - {ownership.ownershipState}</li>
										{/each}
									</ul>
								{/if}
							</div>
						</div>

						<div class="grid h-fit gap-3 border border-rule p-3 text-sm">
							<label class="grid gap-1">
								<span class="text-xs font-bold uppercase tracking-normal text-muted">reason</span>
								<textarea
									value={reasons[user.reefUserId] ?? ''}
									class="min-h-20 border border-rule bg-bg p-2 text-ink"
									placeholder="required"
									oninput={(event) =>
										(reasons = {
											...reasons,
											[user.reefUserId]: (event.currentTarget as HTMLTextAreaElement).value
										})}
								></textarea>
							</label>

							<label class="grid gap-1">
								<span class="text-xs font-bold uppercase tracking-normal text-muted">trust</span>
								<select
									value={selectedTrust[user.reefUserId] ?? user.trustState}
									class="border border-rule bg-bg p-2 text-ink"
									onchange={(event) =>
										(selectedTrust = {
											...selectedTrust,
											[user.reefUserId]: (event.currentTarget as HTMLSelectElement).value
										})}
								>
									{#each trustStates as trustState}
										<option value={trustState}>{trustState}</option>
									{/each}
								</select>
							</label>
							<Button variant="secondary" disabled={busy} onclick={() => updateTrust(user)}>
								update trust
							</Button>

							<label class="grid gap-1">
								<span class="text-xs font-bold uppercase tracking-normal text-muted">assign role</span>
								<select
									value={selectedRole[user.reefUserId] ?? roleOptions[0]?.roleId ?? ''}
									class="border border-rule bg-bg p-2 text-ink"
									onchange={(event) =>
										(selectedRole = {
											...selectedRole,
											[user.reefUserId]: (event.currentTarget as HTMLSelectElement).value
										})}
								>
									{#each roleOptions as role (role.roleId)}
										<option value={role.roleId} disabled={userRoleIds.includes(role.roleId)}>
											{role.roleId}
										</option>
									{/each}
								</select>
							</label>
							<Button variant="secondary" disabled={busy} onclick={() => assignRole(user)}>
								assign role
							</Button>
						</div>
					</li>
				{/each}
			</ul>
		{/if}
	</Card>
{/if}
