// How the app reaches the network, injected so tests hand in a stand-in. On the stick it's XMLHttpRequest, which
// reports bytes as they arrive (React Native's fetch can't stream a body), so a long download shows real progress.

export interface Progress {
    /** Bytes so far. */
    loaded: number;
    /** The full size, when the server said it. */
    total: number | null;
}

export interface Http {
    /** The body as text. Throws `HttpError` for a status other than 2xx and `NetworkError` when nothing came back. */
    text(
        url: string,
        options?: { onProgress?: (progress: Progress) => void; timeoutMs?: number; headers?: Record<string, string> },
    ): Promise<string>;
    /** POSTs JSON and reads JSON back, whatever the status (the server's answers carry a `status`). */
    postJson(url: string, body: unknown, headers?: Record<string, string>): Promise<{ status: number; body: any }>;
    getJson(url: string, headers?: Record<string, string>): Promise<{ status: number; body: any }>;
}

export class HttpError extends Error {
    constructor(public readonly status: number) {
        super(`HTTP ${status}`);
        this.name = "HttpError";
    }
}

export class NetworkError extends Error {
    constructor(message = "No answer") {
        super(message);
        this.name = "NetworkError";
    }
}

/** An `Http` over XMLHttpRequest, which the stick, Node's tests (through a stand-in) and a browser all have. */
export function xhrHttp(Xhr: { new (): XMLHttpRequest } = XMLHttpRequest): Http {
    function request(
        method: string,
        url: string,
        body: string | null,
        options: { onProgress?: (p: Progress) => void; timeoutMs?: number; headers?: Record<string, string> },
    ): Promise<{ status: number; text: string }> {
        return new Promise((resolve, reject) => {
            const xhr = new Xhr();
            xhr.open(method, url, true);
            xhr.timeout = options.timeoutMs ?? 30_000;
            for (const [k, v] of Object.entries(options.headers ?? {})) xhr.setRequestHeader(k, v);
            if (options.onProgress) {
                const report = options.onProgress;
                xhr.onprogress = (e: ProgressEvent) =>
                    report({ loaded: e.loaded, total: e.lengthComputable && e.total > 0 ? e.total : null });
            }
            xhr.onload = () => resolve({ status: xhr.status, text: String(xhr.responseText ?? "") });
            xhr.onerror = () => reject(new NetworkError());
            xhr.ontimeout = () => reject(new NetworkError("Timed out"));
            xhr.send(body);
        });
    }
    const parse = (text: string) => {
        try {
            return JSON.parse(text);
        } catch {
            return null;
        }
    };
    return {
        async text(url, options = {}) {
            const answer = await request("GET", url, null, options);
            if (answer.status < 200 || answer.status > 299) throw new HttpError(answer.status);
            return answer.text;
        },
        async postJson(url, body, headers = {}) {
            const answer = await request("POST", url, JSON.stringify(body), {
                headers: { "Content-Type": "application/json", ...headers },
            });
            return { status: answer.status, body: parse(answer.text) };
        },
        async getJson(url, headers = {}) {
            const answer = await request("GET", url, null, { headers });
            return { status: answer.status, body: parse(answer.text) };
        },
    };
}
