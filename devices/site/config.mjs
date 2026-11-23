// The public website's settings (docs/decisions/website.md). One place for the address, so a domain change is one line.
export const SITE = {
    name: "Tuned",
    url: "https://www.tunedplayer.com",
    // The Android download: /download on this site, which vercel.json redirects to the newest release's APK, so the
    // pages never name where the file is hosted (Melvin, 2026-11-20: "dont mention the github app").
    apk: "/download",
    // The store listings: empty until each exists, and then its badge becomes a link instead of "Coming soon".
    stores: {
        googlePlay: "",
        appStore: "",
        macAppStore: "",
        samsungTv: "",
    },
    // The posts in "From the blog" on the landing page, for someone new to Tuned rather than release notes (Melvin,
    // 2026-11-20). A path missing from posts/ is skipped, and the newest guides fill any gap.
    homePosts: ["roundups/best-iptv-player-android-tv.md", "guides/add-media-server.md", "guides/add-xtream-codes-playlist.md"],
    // Built from docs/privacy.md by devices/build-site.mjs.
    privacy: "/privacy/",
};
