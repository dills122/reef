import { PUBLIC_ARENA_API_BASE_URL } from '$env/static/public';

export type LeaderboardEntry = {
	rank: number;
	botName: string;
	ownerHandle: string;
	finalEquity: number;
	realizedPnl: number;
	maxDrawdown: number;
};

export type SessionUser = {
	githubLogin: string;
	roles: string[];
};

// Placeholder public route until /api/v1/arena/leaderboard ships per D-052 —
// ArenaControlPlaneService.leaderboard exists server-side but is currently
// internal-only. Returns [] on any failure so the page renders an empty state.
export async function fetchLeaderboard(modeId: string): Promise<LeaderboardEntry[]> {
	try {
		const res = await fetch(
			`${PUBLIC_ARENA_API_BASE_URL}/api/v1/arena/leaderboard?modeId=${encodeURIComponent(modeId)}`
		);
		if (!res.ok) return [];
		return (await res.json()) as LeaderboardEntry[];
	} catch {
		return [];
	}
}

export async function fetchSession(): Promise<SessionUser | null> {
	try {
		const res = await fetch(`${PUBLIC_ARENA_API_BASE_URL}/admin/auth/session`, {
			credentials: 'include'
		});
		if (!res.ok) return null;
		return (await res.json()) as SessionUser;
	} catch {
		return null;
	}
}

export function githubLoginUrl(redirectPath: string): string {
	return `${PUBLIC_ARENA_API_BASE_URL}/admin/auth/github/start?redirectPath=${encodeURIComponent(redirectPath)}`;
}
