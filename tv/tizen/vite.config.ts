// The Samsung TV app's build: one small bundle with relative paths (a .wgt is opened from the TV's own files), written
// for Chromium 69, the browser of 2020 TVs (Tizen 5.5), the oldest Tuned supports (docs/decisions/tizen.md).
import { readFileSync } from "node:fs";
import { defineConfig } from "vite";

// The app's version is the Android app's (the repository's package.json is its floor; release.yml passes the real one).
const version =
    process.env.TUNED_VERSION ||
    (JSON.parse(readFileSync(new URL("../../package.json", import.meta.url), "utf8")) as { version: string }).version;

export default defineConfig({
    base: "./",
    define: { __VERSION__: JSON.stringify(version) },
    build: {
        target: "chrome69",
        outDir: "dist",
        assetsInlineLimit: 0,
        sourcemap: false,
        modulePreload: false,
    },
    server: {
        // The dev harness reaches the account server through Vite, as a browser can't call it across origins; the TV can.
        proxy: { "/api": { target: "https://www.tunedplayer.com", changeOrigin: true } },
    },
});
