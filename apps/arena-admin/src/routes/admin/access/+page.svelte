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

	type AccessFilter = 'review' | 'trusted' | 'operators' | 'all';

	const trustStates = [
		{ value: 'new', label: 'new', tone: 'border-rule text-muted', summary: 'signed in, not yet trusted' },
		{
			value: 'trusted',
			label: 'trusted',
			tone: 'border-accent text-ink',
			summary: 'eligible for role-gated controls'
		},
		{ value: 'limited', label: 'limited', tone: 'border-rule-strong text-muted', summary: 'kept signed in with reduced access' },
		{ value: 'banned', label: 'banned', tone: 'border-destructive text-destructive', summary: 'blocked from arena actions' }
	];

	const filters: { id: AccessFilter; label: string }[] = [
		{ id: 'review', label: 'needs review' },
		{ id: 'trusted', label: 'trusted' },
		{ id: 'operators', label: 'operators' },
		{ id: 'all', label: 'all' }
	];

	let users = $state<AdminAccessUser[]>([]);
	let roles = $state<AdminAccessRole[]>([]);
	let loading = $state(true);
	let error = $state('');
	let notice = $state('');
	let filter = $state<AccessFilter>('review');
	let selectedUserId = $state('');
	let selectedTrust = $state<Record<string, string>>({});
	let selectedRole = $state<Record<string, string>>({});
	let reasons = $state<Record<string, string>>({});
	let busyByUser = $state<Record<string, boolean>>({});

	const roleOptions = $derived(roles.filter((role) => role.roleId !== 'participant'));
	const roleDescriptions = $derived(Object.fromEntries(roles.map((role) => [role.roleId, role.description])));
	const trustedCount = $derived(users.filter((user) => user.trustState === 'trusted').length);
	const reviewCount = $derived(
		users.filter((user) => user.trustState === 'new' || user.trustState === 'limited').length
	);
	const operatorCount = $derived(users.filter((user) => hasRole(user, 'operator')).length);
	const filteredUsers = $derived(
		users.filter((user) => {
			if (filter === 'review') return user.trustState === 'new' || user.trustState === 'limited';
			if (filter === 'trusted') return user.trustState === 'trusted';
			if (filter === 'operators') return hasRole(user, 'operator') || hasRole(user, 'platform-admin');
			return true;
		})
	);
	const selectedUser = $derived(
		users.find((user) => user.reefUserId === selectedUserId) ?? filteredUsers[0] ?? users[0] ?? null
	);
	const selectedRoleIds = $derived(selectedUser ? selectedUser.roles.map((role) => role.roleId) : []);
	const selectedBusy = $derived(selectedUser ? !!busyByUser[selectedUser.reefUserId] : false);
	const selectedTrustValue = $derived(
		selectedUser ? (selectedTrust[selectedUser.reefUserId] ?? selectedUser.trustState) : ''
	);
	const selectedAssignRole = $derived(
		selectedUser ? (selectedRole[selectedUser.reefUserId] ?? defaultRoleId()) : ''
	);
	const selectedReason = $derived(selectedUser ? (reasons[selectedUser.reefUserId] ?? '') : '');

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
			selectedRole = {
				...Object.fromEntries(userList.map((user) => [user.reefUserId, defaultRoleId(roleList)])),
				...selectedRole
			};
			if (!selectedUserId || !userList.some((user) => user.reefUserId === selectedUserId)) {
				selectedUserId = preferredUser(userList)?.reefUserId ?? '';
			}
		} catch (err) {
			error = err instanceof Error ? err.message : 'access data load failed';
		} finally {
			loading = false;
		}
	}

	function preferredUser(userList: AdminAccessUser[]): AdminAccessUser | null {
		return (
			userList.find((user) => user.trustState === 'new') ??
			userList.find((user) => user.trustState === 'limited') ??
			userList[0] ??
			null
		);
	}

	function setFilter(nextFilter: AccessFilter) {
		filter = nextFilter;
		const currentStillVisible = filteredUsers.some((user) => user.reefUserId === selectedUserId);
		if (!currentStillVisible) selectedUserId = filteredUsers[0]?.reefUserId ?? users[0]?.reefUserId ?? '';
	}

	function defaultRoleId(roleList = roles): string {
		return roleList.find((role) => role.roleId === 'operator')?.roleId ?? roleList[0]?.roleId ?? '';
	}

	function hasRole(user: AdminAccessUser, roleId: string): boolean {
		return user.roles.some((role) => role.roleId === roleId);
	}

	function trustTone(trustState: string): string {
		return trustStates.find((state) => state.value === trustState)?.tone ?? 'border-rule text-muted';
	}

	function trustSummary(trustState: string): string {
		return trustStates.find((state) => state.value === trustState)?.summary ?? 'unknown trust state';
	}

	async function updateTrust(user: AdminAccessUser, trustState = selectedTrustValue) {
		const reason = requiredReason(user.reefUserId);
		if (!reason) return;
		await mutate(user.reefUserId, async () => {
			await updateAdminUserTrustState(user.reefUserId, trustState, reason);
			notice = `${user.githubLogin} trust set to ${trustState}`;
		});
	}

	async function assignRole(user: AdminAccessUser) {
		const roleId = selectedAssignRole;
		const reason = requiredReason(user.reefUserId);
		if (!roleId || !reason || selectedRoleIds.includes(roleId)) return;
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
		<p class="mt-2 text-sm text-muted">{users.length} users tracked</p>
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

	<div class="mb-6 grid gap-3 sm:grid-cols-4">
		<div class="border border-rule p-3">
			<p class="text-xs font-bold uppercase tracking-normal text-muted">review</p>
			<p class="mt-2 text-2xl text-ink">{reviewCount}</p>
		</div>
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

	<div class="mb-4 flex flex-col gap-3 border-y border-rule py-3 lg:flex-row lg:items-center lg:justify-between">
		<div class="flex flex-wrap gap-2">
			{#each filters as item (item.id)}
				<button
					type="button"
					class="min-h-10 border px-3 text-sm font-bold transition-colors"
					class:border-accent={filter === item.id}
					class:bg-accent-soft={filter === item.id}
					class:text-ink={filter === item.id}
					class:border-rule={filter !== item.id}
					class:text-muted={filter !== item.id}
					aria-pressed={filter === item.id}
					onclick={() => setFilter(item.id)}
				>
					{item.label}
				</button>
			{/each}
		</div>
		<Button variant="secondary" onclick={() => loadAccessData()}>refresh</Button>
	</div>

	{#if users.length === 0}
		<p class="border-t border-rule py-5 text-center text-muted">no admin users recorded.</p>
	{:else}
		<div class="grid gap-6 xl:grid-cols-[minmax(280px,0.95fr)_minmax(420px,1.25fr)]">
			<Card>
				<div class="mb-3 flex items-baseline justify-between gap-4">
					<h2 class="text-xl font-normal lowercase">users</h2>
					<span class="text-sm text-muted">{filteredUsers.length} shown</span>
				</div>

				<ul class="divide-y divide-rule border-t border-rule">
					{#each filteredUsers as user (user.reefUserId)}
						{@const active = selectedUser?.reefUserId === user.reefUserId}
						<li>
							<button
								type="button"
								class="grid w-full gap-2 py-4 text-left"
								onclick={() => (selectedUserId = user.reefUserId)}
							>
								<span class="flex min-w-0 items-center justify-between gap-3">
									<span class="min-w-0">
										<span class="block truncate text-base font-bold" class:text-accent={active} class:text-ink={!active}>
											{user.githubLogin}
										</span>
										<span class="mt-1 block truncate text-sm text-muted">{user.reefUserId}</span>
									</span>
									<span class={`shrink-0 border px-2 py-0.5 text-xs ${trustTone(user.trustState)}`}>
										{user.trustState}
									</span>
								</span>
								<span class="flex flex-wrap gap-1">
									{#each user.roles as role (role.roleId)}
										<span class="border border-rule px-1.5 py-0.5 text-[0.7rem] text-muted">
											{role.roleId}
										</span>
									{/each}
								</span>
							</button>
						</li>
					{/each}
				</ul>
			</Card>

			<Card>
				{#if selectedUser}
					<div class="mb-5 flex flex-col gap-3 border-b border-rule pb-4 lg:flex-row lg:items-start lg:justify-between">
						<div class="min-w-0">
							<h2 class="break-all text-2xl font-normal lowercase text-ink">{selectedUser.githubLogin}</h2>
							<p class="mt-1 break-all text-sm text-muted">{selectedUser.reefUserId}</p>
						</div>
						<span class={`w-fit border px-2 py-1 text-xs ${trustTone(selectedUser.trustState)}`}>
							{selectedUser.trustState}
						</span>
					</div>

					<div class="grid gap-x-8 gap-y-4 border-b border-rule pb-5 text-sm sm:grid-cols-2">
						<div>
							<p class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">display</p>
							<p class="mt-1 text-muted">{selectedUser.displayName || 'none'}</p>
						</div>
						<div>
							<p class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">last seen</p>
							<p class="mt-1 text-muted">{formatTimestamp(selectedUser.lastSeenAt)}</p>
						</div>
						<div>
							<p class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">created</p>
							<p class="mt-1 text-muted">{formatTimestamp(selectedUser.createdAt)}</p>
						</div>
						<div>
							<p class="text-[0.7rem] font-bold uppercase tracking-normal text-muted">bots</p>
							<p class="mt-1 text-muted">{selectedUser.botOwnerships.length}</p>
						</div>
					</div>

					<div class="grid gap-5 border-b border-rule py-5 lg:grid-cols-[1fr_1fr]">
						<section>
							<h3 class="text-lg font-normal lowercase">trust</h3>
							<p class="mt-1 text-sm text-muted">{trustSummary(selectedUser.trustState)}</p>

							<div class="mt-4 grid gap-3">
								<label class="grid gap-1">
									<span class="text-xs font-bold uppercase tracking-normal text-muted">state</span>
									<select
										value={selectedTrustValue}
										class="min-h-11 border border-rule bg-bg p-2 text-ink"
										onchange={(event) =>
											(selectedTrust = {
												...selectedTrust,
												[selectedUser.reefUserId]: (event.currentTarget as HTMLSelectElement).value
											})}
									>
										{#each trustStates as trustState}
											<option value={trustState.value}>{trustState.label}</option>
										{/each}
									</select>
								</label>
								<div class="flex flex-wrap gap-2">
									<Button
										variant="secondary"
										disabled={selectedBusy || selectedTrustValue === selectedUser.trustState}
										onclick={() => updateTrust(selectedUser)}
									>
										update trust
									</Button>
									<Button
										variant="secondary"
										disabled={selectedBusy || selectedUser.trustState === 'trusted'}
										onclick={() => updateTrust(selectedUser, 'trusted')}
									>
										trust
									</Button>
								</div>
							</div>
						</section>

						<section>
							<h3 class="text-lg font-normal lowercase">roles</h3>
							<div class="mt-2 flex flex-wrap gap-2">
								{#each selectedUser.roles as role (role.roleId)}
									<span class="inline-flex min-h-9 items-center gap-2 border border-rule px-2 text-xs text-muted">
										<span title={roleDescriptions[role.roleId] ?? role.roleId}>{role.roleId}</span>
										{#if role.roleId !== 'participant'}
											<button
												type="button"
												class="text-ink underline disabled:text-muted"
												disabled={selectedBusy}
												onclick={() => revokeRole(selectedUser, role.roleId)}
											>
												revoke
											</button>
										{/if}
									</span>
								{/each}
							</div>

							<div class="mt-4 grid gap-3">
								<label class="grid gap-1">
									<span class="text-xs font-bold uppercase tracking-normal text-muted">assign</span>
									<select
										value={selectedAssignRole}
										class="min-h-11 border border-rule bg-bg p-2 text-ink"
										onchange={(event) =>
											(selectedRole = {
												...selectedRole,
												[selectedUser.reefUserId]: (event.currentTarget as HTMLSelectElement).value
											})}
									>
										{#each roleOptions as role (role.roleId)}
											<option value={role.roleId} disabled={selectedRoleIds.includes(role.roleId)}>
												{role.roleId}
											</option>
										{/each}
									</select>
								</label>
								<Button
									variant="secondary"
									disabled={selectedBusy || !selectedAssignRole || selectedRoleIds.includes(selectedAssignRole)}
									onclick={() => assignRole(selectedUser)}
								>
									assign role
								</Button>
							</div>
						</section>
					</div>

					<label class="grid gap-1 border-b border-rule py-5">
						<span class="text-xs font-bold uppercase tracking-normal text-muted">reason</span>
						<textarea
							value={selectedReason}
							class="min-h-24 border border-rule bg-bg p-2 text-ink"
							placeholder="required"
							oninput={(event) =>
								(reasons = {
									...reasons,
									[selectedUser.reefUserId]: (event.currentTarget as HTMLTextAreaElement).value
								})}
						></textarea>
					</label>

					<section class="pt-5">
						<div class="mb-3 flex items-baseline justify-between gap-4">
							<h3 class="text-lg font-normal lowercase">owned bots</h3>
							<span class="text-sm text-muted">{selectedUser.botOwnerships.length}</span>
						</div>
						{#if selectedUser.botOwnerships.length === 0}
							<p class="border-t border-rule py-4 text-sm text-muted">none</p>
						{:else}
							<ul class="divide-y divide-rule border-t border-rule">
								{#each selectedUser.botOwnerships as ownership (`${ownership.botId}-${ownership.ownershipState}`)}
									<li class="grid gap-1 py-3 text-sm sm:grid-cols-[minmax(0,1fr)_auto]">
										<span class="break-all text-ink">{ownership.botId}</span>
										<span class="text-muted">{ownership.ownershipState}</span>
									</li>
								{/each}
							</ul>
						{/if}
					</section>
				{:else}
					<p class="border-t border-rule py-5 text-center text-muted">no user selected.</p>
				{/if}
			</Card>
		</div>
	{/if}
{/if}
