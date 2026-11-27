// Writes the app's service keys from the build's environment into src/platform/keys.ts before a release build
// (npm's prebuild:release). Keys stay out of the repository: CI passes them from its secrets, and with none set the
// file stays empty and the feature stays off. Never prints a key.
import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const tmdb = (process.env.TMDB_API_KEY ?? "").trim();
if (!tmdb) {
    console.log("keys: no TMDB_API_KEY, so TMDB details stay off in this build");
    process.exit(0);
}
const file = fileURLToPath(new URL("../src/platform/keys.ts", import.meta.url));
writeFileSync(
    file,
    `// Written by scripts/keys.mjs for this build only; never commit it with a key in it.\nexport const KEYS = {\n    tmdb: ${JSON.stringify(tmdb)},\n};\n`,
);
console.log("keys: TMDB key built in");
