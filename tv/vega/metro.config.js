const path = require("path");
const { getDefaultConfig, mergeConfig } = require("@react-native/metro-config");

// @tuned/shared (tv/shared) is a file: dependency, linked from node_modules to a folder outside this project, so Metro
// watches it too, and what its compiled code needs (Babel's helpers) is looked up in this project's node_modules.
module.exports = mergeConfig(getDefaultConfig(__dirname), {
    watchFolders: [path.resolve(__dirname, "../shared")],
    resolver: {
        unstable_enableSymlinks: true,
        unstable_enablePackageExports: true,
        nodeModulesPaths: [path.resolve(__dirname, "node_modules")],
    },
});
