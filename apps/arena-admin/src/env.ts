import { defineEnvVars } from '@sveltejs/kit/env';

// Public build-time configuration. Values are inlined into the static bundle,
// so the local-dev fixture flags are dead-code eliminated from production builds
// (see scripts/assert-production-build-no-local-fixtures.mjs).
const optional = (value: string | undefined) => value;

export const variables = defineEnvVars({
	PUBLIC_ARENA_API_BASE_URL: { public: true, static: true, schema: optional },
	PUBLIC_ARENA_LOCAL_DEV_FAKE_ADMIN: { public: true, static: true, schema: optional },
	PUBLIC_ARENA_LOCAL_DEV_FIXTURES: { public: true, static: true, schema: optional }
});
