// The admin's reads and writes in Postgres (docs/decisions/admin.md). The arithmetic is in metrics.mjs; this file is
// only SQL. Test installs (the owner's devices, CI's emulator) are left out of every number.
import { query, transaction } from "../db.mjs";
import { checkoutAllowed, seatPlan } from "../accounts.mjs";
import { entitlements as accountEntitlements } from "../account-store.mjs";
import { change, fillDays, funnel, biggestDrop, monthlyCosts, mrr, revenue as sumRevenue } from "./metrics.mjs";
import { KINDS, redeemProblem } from "./codes.mjs";
import { storedLog } from "./analytics.mjs";

const since = (n) => `now() - make_interval(days => ${n})`;
const real = `NOT i.test`;
const int = (rows, key = "count") => Number(rows[0]?.[key] ?? 0);
const top = async (sql, params = []) => (await query(sql, params)).map((r) => ({ label: r.label ?? "Unknown", count: Number(r.count) }));

/** Counts in one window and the window before it, as `{ now, before, change }`. */
async function window(sqlFor, days) {
    const [a, b] = await Promise.all([query(sqlFor(since(days), "now()")), query(sqlFor(since(days * 2), since(days)))]);
    return { now: int(a), before: int(b), change: change(int(a), int(b)) };
}

const activeIn = (days) =>
    query(`SELECT count(DISTINCT e.install_id) AS count FROM app_events e JOIN installs i ON i.id = e.install_id
           WHERE e.at > ${since(days)} AND ${real}`).then(int);

const installsByDay = (days) =>
    query(
        `SELECT to_char(first_seen, 'YYYY-MM-DD') AS day, count(*) FROM installs i WHERE first_seen > ${since(days)} AND ${real} GROUP BY 1`,
    );

// --- Overview ---

export async function overview(days) {
    const [installs, accounts, dau, wau, mau, byDay, builds, ents, reports] = await Promise.all([
        window((from, to) => `SELECT count(*) FROM installs i WHERE first_seen > ${from} AND first_seen <= ${to} AND ${real}`, days),
        window((from, to) => `SELECT count(*) FROM accounts WHERE created_at > ${from} AND created_at <= ${to}`, days),
        activeIn(1),
        activeIn(7),
        activeIn(30),
        installsByDay(days),
        top(`SELECT build AS label, count(*) FROM installs i WHERE first_seen > ${since(days)} AND ${real} GROUP BY 1 ORDER BY 2 DESC`),
        query(`SELECT source, product, quantity, status, created_at FROM entitlements`),
        query(`SELECT count(*) FROM problem_reports WHERE status = 'open'`).then(int),
    ]);
    const recent = ents.filter((e) => new Date(e.created_at).getTime() > Date.now() - days * 86_400_000);
    const premium = new Set(
        (await query(`SELECT DISTINCT account_id FROM entitlements WHERE status = 'active' AND product LIKE 'premium%'`)).map(
            (r) => r.account_id,
        ),
    ).size;
    const seats = int(await query(`SELECT count(*) FROM devices WHERE seated AND last_seen > ${since(60)}`));
    return {
        installs,
        accounts,
        active: { dau, wau, mau },
        installsByDay: fillDays(byDay, days),
        builds,
        premium,
        seats,
        revenue: { ...sumRevenue(recent), mrr: mrr(ents) },
        openReports: reports,
    };
}

// --- Traffic (the website) ---

