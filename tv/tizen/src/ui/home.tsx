// Home: Tuned's mark atop a rail (Live TV, Films, Shows, Search, Settings) and the section beside it. Landing on a
// rail item opens its section, so the remote needs no OK to move between them.
import type { Tab } from "./nav.ts";
import { setTab } from "./nav.ts";
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
    return (
        // The section puts the remote on its first item once its list is read (lists.tsx).
        <Screen class="home" noStart>
            <nav class="rail" aria-label="Tuned" data-region>
                <Brand />
                {TABS.map((x) => (
                    <button
                        key={x.tab}
                        type="button"
                        class={x.tab === props.tab ? "rail-item picked" : "rail-item"}
                        aria-current={x.tab === props.tab ? "page" : undefined}
                        data-focus
                        onFocus={() => x.tab !== props.tab && setTab(x.tab)}
                        onClick={() => setTab(x.tab)}
                    >
                        <span class="icon" aria-hidden="true">
                            {x.icon}
                        </span>
                        {x.label}
                    </button>
                ))}
            </nav>
            <main class="content" key={props.tab} data-region>
                {props.tab === "live" ? <LiveTv height={HEIGHT} /> : null}
                {props.tab === "movie" ? <Posters kind="movie" height={HEIGHT} /> : null}
                {props.tab === "series" ? <Posters kind="series" height={HEIGHT} /> : null}
                {props.tab === "search" ? <Search height={HEIGHT} /> : null}
                {props.tab === "settings" ? <Settings height={HEIGHT} /> : null}
            </main>
        </Screen>
    );
}
