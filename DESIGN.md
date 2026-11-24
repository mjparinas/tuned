---
name: Tuned
description: Quiet, fast IPTV UI for Android TV and phones — Material 3 with violet/teal brand, pill buttons, soft cards.
colors:
  tuned-violet: "#6D4AFF"
  primary-light: "#5A35F0"
  primary-dark: "#C9BEFF"
  violet-deep: "#3B1FB8"
  signal-teal: "#00C2A8"
  teal-light: "#006B5C"
  teal-dark: "#5FDCC3"
  living-room-ink: "#121018"
  splash-ink: "#1A1726"
  soft-lavender-paper: "#FCFAFF"
  on-surface-light: "#1B1A21"
  on-surface-dark: "#E6E1EA"
  muted-light: "#48454F"
  muted-dark: "#CAC4D0"
  surface-container-dark: "#1E1C25"
  surface-container-high-dark: "#29262F"
  outline-light: "#79757F"
  outline-dark: "#938F99"
  danger-light: "#BA1A1A"
  danger-dark: "#FFB4AB"
  primary-soft-light: "#E6DEFF"
  primary-soft-dark: "#4325D6"
typography:
  display:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontWeight: 400
    lineHeight: 1.2
  headline:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontWeight: 700
    letterSpacing: "-0.015em"
  title:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontWeight: 600
  body:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Roboto, system-ui, sans-serif"
    fontWeight: 600
    letterSpacing: "0.2sp"
rounded:
  field: "14px"
  chip: "999px"
  card: "20px"
  sheet: "24px"
  dialog: "28px"
  brand-icon: "11px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
  tv-side: "12px"
  tv-vertical: "16px"
  dialog-edge: "24px"
components:
  button-primary:
    backgroundColor: "{colors.primary-light}"
    textColor: "#FFFFFF"
    rounded: "{rounded.pill}"
    padding: "10px 24px"
    height: "48px"
  button-primary-dark:
    backgroundColor: "{colors.primary-dark}"
    textColor: "{colors.violet-deep}"
    rounded: "{rounded.pill}"
    padding: "10px 24px"
    height: "48px"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.primary-light}"
    rounded: "{rounded.pill}"
    padding: "10px 24px"
    height: "48px"
  card:
    backgroundColor: "#FFFFFF"
    textColor: "{colors.on-surface-light}"
    rounded: "{rounded.card}"
    padding: "20px"
  card-dark:
    backgroundColor: "{colors.surface-container-dark}"
    textColor: "{colors.on-surface-dark}"
    rounded: "{rounded.card}"
    padding: "20px"
  input:
    backgroundColor: "#F0ECF6"
    textColor: "{colors.on-surface-light}"
    rounded: "{rounded.field}"
    padding: "12px 16px"
    height: "52px"
  input-dark:
    backgroundColor: "{colors.surface-container-high-dark}"
    textColor: "{colors.on-surface-dark}"
    rounded: "{rounded.field}"
    padding: "12px 16px"
    height: "52px"
  dialog:
    backgroundColor: "#FFFFFF"
    textColor: "{colors.on-surface-light}"
    rounded: "{rounded.dialog}"
    width: "560px"
  focus-ring:
    backgroundColor: "transparent"
    textColor: "{colors.primary-light}"
    rounded: "{rounded.card}"
    size: "3px"
---

# Design System: Tuned

## Overview

**Creative North Star: "The Living-Room Remote"**

Tuned is designed for someone on the couch with a D-pad (and often a phone nearby): browse channels and titles quickly, start watching, and forget the chrome. The interface is quiet and fast — cache-first, video-first — and dark-room confident when the lights are down. Accent colour is sparing; the picture and the programme are the heroes.

The system is Material 3 throughout the Android app, with Tuned’s violet/teal brand expressed through colour roles, soft rounded shapes, and pill buttons. The companion website mirrors that same language (`devices/public/site.css`) so account and marketing surfaces feel like the app, not a separate product. Soft lavender paper in light mode and living-room ink in dark mode keep surfaces calm; a soft violet glow at the top of web pages is atmosphere, not decoration for its own sake.

Visual anti-references (confirmed): no loud IPTV clutter; no second video inset or multi-view chrome; no full-bleed / edge-to-edge pop-ups.

**Key Characteristics:**

- Living-room remote first (TV D-pad), phone as peer
- Quiet, fast, get-out-of-the-way
- Dark-room confident; accent used sparingly
- Pill-confident buttons; card-calm containers
- Material 3 structure; brand in colour, shape, and focus

## Colors

