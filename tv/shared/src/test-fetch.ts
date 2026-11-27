// A stand-in server for tv/shared's tests: answers by path, records what was asked.
import type { Fetch, HttpInit, HttpResponse } from "./platform.ts";

export type Route = (url: URL, init: HttpInit) => { status?: number; body: unknown; headers?: Record<string, string> } | undefined;

export function fakeFetch(
    route: Route,
    options: { stream?: boolean; piece?: number } = {},
): Fetch & { calls: { url: string; init: HttpInit }[] } {
    const calls: { url: string; init: HttpInit }[] = [];
    const fn = async (url: string, init: HttpInit = {}): Promise<HttpResponse> => {
        calls.push({ url, init });
        const answer = route(new URL(url), init) ?? { status: 404, body: "Not found" };
        const text = typeof answer.body === "string" ? answer.body : JSON.stringify(answer.body);
        const bytes = new TextEncoder().encode(text);
        const status = answer.status ?? 200;
        const piece = options.piece ?? 7;
        let at = 0;
        return {
            ok: status >= 200 && status < 300,
            status,
            headers: {
                get: (name: string) => (name.toLowerCase() === "content-length" ? String(bytes.length) : (answer.headers?.[name] ?? null)),
            },
            text: async () => text,
            json: async () => JSON.parse(text),
            body: options.stream
                ? {
                      getReader: () => ({
                          read: async () => {
                              if (at >= bytes.length) return { done: true };
                              const value = bytes.subarray(at, at + piece);
                              at += piece;
                              return { done: false, value };
                          },
                          cancel: async () => undefined,
                      }),
                  }
                : null,
        };
    };
    return Object.assign(fn, { calls });
}
