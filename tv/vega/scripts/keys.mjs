// Writes the app's service keys from the build's environment into src/platform/keys.ts before a release build
// (npm's prebuild:release). Keys stay out of the repository: CI passes them from its secrets, and with one not set its
// feature stays off (no TMDB details, no Simkl). Never prints a key.
import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const keys = {
    tmdb: (process.env.TMDB_API_KEY ?? "").trim(),
    simkl: (process.env.SIMKL_CLIENT_ID ?? "").trim(),
};
const file = fileURLToPath(new URL("../src/platform/keys.ts", import.meta.url));
const lines = Object.entries(keys).map(([name, value]) => `    ${name}: ${JSON.stringify(value)},`);
writeFileSync(
    file,
    `// Written by scripts/keys.mjs for this build only; never commit it with a key in it.\nexport const KEYS = {\n${lines.join("\n")}\n};\n`,
);
for (const [name, value] of Object.entries(keys))
    console.log(`keys: ${name} ${value ? "built in" : "not set, so it stays off in this build"}`);
