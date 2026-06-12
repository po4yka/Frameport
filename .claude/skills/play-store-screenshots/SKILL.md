---
name: play-store-screenshots
description: "Use when generating Google Play Store assets for Frameport — phone screenshots (1080x1920), feature graphics (1024x500), tablet screenshots, or marketing images. Also use when updating play-store-screenshots/src/app/page.tsx, refreshing the raw app captures in public/screenshots/, running the Puppeteer batch capture, or aligning slides with design system tokens. Triggers on \"Play Store assets\", \"marketing screenshots\", \"feature graphic\", \"screenshot generator\", \"1080x1920\", or \"play-store-screenshots\"."
disable-model-invocation: true
---

# Google Play Screenshots Generator (Frameport)

## Overview

Build or update the Next.js page in `play-store-screenshots/` that renders Google Play Store screenshots as **advertisements** (not UI showcases) and exports them via `html-to-image` + Puppeteer batch capture at Google Play's required resolutions.

**Google Play constraints:**
- Max 8 screenshots per device type (phone, 7" tablet, 10" tablet)
- Text overlay must not exceed 20% of the screenshot area
- No promotional text (pricing, rankings, awards)
- 24-bit PNG or JPEG only (no alpha transparency)
- Max 8 MB per image
- Minimum 2 screenshots to publish, 4+ recommended for visibility

## Core Principle

**Screenshots are advertisements, not documentation.** Every screenshot sells one idea. If you're showing UI, you're doing it wrong — you're selling a *feeling*, an *outcome*, or killing a *pain point*.

## Existing Project Structure

The generator already exists at `play-store-screenshots/`. Check if it needs updating rather than scaffolding from scratch.

```
play-store-screenshots/
├── public/
│   ├── app-icon.png                  # Copied from app/src/main/ic_launcher-playstore.png
│   └── screenshots/                  # High-res app screenshots
│       ├── home-light.png            # From docs/screenshots/main.png (1080x2400)
│       ├── connection.png            # From docs/screenshots/connection.png (1080x2400)
│       └── gallery.png               # From docs/screenshots/gallery.png (1080x2400)
├── src/app/
│   ├── layout.tsx                    # Geist Sans + Geist Mono font setup
│   └── page.tsx                      # The screenshot generator (single file)
├── capture.mjs                       # Puppeteer batch capture script
└── package.json                      # next, html-to-image, puppeteer (devDep)
```

### Asset Sources

| Asset | Source | Resolution |
|-------|--------|-----------|
| App icon | `app/src/main/ic_launcher-playstore.png` | 512x512 |
| High-res screenshots | `docs/screenshots/*.png` | 1080x2400 |

**Use only high-res screenshots (1080x2400) from `docs/screenshots/`.** Lower-resolution screenshots are too low-quality for Play Store. If a screen is only available as a lower-res capture, use text-focused slides instead.

**Refreshing the cached icon:** After any icon change, run `cp app/src/main/ic_launcher-playstore.png play-store-screenshots/public/app-icon.png` and rerun `bun run capture:prod`.

## Step 1: Confirm Frameport Defaults with the User

### Pre-Filled defaults (confirm, don't ask from scratch)

| Item | Default |
|------|---------|
| App name | Frameport |
| App ID | dev.po4yka.frameport |
| Design philosophy | Clean, camera-inspired. Privacy-first, technical but approachable. |
| Font families | Geist Sans (UI text), Geist Mono (values/technical data) |

#### Light Theme Tokens (canonical default)

```typescript
const BRAND_LIGHT = {
  bg: "#FAFAFA",           // background
  card: "#FFFFFF",         // card
  text: "#1A1A1A",         // foreground
  muted: "#F5F5F5",        // muted
  mutedFg: "#575757",      // mutedForeground
  accent: "#E8E8E8",       // accent
  border: "#E0E0E0",       // border
  success: "#047857",      // success
  warning: "#B45309",      // warning
  error: "#B91C1C",        // destructive
  info: "#1D4ED8",         // info
} as const;
```

#### Dark Theme Tokens (rhythm-break slides only — at most 1-2 of 6)

