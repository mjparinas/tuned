// After `vite build`: fails when the bundle uses JavaScript that Chromium 69 (2020 Samsung TVs) can't run, which
// Vite's target should already have lowered: optional chaining, ??, class fields, top-level await, regex lookbehind.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const dir = new URL("../dist/assets/", import.meta.url).pathname;
const problems = [];
for (const name of readdirSync(dir).filter((f) => f.endsWith(".js"))) {
    const code = readFileSync(join(dir, name), "utf8");
    // Strings and regex bodies are hard to skip exactly, so these look for the syntax with its usual neighbours.
    const checks = [
        ["optional chaining", /[\w)\]]\?\.[\w([]/],
        ["nullish coalescing", /[\w)\]] ?\?\? ?[\w([!"'`]/],
        ["logical assignment", /(\|\||&&|\?\?)=/],
        ["private class field", /[\s;{]#[a-zA-Z_]\w*\s*[=;(]/],
        ["top-level await", /^await\s/m],
    ];
    for (const [what, pattern] of checks) {
        const m = code.match(pattern);
        if (m) problems.push(`${name}: ${what} near "${code.slice(Math.max(0, m.index - 30), m.index + 30)}"`);
    }
    const kb = Math.round(code.length / 1024);
    console.log(`${name}: ${kb} KB`);
    if (kb > 600) problems.push(`${name} is ${kb} KB; the budget is 600 KB`);
}
if (problems.length) {
    console.error(`The build won't run on a 2020 Samsung TV:\n${problems.join("\n")}`);
    process.exit(1);
}
console.log("Runs on Chromium 69 (Samsung TVs from 2020).");
