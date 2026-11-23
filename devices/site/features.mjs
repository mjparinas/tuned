// Every feature of Tuned, in the words of the website (docs/decisions/website.md). The one list the landing page's
// "Everything in Tuned" section and /features.json are built from, so the account page and anything else can show the
// same list. Built from AGENTS.md and docs/decisions/: when a feature is added or removed there, change it here too.
// `premium: true` marks what Tuned Premium unlocks.

export const FEATURE_GROUPS = [
    {
        id: "playlists",
        title: "Bring your playlist",
        icon: "playlist",
        items: [
            {
                title: "Xtream Codes logins",
                body: "A server address, username and password, read through the Xtream API when a provider blocks the M3U.",
            },
            { title: "M3U links and files", body: "Any M3U or M3U8 link, or a file on the device, with its XMLTV guide." },
            { title: "MAC portals", body: "Sign in to a Stalker portal with the MAC address your provider registered." },
            { title: "Several playlists", body: "Keep as many playlists as you like and switch between them.", premium: true },
            { title: "Kept fresh in the background", body: "Channels and the guide refresh on a schedule, on Wi-Fi only if you like." },
            { title: "Countries and languages", body: "Hide the countries, languages and channel groups you never watch." },
            { title: "Guide fixes", body: "Move a channel's times, add extra guide links, or pick a channel's guide entry." },
        ],
    },
    {
        id: "live",
        title: "Live TV",
        icon: "live",
        items: [
            { title: "Opens straight to your channels", body: "The list comes from the device, so nothing waits on the network." },
            { title: "Grid guide", body: "A full TV guide, with what's on now on every channel row." },
            { title: "Quick filters", body: "Sports, PPV, News, Kids, Movie channels, Music, Documentaries, country and language." },
            { title: "Channel numbers and flip back", body: "Type a number on the remote, and jump back to the last channel." },
            { title: "Live events", body: "Today's games and fights from the guide, with Tune to find a working stream." },
            { title: "Follow teams and get alerts", body: "Follow a team, league or fighter and hear when they're on.", premium: true },
            { title: "Pause and rewind live TV", body: "Go back in time on a live channel, then jump to live.", premium: true },
            { title: "Record what you're watching", body: "Save the channel on screen to the device.", premium: true },
            { title: "Backup copies", body: "When a channel dies, Tuned switches to another copy of it." },
            { title: "Channel health", body: "A scan finds channels that never play and hides them, without blaming your internet." },
            { title: "Guide reminders", body: "A reminder before a programme starts." },
        ],
    },
    {
        id: "library",
        title: "Movies and shows",
        icon: "film",
        items: [
            { title: "Posters, ratings and details", body: "Summaries, cast and trailers from TMDB, kept on the device." },
            { title: "Real episode names", body: "Episode names and pictures instead of S1 E1." },
            { title: "Actor pages", body: "Tap an actor to see their films and shows, with what's in your playlist marked." },
            { title: "Continue watching", body: "Pick up where you left off, and see new episodes since you last looked." },
            { title: "Watch later and watched", body: "Save films and episodes for later, and mark what you've seen." },
            { title: "Skip intro", body: "A button that learns each show's intro." },
            { title: "Downloads", body: "Save films, episodes or a whole season and watch without a connection." },
            { title: "Trakt", body: "Your history and up next on Trakt, from every device.", premium: true },
            { title: "Simkl", body: "Your history and watching now on Simkl, from every device.", premium: true },
        ],
    },
    {
        id: "player",
        title: "The player",
        icon: "play",
        items: [
            { title: "Fast start", body: "Media3 with fast-start buffering, and the controls out of the way until you tap." },
            { title: "Captions your way", body: "Size, font, colours and edges, timing in tenths of a second, and subtitle search." },
            { title: "Audio language and sound delay", body: "Pick the language, and line the sound up with the picture." },
            { title: "Cast to your TV", body: "Chromecast and DLNA TVs, with the phone as the remote." },
            {
                title: "Picture-in-picture and background play",
                body: "Keep watching in a small window, or keep listening with the screen off.",
            },
            { title: "Gestures", body: "Swipe for brightness and volume, double-tap to skip, swipe sideways to change channel." },
            { title: "Reconnects on its own", body: "A dropped stream comes back by itself." },
            { title: "Fit, zoom and lock", body: "Fit, fill or stretch the picture, and lock the screen against pockets." },
            { title: "Quality button", body: "Switch between a channel's 4K, FHD, HD and SD copies." },
            { title: "Playback speed", body: "Faster or slower films and episodes." },
        ],
    },
    {
        id: "screens",
        title: "Every screen",
        icon: "tv",
        items: [
            { title: "Android TV and Google TV", body: "Made for the remote, with clear focus and a row on the TV's home screen." },
            { title: "Sign in on the TV with your phone", body: "Scan a code and type your playlist on the phone, not with the remote." },
            { title: "Tablets", body: "A side rail, list and details side by side, and the guide beside the player." },
            { title: "Match frame rate", body: "The TV switches to the video's frame rate for smooth motion." },
            { title: "Lighter mode", body: "A lighter look for slow TV boxes." },
            { title: "Widgets", body: "Favourite channels and Continue watching on the home screen." },
        ],
    },
    {
        id: "family",
        title: "For the whole home",
        icon: "people",
        items: [
            { title: "Profiles", body: "Up to six, each with its own favourites, history and picture.", premium: true },
            {
                title: "Kids profiles and PINs",
                body: "Kids profiles, profile PINs and a parental PIN for adult categories.",
                premium: true,
            },
            { title: "Favourites and your own lists", body: "Favourites in one tap, and your own lists beside it.", premium: true },
            { title: "Rename channels", body: "Give a channel a better name or logo.", premium: true },
            { title: "Themes and accent colours", body: "Light, dark and pure black, with your choice of accent colour.", premium: true },
        ],
    },
    {
        id: "trust",
        title: "Private and dependable",
        icon: "lock",
        items: [
            { title: "Your login stays encrypted", body: "Kept encrypted on the device until you sign out, and never logged." },
            { title: "One stream at a time", body: "Tuned never opens a second connection, so your provider's limit is safe." },
            { title: "Watching comes first", body: "Downloads and scans wait while anything plays." },
            { title: "Tuned account", body: "Sign in with Google and every device keeps the same playlists and settings.", premium: true },
            { title: "No ads", body: "No ads, ever." },
            { title: "Updates itself", body: "Tuned offers each new version and shows what's new." },
            { title: "Accessible", body: "Works with TalkBack and large text." },
        ],
    },
];
