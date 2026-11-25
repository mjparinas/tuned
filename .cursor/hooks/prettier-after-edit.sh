#!/usr/bin/env bash
# Format a file Cursor just edited with Prettier when it is one Prettier owns.
# Fail-open: missing Node modules or an unsupported path must not block the agent.
set -u
payload=$(cat)
file=$(printf '%s' "$payload" | node -e "let s='';process.stdin.on('data',d=>s+=d);process.stdin.on('end',()=>{try{process.stdout.write(JSON.parse(s).file_path||'')}catch{}})")
[[ -n "$file" && -f "$file" ]] || exit 0
case "$file" in
  *.md | *.mdx | *.json | *.yml | *.yaml | *.html | *.css | *.js | *.mjs | *.cjs | *.ts | *.tsx) ;;
  *) exit 0 ;;
esac
root=$(git -C "$(dirname "$file")" rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" || exit 0
[[ -x node_modules/.bin/prettier ]] || exit 0
npx --no-install prettier --write --ignore-unknown -- "$file" >/dev/null 2>&1 || true
exit 0
