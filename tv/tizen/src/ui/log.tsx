// Settings › App log: what went wrong, with logins already scrubbed (diagnostics.md). Up and down scroll it.
import { useRef } from "preact/hooks";
import { logLines } from "../log.ts";
import { t } from "../strings.ts";
import { useKeys } from "./nav.ts";
import { Brand, Screen } from "./parts.tsx";

export function LogScreen() {
    const box = useRef<HTMLPreElement>(null);
    useKeys((press) => {
        if (!box.current || (press.action !== "up" && press.action !== "down")) return false;
        box.current.scrollTop += press.action === "down" ? 200 : -200;
        return true;
    });
    return (
        <Screen class="log">
            <Brand />
            <h1>{t.log}</h1>
            <pre ref={box} tabIndex={-1} data-focus data-start>
                {logLines().join("\n") || "—"}
            </pre>
        </Screen>
    );
}
