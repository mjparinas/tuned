// The admin's endpoints, /api/admin/<action> (docs/decisions/admin.md). Everything it needs comes in `deps`, so the
// preview server (admin-preview.mjs) can hand in sample data and the tests a stand-in.
//
// deps: { data, sessions, redis, env, auth: { webAccount, account, googleRedirect, signInReady }, system, mail, now }
import { checkValue, FLAGS, flagsFor } from "./flags.mjs";
import { checkNewCode } from "./codes.mjs";
import { periodOf } from "./metrics.mjs";
import { roleOf, settingsState } from "./system.mjs";
import { derive, snoozeUntil } from "./todo.mjs";
import { hashRecovery, newRecoveryCodes, newSecret, otpauth, useRecovery, verify } from "./totp.mjs";
import { sameOrigin } from "./session.mjs";
import { AUTOMATIONS } from "./automations.mjs";
import { tunedMail } from "../emails.mjs";

const json = (body, status = 200, headers = {}) => Response.json(body, { status, headers: { "Cache-Control": "no-store", ...headers } });
const TWO_FACTOR_TRIES = 5;

async function body(request) {
    try {
        return (await request.json()) ?? {};
    } catch {
        return {};
    }
}

export function adminApi(deps) {
    const { data, sessions, redis, env } = deps;
    const now = () => (deps.now ? deps.now() : Date.now());
    const twoFactorRequired = () => env.ADMIN_2FA !== "optional";

    /** The signed-in staff member, from the admin cookie, or a new admin session from a Google sign-in. */
    async function who(request) {
        const found = await sessions.read(request, now());
        if (found && roleOf(found.session.email, env)) return { ...found, role: roleOf(found.session.email, env) };
        const accountId = await deps.auth.webAccount(request);
        if (!accountId) return null;
        const acct = await deps.auth.account(accountId);
        const role = roleOf(acct?.email, env);
        if (!role) return { refused: acct?.email ?? null };
        const made = await sessions.create(acct.email.toLowerCase(), role, now());
        await data.audit(acct.email, "signin.google", {});
        return { ...made, role, fresh: true };
    }

    async function stateOf(me) {
        const tf = await data.twoFactor(me.session.email);
        const step = me.session.verified ? "ok" : tf?.enabled_at ? "code" : twoFactorRequired() ? "setup" : "ok";
        return { email: me.session.email, role: me.role, twoFactor: step, optional: !twoFactorRequired() };
    }

    async function tooManyTries(email) {
        const k = `admin2fa:${email}`;
        const count = await redis.command("INCR", k);
        if (count === 1) await redis.command("EXPIRE", k, 900);
        return count > TWO_FACTOR_TRIES;
    }

    async function failed(email, why) {
        await data.audit(email, "2fa.failed", { why });
        await redis.command("INCR", `adminfail:${new Date(now()).toISOString().slice(0, 10)}`);
    }

    /** The sign-in steps: who am I, Google, the second step, sign out. */
    const open = {
        async session(request) {
            const me = await who(request);
            if (!me) return json({ signedIn: false, ready: deps.auth.signInReady() });
            if (me.refused !== undefined) return json({ signedIn: false, refused: me.refused, ready: true }, 403);
            const headers = me.cookie ? { "Set-Cookie": me.cookie } : {};
            return json({ signedIn: true, ...(await stateOf(me)) }, 200, headers);
        },
        async signin(request) {
            if (!deps.auth.signInReady()) return json({ status: "sign-in isn't set up" }, 503);
            const origin = new URL(request.url).origin;
            return await deps.auth.googleRedirect(origin, { to: "admin" });
        },
        async "two-factor"(request) {
            if (request.method !== "POST" || !sameOrigin(request)) return json({ status: "bad request" }, 400);
            const me = await who(request);
            if (!me || me.refused !== undefined) return json({ status: "signed out" }, 401);
            const email = me.session.email;
            const ask = await body(request);
            const tf = await data.twoFactor(email);
            if (ask.action === "setup") {
                if (tf?.enabled_at) return json({ status: "already on" }, 409);
                const secret = newSecret();
                await data.saveTwoFactor(email, secret);
                return json({ status: "ok", secret, link: otpauth(secret, email) });
            }
            if (await tooManyTries(email)) return json({ status: "too many" }, 429);
            if (ask.action === "enable") {
                if (!tf || tf.enabled_at) return json({ status: "bad request" }, 400);
                const step = verify(tf.secret, ask.code, now());
                if (step === null) {
                    await failed(email, "setup code");
                    return json({ status: "wrong code" }, 403);
                }
                const codes = newRecoveryCodes();
                await data.enableTwoFactor(email, step, codes.map(hashRecovery));
                await sessions.update(me.token, { ...me.session, verified: true });
                await data.audit(email, "2fa.enabled", {});
                return json({ status: "ok", recovery: codes });
            }
            if (ask.action === "verify") {
                if (!tf?.enabled_at) return json({ status: "bad request" }, 400);
                if (ask.recovery) {
                    const left = useRecovery(tf.recovery ?? [], ask.recovery);
                    if (!left) {
                        await failed(email, "recovery code");
                        return json({ status: "wrong code" }, 403);
                    }
                    await data.setRecovery(email, left);
                    await data.audit(email, "2fa.recovery-used", { left: left.length });
                } else {
                    const step = verify(tf.secret, ask.code, now(), Number(tf.last_step));
                    if (step === null || !(await data.useTwoFactorStep(email, step)).length) {
                        await failed(email, "code");
                        return json({ status: "wrong code" }, 403);
                    }
                    await data.audit(email, "2fa.verified", {});
                }
                await sessions.update(me.token, { ...me.session, verified: true });
                return json({ status: "ok" });
            }
            return json({ status: "bad request" }, 400);
        },
        async signout(request) {
            if (request.method !== "POST" || !sameOrigin(request)) return json({ status: "bad request" }, 400);
            const me = await sessions.read(request, now());
            if (me) await data.audit(me.session.email, "signout", {});
            return json({ status: "ok" }, 200, { "Set-Cookie": await sessions.end(request) });
        },
    };

    /** The panel's reads (staff) and changes (admins only, from our own page, audited). */
    const panel = {
        async overview(_r, q) {
            const days = periodOf(q.get("days"));
            const [stats, facts, hidden] = await Promise.all([data.overview(days), data.todoFacts(), data.hiddenTodos()]);
            const todo = derive({ ...facts, missingSettings: missingSettings() }, hidden, now());
            return json({ days, ...stats, needsYou: todo.slice(0, 5), todoCount: todo.length });
        },
        traffic: async (_r, q) => json({ days: periodOf(q.get("days")), ...(await data.traffic(periodOf(q.get("days")))) }),
        acquisition: async (_r, q) => json({ days: periodOf(q.get("days")), ...(await data.acquisition(periodOf(q.get("days")))) }),
        funnel: async (_r, q) => {
            const build = ["github", "play", "amazon", "vega", "tizen"].includes(q.get("build")) ? q.get("build") : null;
            return json({ days: periodOf(q.get("days")), build, ...(await data.funnels(periodOf(q.get("days")), build)) });
        },
        devices: async (_r, q) => json({ days: periodOf(q.get("days")), ...(await data.devicesData(periodOf(q.get("days")))) }),
        revenue: async (_r, q) => json({ days: periodOf(q.get("days")), ...(await data.revenueData(periodOf(q.get("days")))) }),
        codes: async () => json({ codes: await data.codes() }),
        users: async (_r, q) => (q.get("id") ? found(await data.user(q.get("id"))) : json(await data.users(q.get("q") ?? ""))),
        installs: async (_r, q) =>
            q.get("id")
                ? found(await data.install(q.get("id")))
                : json(
                      await data.installs({
                          search: q.get("q") ?? "",
                          build: q.get("build") ?? "",
                          country: q.get("country") ?? "",
                          tests: q.get("tests") === "1",
                          before: q.get("before"),
                      }),
                  ),
        reports: async (_r, q) =>
            json(await data.reports(["open", "done", "dismissed", "all"].includes(q.get("status")) ? q.get("status") : "open")),
        flags: async () => {
            const rows = await data.flagRows();
            const byKey = Object.fromEntries(rows.map((r) => [r.key, r]));
            return json({ flags: FLAGS.map((f) => ({ ...f, changed: byKey[f.key] ?? null })), preview: flagsFor(rows, null) });
        },
        todo: async () => {
            const [facts, hidden, tasks] = await Promise.all([data.todoFacts(), data.hiddenTodos(), data.tasks()]);
            return json({ items: derive({ ...facts, missingSettings: missingSettings() }, hidden, now()), tasks });
        },
        automations: async () => {
            const saved = (await data.getSetting("automations")) ?? {};
            return json({
                automations: AUTOMATIONS.map((a) => ({ ...a, on: saved[a.key] ?? true })),
                lastRun: await data.getSetting("cron_last_run"),
                mailReady: Boolean(env.RESEND_API_KEY),
                recipients: String(env.ADMIN_EMAILS ?? "")
                    .split(",")
                    .filter(Boolean).length,
            });
        },
        system: async () => json(await deps.system()),
        log: async (_r, q) =>
            json(await data.auditLog({ before: q.get("before"), actor: q.get("who") ?? "", action: q.get("what") ?? "" })),
    };

    const found = (value) => (value ? json(value) : json({ status: "not found" }, 404));
    const missingSettings = () => settingsState(env).filter((s) => s.required && s.state !== "set");

    /** Changes: POST /api/admin/<action> with { do, ... }. */
    const changes = {
        async reports(ask, actor) {
            if (!["open", "done", "dismissed"].includes(ask.status)) return null;
            await data.setReport(Number(ask.id), ask.status, typeof ask.note === "string" ? ask.note.slice(0, 1000) : null, actor);
            return { audit: ["report.status", { id: ask.id, status: ask.status }] };
        },
        async todo(ask, actor) {
            if (ask.do === "snooze") await data.hideTodo(String(ask.key), snoozeUntil(ask.until, now()));
            else if (ask.do === "dismiss") await data.hideTodo(String(ask.key), null);
            else if (ask.do === "add") {
                const title = String(ask.title ?? "")
                    .trim()
                    .slice(0, 200);
                if (!title) return null;
                await data.addTask(
                    title,
                    String(ask.note ?? "").slice(0, 2000),
                    /^\d{4}-\d{2}-\d{2}$/.test(ask.due ?? "") ? ask.due : null,
                    actor,
                );
            } else if (ask.do === "done") await data.finishTask(Number(ask.id), ask.done !== false);
            else return null;
            return { audit: [`todo.${ask.do}`, { key: ask.key ?? ask.id ?? ask.title }] };
        },
        async codes(ask, actor) {
            if (ask.do === "create") {
                const c = checkNewCode(ask);
                if (c.error) return { error: c.error };
                if (!(await data.createCode(c, actor))) return { error: "That code already exists." };
                return { audit: ["code.create", { code: c.code, kind: c.kind, quantity: c.quantity, maxUses: c.maxUses }], code: c.code };
            }
            if (ask.do === "disable" || ask.do === "enable") {
                await data.setCodeDisabled(String(ask.code), ask.do === "disable");
                return { audit: [`code.${ask.do}`, { code: ask.code }] };
            }
            return null;
        },
        async users(ask, actor) {
            const id = String(ask.id ?? "");
            if (!id) return null;
            if (ask.do === "grant") await data.grantPremium(id, actor);
            else if (ask.do === "revoke") await data.revokeGrant(id, String(ask.ref ?? ""));
            else if (ask.do === "free-seat") await data.freeSeat(id, String(ask.device ?? ""));
            else return null;
            return { audit: [`user.${ask.do}`, { account: id, device: ask.device, ref: ask.ref }] };
        },
        async installs(ask) {
            if (typeof ask.id !== "string") return null;
            await data.markTest(ask.id, ask.test === true);
            return { audit: ["install.test", { id: ask.id, test: ask.test === true }] };
        },
        async flags(ask) {
            const reset = ask.value === null;
            if (!reset && checkValue(ask.key, ask.value) === undefined) return { error: "That value doesn't fit this flag." };
            const share = Number.isInteger(ask.share) && ask.share >= 0 && ask.share <= 100 ? ask.share : 100;
            return { set: [ask.key, reset ? null : ask.value, share], audit: ["flag.set", { key: ask.key, value: ask.value, share }] };
        },
        async automations(ask) {
            if (ask.do === "test") {
                const test = tunedMail({
                    kicker: "Tuned admin",
                    title: "Alerts reach you here ✅",
                    subject: "Tuned admin: test alert",
                    intro: "This is a test from the Automations tab. Purchases, refunds, problem reports and the morning digest come to this address.",
                    why: "Sent to Tuned's admins from the Automations tab.",
                });
                const sent = await deps.mail.send(String(env.ADMIN_EMAILS ?? "").split(","), test.subject, test.text, test.html);
                return { audit: ["automation.test", { sent }], sent };
            }
            if (!AUTOMATIONS.some((a) => a.key === ask.key) || typeof ask.on !== "boolean") return null;
            const saved = (await data.getSetting("automations")) ?? {};
            await data.setSetting("automations", { ...saved, [ask.key]: ask.on });
            return { audit: ["automation.set", { key: ask.key, on: ask.on }] };
        },
        async revenue(ask) {
            if (!Array.isArray(ask.costs) || ask.costs.length > 40) return null;
            const costs = ask.costs
                .map((c) => ({
                    name: String(c.name ?? "").slice(0, 60),
                    cents: Math.round(Number(c.cents)),
                    every: c.every === "year" ? "year" : "month",
                }))
                .filter((c) => c.name && Number.isFinite(c.cents) && c.cents >= 0);
            await data.setSetting("costs", costs);
            return { audit: ["costs.set", { count: costs.length }] };
        },
    };

    return async function handle(request, action) {
        try {
            if (open[action]) return await open[action](request);
            const me = await who(request);
            if (!me || me.refused !== undefined) return json({ status: "signed out" }, 401);
            if ((await stateOf(me)).twoFactor !== "ok") return json({ status: "second step" }, 401);
            const q = new URL(request.url).searchParams;
            if (request.method === "GET") return panel[action] ? await panel[action](request, q) : json({ status: "not found" }, 404);
            if (request.method !== "POST" || !changes[action]) return json({ status: "not found" }, 404);
            if (!sameOrigin(request)) return json({ status: "bad request" }, 400);
            if (me.role !== "admin") return json({ status: "read only" }, 403);
            const done = await changes[action](await body(request), me.session.email);
            if (!done) return json({ status: "bad request" }, 400);
            if (done.error) return json({ status: "bad request", error: done.error }, 400);
            if (done.set) await data.setFlag(...done.set, me.session.email);
            await data.audit(me.session.email, ...done.audit);
            return json({
                status: "ok",
                ...(done.code ? { code: done.code } : {}),
                ...(done.sent !== undefined ? { sent: done.sent } : {}),
            });
        } catch (error) {
            console.error(error);
            return json({ status: "unavailable" }, 503);
        }
    };
}
