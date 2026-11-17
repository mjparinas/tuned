// Melvin, 2026-11-17: product and development decisions "should be updated and kept in sync every time new
// features are added or removed". CI runs this on every pull request: one whose title adds, changes or removes a
// feature must also change AGENTS.md or a file in docs/decisions/, so the record can't fall behind the code.

/** Whether a pull request with this title adds, changes or removes a feature. */
export function needsRecord(title) {
    const t = title.trim();
    if (/^feat(\([^)]*\))?!?:/i.test(t)) return true; // feat: …, feat(player): …, feat!: …
    if (/^[a-z]+(\([^)]*\))?!:/i.test(t)) return true; // any breaking change
    if (/^revert\b/i.test(t)) return true; // a revert takes a feature (or a fix) back out
    return /\b(remove|removes|removed|drop|drops|delete|deletes)\b/i.test(t.replace(/^[a-z]+(\([^)]*\))?:/i, ""));
}

/** Whether the changed files update the decision record. */
export function updatesRecord(files) {
    return files.some((f) => f === "AGENTS.md" || (f.startsWith("docs/decisions/") && f.endsWith(".md")));
}

/** The failure message, or null when the pull request is fine. */
export function check(title, files) {
    if (!needsRecord(title) || updatesRecord(files)) return null;
    return (
        `"${title}" adds, changes or removes a feature, but neither AGENTS.md nor docs/decisions/ changed. ` +
        "Record the decision (what, Melvin's words and date, and why) in the topic's file under docs/decisions/, " +
        "or add a topic and list it in AGENTS.md."
    );
}

if (import.meta.url === `file://${process.argv[1]}`) {
    const { readFileSync } = await import("node:fs");
    const title = process.env.PR_TITLE ?? "";
    const files = readFileSync(0, "utf8").split("\n").filter(Boolean);
    const problem = check(title, files);
    if (problem) {
        console.log(`::error title=Decision record::${problem}`);
        process.exit(1);
    }
    console.log(needsRecord(title) ? "The decision record is updated." : "No feature change in the title, so no record needed.");
}
