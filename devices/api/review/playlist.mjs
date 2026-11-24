// GET /api/review/playlist — also rewritten from /review/playlist.m3u (docs/decisions/premium.md).
import { reviewPlaylistBody, reviewPlaylistHeaders } from "../../lib/review-playlist.mjs";

export function GET() {
    return new Response(reviewPlaylistBody(), { status: 200, headers: reviewPlaylistHeaders() });
}