```typescript
const BRAND = {
  bg: "#1A1A1A",           // background
  card: "#1F1F1F",         // card
  text: "#FAFAFA",         // foreground
  muted: "#262626",        // muted
  mutedFg: "#A3A3A3",      // mutedForeground
  accent: "#2A2A2A",       // accent
  border: "#2A2A2A",       // border
  success: "#10B981",      // success
  warning: "#D97706",      // warning
  error: "#DC2626",        // destructive
  info: "#3B82F6",         // info
} as const;
```

#### Typography Reference

| Element | Family | Weight | Play Store sizing |
|---------|--------|--------|-------------------|
| Category label | Geist Sans | 600 (semibold) | ~35px at 1080w |
| Headline | Geist Sans | 700 (bold) | 103-108px at 1080w |
| Pill/badge text | Geist Mono | 600 (semibold) | 26-30px at 1080w |
| Subtext | Geist Sans | 400 (normal) | 28px at 1080w |

**M3 Expressive principle**: Prefer weight promotion (400->500->700) over size increase for emphasis.

### Ask the User

1. **Number of slides** — "How many screenshots do you want? (Google Play allows up to 8)"
2. **Feature Graphic** — "Shall I generate a Feature Graphic (1024x500)? It's required for Play Store listings."
3. **Localized screenshots** — "Do you want screenshots in multiple languages? If yes, which languages?"
4. **Additional instructions** — "Any specific requirements or preferences?"

**IMPORTANT:** If the user says "figure it out" or similar, use the defaults and proceed without asking.

### Derived (do NOT ask)

- **Background style**: flat solid backgrounds only (no gradients). `BRAND_LIGHT.bg` (`#FAFAFA`) for canonical light slides; `BRAND.bg` (`#1A1A1A`) for rare rhythm-break dark slides.
- **Decorative elements**: subtle low-opacity monochrome `Grid` pattern is the only allowed decoration. No radial glow orbs, no circuit-board, no shield motifs.
- **Light vs dark slides**: light-first. At most 1-2 of 6 slides may use the dark inversion for visual rhythm; never more.
- **Screenshot placement**: use `top` positioning (not `bottom + translateY`) to precisely control where screenshots start below headlines.

## Step 2: Set Up / Update the Project

### If project already exists

```bash
cd play-store-screenshots
bun install  # or npm install
```

Check if `public/screenshots/` has the latest high-res screenshots from `docs/screenshots/`. Copy any updated ones.

### If scaffolding new

Package manager priority: **bun > pnpm > yarn > npm**

```bash
bunx create-next-app@latest play-store-screenshots --typescript --tailwind --app --src-dir --no-eslint --import-alias "@/*"
cd play-store-screenshots
bun add html-to-image
bun add -d puppeteer
bun pm trust puppeteer  # allow postinstall to download Chromium
```

Copy assets:
```bash
mkdir -p public/screenshots
cp ../app/src/main/ic_launcher-playstore.png public/app-icon.png
cp ../docs/screenshots/main.png public/screenshots/home-light.png
cp ../docs/screenshots/connection.png public/screenshots/connection.png
cp ../docs/screenshots/gallery.png public/screenshots/gallery.png
```

### Font Setup (Next.js 16+)

Next.js 16 ships Geist fonts natively via `next/font/google`:

```tsx
// src/app/layout.tsx
import { Geist, Geist_Mono } from "next/font/google";
const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });
```

Use `var(--font-geist-sans)` and `var(--font-geist-mono)` in slide styles.

### Next.js 16 Caveats

- **`useSearchParams` requires Suspense**: Wrap the main component in `<Suspense>` to avoid build failures during static prerendering.
- **Production build for capture**: `bun run build && bun run start` — the dev server HMR websocket causes Puppeteer and Chrome DevTools navigation timeouts.

## Step 3: Plan the Slides

### Screenshot Framework (Narrative Arc)

