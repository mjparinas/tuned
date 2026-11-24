// Minimal public M3U for Google Play reviewers (docs/decisions/premium.md).
// Only open demo streams — no provider, no login. Kept as a module so it can be committed
// (*.m3u is gitignored) and served with the right Content-Type.

/** @returns {string} */
export function reviewPlaylistBody() {
    return `#EXTM3U
# Play Console review playlist. Open demo clips only; Tuned ships no content.
#EXTINF:-1 tvg-id="review.bipbop" tvg-name="Review · BipBop" group-title="Demo | Channels",Review · BipBop
https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8
#EXTINF:-1 tvg-id="review.mux" tvg-name="Review · Mux test" group-title="Demo | Channels",Review · Mux test
https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
#EXTINF:-1 tvg-id="review.sintel" tvg-name="Sintel trailer" group-title="Demo | Movies",Sintel trailer
https://media.w3.org/2010/05/sintel/trailer.mp4
#EXTINF:-1 tvg-id="review.tears" tvg-name="Tears of Steel" group-title="Demo | Movies",Tears of Steel
https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8
`;
}

/** Headers for GET /api/review/playlist (and the /review/playlist.m3u rewrite). */
export function reviewPlaylistHeaders() {
    return {
        "Content-Type": "application/vnd.apple.mpegurl; charset=utf-8",
        "Cache-Control": "public, max-age=3600",
        "X-Robots-Tag": "noindex, nofollow",
        "Access-Control-Allow-Origin": "*",
    };
}
