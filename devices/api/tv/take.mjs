// POST /api/tv/take: see lib/tv.mjs.
import * as redis from "../../lib/redis.mjs";
import { tvRelay } from "../../lib/tv.mjs";

const relay = tvRelay(redis);

export function POST(request) {
    return relay.take(request);
}
