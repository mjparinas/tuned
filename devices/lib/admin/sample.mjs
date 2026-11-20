// Made-up numbers for the admin preview (admin-preview.mjs) and its screenshots. No real person or device.
import { fillDays, funnel, biggestDrop, revenue, mrr } from "./metrics.mjs";

const DAY = 86_400_000;
const now = Date.now();
const iso = (msAgo) => new Date(now - msAgo).toISOString();
const wave = (days, base, swing, seed = 1) =>
    fillDays(
        Array.from({ length: days }, (_, i) => ({
            day: new Date(now - (days - 1 - i) * DAY).toISOString().slice(0, 10),
            count: Math.max(0, Math.round(base + swing * Math.sin((i + seed) / 2.3) + (i / days) * base * 0.6 + ((i * 7 + seed) % 5))),
        })),
        days,
        now,
    );
const list = (pairs) => pairs.map(([label, count]) => ({ label, count }));

const ENTS = [
    ...Array.from({ length: 38 }, (_, i) => ({
        source: i % 3 ? "play" : "web",
        product: i % 4 ? "premium_lifetime" : "premium_yearly",
        quantity: 1,
        status: i === 5 ? "refunded" : "active",
        created_at: iso(i * 0.7 * DAY),
        checked_at: iso(i * 0.7 * DAY),
    })),
    ...Array.from({ length: 9 }, (_, i) => ({
        source: "web",
        product: "seat_once",
        quantity: 1 + (i % 3),
        status: "active",
        created_at: iso(i * 2 * DAY),
        checked_at: iso(i * 2 * DAY),
    })),
    { source: "grant", product: "premium_lifetime", quantity: 1, status: "active", created_at: iso(3 * DAY), checked_at: iso(3 * DAY) },
];

const models = [
    ["onn", "4K Streaming Box", "tv"],
    ["Google", "Pixel 8", "phone"],
    ["NVIDIA", "SHIELD Android TV", "tv"],
    ["Samsung", "Galaxy S24", "phone"],
    ["Xiaomi", "Mi Box S", "tv"],
    ["Samsung", "Galaxy Tab S9", "tablet"],
    ["Google", "Chromecast with Google TV", "tv"],
];
const INSTALLS = Array.from({ length: 24 }, (_, i) => {
    const [maker, model, kind] = models[i % models.length];
    return {
        id: (i + 1).toString(16).padStart(32, "a"),
        first_seen: iso(i * 0.4 * DAY + 3_600_000),
        last_seen: iso(i * 0.1 * DAY),
        build: i % 4 ? "play" : "github",
        version: i % 5 ? "1.12.0" : "1.11.2",
        maker,
        model,
        kind,
        android: kind === "tv" ? "12" : "14",
        sdk: kind === "tv" ? 31 : 34,
        language: ["en-CA", "en-US", "fr-CA", "es-MX"][i % 4],
        country: ["CA", "US", "GB", "MX", "AU"][i % 5],
        source: i % 4 ? "google-play" : "github",
        utm_source: i % 3 === 0 ? "reddit" : null,
        utm_medium: i % 3 === 0 ? "social" : null,
        utm_campaign: i % 3 === 0 ? "launch" : null,
        test: i === 2,
        played: i % 4 !== 1,
    };
});

const REPORTS = [
    {
        id: 41,
        at: iso(2 * 3_600_000),
        install_id: INSTALLS[0].id,
        build: "play",
        version: "1.12.0",
        model: "onn 4K Streaming Box",
        android: "12",
        what: "The guide shows nothing for Sports since this morning. Live TV still plays.",
        crash: "",
        log: "12:01:44 I Refresh: guide 0 programmes from 1 source\n12:01:44 W Guide: xmltv answered 200 with an empty <tv> element",
        contact: "",
        status: "open",
        note: "",
    },
    {
        id: 40,
        at: iso(26 * 3_600_000),
        install_id: INSTALLS[3].id,
        build: "github",
        version: "1.11.2",
        model: "Galaxy S24",
        android: "14",
        what: "It closed when I cast a film to the TV.",
        crash: "java.lang.IllegalStateException: MediaRouter not ready\n    at com.tunedplayer.app.player.Cast.start(Cast.kt:88)",
        log: "",
        contact: "",
        status: "open",
        note: "",
    },
];