export async function traffic(days) {
    const w = `at > ${since(days)}`;
    const [visitors, views, byDay, pages, referrers, sources, campaigns, countries, browsers, os, devices, clicks] = await Promise.all([
        window((from, to) => `SELECT count(DISTINCT visitor) FROM page_views WHERE kind = 'view' AND at > ${from} AND at <= ${to}`, days),
        window((from, to) => `SELECT count(*) FROM page_views WHERE kind = 'view' AND at > ${from} AND at <= ${to}`, days),
        query(
            `SELECT to_char(at, 'YYYY-MM-DD') AS day, count(DISTINCT visitor) AS count FROM page_views WHERE kind = 'view' AND ${w} GROUP BY 1`,
        ),
        top(`SELECT path AS label, count(*) FROM page_views WHERE kind = 'view' AND ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`),
        top(
            `SELECT coalesce(referrer, 'Direct') AS label, count(DISTINCT visitor) AS count FROM page_views WHERE kind = 'view' AND ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`,
        ),
        top(
            `SELECT utm_source AS label, count(DISTINCT visitor) AS count FROM page_views WHERE utm_source IS NOT NULL AND ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`,
        ),
        top(
            `SELECT utm_campaign AS label, count(DISTINCT visitor) AS count FROM page_views WHERE utm_campaign IS NOT NULL AND ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`,
        ),
        top(`SELECT country AS label, count(DISTINCT visitor) AS count FROM page_views WHERE ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`),
        top(`SELECT browser AS label, count(DISTINCT visitor) AS count FROM page_views WHERE ${w} GROUP BY 1 ORDER BY 2 DESC`),
        top(`SELECT os AS label, count(DISTINCT visitor) AS count FROM page_views WHERE ${w} GROUP BY 1 ORDER BY 2 DESC`),
        top(`SELECT device AS label, count(DISTINCT visitor) AS count FROM page_views WHERE ${w} GROUP BY 1 ORDER BY 2 DESC`),
        top(`SELECT target AS label, count(*) FROM page_views WHERE kind = 'click' AND ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`),
    ]);
    return {
        visitors,
        views,
        byDay: fillDays(byDay, days),
        pages,
        referrers,
        sources,
        campaigns,
        countries,
        browsers,
        os,
        devices,
        clicks,
    };
}

// --- Acquisition (where installs came from) ---

export async function acquisition(days) {
    const w = `first_seen > ${since(days)} AND ${real}`;
    const [sources, mediums, campaigns, countries, languages, builds, byDay] = await Promise.all([
        top(`SELECT coalesce(utm_source, source) AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`),
        top(
            `SELECT utm_medium AS label, count(*) FROM installs i WHERE utm_medium IS NOT NULL AND ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`,
        ),
        top(
            `SELECT utm_campaign AS label, count(*) FROM installs i WHERE utm_campaign IS NOT NULL AND ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`,
        ),
        top(`SELECT country AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 20`),
        top(`SELECT split_part(language, '-', 1) AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`),
        top(`SELECT build AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC`),
        installsByDay(days),
    ]);
    return { sources, mediums, campaigns, countries, languages, builds, byDay: fillDays(byDay, days) };
}

// --- Funnel ---

/** Installs in the window that ever sent each event. */
async function reached(days, names, build) {
    const params = [names];
    let filter = "";
    if (build) {
        params.push(build);
        filter = `AND i.build = $2`;
    }
    const rows = await query(
        `SELECT e.name, count(DISTINCT e.install_id) AS count FROM app_events e JOIN installs i ON i.id = e.install_id
         WHERE i.first_seen > ${since(days)} AND ${real} ${filter} AND e.name = ANY($1) GROUP BY 1`,
        params,
    );
    return Object.fromEntries(rows.map((r) => [r.name, Number(r.count)]));
}

