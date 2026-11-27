// The browser preview: the stick's screens drawn by React Native for Web, with stand-ins for Amazon's modules
// (docs/decisions/vega.md). For screenshots and checking layouts; playback and speed are only checked on a stick.
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));

export default defineConfig({
    root: here("."),
    plugins: [react()],
    define: { __DEV__: "false", "process.env.NODE_ENV": '"production"', global: "globalThis" },
    resolve: {
        extensions: [".web.tsx", ".web.ts", ".web.js", ".tsx", ".ts", ".mjs", ".js", ".json"],
        alias: [
            { find: /^react-native$/, replacement: "react-native-web" },
            { find: "@amazon-devices/react-native-kepler", replacement: here("stubs/kepler.tsx") },
            { find: "@amazon-devices/react-native-w3cmedia", replacement: here("stubs/w3cmedia.tsx") },
            { find: "@amazon-devices/react-native-async-storage__async-storage", replacement: here("stubs/asyncStorage.ts") },
            { find: "@amazon-devices/expo-crypto", replacement: here("stubs/expoCrypto.ts") },
        ],
    },
    build: { outDir: here("out"), emptyOutDir: true },
    server: { port: 5178 },
});
