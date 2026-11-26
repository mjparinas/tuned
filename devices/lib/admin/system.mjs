// What the System tab checks and the To do tab asks for (docs/decisions/admin.md). Names only, never values.

/** Every environment variable the server reads, grouped, with what it powers. A pair counts as set only when whole. */
export const SETTINGS = [
    {
        group: "Storage",
        names: ["KV_REST_API_URL", "KV_REST_API_TOKEN"],
        alt: ["UPSTASH_REDIS_REST_URL", "UPSTASH_REDIS_REST_TOKEN"],
        powers: "Upstash Redis: seats, TV codes, sessions, rate limits",
        required: true,
    },
    {
        group: "Storage",
        names: ["DATABASE_URL"],
        powers: "Neon Postgres: accounts, purchases, backups, the admin's numbers",
        required: true,
    },
    {
        group: "Sign-in",
        names: ["GOOGLE_WEB_CLIENT_ID", "GOOGLE_WEB_CLIENT_SECRET"],
        powers: "Google sign-in on the website, and the admin's",
        required: true,
    },
    { group: "Admin", names: ["ADMIN_EMAILS"], powers: "Who may sign in to the admin with full access", required: true },
    { group: "Admin", names: ["SUPPORT_EMAILS"], powers: "Who may read the admin without changing anything", required: false },
    { group: "Admin", names: ["ANALYTICS_SECRET"], powers: "Salt for the website's once-a-day visitor count", required: true },
    { group: "Admin", names: ["CRON_SECRET"], powers: "Lets Vercel's daily job run the digest and alerts", required: true },
    {
        group: "Email",
        names: ["RESEND_API_KEY"],
        powers: "Alert and digest emails to admins, and the daily emails to new accounts",
        required: false,
    },
    { group: "Email", names: ["ADMIN_FROM"], powers: "The From address of admin emails (a domain verified in Resend)", required: false },
    {
        group: "Email",
        names: ["MAIL_FROM"],
        powers: "The From address of the daily emails to new accounts (a domain verified in Resend)",
        required: false,
    },
    {
        group: "Email",
        names: ["MAIL_POSTAL_ADDRESS"],
        powers: "The postal address every email to people must carry (CAN-SPAM, CASL)",
        required: false,
    },
    { group: "Email", names: ["EMAIL_SECRET"], powers: "Signs the unsubscribe links in emails (a long random string)", required: false },
    { group: "Payments", names: ["GOOGLE_PLAY_SERVICE_ACCOUNT"], powers: "Checking Play purchases", required: true },
    {
        group: "Payments",
        names: ["STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET"],
        powers: "Website checkout and its receipts",
        required: false,
    },
    { group: "Payments", names: ["STRIPE_TAX"], powers: "Stripe Tax on website checkouts", required: false },
    {
        group: "Monitoring",
        names: ["GITHUB_TOKEN"],
        powers: "CI and release status on the System tab (works without it, at a lower rate limit)",
        required: false,
    },
];

/** Each setting with `state`: "set", "partly set" or "missing". */
export function settingsState(env = process.env) {
    return SETTINGS.map((s) => {
        const present = (names) => names.filter((n) => Boolean(env[n])).length;
        const count = Math.max(present(s.names), s.alt ? present(s.alt) : 0);
        const state = count === s.names.length ? "set" : count === 0 ? "missing" : "partly set";
        return { group: s.group, names: s.names, powers: s.powers, required: s.required, state };
    });
}

/** Comma-separated addresses, lowercased. */
export const emailList = (value) =>
    String(value ?? "")
        .split(",")
        .map((e) => e.trim().toLowerCase())
        .filter(Boolean);

/** "admin", "support" or null for an email. */
export function roleOf(email, env = process.env) {
    const e = String(email ?? "").toLowerCase();
    if (!e) return null;
    if (emailList(env.ADMIN_EMAILS).includes(e)) return "admin";
    if (emailList(env.SUPPORT_EMAILS).includes(e)) return "support";
    return null;
}

/** Green, amber or red for a check, from how many hours since it last worked. */
export const freshness = (hours, amberAfter, redAfter) =>
    hours == null ? "red" : hours >= redAfter ? "red" : hours >= amberAfter ? "amber" : "green";