const USERS = [
    {
        id: "7f3c1a52-0d4e-4f0b-9a7e-2b1c3d4e5f60",
        email: "sam.lee@example.com",
        name: "Sam Lee",
        created_at: iso(2 * DAY),
        last_seen: iso(3_600_000),
        devices: 3,
        products: ["premium_lifetime", "seat_once"],
    },
    {
        id: "8a2b3c4d-1e2f-4a5b-8c7d-9e0f1a2b3c4d",
        email: "jordan@example.org",
        name: "Jordan P.",
        created_at: iso(5 * DAY),
        last_seen: iso(DAY),
        devices: 2,
        products: ["premium_yearly"],
    },
    {
        id: "9b3c4d5e-2f3a-4b6c-9d8e-0f1a2b3c4d5e",
        email: "alex.martin@example.net",
        name: "",
        created_at: iso(9 * DAY),
        last_seen: iso(4 * DAY),
        devices: 1,
        products: [],
    },
    {
        id: "0c4d5e6f-3a4b-4c7d-8e9f-1a2b3c4d5e6f",
        email: "riley@example.com",
        name: "Riley",
        created_at: iso(15 * DAY),
        last_seen: iso(15 * DAY),
        devices: 1,
        products: ["premium_lifetime"],
    },
];

const FLAG_ROWS = [{ key: "subtitle_search", value: false, share: 100, updated_by: "melvin@example.com", updated_at: iso(5 * 3_600_000) }];
const AUDIT = [
    {
        id: 9,
        at: iso(5 * 3_600_000),
        actor: "melvin@example.com",
        action: "flag.set",
        detail: { key: "subtitle_search", value: false, share: 100 },
    },
    {
        id: 8,
        at: iso(6 * 3_600_000),
        actor: "melvin@example.com",
        action: "code.create",
        detail: { code: "TUNED-7KQM-2XPA", kind: "premium", quantity: 1, maxUses: 3 },
    },
    { id: 7, at: iso(7 * 3_600_000), actor: "melvin@example.com", action: "2fa.verified", detail: {} },
    { id: 6, at: iso(7 * 3_600_000), actor: "melvin@example.com", action: "signin.google", detail: {} },
    {
        id: 5,
        at: iso(30 * 3_600_000),
        actor: "melvin@example.com",
        action: "user.free-seat",
        detail: { account: USERS[0].id, device: "d1" },
    },
];

const get = (k) =>
    ({
        costs: [
            { name: "Vercel Pro", cents: 2000, every: "month" },
            { name: "Google Play developer fee", cents: 2500, every: "year" },
            { name: "Neon", cents: 0, every: "month" },
        ],
        automations: { signin_failures: true },
        cron_last_run: now - 40 * 60_000,
    })[k] ?? null;

