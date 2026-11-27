// Long lists drawn a screenful at a time, so a playlist of 20,000 channels moves as fast as one of 20 (speed.md). Rows
// and grid cells keep a fixed size; only those on screen, plus a few past each edge so the remote always has a next
// one to land on, are in the page. Focus scrolls the list (focus.ts uses scrollIntoView).
import type { ComponentChildren } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { remotePlacedByApp } from "../focus.ts";

const SPARE = 3;

/** Preact 11 no longer adds "px" to a number in a style. */
export const px = (n: number) => `${n}px`;

export function VirtualList<T>(props: {
    items: readonly T[];
    rowHeight: number;
    height: number;
    row: (item: T, index: number) => ComponentChildren;
    onPick: (item: T, index: number) => void;
    onFocusItem?: (item: T, index: number) => void;
    /** Changing this scrolls back to the top (a new group was picked). */
    resetKey?: string;
    start?: boolean;
    label?: string;
}) {
    return (
        <VirtualGrid
            items={props.items}
            columns={1}
            cellHeight={props.rowHeight}
            height={props.height}
            cell={props.row}
            onPick={props.onPick}
            onFocusItem={props.onFocusItem}
            resetKey={props.resetKey}
            start={props.start}
            label={props.label}
            class="vlist"
        />
    );
}

export function VirtualGrid<T>(props: {
    items: readonly T[];
    columns: number;
    cellHeight: number;
    height: number;
    cell: (item: T, index: number) => ComponentChildren;
    onPick: (item: T, index: number) => void;
    onFocusItem?: (item: T, index: number) => void;
    resetKey?: string;
    start?: boolean;
    label?: string;
    class?: string;
}) {
    const box = useRef<HTMLDivElement>(null);
    const [top, setTop] = useState(0);
    const rows = Math.ceil(props.items.length / props.columns);
    useEffect(() => {
        if (box.current) box.current.scrollTop = 0;
        setTop(0);
    }, [props.resetKey]);
    // Opening a screen: the remote lands on the first item as soon as there is one, unless the person already moved it.
    useEffect(() => {
        if (!props.start || !props.items.length || !box.current) return;
        const active = document.activeElement;
        if (active && active !== document.body && !remotePlacedByApp()) return;
        box.current.querySelector<HTMLElement>("[data-focus]")?.focus({ preventScroll: true });
    }, [props.start, props.items.length > 0]);
    const firstRow = Math.max(0, Math.floor(top / props.cellHeight) - SPARE);
    const lastRow = Math.min(rows, Math.ceil((top + props.height) / props.cellHeight) + SPARE);
    const cells: ComponentChildren[] = [];
    for (let r = firstRow; r < lastRow; r++) {
        for (let c = 0; c < props.columns; c++) {
            const index = r * props.columns + c;
            if (index >= props.items.length) break;
            const item = props.items[index];
            cells.push(
                <div
                    key={index}
                    class="cell"
                    data-focus
                    data-start={props.start && index === 0 ? "" : undefined}
                    tabIndex={-1}
                    role="button"
                    style={{
                        top: px(r * props.cellHeight),
                        left: `${(c * 100) / props.columns}%`,
                        width: `${100 / props.columns}%`,
                        height: px(props.cellHeight),
                    }}
                    onClick={() => props.onPick(item, index)}
                    onFocus={() => props.onFocusItem?.(item, index)}
                >
                    {props.cell(item, index)}
                </div>,
            );
        }
    }
    return (
        <div
            ref={box}
            class={`vgrid ${props.class ?? ""}`}
            style={{ height: px(props.height) }}
            aria-label={props.label}
            onScroll={(e) => setTop((e.currentTarget as HTMLDivElement).scrollTop)}
        >
            <div class="vgrid-inner" style={{ height: px(rows * props.cellHeight) }}>
                {cells}
            </div>
        </div>
    );
}
