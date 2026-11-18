// Prints the startup benchmark's medians as a Markdown table for the Baseline profile workflow's summary.
// Usage: node scripts/startup-summary.mjs <directory holding *benchmarkData.json>
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

function findReports(dir) {
    return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) return findReports(path);
        return name.endsWith("benchmarkData.json") ? [path] : [];
    });
}

export function summarize(reports) {
    const rows = reports
        .flatMap((report) => report.benchmarks ?? [])
        .map((b) => ({ name: b.name, median: b.metrics?.timeToInitialDisplayMs?.median }))
        .filter((r) => typeof r.median === "number");
    if (rows.length === 0) return "No startup numbers were recorded.\n";
    const lines = ["| Cold start to Live TV | Median |", "| --- | --- |"];
    for (const r of rows) lines.push(`| ${r.name} | ${Math.round(r.median)} ms |`);
    const before = rows.find((r) => r.name.includes("Without"));
    const after = rows.find((r) => r.name.includes("WithProfile"));
    if (before && after) {
        const saved = Math.round(((before.median - after.median) / before.median) * 100);
        lines.push("", `The profile changes cold start by ${saved}% (${Math.round(before.median)} ms to ${Math.round(after.median)} ms).`);
    }
    return lines.join("\n") + "\n";
}

if (process.argv[2]) {
    const reports = findReports(process.argv[2]).map((path) => JSON.parse(readFileSync(path, "utf8")));
    process.stdout.write(summarize(reports));
}
