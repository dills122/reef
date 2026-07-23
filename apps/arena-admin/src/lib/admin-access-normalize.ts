export function normalizeAdminAccessCollections<
	T extends { roles?: unknown; botOwnerships?: unknown }
>(user: T): T & { roles: unknown[]; botOwnerships: unknown[] } {
	return {
		...user,
		roles: Array.isArray(user.roles) ? user.roles : [],
		botOwnerships: Array.isArray(user.botOwnerships) ? user.botOwnerships : []
	};
}
