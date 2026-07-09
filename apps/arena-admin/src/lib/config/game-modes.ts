export type GameMode = {
	id: string;
	name: string;
	description: string;
	mandatory: boolean;
	cadence: string;
	entry: string;
	scoring: string;
	details: string;
	// Backend has no "current version" concept yet — every leaderboard read
	// must name a scoringPolicyVersion explicitly. Provisional until a real
	// versioning/rollover scheme exists.
	scoringPolicyVersion: string;
};

// Source of truth for what ships to arena.game_modes (D-052). Keep this in
// sync with the backend seed until reference data is admin-CLI managed.
export const GAME_MODES: GameMode[] = [
	{
		id: 'weekly-major',
		name: 'Weekly Major',
		description: 'The flagship weekly run. All active, non-banned bots compete — no opt-out.',
		mandatory: true,
		scoringPolicyVersion: 'score-v1',
		cadence: 'Runs weekly. Auto-entered — no signup needed.',
		entry: 'Automatic for every active, non-banned bot. No opt-out.',
		scoring: 'Ranked by final equity, with max drawdown tracked as a tiebreaker and risk signal.',
		details:
			"The Weekly Major is the primary leaderboard bots are judged on. Because entry is mandatory, standings here are the closest thing to an overall bot ranking. Runs execute under the same order lifecycle, matching, and risk rules as every other session — there's no relaxed or practice mode."
	},
	{
		id: 'momentum-sprint',
		name: 'Momentum Sprint',
		description: 'Short, high-volatility sessions that reward fast directional bets. Optional.',
		mandatory: false,
		scoringPolicyVersion: 'score-v1',
		cadence: 'Runs on a shorter cycle than the Weekly Major; exact cadence still being finalized.',
		entry: 'Opt in per bot once opt-in/opt-out ships in the admin area. Not required.',
		scoring:
			'Rewards fast, correct directional calls over a compressed session window rather than steady equity growth.',
		details:
			"Momentum Sprint sessions are intentionally short and volatile — built for bots tuned for quick directional reads rather than patient market-making. Skip it if your bot's edge depends on a longer horizon; it won't affect your Weekly Major standing."
	},
	{
		id: 'liquidity-provision',
		name: 'Liquidity Provision',
		description:
			'Scored on spread capture and book depth contribution rather than directional PnL. Optional.',
		mandatory: false,
		scoringPolicyVersion: 'score-v1',
		cadence: 'Runs alongside other optional modes; exact cadence still being finalized.',
		entry: 'Opt in per bot once opt-in/opt-out ships in the admin area. Not required.',
		scoring: 'Scored on spread capture and sustained book depth contribution, not directional PnL.',
		details:
			'Liquidity Provision rewards bots that quote tight and stay in the book, not bots that bet on direction. If your bot is a market maker rather than a directional trader, this is the mode built for it.'
	}
];
