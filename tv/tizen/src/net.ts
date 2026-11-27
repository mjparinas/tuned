// Networking on the TV. A Tizen app may reach any server its config.xml allows, and `fetch` streams the answer, so
// playlists and guides are read as they arrive. Should `fetch` ever be refused (an older Tizen applying the browser's
// cross-origin rule to it), the same request goes through XMLHttpRequest, which Tizen always lets an app make.
import type { Fetch, HttpInit, HttpResponse } from "@tuned/shared";

function xhr(url: string, init: HttpInit = {}): Promise<HttpResponse> {
    return new Promise((resolve, reject) => {
        const request = new XMLHttpRequest();
        request.open(init.method ?? "GET", url);
        request.responseType = "text";
        for (const [k, v] of Object.entries(init.headers ?? {})) request.setRequestHeader(k, v);
        request.onerror = () => reject(new TypeError("Network request failed"));
        request.ontimeout = () => reject(new TypeError("Network request timed out"));
        request.onload = () => {
            const text = request.responseText;
            resolve({
                ok: request.status >= 200 && request.status < 300,
                status: request.status,
                headers: { get: (name) => request.getResponseHeader(name) },
                text: async () => text,
                json: async () => JSON.parse(text),
                body: null,
            });
        };
        request.send(init.body ?? null);
    });
}

let fetchWorks = true;

export const tvFetch: Fetch = async (url, init) => {
    if (fetchWorks) {
        try {
            return (await fetch(url, init as RequestInit)) as unknown as HttpResponse;
        } catch (error) {
            if (!(error instanceof TypeError)) throw error;
            const answer = await xhr(url, init).catch(() => null);
            if (!answer) throw error;
            // fetch failed where XMLHttpRequest got through: use it from now on.
            fetchWorks = false;
            return answer;
        }
    }
    return xhr(url, init);
};