export async function funnels(days, build = null) {
    const installed = int(
        await query(
            `SELECT count(*) FROM installs i WHERE first_seen > ${since(days)} AND ${real}${build ? ` AND build = $1` : ""}`,
            build ? [build] : [],
        ),
    );
    const got = await reached(
        days,
        ["playlist_added", "first_play", "premium_seen", "premium_bought", "account_signed_in", "backup_saved"],
        build,
    );
    const gettingGoing = funnel([
        { label: "Installed", count: installed },
        { label: "Added a playlist", count: got.playlist_added ?? 0 },
        { label: "Played something", count: got.first_play ?? 0 },
    ]);
    const paying = funnel([
        { label: "Installed", count: installed },
        { label: "Saw Premium", count: got.premium_seen ?? 0 },
        { label: "Bought Premium", count: got.premium_bought ?? 0 },
    ]);
    const account = funnel([
        { label: "Installed", count: installed },
        { label: "Signed in to an account", count: got.account_signed_in ?? 0 },
        { label: "Saved a backup", count: got.backup_saved ?? 0 },
    ]);
    const visitors = int(await query(`SELECT count(DISTINCT visitor) FROM page_views WHERE kind = 'view' AND at > ${since(days)}`));
    const clicked = int(await query(`SELECT count(DISTINCT visitor) FROM page_views WHERE kind = 'click' AND at > ${since(days)}`));
    const website = funnel([
        { label: "Visited the website", count: visitors },
        { label: "Clicked a button", count: clicked },
    ]);
    return {
        gettingGoing,
        paying,
        account,
        website,
        drops: { gettingGoing: biggestDrop(gettingGoing), paying: biggestDrop(paying), account: biggestDrop(account) },
    };
}

// --- Devices ---

export async function devicesData(days) {
    const w = `last_seen > ${since(days)} AND ${real}`;
    const [kinds, android, makers, models, versions, builds] = await Promise.all([
        top(`SELECT kind AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC`),
        top(`SELECT 'Android ' || android AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`),
        top(`SELECT maker AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`),
        top(`SELECT maker || ' ' || model AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 20`),
        top(`SELECT version AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC LIMIT 15`),
        top(`SELECT build AS label, count(*) FROM installs i WHERE ${w} GROUP BY 1 ORDER BY 2 DESC`),
    ]);
    return { kinds, android, makers, models, versions, builds };
}

// --- Revenue ---

export async function revenueData(days) {
    const rows = await query(`SELECT source, product, quantity, status, created_at, checked_at FROM entitlements`);
    const inWindow = (r, n) => new Date(r.created_at).getTime() > Date.now() - n * 86_400_000;
    const recent = rows.filter((r) => inWindow(r, days));
    const before = rows.filter((r) => inWindow(r, days * 2) && !inWindow(r, days));
    const byDay = await query(
        `SELECT to_char(created_at, 'YYYY-MM-DD') AS day, count(*) FROM entitlements WHERE source <> 'grant' AND created_at > ${since(days)} GROUP BY 1`,
    );
    const costs = (await getSetting("costs")) ?? [];
    const refunds14 = rows.filter((r) => r.status === "refunded" && new Date(r.checked_at).getTime() > Date.now() - 14 * 86_400_000).length;
    const now = sumRevenue(recent);
    const monthly = mrr(rows);
    const costMonthly = monthlyCosts(costs);
    return {
        now,
        before: sumRevenue(before),
        change: change(now.gross, sumRevenue(before).gross),
        mrr: monthly,
        purchasesByDay: fillDays(byDay, days),
        active: {
            premium: rows.filter((r) => r.status === "active" && r.product.startsWith("premium")).length,
            seats: rows.filter((r) => r.status === "active" && r.product.startsWith("seat")).reduce((s, r) => s + r.quantity, 0),
            grants: rows.filter((r) => r.status === "active" && r.source === "grant").length,
        },
        refunds14,
        costs,
        costMonthly,
        // Lifetime sales come once, so profit a month uses yearly plans' monthly share plus this period's one-time
        // sales spread over its days.
        profitMonthly: Math.round(monthly + (now.net - (now.byProduct.premium_yearly ?? 0)) * (30 / days)) - costMonthly,
    };
}

// --- Codes ---

export async function codes() {
    return query(
        `SELECT code, kind, quantity, max_uses, uses, expires_at, note, disabled, created_by, created_at FROM codes ORDER BY created_at DESC LIMIT 200`,
    );
}