| Slot | Purpose | Frameport Suggestion |
|------|---------|----------------------|
| #1 | **Hero / Main Benefit** | Home/gallery screen on light bg. "Your camera. Your files. No cloud." |
| #2 | **Differentiator** | Connection screen, text-focused. "One tap. No app required." |
| #3 | **Core Feature** | Live-view screen. "See what your camera sees." |
| #4 | **Core Feature** | Import/transfer screen. "Import. Direct. Fast." |
| #5 | **Core Feature** | Diagnostics screen. "Know why it connected." |
| #6 | **Privacy** | Feature pills + icon, dark inversion for rhythm. "Zero cloud. Zero accounts." |

**Rules:**
- Each slide sells ONE idea
- Vary layouts — never repeat the same template structure in adjacent slides
- Light-first: at most 1-2 of 6 slides may be the dark inversion, for rhythm only
- Text overlay must not exceed 20% of the screenshot area
- Slides with no high-res screenshot available should be text-focused (feature cards, protocol pills, etc.)

## Step 4: Write Copy FIRST

### The Iron Rules

1. **One idea per headline.** Never join two things with "and."
2. **Short, common words.** 1-2 syllables. No jargon unless domain-specific.
3. **3-5 words per line.** Readable at thumbnail size.
4. **Line breaks are intentional.** Control with `<br />`.
5. **Max 20% text overlay.**

### Three Approaches (pick one per slide)

| Type | What it does | Example |
|------|-------------|---------|
| **Paint a moment** | You picture yourself doing it | "Tap connect. Files arrive." |
| **State an outcome** | What your life looks like after | "Your photos. Right there." |
| **Kill a pain** | Name a problem and destroy it | "No app login. Ever." |

## Step 5: Build the Page

### Architecture

The entire generator is a single `page.tsx` file:

```
page.tsx
├── Constants (PHONE_W/H, FEATURE_GRAPHIC, BRAND tokens)
├── Screenshot component (frameless, 40px border-radius, bgColor prop)
├── Caption component (label + headline)
├── Decorative components (Grid, Pill)
├── Slide container component
├── Slide1..N components (one per slide)
├── FeatureGraphicSlide component (1024x500)
├── SLIDES array (registry)
├── ScreenshotPreview (ResizeObserver scaling + click-to-export)
├── ScreenshotsPage (grid + export logic)
└── Page wrapper (Suspense boundary)
```

### Key Dimensions

```typescript
const PHONE_W = 1080;
const PHONE_H = 1920;
const FEATURE_GRAPHIC = { w: 1024, h: 500 };
```

### Screenshot Component (Frameless)

```tsx
function Screenshot({ src, alt, style, bgColor = "#ffffff" }: {
  src: string; alt: string; style?: React.CSSProperties; bgColor?: string;
}) {
  return (
    <div style={{ position: "relative", ...style }}>
      <div style={{
        width: "100%", height: "100%",
        borderRadius: 40,
        overflow: "hidden",
        boxShadow: "0 12px 60px rgba(0,0,0,0.5)",
        background: bgColor,
      }}>
        <img src={src} alt={alt}
          style={{ display: "block", width: "100%", height: "100%",
            objectFit: "cover", objectPosition: "top" }}
          draggable={false} />
      </div>
    </div>
  );
}
```

### Phone Placement (Critical)

The high-res screenshots are 1080x2400 (taller than the 1080x1920 canvas). **Use `top` positioning** to control exactly where the phone starts below the headline:

```tsx
// 3-line headline (~400px) + gap = top: 520
<Screenshot src="/screenshots/home-light.png" alt="Home"
  style={{
    position: "absolute",
    top: 520,
    left: "50%",
    transform: "translateX(-50%)",
    width: "76%",
    aspectRatio: "1080/2400",
  }}
/>
```

**Never use `bottom: 0` + `translateY(N%)` for 1080x2400 screenshots** — the percentage math is hard to get right and leads to overlap or excessive gaps.

### Single-Slide Mode

Support `?slide=N` (1-6) and `?slide=fg` for headless capture:

