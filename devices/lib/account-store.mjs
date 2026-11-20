// Where accounts live in Postgres (schema.sql). Everything that reads or writes the tables is here, so the
// endpoints stay short and the rules in accounts.mjs stay free of SQL.
import { EXPIRY_DAYS, BACKUP_VERSIONS, sha } from "./accounts.mjs";
import { query, time, transaction } from "./db.mjs";

/** The account for a Google sign-in, made the first time. */
export async function accountFor({ subject, email, name }) {
    const found = await query(
        `UPDATE accounts SET email = $2, name = $3 FROM identities i
         WHERE i.provider = 'google' AND i.subject = $1 AND i.account_id = accounts.id RETURNING accounts.id`,
        [subject, email, name],
    );
    if (found.length) return found[0].id;
    const [, , made] = await transaction([
        [`SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, [subject]],
        [
            `WITH a AS (INSERT INTO accounts (email, name)
                SELECT $2, $3 WHERE NOT EXISTS (SELECT 1 FROM identities WHERE provider = 'google' AND subject = $1)
                RETURNING id)
             INSERT INTO identities (provider, subject, account_id) SELECT 'google', $1, id FROM a`,
            [subject, email, name],
        ],
        [`SELECT account_id AS id FROM identities WHERE provider = 'google' AND subject = $1`, [subject]],
    ]);
    return made[0].id;
}

/** Signs a device in to an account: a new token, replacing any the same device had. Returns the token. */
export async function signInDevice(account, { device, kind, build, name }, token) {
    await query(
        `INSERT INTO devices (account_id, device_hash, token_hash, name, kind, build)
         VALUES ($1, $2, $3, $4, $5, $6)
         ON CONFLICT (account_id, device_hash)
         DO UPDATE SET token_hash = $3, name = $4, kind = $5, build = $6, last_seen = now()`,
        [account, device, sha(token), name, kind, build],
    );
}

/** The device a token belongs to, marked as used now; null for a token that was signed out or removed. */
export async function deviceFor(token) {
    const rows = await query(
        `UPDATE devices SET last_seen = now() WHERE token_hash = $1
         RETURNING id, account_id AS account, name, kind, build, seated`,
        [sha(token)],
    );
    return rows[0] ?? null;
}

export async function account(id) {
    const rows = await query(`SELECT id, email, name, stripe_customer AS "stripeCustomer" FROM accounts WHERE id = $1`, [id]);
    return rows[0] ?? null;
}

export async function entitlements(accountId) {
    const rows = await query(
        `SELECT source, ref, product, quantity, status, expires_at, checked_at FROM entitlements WHERE account_id = $1`,
        [accountId],
    );
    return rows.map((r) => ({ ...r, expiresAt: time(r.expires_at), checkedAt: time(r.checked_at) }));
}

export async function devices(accountId) {
    const rows = await query(`SELECT id, name, kind, build, seated, last_seen FROM devices WHERE account_id = $1 ORDER BY last_seen DESC`, [
        accountId,
    ]);
    return rows.map((r) => ({ id: r.id, name: r.name, kind: r.kind, build: r.build, seated: r.seated, lastSeen: time(r.last_seen) }));
}

/**
 * Gives `deviceId` a seat if it has one or one is free under `limit`, in one transaction that holds the account's
 * lock, so two devices asking at once can't both take the last seat. True when the device holds a seat after.
 */
export async function takeSeat(accountId, deviceId, limit) {
    const [, rows] = await transaction([
        [`SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, [accountId]],
        [
            `UPDATE devices d SET seated = true WHERE d.id = $2 AND d.account_id = $1 AND (
                (d.seated AND d.last_seen > now() - make_interval(days => $4))
                OR (SELECT count(*) FROM devices o WHERE o.account_id = $1 AND o.id <> $2 AND o.seated
                    AND o.last_seen > now() - make_interval(days => $4)) < $3)
             RETURNING id`,
            [accountId, deviceId, limit, EXPIRY_DAYS],
        ],
    ]);
    return rows.length > 0;
}

export async function releaseSeat(accountId, deviceId) {
    await query(`UPDATE devices SET seated = false WHERE id = $2 AND account_id = $1`, [accountId, deviceId]);
}

/** Removes a device from the account, which signs it out (its token stops working). */
export async function removeDevice(accountId, deviceId) {
    const rows = await query(`DELETE FROM devices WHERE id = $2 AND account_id = $1 RETURNING id`, [accountId, deviceId]);
    return rows.length > 0;
}

export async function renameDevice(accountId, deviceId, name) {
    await query(`UPDATE devices SET name = $3 WHERE id = $2 AND account_id = $1`, [accountId, deviceId, name]);
}

/** Saves an entitlement from Play or Stripe, keyed by its source and ref, so a repeated event changes nothing. */
export async function saveEntitlement({ source, ref, account: accountId, product, quantity, status, expiresAt }) {
    await query(
        `INSERT INTO entitlements (source, ref, account_id, product, quantity, status, expires_at, checked_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, now())
         ON CONFLICT (source, ref) DO UPDATE SET quantity = $5, status = $6, expires_at = $7, checked_at = now()`,
        [source, ref, accountId, product, quantity, status, expiresAt == null ? null : new Date(expiresAt).toISOString()],
    );
}

