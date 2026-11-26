-- Tuned accounts (docs/decisions/accounts.md). Run once in the Neon SQL editor, or with `psql "$DATABASE_URL" -f schema.sql`.
-- Safe to run again: every statement checks first.

CREATE TABLE IF NOT EXISTS accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL,
    name text NOT NULL DEFAULT '',
    stripe_customer text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- A Google or Apple account signing in. `provider` is `google` or `apple`; `subject` is that provider's id for the
-- person, which never changes, unlike the email. The same email on both providers joins to one Tuned account.
CREATE TABLE IF NOT EXISTS identities (
    provider text NOT NULL,
    subject text NOT NULL,
    account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    PRIMARY KEY (provider, subject)
);

-- A phone, tablet or TV signed in to the account. Only hashes of its Android id and its token are kept.
CREATE TABLE IF NOT EXISTS devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    device_hash text NOT NULL,
    token_hash text NOT NULL UNIQUE,
    name text NOT NULL,
    kind text NOT NULL,
    build text NOT NULL,
    seated boolean NOT NULL DEFAULT false,
    first_seen timestamptz NOT NULL DEFAULT now(),
    last_seen timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, device_hash)
);

-- What the account owns, from Play or Stripe. `ref` is a hash of the Play token, or Stripe's payment or subscription id.
CREATE TABLE IF NOT EXISTS entitlements (
    source text NOT NULL,
    ref text NOT NULL,
    account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    product text NOT NULL,
    quantity integer NOT NULL DEFAULT 1,
    status text NOT NULL,
    expires_at timestamptz,
    checked_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, ref)
);
CREATE INDEX IF NOT EXISTS entitlements_account ON entitlements (account_id);

-- The backup: the app's sync copy as gzipped JSON in base64, the last few versions.
CREATE TABLE IF NOT EXISTS backups (
    account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    version integer NOT NULL,
    data text NOT NULL,
    size integer NOT NULL,
    device_name text NOT NULL,
    saved_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, version)
);

-- Stripe events applied, for support (every change is keyed by Stripe's ids, so a repeat changes nothing).
CREATE TABLE IF NOT EXISTS webhook_events (
    id text PRIMARY KEY,
    received_at timestamptz NOT NULL DEFAULT now()
);

-- Each playlist's live channels and next 12 hours of guide, sent by the app for the website's Channels and guide page
-- (docs/decisions/web.md › The app's copy): gzipped JSON in base64, the newest per playlist, a few playlists per account.
CREATE TABLE IF NOT EXISTS channel_lists (
    account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    playlist_id text NOT NULL,
    data text NOT NULL,
    size integer NOT NULL,
    channels integer NOT NULL,
    device_name text NOT NULL,
    saved_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, playlist_id)
);

-- The daily Getting to know Tuned emails (docs/decisions/emails.md): whether an account wants them, and which went out.
-- lib/email-store.mjs also makes these the first time it needs them.
CREATE TABLE IF NOT EXISTS email_prefs (
    account_id uuid PRIMARY KEY REFERENCES accounts (id) ON DELETE CASCADE,
    tips boolean NOT NULL DEFAULT true,
    changed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS email_sends (
    account_id uuid NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    key text NOT NULL,
    sent_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, key)
);