export async function createCode(c, actor) {
    const rows = await query(
        `INSERT INTO codes (code, kind, quantity, max_uses, expires_at, note, created_by)
         VALUES ($1, $2, $3, $4, CASE WHEN $5::int IS NULL THEN NULL ELSE now() + make_interval(days => $5::int) END, $6, $7)
         ON CONFLICT DO NOTHING RETURNING code`,
        [c.code, c.kind, c.quantity, c.maxUses, c.days, c.note, actor],
    );
    return rows.length > 0;
}

export async function setCodeDisabled(code, disabled) {
    await query(`UPDATE codes SET disabled = $2 WHERE code = $1`, [code, disabled]);
}

/**
 * Redeems `code` for an account: Premium for life, or seats of the kind the account's Premium allows (up to the
 * cap). Answers a status word.
 */
export async function redeem(code, accountId) {
    const [row] = await query(`SELECT * FROM codes WHERE code = $1`, [code]);
    const used = (await query(`SELECT 1 FROM code_redemptions WHERE code = $1 AND account_id = $2`, [code, accountId])).length > 0;
    const problem = redeemProblem(row, used);
    if (problem) return { status: problem };
    const plan = seatPlan(await accountEntitlements(accountId), Date.now());
    let product = KINDS[row.kind].product;
    let quantity = row.quantity;
    if (row.kind === "seats") {
        product = plan.seatKind;
        quantity = Math.min(quantity, plan.canBuy);
        if (!product) return { status: "premium first" };
        if (quantity < 1) return { status: "too many seats" };
    }
    const allowed = checkoutAllowed(plan, product, quantity);
    if (allowed !== "ok") return { status: allowed };
    const granted = await query(redeemSql(product === "seat_yearly"), [code, accountId, product, quantity, `code:${code}:${accountId}`]);
    return granted.length ? { status: "ok", product, quantity } : { status: "used up" };
}

/**
 * One statement: the use is counted first and the grant made only if a use was left, so two accounts redeeming the
 * last use at once can't both get it (separate statements in a transaction would both commit). `$2`, the account, is
 * cast in each place: `code_redemptions.account_id` is text and `entitlements.account_id` a uuid, and Postgres gives a
 * parameter one type for the whole statement, so without the casts the grant failed with "is of type uuid but
 * expression is of type text" and every code gave "Something went wrong" (2026-11-20).
 */
export function redeemSql(yearly) {
    const expires = yearly ? `now() + interval '1 year'` : "NULL";
    return `WITH used AS (UPDATE codes SET uses = uses + 1 WHERE code = $1 AND uses < max_uses AND NOT disabled RETURNING code),
              redeemed AS (INSERT INTO code_redemptions (code, account_id) SELECT code, $2::text FROM used RETURNING code)
         INSERT INTO entitlements (source, ref, account_id, product, quantity, status, expires_at)
         SELECT 'grant', $5, $2::uuid, $3, $4, 'active', ${expires} FROM redeemed RETURNING ref`;
}

// --- Users (accounts) ---

export async function users(search) {
    const like = `%${String(search ?? "")
        .trim()
        .toLowerCase()}%`;
    const [list, totals] = await Promise.all([
        query(
            `SELECT a.id, a.email, a.name, a.created_at,
                (SELECT max(last_seen) FROM devices d WHERE d.account_id = a.id) AS last_seen,
                (SELECT count(*) FROM devices d WHERE d.account_id = a.id) AS devices,
                (SELECT string_agg(DISTINCT product, ',') FROM entitlements e WHERE e.account_id = a.id AND e.status = 'active') AS products
             FROM accounts a WHERE lower(a.email) LIKE $1 OR a.id::text = $2 ORDER BY a.created_at DESC LIMIT 100`,
            [like, String(search ?? "").trim()],
        ),
        query(
            `SELECT count(*) AS total, count(*) FILTER (WHERE created_at > ${since(30)}) AS new30,
                (SELECT count(DISTINCT account_id) FROM devices WHERE last_seen > ${since(30)}) AS seen30,
                (SELECT count(DISTINCT account_id) FROM entitlements WHERE status = 'active' AND product LIKE 'premium%') AS premium
             FROM accounts`,
        ),
    ]);
    const t = totals[0] ?? {};
    return {
        totals: {
            total: Number(t.total ?? 0),
            new30: Number(t.new30 ?? 0),
            seen30: Number(t.seen30 ?? 0),
            premium: Number(t.premium ?? 0),
        },
        users: list.map((u) => ({ ...u, devices: Number(u.devices), products: u.products ? u.products.split(",") : [] })),
    };
}