export async function markRefunded(source, ref) {
    await query(`UPDATE entitlements SET status = 'refunded', checked_at = now() WHERE source = $1 AND ref = $2`, [source, ref]);
}

export async function setStripeCustomer(accountId, customer) {
    await query(`UPDATE accounts SET stripe_customer = $2 WHERE id = $1 AND stripe_customer IS NULL`, [accountId, customer]);
}

/** Notes a Stripe event as applied, for support. */
export async function recordEvent(eventId) {
    await query(`INSERT INTO webhook_events (id) VALUES ($1) ON CONFLICT DO NOTHING`, [eventId]);
}

/** The newest backup's details, without its contents. */
export async function backupInfo(accountId) {
    const rows = await query(`SELECT version, size, device_name, saved_at FROM backups WHERE account_id = $1 ORDER BY version DESC`, [
        accountId,
    ]);
    return rows.map((r) => ({ version: r.version, size: r.size, device: r.device_name, savedAt: time(r.saved_at) }));
}

/** One version of the backup (the newest when `version` is null), with its contents. */
export async function backup(accountId, version) {
    const rows = await query(
        version == null
            ? `SELECT version, data, saved_at FROM backups WHERE account_id = $1 ORDER BY version DESC LIMIT 1`
            : `SELECT version, data, saved_at FROM backups WHERE account_id = $1 AND version = $2`,
        version == null ? [accountId] : [accountId, version],
    );
    return rows[0] ? { version: rows[0].version, data: rows[0].data, savedAt: time(rows[0].saved_at) } : null;
}

/**
 * Saves a new version on top of `base`, only if `base` is still the newest (0 when there is none), so two devices
 * can't overwrite each other unseen; keeps the last few versions. The new version, or null when `base` is stale.
 */
export async function saveBackup(accountId, base, data, size, deviceName) {
    const [, saved] = await transaction([
        [`SELECT pg_advisory_xact_lock(hashtextextended($1, 1))`, [accountId]],
        [
            `INSERT INTO backups (account_id, version, data, size, device_name)
             SELECT $1, $2 + 1, $3, $4, $5
             WHERE COALESCE((SELECT max(version) FROM backups WHERE account_id = $1), 0) = $2
             RETURNING version`,
            [accountId, base, data, size, deviceName],
        ],
        [
            `DELETE FROM backups WHERE account_id = $1
             AND version <= (SELECT max(version) FROM backups WHERE account_id = $1) - $2`,
            [accountId, BACKUP_VERSIONS],
        ],
    ]);
    return saved[0]?.version ?? null;
}

export async function deleteBackups(accountId) {
    await query(`DELETE FROM backups WHERE account_id = $1`, [accountId]);
    // The channel lists the app shares come from the same playlists, so they go with the backup.
    await ensureChannelLists();
    await query(`DELETE FROM channel_lists WHERE account_id = $1`, [accountId]);
}

// The channel lists' table, made the first time it's needed, so the website works before schema.sql is run again.
let channelListsReady = null;
function ensureChannelLists() {
    channelListsReady ??= query(
        `CREATE TABLE IF NOT EXISTS channel_lists (
            account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
            playlist_id text NOT NULL,
            data text NOT NULL,
            size integer NOT NULL,
            channels integer NOT NULL,
            device_name text NOT NULL,
            saved_at timestamptz NOT NULL DEFAULT now(),
            PRIMARY KEY (account_id, playlist_id)
        )`,
    ).catch((e) => {
        channelListsReady = null;
        throw e;
    });
    return channelListsReady;
}

/**
 * Saves a playlist's channel list from the app (docs/decisions/web.md › The app's copy), replacing that playlist's last
 * one, and keeps only the account's `keep` newest playlists.
 */
export async function saveChannelList(accountId, playlistId, data, size, channels, deviceName, keep) {
    await ensureChannelLists();
    await transaction([
        [
            `INSERT INTO channel_lists (account_id, playlist_id, data, size, channels, device_name, saved_at)
             VALUES ($1, $2, $3, $4, $5, $6, now())
             ON CONFLICT (account_id, playlist_id) DO UPDATE
             SET data = EXCLUDED.data, size = EXCLUDED.size, channels = EXCLUDED.channels,
                 device_name = EXCLUDED.device_name, saved_at = EXCLUDED.saved_at`,
            [accountId, playlistId, data, size, channels, deviceName],
        ],
        [
            `DELETE FROM channel_lists WHERE account_id = $1 AND playlist_id NOT IN
             (SELECT playlist_id FROM channel_lists WHERE account_id = $1 ORDER BY saved_at DESC LIMIT $2)`,
            [accountId, keep],
        ],
    ]);
}

/** A playlist's channel list from the app (gzipped JSON in base64), with when and where it was saved, or null. */
export async function channelList(accountId, playlistId) {
    await ensureChannelLists();
    const rows = await query(`SELECT data, channels, device_name, saved_at FROM channel_lists WHERE account_id = $1 AND playlist_id = $2`, [
        accountId,
        playlistId,
    ]);
    const r = rows[0];
    return r ? { data: r.data, channels: r.channels, device: r.device_name, savedAt: time(r.saved_at) } : null;
}

/** Deletes the account and, through the foreign keys, its devices, entitlements, backups and channel lists. */
export async function deleteAccount(accountId) {
    await query(`DELETE FROM accounts WHERE id = $1`, [accountId]);
}
