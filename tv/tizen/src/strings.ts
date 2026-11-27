// Every word a person reads on the TV, in one place so the 11 translations (docs/decisions/languages.md) can be added
// beside it, picked by the TV's language as on Android. "Tuned" is never translated.
export const t = {
    noContent: "Tuned includes no content. Use it with a playlist from your own provider.",
    welcome: "Watch your own playlist on your Samsung TV",
    signInTitle: "Add a playlist with your phone",
    signInStep1: "On your phone, go to",
    signInStep2: "and enter this code, or scan the QR code.",
    signInDigits: (d: string) => `If your phone asks, the TV's check digits are ${d}.`,
    signInWhat: "Send a playlist from your phone, or sign in to your Tuned account for Premium.",
    gettingCode: "Getting a code…",
    relayOffline: "Couldn't reach Tuned's sign-in service. Check the TV's internet connection.",
    codeExpiresIn: (m: number) => `This code works for ${m} more min.`,
    typeInstead: "Type a login with the remote",
    tryAgain: "Try again",
    back: "Back",
    arrived: "Got it. Adding your playlist…",
    signedInPremium: "Signed in to your Tuned account.",
    xtream: "Xtream login",
    link: "Playlist link",
    server: "Server",
    username: "Username",
    password: "Password",
    playlistUrl: "Playlist link (M3U)",
    guideUrl: "Guide link (XMLTV, optional)",
    add: "Add playlist",
    fillXtream: "Fill in the server, username and password.",
    fillLink: "Fill in the playlist link.",
    loadingTitle: "Getting your channels",
    steps: {
        login: "Signing in to your provider",
        live: "Downloading live channels",
        movies: "Downloading films",
        shows: "Downloading shows",
        playlist: "Downloading the playlist",
        guide: "Downloading the TV guide",
    } as Record<string, string>,
    stepOf: (n: number, count: number) => `Step ${n} of ${count}`,
    loadFailed: {
        login: "Your provider didn't accept this login. Check the username and password.",
        unreachable: "Couldn't reach your provider. Check the TV's internet connection and the server address.",
        empty: "The playlist is empty.",
        server: "Your provider's server had a problem. Try again in a few minutes.",
    } as Record<string, string>,
    changeLogin: "Change login",
    live: "Live TV",
    movies: "Films",
    shows: "Shows",
    search: "Search",
    settings: "Settings",
    all: "All",
    favourites: "Favourites",
    nothingHere: "Nothing here yet.",
    nowOn: "Now",
    nextOn: "Next",
    noGuide: "No guide for this channel",
    play: "Play",
    resume: (time: string) => `Resume from ${time}`,
    fromStart: "Play from the start",
    favourite: "Favourite",
    unfavourite: "Remove from Favourites",
    season: (n: number) => `Season ${n}`,
    episode: (s: number, e: number) => `S${s} E${e}`,
    episodeNumber: (e: number) => `Episode ${e}`,
    loadingEpisodes: "Getting episodes…",
    noEpisodes: "No episodes found for this show.",
    searchHint: "Type to search channels, films and shows",
    noResults: "Nothing matches.",
    refreshNow: "Refresh now",
    refreshing: "Refreshing…",
    upToDate: (when: string) => `Up to date, refreshed ${when}`,
    refreshFailed: "The last refresh didn't finish.",
    playlists: "Playlists",
    addPlaylist: "Add another playlist",
    premiumForMore: "More than one playlist is Premium. Sign in to your Tuned account on this TV.",
    using: "In use",
    use: "Use",
    remove: "Remove",
    account: "Tuned account",
    accountSignedIn: (email: string) => `Signed in as ${email}`,
    accountPremium: "Premium",
    accountFree: "Free",
    accountSignedOut: "Not signed in. Premium comes with a Tuned account: buy it at tunedplayer.com, then sign in here.",
    accountSignIn: "Sign in with your phone",
    channelNumbers: "Channel numbers",
    hideAdult: "Hide adult groups",
    on: "On",
    off: "Off",
    log: "App log",
    signOut: "Sign out of everything",
    signOutConfirm: "Sign out and remove every playlist from this TV?",
    cancel: "Cancel",
    opening: "Opening…",
    playFailed: "Couldn't play this. The stream may be down, or your provider allows fewer connections.",
    channelLine: (n: number, name: string) => (n ? `${n}  ${name}` : name),
    audio: "Audio",
    captions: "Captions",
    captionsOff: "Off",
    track: (i: number) => `Track ${i + 1}`,
    justNow: "just now",
    minutesAgo: (m: number) => `${m} min ago`,
    hoursAgo: (h: number) => `${h} h ago`,
    left: (eta: string) => {
        const [unit, value] = eta.split(":");
        return unit === "s" ? `about ${value} s left` : unit === "m" ? `about ${value} min left` : `about ${value} h left`;
    },
};

export function ago(at: number, now = Date.now()): string {
    const min = Math.floor((now - at) / 60_000);
    if (min < 1) return t.justNow;
    if (min < 60) return t.minutesAgo(min);
    return t.hoursAgo(Math.floor(min / 60));
}

export function clock(ms: number): string {
    const s = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    const two = (n: number) => String(n).padStart(2, "0");
    return h ? `${h}:${two(m)}:${two(sec)}` : `${m}:${two(sec)}`;
}

export function timeOfDay(ms: number): string {
    return new Date(ms).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

export function bytes(n: number): string {
    if (n < 1024 * 1024) return `${Math.max(1, Math.round(n / 1024))} KB`;
    return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
