// The website's cookieless counter (docs/decisions/admin.md): one page view per load, and a click for any element
// with data-track="name". Nothing is stored in the browser; the server counts a visitor once a day from a salted hash
// it never keeps. Global Privacy Control is honoured on both sides.
(() => {
    if (navigator.globalPrivacyControl || location.pathname.startsWith("/admin")) return;
    const q = new URLSearchParams(location.search);
    const base = {
        path: location.pathname,
        referrer: document.referrer || null,
        utmSource: q.get("utm_source"),
        utmMedium: q.get("utm_medium"),
        utmCampaign: q.get("utm_campaign"),
    };
    const send = (extra) => {
        const body = JSON.stringify({ ...base, ...extra });
        if (!navigator.sendBeacon?.("/api/pv", new Blob([body], { type: "application/json" })))
            fetch("/api/pv", { method: "POST", body, headers: { "Content-Type": "application/json" }, keepalive: true }).catch(() => {});
    };
    send({ kind: "view" });
    document.addEventListener("click", (e) => {
        const el = e.target.closest?.("[data-track]");
        if (el) send({ kind: "click", target: el.dataset.track });
    });
})();