export async function user(id) {
    const [account] = await query(`SELECT id, email, name, stripe_customer, created_at FROM accounts WHERE id::text = $1`, [id]);
    if (!account) return null;
    const [devices, ents, backups, redemptions] = await Promise.all([
        query(`SELECT id, name, kind, build, seated, first_seen, last_seen FROM devices WHERE account_id = $1 ORDER BY last_seen DESC`, [
            account.id,
        ]),
        query(
            `SELECT source, ref, product, quantity, status, expires_at, created_at FROM entitlements WHERE account_id = $1 ORDER BY created_at DESC`,
            [account.id],
        ),
        query(`SELECT version, size, device_name, saved_at FROM backups WHERE account_id = $1 ORDER BY version DESC`, [account.id]),
        query(`SELECT code, at FROM code_redemptions WHERE account_id = $1 ORDER BY at DESC`, [account.id]),
    ]);
    const plan = seatPlan(
        ents.map((e) => ({ ...e, expiresAt: e.expires_at ? new Date(e.expires_at).getTime() : null })),
        Date.now(),
    );
    // A Play or Stripe ref is a hash or a payment id; only the start is shown.
    return { account, plan, devices, entitlements: ents.map((e) => ({ ...e, ref: String(e.ref).slice(0, 14) })), backups, redemptions };
}

export async function grantPremium(accountId, actor) {
    await query(
        `INSERT INTO entitlements (source, ref, account_id, product, quantity, status) VALUES ('grant', $1, $2, 'premium_lifetime', 1, 'active')
         ON CONFLICT (source, ref) DO UPDATE SET status = 'active', checked_at = now()`,
        [`admin:${accountId}`, accountId],
    );
    return { actor };
}

export async function revokeGrant(accountId, ref) {
    await query(
        `UPDATE entitlements SET status = 'expired', checked_at = now() WHERE account_id = $1 AND source = 'grant' AND ref LIKE $2 || '%'`,
        [accountId, ref],
    );
}

export async function freeSeat(accountId, deviceId) {
    await query(`UPDATE devices SET seated = false WHERE id::text = $2 AND account_id = $1`, [accountId, deviceId]);
}

// --- Installs ---

export async function installs({ search = "", build = "", country = "", tests = false, before = null } = {}) {
    const params = [];
    const where = [];
    const add = (sql, value) => {
        params.push(value);
        where.push(sql.replaceAll("?", `$${params.length}`));
    };
    if (!tests) where.push("NOT test");
    if (search)
        add(
            "(id LIKE ? OR lower(coalesce(model, '')) LIKE ? OR lower(coalesce(utm_source, source, '')) LIKE ? OR lower(coalesce(utm_campaign, '')) LIKE ?)",
            `%${search.toLowerCase()}%`,
        );
    if (build) add("build = ?", build);
    if (country) add("country = ?", country.toUpperCase());
    if (before) add("first_seen < ?", new Date(Number(before)).toISOString());
    const rows = await query(
        `SELECT id, first_seen, last_seen, build, version, maker, model, kind, android, country, coalesce(utm_source, source) AS source, utm_campaign, test,
            (SELECT count(*) FROM app_events e WHERE e.install_id = installs.id AND e.name = 'first_play') > 0 AS played
         FROM installs ${where.length ? `WHERE ${where.join(" AND ")}` : ""} ORDER BY first_seen DESC LIMIT 50`,
        params,
    );
    return { installs: rows, next: rows.length === 50 ? new Date(rows[49].first_seen).getTime() : null };
}