export const sampleData = {
    overview: async (days) => ({
        installs: { now: 412, before: 351, change: 17 },
        accounts: { now: 96, before: 70, change: 37 },
        active: { dau: 288, wau: 903, mau: 1874 },
        installsByDay: wave(days, 12, 5),
        builds: list([
            ["play", 341],
            ["github", 71],
        ]),
        premium: 37,
        seats: 104,
        revenue: { ...revenue(ENTS), mrr: mrr(ENTS) },
        openReports: 2,
    }),
    traffic: async (days) => ({
        visitors: { now: 2381, before: 2010, change: 18 },
        views: { now: 4107, before: 3790, change: 8 },
        byDay: wave(days, 70, 25, 3),
        pages: list(
            [
                ["/", 1920],
                ["/tv", 1388],
                ["/account", 641],
                ["/admin", 0],
            ].filter(([, c]) => c),
        ),
        referrers: list([
            ["Direct", 1210],
            ["reddit.com", 540],
            ["google.com", 388],
            ["youtube.com", 121],
            ["t.co", 64],
        ]),
        sources: list([
            ["reddit", 498],
            ["youtube", 117],
        ]),
        campaigns: list([
            ["launch", 520],
            ["tv-setup-video", 95],
        ]),
        countries: list([
            ["CA", 822],
            ["US", 790],
            ["GB", 301],
            ["AU", 140],
            ["MX", 96],
        ]),
        browsers: list([
            ["Chrome", 1490],
            ["Safari", 512],
            ["Samsung Internet", 204],
            ["Firefox", 120],
        ]),
        os: list([
            ["Android", 1402],
            ["iOS", 498],
            ["Windows", 301],
            ["macOS", 180],
        ]),
        devices: list([
            ["phone", 1820],
            ["desktop", 461],
            ["tv", 100],
        ]),
        clicks: list([
            ["get-on-play", 612],
            ["download-apk", 188],
            ["sign-in", 141],
        ]),
    }),
    acquisition: async (days) => ({
        sources: list([
            ["google-play", 283],
            ["reddit", 64],
            ["github", 47],
            ["youtube", 18],
        ]),
        mediums: list([
            ["social", 82],
            ["video", 18],
        ]),
        campaigns: list([
            ["launch", 66],
            ["tv-setup-video", 18],
        ]),
        countries: list([
            ["CA", 141],
            ["US", 133],
            ["GB", 54],
            ["AU", 31],
            ["MX", 22],
        ]),
        languages: list([
            ["en", 344],
            ["fr", 41],
            ["es", 27],
        ]),
        builds: list([
            ["play", 341],
            ["github", 71],
        ]),
        byDay: wave(days, 12, 5),
    }),
    funnels: async () => {
        const gettingGoing = funnel([
            { label: "Installed", count: 412 },
            { label: "Added a playlist", count: 301 },
            { label: "Played something", count: 268 },
        ]);
        const paying = funnel([
            { label: "Installed", count: 412 },
            { label: "Saw Premium", count: 188 },
            { label: "Bought Premium", count: 31 },
        ]);
        const account = funnel([
            { label: "Installed", count: 412 },
            { label: "Signed in to an account", count: 96 },
            { label: "Saved a backup", count: 81 },
        ]);
        const website = funnel([
            { label: "Visited the website", count: 2381 },
            { label: "Clicked a button", count: 941 },
        ]);
        return {
            gettingGoing,
            paying,
            account,
            website,
            drops: { gettingGoing: biggestDrop(gettingGoing), paying: biggestDrop(paying), account: biggestDrop(account) },
        };
    },
    devicesData: async () => ({
        kinds: list([
            ["tv", 1210],
            ["phone", 571],
            ["tablet", 93],
        ]),
        android: list([
            ["Android 12", 640],
            ["Android 14", 512],
            ["Android 11", 301],
            ["Android 13", 280],
            ["Android 9", 141],
        ]),
        makers: list([
            ["onn", 402],
            ["Google", 377],
            ["NVIDIA", 290],
            ["Samsung", 268],
            ["Xiaomi", 211],
        ]),
        models: list([
            ["onn 4K Streaming Box", 402],
            ["NVIDIA SHIELD Android TV", 290],
            ["Google Chromecast with Google TV", 260],
            ["Xiaomi Mi Box S", 211],
            ["Samsung Galaxy S24", 120],
            ["Google Pixel 8", 117],
        ]),
        versions: list([
            ["1.12.0", 1420],
            ["1.11.2", 380],
            ["1.10.0", 74],
        ]),
        builds: list([
            ["play", 1560],
            ["github", 314],
        ]),
    }),
    revenueData: async (days) => {
        const r = revenue(ENTS);
        return {
            now: r,
            before: revenue(ENTS.slice(0, 10)),
            change: 24,
            mrr: mrr(ENTS),
            purchasesByDay: wave(days, 1.5, 1.2, 5),
            active: { premium: 37, seats: 18, grants: 1 },
            refunds14: 1,
            costs: get("costs"),
            costMonthly: 2208,
            profitMonthly: r.net - 2208,
        };
    },
    codes: async () => [
        {
            code: "TUNED-7KQM-2XPA",
            kind: "premium",
            quantity: 1,
            max_uses: 3,
            uses: 1,
            expires_at: null,
            note: "Friends",
            disabled: false,
            created_by: "melvin@example.com",
            created_at: iso(6 * 3_600_000),
        },
        {
            code: "LAUNCH-SEATS",
            kind: "seats",
            quantity: 2,
            max_uses: 50,
            uses: 50,
            expires_at: iso(-20 * DAY),
            note: "Launch week",
            disabled: false,
            created_by: "melvin@example.com",
            created_at: iso(12 * DAY),
        },
        {
            code: "REVIEWER",
            kind: "premium",
            quantity: 1,
            max_uses: 5,
            uses: 2,
            expires_at: iso(2 * DAY),
            note: "Press",
            disabled: true,
            created_by: "melvin@example.com",
            created_at: iso(30 * DAY),
        },
    ],
    users: async (q) => ({
        totals: { total: 1204, new30: 96, seen30: 811, premium: 37 },
        users: USERS.filter((u) => !q || u.email.includes(q.toLowerCase())),
    }),
    user: async (id) => {
        const u = USERS.find((x) => x.id === id);
        if (!u) return null;
        return {
            account: { id: u.id, email: u.email, name: u.name, created_at: u.created_at },
            plan: { premium: "lifetime", seatKind: "seat_once", extra: 1, limit: 4, canBuy: 6 },
            devices: [
                { id: "d1", name: "Living room TV", kind: "tv", build: "play", seated: true, last_seen: iso(3_600_000) },
                { id: "d2", name: "Pixel 8", kind: "phone", build: "play", seated: true, last_seen: iso(5 * 3_600_000) },
                { id: "d3", name: "Bedroom Chromecast", kind: "tv", build: "play", seated: true, last_seen: iso(3 * DAY) },
            ],
            entitlements: [
                {
                    source: "play",
                    ref: "3f9a0c11d2e4b5",
                    product: "premium_lifetime",
                    quantity: 1,
                    status: "active",
                    expires_at: null,
                    created_at: iso(2 * DAY),
                },
                {
                    source: "web",
                    ref: "pi_3QxYz8Lk2",
                    product: "seat_once",
                    quantity: 1,
                    status: "active",
                    expires_at: null,
                    created_at: iso(DAY),
                },
            ],
            backups: [
                { version: 12, size: 182_000, device_name: "Pixel 8", saved_at: iso(5 * 3_600_000) },
                { version: 11, size: 181_400, device_name: "Living room TV", saved_at: iso(DAY) },
            ],
            redemptions: [],
        };
    },
    installs: async () => ({ installs: INSTALLS, next: null }),
    install: async (id) => {
        const i = INSTALLS.find((x) => x.id === id);
        if (!i) return null;
        return {
            install: i,
            counts: list([
                ["app_open", 48],
                ["play", 131],
                ["screen", 210],
                ["feature", 22],
                ["playlist_added", 1],
                ["first_play", 1],
            ]).map((c) => ({ name: c.label, count: c.count })),
            latest: [
                { name: "play", at: iso(3_600_000), props: { section: "live", cast: false } },
                { name: "feature", at: iso(2 * 3_600_000), props: { name: "auto_tune" } },
                { name: "app_open", at: iso(2 * 3_600_000), props: { cold: true } },
            ],
            reports: REPORTS.filter((r) => r.install_id === id),
        };
    },
    reports: async (status) => ({
        reports: status === "open" || status === "all" ? REPORTS : [],
        counts: { open: 2, done: 14, dismissed: 3 },
    }),
    flagRows: async () => FLAG_ROWS,
    todoFacts: async () => ({ openReports: 2, oldestReportDays: 1, refunds: 1, digestLateHours: 0.7 }),
    hiddenTodos: async () => ({}),
    tasks: async () => [
        {
            id: 1,
            title: "Upload the first App Bundle to Play's internal track",
            note: "",
            due: new Date(now + 2 * DAY).toISOString().slice(0, 10),
            done_at: null,
            created_by: "melvin@example.com",
        },
        { id: 2, title: "Write the store listing", note: "", due: null, done_at: iso(DAY), created_by: "melvin@example.com" },
    ],
    getSetting: async (k) => get(k),
    auditLog: async () => ({ entries: AUDIT, next: null }),
    twoFactor: async () => ({ enabled_at: "now" }),
    audit: async () => {},
    databaseHealth: async () => ({ ms: 38, bytes: 23_400_000 }),
};

