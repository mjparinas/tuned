// Small pieces every screen uses: a focusable button, the spinner, a progress line with real numbers, the QR code and
// Tuned's mark and name in the upper left (tv.md › TvBrand).
import type { ComponentChildren } from "preact";
import { useEffect, useMemo, useRef } from "preact/hooks";
import qrcode from "qrcode-generator";
import { focusStart } from "../focus.ts";
import { t } from "../strings.ts";

export function Button(props: {
    label: ComponentChildren;
    onClick: () => void;
    start?: boolean;
    disabled?: boolean;
    busy?: boolean;
    kind?: "primary" | "plain";
    id?: string;
}) {
    return (
        <button
            type="button"
            id={props.id}
            class={`button ${props.kind ?? "plain"}`}
            data-focus
            data-start={props.start ? "" : undefined}
            disabled={props.disabled}
            aria-busy={props.busy ? "true" : undefined}
            onClick={() => !props.busy && props.onClick()}
        >
            {props.busy ? <Spinner small /> : null}
            <span>{props.label}</span>
        </button>
    );
}

export function Spinner(props: { small?: boolean }) {
    return <span class={props.small ? "spinner small" : "spinner"} role="progressbar" aria-label={t.loading} />;
}

/** A bar with what's done of the whole, when the whole is known; otherwise a moving bar. */
export function Bar(props: { done: number; total: number }) {
    const known = props.total > 0;
    const share = known ? Math.min(1, props.done / props.total) : 0;
    return (
        <div class={known ? "bar" : "bar unknown"} role="progressbar" aria-valuenow={known ? Math.round(share * 100) : undefined}>
            <div style={{ width: known ? `${share * 100}%` : "30%" }} />
        </div>
    );
}

export function Qr(props: { text: string; size: number }) {
    const svg = useMemo(() => {
        const qr = qrcode(0, "M");
        qr.addData(props.text);
        qr.make();
        return qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
    }, [props.text]);
    return <div class="qr" style={{ width: `${props.size}px`, height: `${props.size}px` }} dangerouslySetInnerHTML={{ __html: svg }} />;
}

export function Brand() {
    return (
        <div class="brand">
            <img src="./icon-512.png" alt="" width={44} height={44} />
            <span>Tuned</span>
        </div>
    );
}

/** A screen: inside the overscan margin, with the remote put on its likely choice when it opens. */
export function Screen(props: { children: ComponentChildren; class?: string; layer?: boolean; noStart?: boolean }) {
    const ref = useRef<HTMLDivElement>(null);
    useEffect(() => {
        if (props.noStart) return;
        const id = requestAnimationFrame(() => ref.current && focusStart(ref.current));
        return () => cancelAnimationFrame(id);
    }, []);
    return (
        <div ref={ref} class={`screen ${props.class ?? ""}`} data-layer={props.layer ? "" : undefined}>
            {props.children}
        </div>
    );
}
