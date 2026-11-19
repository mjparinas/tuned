// Postgres on Neon (added to the Vercel project from the Marketplace, which sets DATABASE_URL), over Neon's HTTP
// driver, so each call is one HTTPS request and nothing holds a connection open between requests.
let client = null;

async function sql() {
    if (client) return client;
    const url = process.env.DATABASE_URL;
    if (!url) throw new Error("The database isn't connected to this Vercel project");
    const { neon } = await import("@neondatabase/serverless");
    client = neon(url);
    return client;
}

/** One statement; its rows. */
export async function query(text, params = []) {
    return (await sql()).query(text, params);
}

/** Several statements in one transaction, run in order; the rows of each. */
export async function transaction(statements) {
    const db = await sql();
    return db.transaction(statements.map(([text, params]) => db.query(text, params ?? [])));
}

export const time = (value) => (value == null ? null : new Date(value).getTime());
