// The admin's arithmetic, kept apart from the database so it can be tested (docs/decisions/admin.md).

export const PERIODS = [1, 7, 30, 90, 365];
export const DAY_MS = 86_400_000;

/** `?days=` as one of the periods, 30 when missing or odd. */
export const periodOf = (value) => (PERIODS.includes(Number(value)) ? Number(value) : 30);

/** The change from `before` to `now` in percent, or null when there was nothing before. */
export function change(now, before) {
    if (!before) return null;
    return Math.round(((now - before) / before) * 100);
}

/**
 * A funnel whose steps never grow: each step is at most the one before (someone can't buy without installing), and
 * each carries its share of the first step and of the step before.
 */
export function funnel(steps) {
    let cap = Infinity;
    const first = steps[0]?.count || 0;
    let previous = null;
    return steps.map((step) => {
        const count = Math.min(step.count, cap);
        cap = count;
        const out = {
            label: step.label,
            count,
            ofFirst: first ? Math.round((count / first) * 1000) / 10 : 0,
            ofPrevious: previous ? Math.round((count / previous) * 1000) / 10 : 100,
        };
        previous = count;
        return out;
    });
}

/** The step that loses the most people, once at least `min` reached the step before it. */
export function biggestDrop(steps, min = 5) {
    let worst = null;
    for (let i = 1; i < steps.length; i++) {
        const from = steps[i - 1].count;
        if (from < min) continue;
        const lost = Math.round((1 - steps[i].count / from) * 100);
        if (!worst || lost > worst.lost) worst = { from: steps[i - 1].label, to: steps[i].label, lost };
    }
    return worst;
}

/** List prices in US cents. The stores and Stripe can't say what tax took, so revenue is gross at list price. */
export const PRICES = { premium_yearly: 999, premium_lifetime: 2499, extra_seat: 499, extra_seat_yearly: 199 };
export const FEES = { play: (cents) => Math.round(cents * 0.15), web: (cents) => Math.round(cents * 0.029) + 30, grant: () => 0 };

/**
 * Sums purchases `{ source, product, quantity, status }` into gross and net, by product and by store. Refunded rows
 * count against gross; given-away (`grant`) rows count as nothing.
 */
export function revenue(rows) {
    const out = { gross: 0, net: 0, refunds: 0, byProduct: {}, bySource: {}, count: 0 };
    for (const row of rows) {
        if (row.source === "grant") continue;
        const cents = (PRICES[row.product] ?? 0) * Math.max(1, row.quantity ?? 1);
        const fee = (FEES[row.source] ?? FEES.web)(cents);
        if (row.status === "refunded") {
            out.refunds += cents;
            continue;
        }
        out.count += 1;
        out.gross += cents;
        out.net += cents - fee;
        out.byProduct[row.product] = (out.byProduct[row.product] ?? 0) + cents;
        out.bySource[row.source] = (out.bySource[row.source] ?? 0) + cents;
    }
    return out;
}

/** Yearly plans as a monthly figure: each active yearly row is a twelfth of its price. */
export function mrr(rows) {
    let cents = 0;
    for (const row of rows) {
        if (row.status !== "active" || row.source === "grant") continue;
        if (row.product === "premium_yearly" || row.product === "extra_seat_yearly") cents += ((PRICES[row.product] ?? 0) * Math.max(1, row.quantity ?? 1)) / 12;
    }
    return Math.round(cents);
}

/** Running costs `{ name, cents, every: "month" | "year" }` as a monthly total. */
export const monthlyCosts = (costs) => Math.round(costs.reduce((sum, c) => sum + (c.every === "year" ? c.cents / 12 : c.cents), 0));

/** Days from `start` to `end` filled with zeros where a day had nothing, for charts. */
export function fillDays(rows, days, end = Date.now()) {
    const byDay = new Map(rows.map((r) => [r.day, Number(r.count)]));
    const out = [];
    for (let i = days - 1; i >= 0; i--) {
        const day = new Date(end - i * DAY_MS).toISOString().slice(0, 10);
        out.push({ day, count: byDay.get(day) ?? 0 });
    }
    return out;
}
