@AGENTS.md

## Notes from earlier tasks

- Pull requests target `staging`. To fix a pull request's merge conflicts, fetch `refs/pull/<n>/head`, merge `origin/staging` into it, resolve, run the checks, and push the result to the pull request's own head branch (a fast-forward, never a force push). When both sides edit the same doc paragraph, keep both sides' facts.
- A change that only re-indents a big block (wrapping it in a provider, say) conflicts with everything; take staging's file and reapply the wrapper, then `./gradlew spotlessApply`.
- The sandbox has no Android SDK. Install the command-line tools into `/opt/android`, then `sdkmanager "platforms;android-37.0" "build-tools;37.0.0" platform-tools`, and write `sdk.dir=/opt/android` to `local.properties` (git-ignored). `:app:testGithubDebugUnitTest` then runs in about 7 minutes. Website tests: `node --test lib/*.test.mjs lib/admin/*.test.mjs` in `devices/`.
