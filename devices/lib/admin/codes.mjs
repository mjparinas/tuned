// Codes (docs/decisions/admin.md): Premium or extra seats given away, made on the admin's Codes tab and redeemed by
// a signed-in account on the website. A redeemed code becomes a `grant` entitlement, so it counts like a purchase.
import { randomBytes } from "node:crypto";

const LETTERS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const KINDS = {
    premium: { label: "Premium for life", product: "premium_lifetime" },
    seats: { label: "Extra seats", product: "extra_seat" },
};
export const MAX_SEATS_PER_CODE = 10;

/** Uppercase letters, digits and dashes, 4 to 24 long, or null. */
export function normalizeCode(value) {
    const code = String(value ?? "")
        .trim()
        .toUpperCase()
        .replace(/\s+/g, "-");
    return /^[A-Z0-9][A-Z0-9-]{2,22}[A-Z0-9]$/.test(code) ? code : null;
}

/** A random code like `TUNED-7KQM-2XPA`. */
export function newCode(random = randomBytes) {
    const chars = [...random(8)].map((b) => LETTERS[b % LETTERS.length]).join("");
    return `TUNED-${chars.slice(0, 4)}-${chars.slice(4)}`;
}

/** Checks the admin's form: `{ code?, kind, quantity, maxUses, days, note }`. */
export function checkNewCode(input, random = randomBytes) {
    const kind = KINDS[input?.kind] ? input.kind : null;
    if (!kind) return { error: "Pick what the code gives." };
    const code = input.code ? normalizeCode(input.code) : newCode(random);
    if (!code) return { error: "Use 4 to 24 letters, digits or dashes." };
    const quantity = kind === "seats" ? Number(input.quantity) : 1;
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > MAX_SEATS_PER_CODE) return { error: `Seats are 1 to ${MAX_SEATS_PER_CODE}.` };
    const maxUses = Number(input.maxUses ?? 1);
    if (!Number.isInteger(maxUses) || maxUses < 1 || maxUses > 10_000) return { error: "Uses are 1 to 10,000." };
    const days = input.days ? Number(input.days) : null;
    if (days !== null && (!Number.isInteger(days) || days < 1 || days > 3650)) return { error: "Days are 1 to 3,650." };
    return { code, kind, quantity, maxUses, days, note: String(input.note ?? "").slice(0, 200) };
}

/** Why a code can't be redeemed now, or null when it can. */
export function redeemProblem(row, alreadyUsedByAccount, now = Date.now()) {
    if (!row || row.disabled) return "not found";
    if (row.expires_at && new Date(row.expires_at).getTime() <= now) return "expired";
    if (row.uses >= row.max_uses) return "used up";
    if (alreadyUsedByAccount) return "already used";
    return null;
}
