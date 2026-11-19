// Upstash Redis over its REST API (added to the Vercel project from the Marketplace), with no client library.

function config() {
    const url = process.env.KV_REST_API_URL ?? process.env.UPSTASH_REDIS_REST_URL;
    const token = process.env.KV_REST_API_TOKEN ?? process.env.UPSTASH_REDIS_REST_TOKEN;
    if (!url || !token) throw new Error("Redis isn't connected to this Vercel project");
    return { url: url.replace(/\/$/, ""), token };
}

async function call(path, body) {
    const { url, token } = config();
    const response = await fetch(`${url}${path}`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: JSON.stringify(body),
    });
    if (!response.ok) throw new Error(`Redis answered ${response.status}`);
    return response.json();
}

/** One command, such as `["GET", key]`. */
export async function command(...args) {
    const { result, error } = await call("", args);
    if (error) throw new Error(error);
    return result;
}

/** Several commands that run together or not at all. */
export async function transaction(commands) {
    const results = await call("/multi-exec", commands);
    const failed = results.find((r) => r.error);
    if (failed) throw new Error(failed.error);
    return results.map((r) => r.result);
}

/** A hash's fields as an object. */
export async function hash(key) {
    const flat = (await command("HGETALL", key)) ?? [];
    const out = {};
    for (let i = 0; i + 1 < flat.length; i += 2) out[flat[i]] = flat[i + 1];
    return out;
}
