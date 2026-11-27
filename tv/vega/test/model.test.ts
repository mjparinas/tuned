import { AppModel, hasPremium } from "../src/app/model";
import type { Http } from "../src/core/http";
import { memoryStore } from "../src/core/storage";

function http(routes: Record<string, (body: any) => { status: number; body: any } | Promise<never>>): Http {
    const answer = async (url: string, body?: any) => {
        const path = new URL(url).pathname;
        const route = routes[path];
        if (!route) throw new Error(`no route ${path}`);
        return route(body);
    };
    return {
        getJson: (url) => answer(url),
        postJson: (url, body) => answer(url, body),
        text: async () => {
            throw new Error("no text");
        },
    };
}

const services = (h: Http, store = memoryStore()) => ({
    store,
    http: h,
    random: (n: number) => new Uint8Array(n).fill(7),
    player: { open: async () => {}, close: async () => {} },
    deviceName: "Test stick",
    site: "https://tuned.example",
});

describe("the Tuned account on Vega", () => {
    it("signs in with the website's link as a vega TV and takes a Premium seat", async () => {
        const asked: any[] = [];
        const model = new AppModel(
            services(
                http({
                    "/api/auth/token": (b) => (
                        asked.push(b),
                        { status: 200, body: { status: "ok", token: "tok", email: "a@example.com" } }
                    ),
                    "/api/account/seat": () => ({ status: 200, body: { seat: "active" } }),
                    "/api/backup": () => ({ status: 404, body: { status: "none" } }),
                }),
            ),
        );
        expect(await model.accountLinked("link")).toBe(true);
        expect(asked[0]).toMatchObject({ link: "link", kind: "tv", build: "vega", name: "Test stick", device: "07".repeat(16) });
        expect(model.state.account).toMatchObject({ email: "a@example.com", premium: true, seat: "active" });
        expect(hasPremium(model.state.account)).toBe(true);
    });

    it("keeps Premium through an outage, and only full locks", async () => {
        let up = true;
        let seat = "active";
        const model = new AppModel(
            services(
                http({
                    "/api/auth/token": () => ({ status: 200, body: { status: "ok", token: "tok", email: "" } }),
                    "/api/account/seat": () => (up ? { status: 200, body: { seat } } : Promise.reject(new Error("offline"))),
                    "/api/backup": () => ({ status: 404, body: null }),
                }),
            ),
        );
        await model.accountLinked("link");
        up = false;
        await model.checkAccount();
        expect(hasPremium(model.state.account)).toBe(true);
        expect(model.state.accountCheck).toMatchObject({ busy: false, result: { ok: false } });
        up = true;
        seat = "full";
        await model.checkAccount();
        expect(hasPremium(model.state.account)).toBe(false);
    });

    it("restores the playlist from the account's backup on the welcome screen", async () => {
        const model = new AppModel(
            services(
                http({
                    "/api/auth/token": () => ({ status: 200, body: { status: "ok", token: "tok", email: "" } }),
                    "/api/account/seat": () => ({ status: 200, body: { seat: "none" } }),
                    "/api/backup": () => ({
                        status: 200,
                        body: { version: 3, playlists: [{ server: "http://tv.example", username: "u", password: "p", playlistUrl: "" }] },
                    }),
                    "/player_api.php": () => ({ status: 200, body: { user_info: { auth: 0 } } }),
                }),
            ),
        );
        await model.accountLinked("link");
        expect(model.state.login).toMatchObject({ kind: "xtream", server: "http://tv.example" });
        expect(model.state.route).toBe("loading");
        expect(model.state.loading.error).toMatch(/didn't accept/);
    });

    it("signs out back to how it was installed", async () => {
        const store = memoryStore();
        const model = new AppModel(
            services(
                http({
                    "/api/auth/token": () => ({ status: 200, body: { status: "ok", token: "tok", email: "" } }),
                    "/api/account/seat": () => ({ status: 200, body: { seat: "active" } }),
                    "/api/backup": () => ({ status: 404, body: null }),
                    "/api/auth/signout": () => ({ status: 200, body: { status: "ok" } }),
                }),
                store,
            ),
        );
        await model.accountLinked("link");
        await model.saved.saveLogin({ kind: "link", playlistUrl: "http://x.example/a.m3u" });
        await model.signOut();
        expect(await store.keys()).toEqual([]);
        expect(model.state).toMatchObject({ route: "signin", login: null, account: null });
    });
});
