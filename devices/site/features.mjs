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
            {
                title: "Plex, Jellyfin and Emby",
                body: "Add a home media server for Live TV, films and shows, with the server's own guide when it has one.",
            },
            { title: "Several playlists", body: "Keep as many playlists as you like and switch between them.", premium: true },
            { title: "Switch back instantly", body: "Jump to a recent playlist without waiting on a full download again." },
            { title: "Kept fresh in the background", body: "Channels and the guide refresh on a schedule, on Wi-Fi only if you like." },
            {
                title: "Real progress while it loads",
                body: "Numbered steps, bytes when the size is known, and about how much longer once Tuned can tell.",
            },
            { title: "Countries and languages", body: "Hide the countries, languages and channel groups you never watch." },
            { title: "Guide fixes", body: "Move a channel's times, add extra guide links, or pick a channel's guide entry." },
            {
                title: "Playlists and settings on the web",
                body: "Manage them at tunedplayer.com from a browser; the app joins the backup with its own.",
                premium: true,
            },
        ],
    },
    {
        id: "live",
        title: "Live TV",
        icon: "live",
        items: [
            { title: "Opens straight to your channels", body: "The list comes from the device, so nothing waits on the network." },
            { title: "Grid guide", body: "A full TV guide, with what's on now on every channel row." },
            { title: "Search the whole guide", body: "Find a show or team across every channel's programmes." },
            {
                title: "Quick filters that stick",
                body: "Sports, PPV, News, Kids, quality, on now, guide, catch-up and watched — kept per profile until you change them.",
            },
            { title: "Channel numbers and flip back", body: "Type a number on the remote, and jump back to the last channel." },
            { title: "Live events", body: "Today's games and fights from the guide, with Tune to find a working stream." },
            { title: "Follow teams and get alerts", body: "Follow a team, league or fighter and hear when they're on.", premium: true },
            { title: "Pause and rewind live TV", body: "Go back in time on a live channel, then jump to live.", premium: true },
            { title: "Record what you're watching", body: "Save the channel on screen to the device.", premium: true },
            { title: "Record from the guide", body: "Schedule a programme from its row before it starts.", premium: true },
            { title: "Backup copies", body: "When a channel dies, Tuned switches to another copy of it." },
            { title: "Channel health", body: "A scan finds channels that never play and hides them, without blaming your internet." },
            { title: "Guide reminders", body: "A reminder before a programme starts." },
            {
                title: "Channels and guide on the web",
                body: "The same Live TV guide in your browser: filters, logos, and a click that opens VLC or copies the link.",
                premium: true,
            },
            {
                title: "Watch in the browser",
                body: "Premium Live TV at tunedplayer.com/watch from your app's channel copy, one stream at a time.",
                premium: true,
            },
        ],
    },
    {
        id: "library",
        title: "Movies and shows",
        icon: "film",
        items: [
            { title: "Posters, ratings and details", body: "Summaries, cast and trailers from TMDB, kept on the device." },
            { title: "Real episode names", body: "Episode names and pictures instead of S1 E1." },
            { title: "Sort your way", body: "Newest, year, rating as the poster shows it, or A to Z." },
            { title: "Actor pages", body: "Tap an actor to see their films and shows, with what's in your playlist marked." },
            { title: "Continue watching", body: "Pick up where you left off, and see new episodes since you last looked." },
            {
                title: "Favourites and watched",
                body: "Heart channels, films and whole shows to find them again, and mark what you've seen.",
            },
            { title: "Skip intro", body: "A button that learns each show's intro." },
            {
                title: "Downloads",
                body: "Save films, episodes or a whole season on a phone or tablet and watch without a connection.",
            },
            {
                title: "Trakt",
                body: "Scrobble films and episodes, and import your watched history into Tuned.",
                premium: true,
            },
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
            {
                title: "Dolby and DTS where the device can't",
                body: "Surround sound plays on phones and boxes that lack those decoders.",
            },
            { title: "Cast to your TV", body: "Chromecast and DLNA TVs, with the phone as the remote while it plays." },
            {
                title: "Mini player, picture-in-picture and background play",
                body: "Back shrinks live TV, films and episodes so you can browse; leave the app for a small window, or keep listening with the screen off.",
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
            {
                title: "Android TV and Google TV",
                body: "Made for the remote: clear focus, Tuned's logo up top, dark by default, and layouts that use the big screen.",
            },
            {
                title: "Calibrate the TV screen",
                body: "Move and stretch the overscan margin so nothing is cropped on your set.",
            },
            {
                title: "Cinematic show pages on TV",
                body: "Full-screen hero, season tabs and episode stills — not a narrow side pane.",
            },
            {
                title: "Movies and Shows beside the list",
                body: "On a TV, posters in the middle and details of the title under the remote beside them.",
            },
            {
                title: "Search with the remote",
                body: 'Tuned\'s own D-pad keyboard and inline voice, with no "use a keyboard on your device" strip.',
            },
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
            {
                title: "Favourites and your own lists",
                body: "Favourites in one tap, and your own lists managed in Settings or on the website.",
                premium: true,
            },
            {
                title: "Rename channels",
                body: "Give a channel a better name or logo on a phone, tablet or the website.",
                premium: true,
            },
            { title: "Themes and accent colours", body: "Light, dark and pure black, with your choice of accent colour.", premium: true },
            {
                title: "Eleven languages",
                body: "Spanish, Portuguese, French, German, Italian, Turkish, Russian, Polish, Arabic, Hindi and Chinese, from the device's language.",
            },
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
            {
                title: "Tuned account",
                body: "Sign in with Google or Apple and every device keeps the same playlists and settings. If a first download fails, Live TV can fill from the account's copy.",
                premium: true,
            },
            { title: "No ads", body: "No ads, ever." },
            {
                title: "Updates itself",
                body: "Tuned offers each new version, with real progress while it downloads and installs.",
            },
            { title: "Accessible", body: "Works with TalkBack and large text." },
        ],
    },
];
