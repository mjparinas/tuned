/** Core logic runs on Node: no stick, no React Native (docs/decisions/vega.md). */
module.exports = {
    testEnvironment: "node",
    roots: ["<rootDir>/test"],
    testMatch: ["**/*.test.ts"],
    transform: {
        "^.+\\.tsx?$": [
            "ts-jest",
            {
                tsconfig: {
                    module: "commonjs",
                    target: "es2022",
                    esModuleInterop: true,
                    strict: true,
                    isolatedModules: true,
                    allowImportingTsExtensions: true,
                    noEmit: true,
                },
            },
        ],
    },
};
