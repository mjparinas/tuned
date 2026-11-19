// The public website's settings (docs/decisions/website.md). One place for the address, so a domain change is one line.
export const SITE = {
    name: "Tuned",
    url: "https://www.tunedplayer.com",
    // The newest GitHub release's APK. GitHub keeps this address pointing at the latest release, so it never goes stale.
    apk: "https://github.com/mjparinas/tuned/releases/latest/download/tuned.apk",
    releases: "https://github.com/mjparinas/tuned/releases",
    // The store listings: empty until each exists, and then its badge becomes a link instead of "Coming soon".
    stores: {
        googlePlay: "",
        appStore: "",
        macAppStore: "",
        samsungTv: "",
    },
    privacy: "https://github.com/mjparinas/tuned/blob/main/docs/privacy.md",
};
