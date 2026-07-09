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

// Requires an X-Client-Id header per the venue-intake read boundary
// (ExternalApiBoundary.checkRead) — public/unauthenticated, but still
// client-identified for rate limiting. Returns [] on any failure so the
// page renders an empty state rather than an error.
export async function fetchLeaderboard(
	modeId: string,
	scoringPolicyVersion: string
): Promise<LeaderboardEntry[]> {
	try {
		const params = new URLSearchParams({ modeId, scoringPolicyVersion });
		const res = await fetch(`${PUBLIC_ARENA_API_BASE_URL}/api/v1/arena/leaderboard?${params}`, {
			headers: { 'X-Client-Id': 'arena-admin-web' }
		});
		if (!res.ok) return [];
		const body = (await res.json()) as { entries: LeaderboardEntry[] };
		return body.entries;
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
