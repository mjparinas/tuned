// What the daily emails keep (docs/decisions/emails.md): whether an account wants them, and which ones it has had.
// Nothing about what anyone watches. The tables are made the first time they're needed (as channel_lists is), so the
// emails work before schema.sql is run again; deleting an account deletes both.
import { query } from "./db.mjs";

let ready = null;
function ensure() {
    ready ??= query(
        `CREATE TABLE IF NOT EXISTS email_prefs (
            account_id uuid PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
            tips boolean NOT NULL DEFAULT true,
            changed_at timestamptz NOT NULL DEFAULT now()
        )`,
    )
        .then(() =>
            query(
                `CREATE TABLE IF NOT EXISTS email_sends (
                    account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
                    key text NOT NULL,
                    sent_at timestamptz NOT NULL DEFAULT now(),
                    PRIMARY KEY (account_id, key)
                )`,
            ),
        )
        .catch((e) => {
            ready = null;
            throw e;
        });
    return ready;
}

/**
 * Accounts that may be due an email: made since `since`, not unsubscribed, fewer than `total` sends, and nothing sent
 * in the last `gapMs`. Each with its sends, whether it has Premium (any live Premium entitlement), and its builds.
 */
export async function candidates({ since, total, gapMs, now = Date.now(), limit = 500 }) {
    await ensure();
    const rows = await query(
        `SELECT a.id, a.email, a.created_at,
                COALESCE((SELECT json_agg(json_build_object('key', s.key, 'at', s.sent_at)) FROM email_sends s WHERE s.account_id = a.id), '[]') AS sent,
                EXISTS (SELECT 1 FROM entitlements e WHERE e.account_id = a.id AND e.status = 'active'
                        AND e.product IN ('premium_yearly', 'premium_lifetime') AND (e.expires_at IS NULL OR e.expires_at > $4)) AS premium,
                COALESCE((SELECT array_agg(DISTINCT d.build) FROM devices d WHERE d.account_id = a.id), '{}') AS builds
         FROM accounts a
         LEFT JOIN email_prefs p ON p.account_id = a.id
         WHERE a.created_at >= $1 AND a.email <> '' AND COALESCE(p.tips, true)
           AND (SELECT count(*) FROM email_sends s WHERE s.account_id = a.id) < $2
           AND NOT EXISTS (SELECT 1 FROM email_sends s WHERE s.account_id = a.id AND s.sent_at > $3)
         ORDER BY a.created_at
         LIMIT $5`,
        [new Date(since), total, new Date(now - gapMs), new Date(now), limit],
    );
    return rows.map((r) => ({
        id: r.id,
        email: r.email,
        createdAt: new Date(r.created_at).getTime(),
        sent: (typeof r.sent === "string" ? JSON.parse(r.sent) : r.sent).map((s) => ({ key: s.key, at: new Date(s.at).getTime() })),
        premium: r.premium === true,
        builds: r.builds ?? [],
    }));
}

/** Records what went out, so a second run never sends it again. */
export async function recordSends(list) {
    if (!list.length) return;
    await ensure();
    await query(`INSERT INTO email_sends (account_id, key) SELECT * FROM unnest($1::uuid[], $2::text[]) ON CONFLICT DO NOTHING`, [
        list.map((s) => s.account),
        list.map((s) => s.key),
    ]);
}

/** Whether the account gets the daily emails (on unless turned off). */
export async function tipsOn(accountId) {
    await ensure();
    const rows = await query(`SELECT tips FROM email_prefs WHERE account_id = $1`, [accountId]);
    return rows[0]?.tips ?? true;
}

export async function setTips(accountId, on) {
    await ensure();
    await query(
        `INSERT INTO email_prefs (account_id, tips) VALUES ($1, $2)
         ON CONFLICT (account_id) DO UPDATE SET tips = $2, changed_at = now()`,
        [accountId, on],
    );
}
