-- The admin's own tables (docs/decisions/admin.md), beside the accounts tables in schema.sql. Plain and idempotent:
-- run it by hand in Neon's SQL editor after schema.sql, and again whenever it changes.

-- One row per app install: a random id the app makes (never Android's advertising id), and what the device is.
CREATE TABLE IF NOT EXISTS installs (
    id text PRIMARY KEY,
    first_seen timestamptz NOT NULL DEFAULT now(),
    last_seen timestamptz NOT NULL DEFAULT now(),
    build text,
    version text,
    version_code integer,
    android text,
    sdk integer,
    maker text,
    model text,
    kind text,
    language text,
    time_zone text,
    country text,
    source text,
    utm_source text,
    utm_medium text,
    utm_campaign text,
    test boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS installs_first_seen ON installs (first_seen DESC);
CREATE INDEX IF NOT EXISTS installs_last_seen ON installs (last_seen DESC);

-- Allow-listed app events (lib/admin/analytics.mjs EVENTS). Kept 25 months.
CREATE TABLE IF NOT EXISTS app_events (
    id bigserial PRIMARY KEY,
    install_id text NOT NULL,
    at timestamptz NOT NULL,
    name text NOT NULL,
    props jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS app_events_name_at ON app_events (name, at DESC);
CREATE INDEX IF NOT EXISTS app_events_install ON app_events (install_id, at DESC);

-- The website's cookieless counter: a visitor is a once-a-day salted hash, never an address.
CREATE TABLE IF NOT EXISTS page_views (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    visitor text NOT NULL,
    kind text NOT NULL DEFAULT 'view',
    path text NOT NULL,
    target text,
    referrer text,
    utm_source text,
    utm_medium text,
    utm_campaign text,
    country text,
    browser text,
    os text,
    device text
);
CREATE INDEX IF NOT EXISTS page_views_at ON page_views (at DESC);

-- Problem reports sent from Settings › Report a problem.
CREATE TABLE IF NOT EXISTS problem_reports (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    install_id text,
    build text,
    version text,
    model text,
    android text,
    what text NOT NULL DEFAULT '',
    crash text NOT NULL DEFAULT '',
    log text NOT NULL DEFAULT '',
    contact text NOT NULL DEFAULT '',
    status text NOT NULL DEFAULT 'open',
    note text NOT NULL DEFAULT '',
    handled_by text,
    handled_at timestamptz
);
CREATE INDEX IF NOT EXISTS problem_reports_status ON problem_reports (status, at DESC);

-- Remote flags: a row only once an admin changes one from its default.
CREATE TABLE IF NOT EXISTS feature_flags (
    key text PRIMARY KEY,
    value jsonb NOT NULL,
    share integer NOT NULL DEFAULT 100,
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Codes that give Premium or seats away, and who used them.
CREATE TABLE IF NOT EXISTS codes (
    code text PRIMARY KEY,
    kind text NOT NULL,
    quantity integer NOT NULL DEFAULT 1,
    max_uses integer NOT NULL DEFAULT 1,
    uses integer NOT NULL DEFAULT 0,
    expires_at timestamptz,
    note text NOT NULL DEFAULT '',
    disabled boolean NOT NULL DEFAULT false,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS code_redemptions (
    code text NOT NULL,
    account_id text NOT NULL,
    at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (code, account_id)
);

-- Every admin sign-in and change.
CREATE TABLE IF NOT EXISTS admin_audit (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    actor text NOT NULL,
    action text NOT NULL,
    detail jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS admin_audit_at ON admin_audit (id DESC);

-- The admins' own tasks, and worked-out to-dos that were snoozed (until a time) or dismissed (until null).
CREATE TABLE IF NOT EXISTS admin_tasks (
    id bigserial PRIMARY KEY,
    title text NOT NULL,
    note text NOT NULL DEFAULT '',
    due date,
    done_at timestamptz,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS admin_todo_hidden (
    key text PRIMARY KEY,
    until timestamptz
);

-- Two-step sign-in for each staff address: the authenticator secret, the last step used, recovery code hashes.
CREATE TABLE IF NOT EXISTS admin_two_factor (
    email text PRIMARY KEY,
    secret text NOT NULL,
    enabled_at timestamptz,
    last_step bigint NOT NULL DEFAULT -1,
    recovery jsonb NOT NULL DEFAULT '[]'
);

-- Small settings: automations, running costs, maintenance mode, when each alert last went out.
CREATE TABLE IF NOT EXISTS admin_settings (
    key text PRIMARY KEY,
    value jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
