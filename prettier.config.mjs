// Prettier formats the YAML, JSON and Markdown (workflows, configs, docs); ktlint formats Kotlin (Spotless).
// Same width as Liftsmith and the Kotlin code, so nothing argues about line length.
/** @type {import("prettier").Config} */
const config = {
    printWidth: 140,
    proseWrap: "preserve",
};

export default config;