export const sampleSystem = async () => ({
    database: { state: "green", bytes: 23_400_000, limitBytes: 512 * 1024 * 1024, ms: 38 },
    redis: { state: "green", ms: 12 },
    ci: {
        state: "green",
        staging: { conclusion: "success", at: iso(40 * 60_000), url: "https://github.com/mjparinas/tuned/actions" },
        main: { conclusion: "success", at: iso(11 * 3_600_000), url: "https://github.com/mjparinas/tuned/actions" },
    },
    releases: {
        state: "green",
        version: "v1.12.0",
        publishedAt: iso(11 * 3_600_000),
        last: { conclusion: "success", at: iso(11 * 3_600_000), url: "https://github.com/mjparinas/tuned/actions" },
    },
    deploy: { state: "green", env: "production", commit: "411036a", branch: "main", region: "iad1" },
    security: { state: "green", failedToday: 0, twoFactor: "required" },
    settings: {
        state: "amber",
        list: (await import("./system.mjs")).settingsState({
            KV_REST_API_URL: 1,
            KV_REST_API_TOKEN: 1,
            DATABASE_URL: 1,
            GOOGLE_WEB_CLIENT_ID: 1,
            GOOGLE_WEB_CLIENT_SECRET: 1,
            ADMIN_EMAILS: 1,
            ANALYTICS_SECRET: 1,
            CRON_SECRET: 1,
            STRIPE_SECRET_KEY: 1,
        }),
    },
});
