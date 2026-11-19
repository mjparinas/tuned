Vendored players for the provider check (`docs/decisions/web.md`), served from our own site because the page's
CSP allows scripts only from it. Both are Apache 2.0.

- `hls.light.min.js`: hls.js 1.6.13 (`dist/hls.light.min.js`), https://github.com/video-dev/hls.js
- `mpegts.min.js`: mpegts.js 1.8.0 (`dist/mpegts.js`), https://github.com/xqq/mpegts.js

The source map lines are removed. To update, take the same files from the npm packages.