export async function install(id) {
    const [row] = await query(`SELECT * FROM installs WHERE id = $1`, [id]);
    if (!row) return null;
    const [counts, latest, reports] = await Promise.all([
        query(`SELECT name, count(*) AS count, max(at) AS last FROM app_events WHERE install_id = $1 GROUP BY 1 ORDER BY 2 DESC`, [id]),
        query(`SELECT name, at, props FROM app_events WHERE install_id = $1 ORDER BY at DESC LIMIT 50`, [id]),
        query(`SELECT id, at, what, status FROM problem_reports WHERE install_id = $1 ORDER BY at DESC LIMIT 20`, [id]),
    ]);
    return { install: row, counts: counts.map((c) => ({ ...c, count: Number(c.count) })), latest, reports };
}

export async function markTest(id, test) {
    await query(`UPDATE installs SET test = $2 WHERE id = $1`, [id, test]);
}

// --- What the app sends ---

export async function saveBatch(batch, country) {
    const d = batch.device;
    const statements = [
        [
            `INSERT INTO installs (id, build, version, version_code, android, sdk, maker, model, kind, language, time_zone, country, source, utm_source, utm_medium, utm_campaign, test)
             VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17)
             ON CONFLICT (id) DO UPDATE SET last_seen = now(),
                build = coalesce($2, installs.build), version = coalesce($3, installs.version), version_code = coalesce($4, installs.version_code),
                android = coalesce($5, installs.android), sdk = coalesce($6, installs.sdk), maker = coalesce($7, installs.maker),
                model = coalesce($8, installs.model), kind = coalesce($9, installs.kind), language = coalesce($10, installs.language),
                time_zone = coalesce($11, installs.time_zone), country = coalesce($12, installs.country),
                test = installs.test OR $17`,
            [
                batch.install,
                d.build ?? null,
                d.version ?? null,
                d.versionCode ?? null,
                d.android ?? null,
                d.sdk ?? null,
                d.maker ?? null,
                d.model ?? null,
                d.kind ?? null,
                d.language ?? null,
                d.timeZone ?? null,
                country,
                d.source ?? null,
                d.utmSource ?? null,
                d.utmMedium ?? null,
                d.utmCampaign ?? null,
                batch.test,
            ],
        ],
    ];
    if (batch.events.length) {
        statements.push([
            `INSERT INTO app_events (install_id, at, name, props)
             SELECT $1, to_timestamp(t.at / 1000.0), t.name, t.props FROM jsonb_to_recordset($2::jsonb) AS t(at double precision, name text, props jsonb)`,
            [batch.install, JSON.stringify(batch.events)],
        ]);
    }
    await transaction(statements);
}