A violet-led Material palette with a signal-teal secondary, tuned for light, dark, and optional pure-black OLED — plus user-chosen accents that still meet contrast.

### Primary

- **Tuned Violet** (`#6D4AFF`): Brand mark (back play button) and marketing accent. Soft violet page glow on the website uses this hue at low opacity.
- **Primary Light** (`#5A35F0`): Default filled controls and links in light theme (contrast-safe Material primary).
- **Primary Dark** (`#C9BEFF`): Default filled controls and links in dark theme.
- **Violet Deep** (`#3B1FB8`): Deep violet accents; on-primary text against primary-dark fills.

### Secondary

- **Signal Teal** (`#00C2A8`): Middle play button on the channel-stack mark; secondary brand signal.
- **Teal Light / Dark** (`#006B5C` / `#5FDCC3`): Material secondary roles in light and dark schemes.

### Tertiary

- Magenta-violet tertiary tones in the Material schemes (`#8E4A8A` light / `#F5B2EC` dark) for occasional contrast — not a third brand colour in marketing.

### Neutral

- **Soft Lavender Paper** (`#FCFAFF`): Light background and surface.
- **Living-Room Ink** (`#121018`): Dark background and surface; TV system default leans dark.
- **Splash Ink** (`#1A1726`): Splash and mark backdrop (never white behind the white play tip).
- **On-surface** light/dark (`#1B1A21` / `#E6E1EA`); muted (`#48454F` / `#CAC4D0`); outline (`#79757F` / `#938F99`).
- Surface containers step up from ink (dark: `#1A1820` → `#1E1C25` → `#29262F` → `#34313A`) for tonal depth without busy chrome.
- **Danger** (`#BA1A1A` / `#FFB4AB`): Destructive actions and errors.

### Named Rules

**The Sparing Accent Rule.** Primary violet/teal marks focus, primary actions, and brand moments — never wallpaper a whole browse screen in brand colour. Video and posters own the pixels.

**The Contrast-Everywhere Rule.** Accent and status colours must stay ≥4.5:1 on every surface/card in light, dark, and pure black (`ThemeContrastTest`). A new accent that fails does not ship.

## Typography

**Display Font:** Roboto (system / Material default)  
**Body Font:** Roboto (system / Material default)  
**Label/Mono Font:** Roboto (same family; no separate mono brand face)

**Character:** Device-native and calm. Semibold titles and labels carry hierarchy; nothing decorative fights the content. The website loads no webfonts so CSP stays `'self'` and the stack matches the app.

### Hierarchy

- **Display / Headline** (Material scale; web h1 ~1.75–2rem, weight 700, tight tracking): Screen and page titles.
- **Title** (Material titleLarge/Medium, **SemiBold**): Section and card titles.
- **Body** (16px / 1.5 on web; Material body roles in-app): Primary reading; muted colour for supporting copy.
- **Label** (**SemiBold**, slight letter-spacing `0.2sp` on labelLarge): Buttons, chips, field labels, settings rows.

### Named Rules

**The System Face Rule.** Do not introduce a display webfont or custom typeface for product UI. Brand lives in colour and the channel-stack mark, not in a novelty font.

**The Grow-With-Settings Rule.** Type is in `sp` / relative sizes so system font scale works; text containers use minimum heights, never fixed heights that clip large text.

## Layout

Material navigation matched to size: bottom destinations on compact phones; navigation rail / larger layouts on tablets and TV. Content is dense enough to browse quickly but not cluttered — lists and poster grids page from Room.

On Android TV, every screen sits inside an overscan-safe margin (**12 dp** sides, **16 dp** top/bottom by default, calibratable). Screen backdrops (glow, gradient) may **bleed** to the physical edges via `bleedIntoTvMargin` so the margin never reads as a white frame. The player stays full-bleed; its controls keep their own margins.

Website account/content column caps around **560px**, centred, with **16px** horizontal padding (more top padding from **720px** up). Dialogs keep **24 dp** off every screen edge and cap at **560 dp** (sign-in code dialogs on TV may use **720 dp**). Focus targets are at least **48 dp**.

### Named Rules

**The Overscan Rule.** On TV, interactive content stays inside `TvSafeArea`; only intentional backdrops bleed.

**The Centred Dialog Rule.** Pop-ups never stretch edge-to-edge; they sit centred with edge margin.

## Elevation & Depth

**Hybrid:** In the app, depth is Material **tonal elevation** (surface container steps) — flat at rest, no decorative drop shadows on Compose surfaces. On the website, cards use a **soft ambient shadow** plus a soft violet radial glow at the top of the page for atmosphere. Focus is a **3 dp/px accent outline**, not a heavy shadow.

