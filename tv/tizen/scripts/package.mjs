// Packs dist/ into dist-wgt/Tuned.wgt, the file a Samsung TV installs. Unsigned: a TV and Samsung's store take only a
// package signed with Samsung's author and distributor certificates, which Melvin makes in Tizen Studio's Certificate
// Manager (docs/decisions/tizen.md › Publishing). With them, `tizen package -t wgt -s <profile> -- dist-wgt/Tuned.wgt`
// signs this file in place.
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import { zipSync } from "fflate";

const root = new URL("..", import.meta.url).pathname;
const dist = join(root, "dist");
const version = (process.env.TUNED_VERSION || JSON.parse(readFileSync(join(root, "../../package.json"), "utf8")).version)
    .split(".")
    .slice(0, 3)
    .join(".");

const files = {};
const walk = (dir) => {
    for (const name of readdirSync(dir)) {
        const path = join(dir, name);
        if (statSync(path).isDirectory()) walk(path);
        else files[relative(dist, path)] = new Uint8Array(readFileSync(path));
    }
};
walk(dist);
if (!files["config.xml"] || !files["index.html"]) throw new Error("Run `npm run build` first: dist/ has no config.xml or index.html.");
files["config.xml"] = new TextEncoder().encode(
    new TextDecoder().decode(files["config.xml"]).replace(/version="0\.0\.0"/, `version="${version}"`),
);

mkdirSync(join(root, "dist-wgt"), { recursive: true });
const out = join(root, "dist-wgt", "Tuned.wgt");
writeFileSync(out, zipSync(files, { level: 9 }));
console.log(
    `${relative(root, out)}: Tuned ${version}, ${Object.keys(files).length} files, ${Math.round(statSync(out).size / 1024)} KB (unsigned)`,
);