export async function savePageView(view, visitor, country) {
    await query(
        `INSERT INTO page_views (visitor, kind, path, target, referrer, utm_source, utm_medium, utm_campaign, country, browser, os, device)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
        [
            visitor,
            view.kind,
            view.path,
            view.target,
            view.referrer,
            view.utmSource,
            view.utmMedium,
            view.utmCampaign,
            country,
            view.browser,
            view.os,
            view.device,
        ],
    );
}

export async function saveReport(report) {
    const rows = await query(
        `INSERT INTO problem_reports (install_id, build, version, model, android, what, crash, log, contact)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING id`,
        [
            report.install,
            report.device.build ?? null,
            report.device.version ?? null,
            report.device.model ?? null,
            report.device.android ?? null,
            report.what,
            report.crash,
            storedLog(report),
            report.contact,
        ],
    );
    return rows[0].id;
}

// --- Reports ---

export async function reports(status = "open") {
    const rows = await query(
        `SELECT id, at, install_id, build, version, model, android, what, crash, left(log, 200000) AS log, contact, status, note, handled_by, handled_at
         FROM problem_reports WHERE ($1 = 'all' OR status = $1) ORDER BY at DESC LIMIT 100`,
        [status],
    );
    const counts = await query(`SELECT status, count(*) FROM problem_reports GROUP BY 1`);
    return { reports: rows, counts: Object.fromEntries(counts.map((c) => [c.status, Number(c.count)])) };
}

export async function setReport(id, status, note, actor) {
    await query(`UPDATE problem_reports SET status = $2, note = coalesce($3, note), handled_by = $4, handled_at = now() WHERE id = $1`, [
        id,
        status,
        note,
        actor,
    ]);
}

// --- Flags ---

export const flagRows = () => query(`SELECT key, value, share, updated_by, updated_at FROM feature_flags`);

export async function setFlag(key, value, share, actor) {
    if (value === null) return query(`DELETE FROM feature_flags WHERE key = $1`, [key]);
    await query(
        `INSERT INTO feature_flags (key, value, share, updated_by) VALUES ($1, $2::jsonb, $3, $4)
         ON CONFLICT (key) DO UPDATE SET value = $2::jsonb, share = $3, updated_by = $4, updated_at = now()`,
        [key, JSON.stringify(value), share, actor],
    );
}

// --- To do ---

export async function todoFacts() {
    const [reports] = await query(`SELECT count(*) AS count, min(at) AS oldest FROM problem_reports WHERE status = 'open'`);
    const [refunds] = await query(`SELECT count(*) AS count FROM entitlements WHERE status = 'refunded' AND checked_at > ${since(14)}`);
    const lastDigest = await getSetting("cron_last_run");
    return {
        openReports: Number(reports.count),
        oldestReportDays: reports.oldest ? Math.floor((Date.now() - new Date(reports.oldest).getTime()) / 86_400_000) : 0,
        refunds: Number(refunds.count),
        digestLateHours: lastDigest ? (Date.now() - lastDigest) / 3_600_000 : null,
    };
}

export async function hiddenTodos() {
    const rows = await query(`SELECT key, until FROM admin_todo_hidden`);
    return Object.fromEntries(rows.map((r) => [r.key, r.until ? new Date(r.until).getTime() : null]));
}

export const hideTodo = (key, until) =>
    query(`INSERT INTO admin_todo_hidden (key, until) VALUES ($1, $2) ON CONFLICT (key) DO UPDATE SET until = $2`, [
        key,
        until ? new Date(until).toISOString() : null,
    ]);

export const tasks = () =>
    query(
        `SELECT id, title, note, due, done_at, created_by, created_at FROM admin_tasks WHERE done_at IS NULL OR done_at > ${since(7)} ORDER BY done_at NULLS FIRST, due NULLS LAST, id`,
    );
export const addTask = (title, note, due, actor) =>
    query(`INSERT INTO admin_tasks (title, note, due, created_by) VALUES ($1, $2, $3, $4) RETURNING id`, [title, note, due || null, actor]);
export const finishTask = (id, done) =>
    query(`UPDATE admin_tasks SET done_at = CASE WHEN $2 THEN now() ELSE NULL END WHERE id = $1`, [id, done]);

// --- Settings, audit, two-step ---

export async function getSetting(key) {
    const [row] = await query(`SELECT value FROM admin_settings WHERE key = $1`, [key]);
    return row ? row.value : null;
}

export const setSetting = (key, value) =>
    query(
        `INSERT INTO admin_settings (key, value) VALUES ($1, $2::jsonb) ON CONFLICT (key) DO UPDATE SET value = $2::jsonb, updated_at = now()`,
        [key, JSON.stringify(value)],
    );

export const audit = (actor, action, detail = {}) =>
    query(`INSERT INTO admin_audit (actor, action, detail) VALUES ($1, $2, $3::jsonb)`, [actor, action, JSON.stringify(detail)]);

export async function auditLog({ before = null, actor = "", action = "" } = {}) {
    const params = [];
    const where = [];
    if (before) {
        params.push(Number(before));
        where.push(`id < $${params.length}`);
    }
    if (actor) {
        params.push(actor.toLowerCase());
        where.push(`lower(actor) = $${params.length}`);
    }
    if (action) {
        params.push(action.endsWith(".") ? `${action}%` : action.includes(".") ? action : `${action}.%`);
        where.push(`action LIKE $${params.length}`);
    }
    const rows = await query(
        `SELECT id, at, actor, action, detail FROM admin_audit ${where.length ? `WHERE ${where.join(" AND ")}` : ""} ORDER BY id DESC LIMIT 100`,
        params,
    );
    return { entries: rows, next: rows.length === 100 ? rows[99].id : null };
}

export async function twoFactor(email) {
    const [row] = await query(`SELECT email, secret, enabled_at, last_step, recovery FROM admin_two_factor WHERE email = $1`, [email]);
    return row ?? null;
}

export const saveTwoFactor = (email, secret) =>
    query(
        `INSERT INTO admin_two_factor (email, secret) VALUES ($1, $2) ON CONFLICT (email) DO UPDATE SET secret = $2, enabled_at = NULL, last_step = -1, recovery = '[]' WHERE admin_two_factor.enabled_at IS NULL`,
        [email, secret],
    );

export const enableTwoFactor = (email, step, recovery) =>
    query(`UPDATE admin_two_factor SET enabled_at = now(), last_step = $2, recovery = $3::jsonb WHERE email = $1`, [
        email,
        step,
        JSON.stringify(recovery),
    ]);

export const useTwoFactorStep = (email, step) =>
    query(`UPDATE admin_two_factor SET last_step = $2 WHERE email = $1 AND last_step < $2 RETURNING email`, [email, step]);
export const setRecovery = (email, recovery) =>
    query(`UPDATE admin_two_factor SET recovery = $2::jsonb WHERE email = $1`, [email, JSON.stringify(recovery)]);

/** For the System tab: whether Postgres answers, how long it took, and how big it is. */
export async function databaseHealth() {
    const started = Date.now();
    const [row] = await query(`SELECT pg_database_size(current_database()) AS bytes`);
    return { ms: Date.now() - started, bytes: Number(row.bytes) };
}

/** Raw rows kept 25 months, as the privacy policy says; run by the daily job. */
export async function prune() {
    await query(`DELETE FROM app_events WHERE at < now() - interval '25 months'`);
    await query(`DELETE FROM page_views WHERE at < now() - interval '25 months'`);
}

/** What happened after `fromMs`, for the hourly alerts. */
export async function happenedSince(fromMs) {
    const from = new Date(fromMs).toISOString();
    const [r] = await query(
        `SELECT (SELECT count(*) FROM entitlements WHERE created_at > $1 AND source <> 'grant' AND status = 'active') AS purchases,
                (SELECT count(*) FROM entitlements WHERE checked_at > $1 AND status = 'refunded') AS refunds,
                (SELECT count(*) FROM problem_reports WHERE at > $1) AS reports`,
        [from],
    );
    return { purchases: Number(r.purchases), refunds: Number(r.refunds), reports: Number(r.reports) };
}

/** Yesterday's numbers for the morning email. */
export async function digest() {
    const day = `now() - interval '1 day'`;
    const [r] = await query(
        `SELECT (SELECT count(*) FROM installs WHERE first_seen > ${day} AND NOT test) AS installs,
                (SELECT count(*) FROM accounts WHERE created_at > ${day}) AS accounts,
                (SELECT count(*) FROM entitlements WHERE created_at > ${day} AND source <> 'grant') AS purchases,
                (SELECT count(*) FROM problem_reports WHERE at > ${day}) AS reports,
                (SELECT count(DISTINCT visitor) FROM page_views WHERE at > ${day}) AS visitors`,
    );
    return Object.fromEntries(Object.entries(r).map(([k, v]) => [k, Number(v)]));
}
