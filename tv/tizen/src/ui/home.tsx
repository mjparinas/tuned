// Home: Tuned's mark atop a rail (Live TV, Movies, Shows, Search, Settings) and the section beside it. Landing on a
// rail item opens its section, so the remote needs no OK to move between them.
import type { Tab } from "./nav.ts";
import { open, setTab } from "./nav.ts";
import { currentProfile, profileId, profiles } from "../app.ts";
import { useStore } from "../state.ts";
import { Avatar } from "./profiles.tsx";
import { t } from "../strings.ts";
import { Brand, Screen } from "./parts.tsx";
import { LiveTv } from "./live.tsx";
import { Posters } from "./posters.tsx";
import { Search } from "./search.tsx";
import { Settings } from "./settings.tsx";

const TABS: { tab: Tab; label: string; icon: string }[] = [
    { tab: "live", label: t.live, icon: "▶" },
    { tab: "movie", label: t.movies, icon: "◼" },
    { tab: "series", label: t.shows, icon: "▤" },
    { tab: "search", label: t.search, icon: "⌕" },
    { tab: "settings", label: t.settings, icon: "⚙" },
];

/** The content's height inside the overscan margin. */
const HEIGHT = 1080 - 2 * 48;

export function Home(props: { tab: Tab }) {
    useStore(profileId);
    const many = useStore(profiles).profiles.length > 1;
    const who = currentProfile();
    // A kids profile has no Settings, as on Android (profiles.md).
    const tabs = who?.kids ? TABS.filter((x) => x.tab !== "settings") : TABS;
    const tab = who?.kids && props.tab === "settings" ? "live" : props.tab;
    return (
        // The section puts the remote on its first item once its list is read (lists.tsx).
        <Screen class="home" noStart>
            <nav class="rail" aria-label="Tuned" data-region>
                <Brand />
                {tabs.map((x) => (
                    <button
                        key={x.tab}
                        type="button"
                        class={x.tab === tab ? "rail-item picked" : "rail-item"}
                        aria-current={x.tab === tab ? "page" : undefined}
                        data-focus
                        onFocus={() => x.tab !== tab && setTab(x.tab)}
                        onClick={() => setTab(x.tab)}
                    >
                        <span class="icon" aria-hidden="true">
                            {x.icon}
                        </span>
                        {x.label}
                    </button>
                ))}
                {many && who ? (
                    // Switching profile is the rail's last item, so landing on it opens nothing; OK does.
                    <button
                        type="button"
                        class="rail-item who"
                        data-focus
                        aria-label={t.switchProfile}
                        onClick={() => open({ name: "profiles" })}
                    >
                        <Avatar profile={who} size={44} />
                        <span class="grow">{who.name}</span>
                    </button>
                ) : null}
            </nav>
            <main class="content" key={tab} data-region>
                {tab === "live" ? <LiveTv height={HEIGHT} /> : null}
                {tab === "movie" ? <Posters kind="movie" height={HEIGHT} /> : null}
                {tab === "series" ? <Posters kind="series" height={HEIGHT} /> : null}
                {tab === "search" ? <Search height={HEIGHT} /> : null}
                {tab === "settings" ? <Settings height={HEIGHT} /> : null}
            </main>
        </Screen>
    );
}