### Shadow Vocabulary

- **Web card** (`0 1px 2px rgba(27,26,33,0.05), 0 6px 20px rgba(27,26,33,0.05)` light; `0 8px 24px rgba(0,0,0,0.25)` dark): Calm lift under `.card`.
- **Brand icon** (`0 4px 14px rgba(11,10,16,0.35)`): Mark sitting on the web brand row.
- **App:** Prefer tonal containers over shadows.

### Named Rules

**The Flat-App Rule.** Do not invent Compose drop shadows for ordinary lists and settings; use surface containers.

**The Glow-Is-Atmosphere Rule.** The violet page glow is background mood only — never a substitute for hierarchy or focus.

## Shapes

Soft, modern-web rounding throughout (Melvin: rounded inputs and buttons). Fields **14**, cards **20**, sheets **24**, larger surfaces up to **32**, chips and buttons **fully round (pill)**. Feature-sell cards on the web may use a violet→teal gradient edge. Dialogs use Material’s large rounded card, never a full-screen takeover for ordinary confirms.

### Named Rules

**The Pill Button Rule.** Primary and quiet buttons are fully round; do not square them off for “dashboard” fashion.

## Components

### Buttons

- **Shape:** Fully round pill (`999px` / Material full).
- **Primary:** Filled primary / on-primary; min height 48; padding ~10×24; semibold label.
- **Hover / Focus (web):** Slight brightness lift; `:focus-visible` 3px accent outline with 2px offset. TV/keyboard: `focusRing` 3 dp in primary.
- **Quiet / Plain:** Transparent fill; quiet uses primary text; plain uses danger for destructive.

### Chips

- **Style:** Material chips; small shape is pill (`percent = 50`). Selected state also named in words for a11y, not colour alone.

### Cards / Containers

- **Corner Style:** 20 dp/px.
- **Background:** Light card white / lavender paper; dark surface container.
- **Shadow Strategy:** Soft ambient on web; tonal in-app.
- **Border:** 1px line (`#E4E0EB` / dark `#34313A`); feature cards may use violet→teal gradient border.
- **Internal Padding:** ~20–24.

### Inputs / Fields

- **Style:** 14 radius; filled surface-container; 1px field-line border (≥3:1 against card for WCAG non-text contrast).
- **Focus:** Border shifts to primary; background may lighten to card.
- **Min height:** 52 on web fields.

### Navigation

- Phone: Material bottom bar / tabs for browse. Large screens: rail. TV: focus rings + start focus on the likely action; Tuned mark/name upper-left (`TvBrand` / `BackTopBar`). Website: brand row (channel stack + name) then centred main.

### Dialogs & Sheets

- **TunedDialog:** Centred, 24 dp edge margin, max 560 dp (720 dp for TV sign-in codes). Never Material `AlertDialog` directly.
- **TunedSheet:** Bottom sheet on phone; on TV a full-screen panel inside overscan with focus trapped, no drag handle, Back to dismiss.
- **OneLineCode:** Sign-in codes never wrap; shrink before wrapping.

### Focus ring (signature)

- 3 dp accent outline following the control’s shape while focused (TV/remote/keyboard). Touch paths do not show it. Website mirrors with `:focus-visible`.

## Do's and Don'ts

### Do:

- **Do** keep the UI quiet so browsing and watching feel effortless — chrome yields to video and posters.
- **Do** use Material 3 roles and TunedShapes; express brand through violet/teal, the channel-stack mark, and focus rings.
- **Do** keep dialogs centred with ≥24 dp edge margin and ≤560 dp (≤720 dp only for TV code dialogs).
- **Do** honour TV overscan margins and bleed backdrops intentionally.
- **Do** meet contrast in every theme/accent; give colour-only state a text/semantics twin.
- **Do** match website surfaces to the app via `site.css` tokens. Admin keeps a denser ops hybrid (violet/teal on ink, Focus Overview Needs-you-first); it is not the consumer site chrome.

### Don't:

- **Don't** build loud IPTV clutter (neon overload, dense ornamental chrome, competing status jewellery).
- **Don't** add a second video inset, multi-view, or live preview chrome — one stream only.
- **Don't** ship full-bleed / edge-to-edge pop-ups for ordinary dialogs.
- **Don't** replace Roboto with a novelty display face or load third-party webfonts on the product site.
- **Don't** put player tools behind a three-dot menu — top-row buttons only.
- **Don't** invent Compose drop shadows where tonal surfaces already carry depth.
