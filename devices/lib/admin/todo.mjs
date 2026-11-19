// The To do tab (as in Liftsmith): what needs a person, worked out each time from the data, plus the admins' own
// tasks. Each worked-out item has a key that changes with the data, so snoozing or dismissing one hides only that
// state of it: a new report or a bigger number brings it back.

/**
 * `facts`: { openReports, oldestReportDays, refunds, digestLateHours, missingSettings: [{ names, powers }] } and `hidden`: { [key]: untilMs | null (dismissed) }.
 */
export function derive(facts, hidden = {}, now = Date.now()) {
    const items = [];
    const add = (key, title, detail, tab, kind) => items.push({ key, title, detail, tab, kind });
    if (facts.openReports > 0)
        add(
            `reports:${facts.openReports}`,
            facts.openReports === 1 ? "1 problem report to read" : `${facts.openReports} problem reports to read`,
            facts.oldestReportDays > 1
                ? `The oldest is ${facts.oldestReportDays} days old.`
                : facts.oldestReportDays === 1
                  ? "The oldest is a day old."
                  : "Sent today.",
            "Reports",
            "people",
        );
    if (facts.refunds > 0)
        add(
            `refunds:${facts.refunds}`,
            facts.refunds === 1 ? "1 refund in 14 days" : `${facts.refunds} refunds in 14 days`,
            "Their seats were given back.",
            "Revenue",
            "money",
        );
    if (facts.digestLateHours != null && facts.digestLateHours > 26)
        add("cron-late", "The daily job hasn't run", `Last ran ${Math.round(facts.digestLateHours)} hours ago.`, "Automations", "urgent");
    for (const s of facts.missingSettings ?? [])
        add(`setup:${s.names.join("+")}`, `Set ${s.names.join(" and ")}`, s.powers, "System", "setup");
    const order = { urgent: 0, people: 1, money: 2, setup: 3 };
    return items
        .filter((i) => {
            if (!(i.key in hidden)) return true;
            const until = hidden[i.key];
            return until !== null && until <= now;
        })
        .sort((a, b) => order[a.kind] - order[b.kind]);
}

/** The time a snooze ends: "tomorrow" is 8 AM UTC the next day, "week" is seven days on. */
export function snoozeUntil(choice, now = Date.now()) {
    if (choice === "week") return now + 7 * 86_400_000;
    const d = new Date(now + 86_400_000);
    d.setUTCHours(8, 0, 0, 0);
    return d.getTime();
}