```tsx
export default function Page() {
  return (
    <Suspense fallback={<div style={{ background: "#0a0a0a", minHeight: "100vh" }} />}>
      <ScreenshotsPage />
    </Suspense>
  );
}

function ScreenshotsPage() {
  const searchParams = useSearchParams();
  const slideParam = searchParams.get("slide");
  if (slideParam) {
    if (slideParam === "fg") return <FeatureGraphicSlide />;
    const idx = parseInt(slideParam) - 1;
    const slide = SLIDES[idx];
    if (slide) { const C = slide.component; return <C />; }
  }
  // ... grid view with export
}
```

## Step 6: Export

### Browser Export (interactive)

```typescript
import { toPng } from "html-to-image";
const opts = { width: w, height: h, pixelRatio: 1, cacheBust: true, backgroundColor: "#FAFAFA" };
await toPng(el, opts);  // warm-up call
const dataUrl = await toPng(el, opts);  // actual capture
```

### Puppeteer Batch Export (headless)

Use `capture.mjs` against the **production** build (dev server HMR causes timeouts):

```bash
bun run build && bun run start -- -p 3099 &
node capture.mjs
```

```javascript
// capture.mjs
import puppeteer from "puppeteer";
const browser = await puppeteer.launch({ headless: true });
const page = await browser.newPage();
for (const slide of SLIDES) {
  await page.setViewport({ width: slide.w, height: slide.h, deviceScaleFactor: 1 });
  await page.goto(`http://localhost:3099/?slide=${slide.param}`, { waitUntil: "load", timeout: 60000 });
  await new Promise(r => setTimeout(r, 2000));  // fonts + images
  await page.screenshot({ path: outPath, type: "png", clip: { x: 0, y: 0, width: slide.w, height: slide.h } });
}
```

Captured images go to `docs/screenshots/` for README usage.

### Key Export Rules

- **Double-call trick** for html-to-image: first call warms up fonts/images
- **backgroundColor**: Always set to strip alpha (Google Play rejects alpha PNGs)
- **Numbered filenames**: `01-hero.png`, `02-connect.png`, etc.
- **Feature Graphic filename**: `feature-graphic.png`
- **Production server only** for Puppeteer — dev server HMR websocket causes infinite loading

## Step 7: Final QA Gate

### Google Play Compliance

- [ ] No alpha transparency (`backgroundColor` set)
- [ ] Text overlay <= 20% of screenshot area
- [ ] No promotional pricing, rankings, or awards
- [ ] Aspect ratio valid (1920/1080 = 1.78, passes max 2:1)
- [ ] Minimum 4 screenshots
- [ ] Feature Graphic exactly 1024x500
- [ ] Each file under 8 MB
- [ ] All exports are 24-bit PNG

### Visual Quality

- [ ] No repeated layouts in adjacent slides
- [ ] No text/screenshot overlap
- [ ] Screenshots fully contained (no clipping at edges)
- [ ] At least 1 light contrast slide for rhythm
- [ ] Decorative elements don't cover app UI

### Design System Alignment

- [ ] Colors match design system tokens (light canonical: `#FAFAFA` bg, `#1A1A1A` text, `#1D4ED8` info; dark inversion: `#1A1A1A` bg, `#FAFAFA` text, `#3B82F6` info)
- [ ] Font families are Geist Sans (headlines, labels) and Geist Mono (pills, badges, values)
- [ ] Weight emphasis follows M3 Expressive principle (400->500->700, not size increase)
- [ ] Pill/badge corners use design system radii (12-16px range)

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Text overlaps phone screenshot | Use `top` positioning, not `bottom + translateY` |
| Screenshot clipped at edges | Use `left/right: "4%"` not negative values |
| Blank Puppeteer captures | Must use production build (`bun run build && bun run start`) |
| useSearchParams build error | Wrap component in `<Suspense>` |
| Low-res screenshots look bad | Only use 1080x2400 from `docs/screenshots/`; text-focused slides for others |
| Decorative gradients on slides | Use solid backgrounds plus the `Grid` utility for subtle texture. No `linear-gradient(...)` or radial-glow orbs. |
| All slides look the same | Vary: centered phone, right-offset, left-offset, text-only, pills-only |
| Copy too complex | "One second at arm's length" test; 3-5 words per line |
