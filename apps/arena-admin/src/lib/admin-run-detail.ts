import type { ArenaRun, ArenaRunBotResult, ArenaRunEnforcementEvent } from './api';

type RunDetailState = {
	run: ArenaRun | null;
	results: ArenaRunBotResult[];
	enforcementEvents: ArenaRunEnforcementEvent[];
	loading: boolean;
	error: string;
};

type RunDetailApi = {
	fetchAdminRuns: (limit: number) => Promise<ArenaRun[]>;
	fetchAdminRunResults: (runId: string) => Promise<ArenaRunBotResult[]>;
	fetchAdminRunEnforcementEvents: (runId: string) => Promise<ArenaRunEnforcementEvent[]>;
};

export function createRunDetailLoader(api: RunDetailApi, publish: (state: RunDetailState) => void) {
	let generation = 0;

	return (runId: string) => {
		const request = ++generation;
		const empty = { run: null, results: [], enforcementEvents: [], loading: false, error: '' };
		publish({ ...empty, loading: Boolean(runId) });

		if (runId) void load();

		// Svelte effect cleanup invalidates pending work on navigation and unmount.
		return () => {
			if (generation === request) generation++;
		};

		async function load() {
			try {
				const [runs, results, enforcementEvents] = await Promise.all([
					api.fetchAdminRuns(100),
					api.fetchAdminRunResults(runId),
					api.fetchAdminRunEnforcementEvents(runId)
				]);
				if (generation !== request) return;
				publish({ ...empty, run: runs.find((run) => run.runId === runId) ?? null, results, enforcementEvents });
			} catch (error) {
				if (generation !== request) return;
				publish({ ...empty, error: error instanceof Error ? error.message : 'run detail load failed' });
			}
		}
	};
}
