// The app's own service keys (AGENTS.md: release secrets built in at build time, never committed and never typed in).
// This file is empty in the repository; `npm run build:release` fills it from the build's environment
// (scripts/keys.mjs), and a build without a key turns its feature off (no TMDB details, no Simkl, no Trakt).
export const KEYS = {
    tmdb: "",
    simkl: "",
    trakt: "",
};
