# DSH Web design tokens -> Android Compose / M3

Extracted from the DeepSeek Harness source. Paths below are relative to the dsh repository root. The app's implementation is in `app/src/main/java/io/github/timhub135/dsh/core/designsystem/` (§8).
Theme switch on web: `body` = light, `body[data-ds-dark-theme]` = dark. Feature code only consumes `--dsw-alias-*`; the static scale is private to the theme.

Key fact for porting: the **brand primary is monochrome** (near-black in light, near-white in dark). The DeepSeek blue (`deepseek-500` #4176E6) is the *accent* used for links, info buttons, focus rings, "business" state and the selected sidebar item, not for primary buttons.

---

## 1. Color

Source: `packages/client/ui-theme/src/styles/design-platform.css`

### 1.1 Static palette (identical in light and dark, except `neutral-bluish-60`)

**DeepSeek (brand blue)**

| Token | Hex | | Token | Hex |
|---|---|---|---|---|
| deepseek-50 | #EDF3FE | | deepseek-450 | #5686FE |
| deepseek-100 | #E4EDFD | | **deepseek-500** | **#4176E6** |
| deepseek-200 | #D3E2FF | | deepseek-600 | #4868B2 |
| deepseek-300 | #B7C8FE | | deepseek-700-delete | #2F4C8F (slated for deletion) |
| deepseek-400 | #7AAAFF (brightened from #679EFE for dark toast legibility) | | deepseek-800 / 900 | #34415B / #283142 |

**Blue (Tailwind-like)**: 50 #EFF6FF, 50p #EAF3FF, 75 #E5F0FF, 100 #DBEAFE, 300 #93C5FD, 400 #60A5FA, 450 #4D93F8, 500 #3B82F6, 600 #2563EB, 800 #1E40AF, 900 #0E3074, 950 #172554

**Green**: 100 #E6FAED, 400 #4ED17E, 500 #22C55E, 900 #233C2C; 500-a08 = #22C55E @8%, 500-a12 = #22C55E @12%
**Red**: 50 #FEF2F2, 100 #FEE2E2, 400 #F25A5A, 500 #EF4444, 600 #EC1313, 900 #570C0C; 400-a12 = #F25A5A @12%, 600-a08 = #EC1313 @8%
**Amber**: 100 #FEF5E7, 400 #F7AD31, 500 #F59E0B, 600 #DD8629, 900 #27241F

**Neutral (pure grey)**

| 00 | 50 | 100 | 150 | 200 | 250 | 300 | 400 | 500 | 550 | 600 | 700 | 800 | 850 | 900 | 1000 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| #FFFFFF | #FAFAFA | #F5F5F5 | #EDEDED | #E5E5E5 | #DCDCDC | #D4D4D4 | #A2A4A6 | #7F8287 | #65676B | #545557 | #3C3C3D | #292929 | #212123 | #0F0F0F | #000000 |

**Neutral-bluish (the main UI neutral ramp)**

| Step | Hex | Step | Hex | Step | Hex |
|---|---|---|---|---|---|
| 00 | #FFFFFF | 200 | #E1E5EE | 750 | #43454A |
| 50 | #F9FAFB | 300 | #CFD3D6 | 800 | #353638 |
| 55 | #F5F7FA | 310 | #CAD2DB | 850 | #2C2C2E |
| 60 | #F5F6F7 (dark sheet: #F9FAFB) | 400 | #ADB2B8 | 875 | #232324 |
| 75 | #F1F3F5 | 500 | #979DA6 | 900 | #1B1B1C |
| 90 | #EDF0F4 | 600 | #81858C | 950 | #151517 |
| 100 | #EBEEF2 | 700 | #61666B | 1000 | #0F1115 |
| 150 | #E9ECF2 | | | | |

### 1.2 Semantic aliases (resolved)

"@n%" = alpha. `≈` = computed from `color-mix(in srgb, ...)`.

**Text / labels**

| Alias | Light | Dark |
|---|---|---|
| label-primary | #0F1115 (bluish-1000) | #F9FAFB (bluish-50) |
| label-secondary | #61666B (bluish-700) | #CFD3D6 (bluish-300) |
| label-tertiary | #81858C (bluish-600) | #ADB2B8 (bluish-400) |
| label-caption | #ADB2B8 | #81858C |
| label-dimmed (disabled-ish) | #E1E5EE | #43454A |
| label-primary-dimmed | #151517 | #EBEEF2 |
| label-primary-foreground (text on primary fill) | #FFFFFF | #0F1115 |
| label-primary-inverted | #FFFFFF | #353638 |
| label-primary-bluish | #0E3074 | #F9FAFB |
| brand-text | #0F1115 | #F9FAFB |
| label-document-preview | #61666B | #CFD3D6 |
| label-shimmer | #000000 @30% | #FFFFFF @45% |
| label-deep-diving | mix(deepseek-500 70%, blue-950) ≈ #345EBA | mix(deepseek-450 55%, bluish-400) ≈ #7D9ADF |
| label-deep-diving-shimmer | mix(deepseek-500 30%, blue-950) ≈ #243D80 | mix(blue-300 65%, deepseek-400) ≈ #8ABCFE |
| menu-icon | #353638 | #EBEEF2 |
| **link** | **#4176E6** (deepseek-500) | **#7AAAFF** (deepseek-400) |

**Backgrounds / surfaces**

| Alias | Light | Dark |
|---|---|---|
| bg-base (window) | #FFFFFF | #151517 (bluish-950) |
| bg-layer-1 | #FFFFFF | #232324 (bluish-875) |
| bg-layer-2 (dialogs, settings cards) | #FFFFFF | #2C2C2E (bluish-850) |
| bg-layer-3 | #FFFFFF | #353638 (bluish-800) |
| bg-module-platform (nested editors) | #F5F6F7 | #353638 |
| bg-overlay | #E9ECF2 | #61666B |
| bg-multi-select | #F5F6F7 | #212123 |
| bg-document-preview | #EBEEF2 | #151517 |
| bg-skeleton | #000000 @4% | #FFFFFF @8% |
| specific-sidebar-fill | #F9FAFB | #1B1B1C |
| specific-sidebar-nav-item-active | #EBEEF2 | #43454A |
| specific-sidebar-nav-item-active-accent | #E4EDFD | #353638 |
| specific-bubble (user message) | #EDF3FE (deepseek-50) | #2C2C2E |
| specific-bubble-highlight | #D3E2FF | #43454A |
| specific-input-major (composer) | #FFFFFF | #2C2C2E |
| specific-login-input | #F9FAFB | #1B1B1C |
| specific-selector / specific-tip | #F5F6F7 | #353638 |
| settings-card-fill = bg-layer-2 (base.css) | #FFFFFF | #2C2C2E |
| markdown-code-block | #F9FAFB | #1B1B1C |
| markdown-code-block-banner | #F9FAFB | #2C2C2E |
| markdown-inline-code | #FAFAFA | #292929 |
| markdown-citation | #EBEEF2 | #353638 |
| markdown-tag | #F1F3F5 | #2C2C2E |
| markdown-placeholder | #F5F6F7 | #2C2C2E |
| toast-bg / toast-label | #353638 / #FFFFFF | #43454A / #FFFFFF |
| tooltip-bg | #2C2C2E | #43454A |
| tooltip-key-bg | mix(tooltip-bg, white 18%) ≈ #525254 | ≈ #65666B |

**Borders** (all drawn at 0.5px, see §4/§7)

| Alias | Light | Dark |
|---|---|---|
| border-l1 | #000000 @4% | #FFFFFF @6% |
| border-l2 | #000000 @10% | #FFFFFF @12% |
| border-l2-darkmode-thin | #000000 @10% | #FFFFFF @6% |
| border-l3 | #000000 @12% | #FFFFFF @16% |
| border-l4 (= settings-card-stroke, default elevation stroke) | #000000 @16% | #FFFFFF @20% |
| border-inverted / inverted2 | transparent / transparent | #FFFFFF @6% / @8% |

**Brand, buttons, interaction**

| Alias | Light | Dark |
|---|---|---|
| brand-primary = button-primary-fill | #0F1115 | #F9FAFB |
| button-primary-hover | #43454A | #EBEEF2 |
| button-primary-dimmed | #EBEEF2 | #43454A |
| brand-primary-new-color (blue brand) | #4176E6 | #5686FE |
| button-info-fill / hover | #4176E6 / #7AAAFF | #7AAAFF / #4176E6 |
| button-contrast-fill | #61666B | #F9FAFB |
| button-elevated-fill | #FFFFFF | #43454A |
| button-floating-fill / hover | #FFFFFF / #F1F3F5 | #2C2C2E / #353638 |
| button-ghost-active-fill / hover / border | #EBEEF2 / #E9ECF2 / #979DA6 | #43454A / #61666B / #81858C |
| button-tool-bar-fill / hover / fill-invisible | #545557 @50% / @60% / #1F1F1F @36% | same |
| interactive-bg-hover | #263148 @6% | #FFFFFF @8% |
| interactive-bg-active (pressed) | #263148 @10% | #FFFFFF @14% |
| interactive-bg-hover-accent | #263148 @14% | #FFFFFF @24% |
| interactive-bg-hover-solid | #F1F3F5 | #353638 |
| interactive-bg-hover-danger | #EC1313 @5% | #F25A5A @15% |
| switch-thumb (off) | #FFFFFF | #ADB2B8 |
| state-business-primary (accent, focus ring) | #4176E6 | #7AAAFF |
| state-business-tertiary (accent container) | #E4EDFD | #34415B |
| bg-document-selection (text selection) | blue-500 @40% = #3B82F6 @40% | same |

**Status**

| Alias | Light | Dark |
|---|---|---|
| state-error-primary | #EC1313 (red-600) | #F25A5A (red-400) |
| state-error-secondary | #F25A5A | #F25A5A |
| state-success-primary / secondary | #22C55E / #4ED17E | same |
| state-success-tertiary (container) | #E6FAED | #233C2C |
| state-warn-primary / secondary | #F59E0B / #F7AD31 | same |
| state-warn-label (warn text) | #DD8629 | #DD8629 |
| state-warn-tertiary (container) | #FEF5E7 | #27241F |
| state-idle-primary | #D4D4D4 | #545557 |
| code-diff-added / deleted | #22C55E @8% / #EC1313 @8% | #22C55E @12% / #F25A5A @12% |
| file-diff-added bg / gutter / marker | #E6F4E7 / #EDF7ED / #01A241 | #1F3124 / #132016 / #41C977 |
| file-diff-deleted bg / gutter / marker | #FCE6E2 / #FDECE9 / #BA2723 | #3C1F1B / #28130E / #FA423E |

**Masks / scrims**

| Alias | Light | Dark |
|---|---|---|
| bg-mask-1 (modal scrim) | #000000 @24% | #000000 @50% |
| bg-mask-2 | #000000 @12% | #000000 @20% |
| bg-mask-3 | #000000 @48% | #000000 @48% |
| bg-mask-photo (lightbox) | #000000 @88% | #000000 @88% |
| bg-mask-drop (drag-drop overlay) | #FFFFFF @70% | #272730 @70% |

Other: scrollbar thumb l1 #E5E5E5 (hover #D4D4D4) / dark #3C3C3D (hover #545557); illustration-* neutrals in source. Gradients (`gradient-shadow-text.css`): `--dsw-linear-gradient-think` 180deg #FFFFFF 20.19% -> transparent (dark #151517).

---

## 2. Typography

Sources: `packages/client/ui-theme/src/styles/base.css`, `brand-font.css`, `gradient-shadow-text.css`, `design-platform.css` (header comment).

**Families**
- Text (`--dsh-font-family-text-default`): `-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Helvetica Neue', Helvetica, Arial, sans-serif` — i.e. system UI font; CJK via PingFang SC / Hiragino Sans GB / Microsoft YaHei. On Android: `FontFamily.Default` (Roboto + Noto Sans CJK fallback).
- Code (`--dsh-font-family-code-default`): `'SF Mono', 'JetBrains Mono', 'Fira Code', Consolas, 'Liberation Mono', Menlo, Courier, 'PingFang SC', 'Microsoft YaHei'` (no bare `monospace` tail on purpose — avoids SimSun on Windows CJK). Android: `FontFamily.Monospace` (no bundled font).
- Brand (`--dsw-font-family-brand`): `'Montserrat'` (bundled woff2, weights 300/400/500, SIL OFL; `brand-font.css`), then text stack. Used for wordmark/brand display only.
- User-configurable: font lists `--dsh-font-family-text/-code` prepend to the stacks; content font size 10–22px (default 14), code size 10–16px (default 11).
- Weight rule: Figma 510 always renders as **500**. Weights in use: 400, 500, 600, 700.

**UI type scale** (`--dsw-font-*`, fixed px)

| Token | Size / line-height | Weight |
|---|---|---|
| xl-24 | 24 / 32 | 600 |
| l-20 | 20 / 28 | 500 |
| m-18 (sic) | 16 / 28 | 500 |
| base-16 / base-strong-16 | 16 / 24 | 400 / 500 |
| s-14 / s-strong-14 | 14 / 22 | 400 / 500 |
| xs-13 / xs-strong-13 | 13 / 20 | 400 / 500 |
| xxs-12 / xxs-strong-12 | 12 / 18 | 400 / 500 |
| xxxs-11 / xxxs-strong-11 | 11 / 14 | 400 / 500 |

**Markdown (content) scale** — values at default content size 14px; headings/body shift by `delta = contentSize - 14`. Scale is Figma's ×0.875 (16 -> 14).

| Token | Size / line-height | Weight |
|---|---|---|
| markdown-h1 | 21 / 30 | 700 |
| markdown-h2 | 19 / 28 | 700 |
| markdown-h3 | 18 / 26 | 700 |
| markdown-h4 | 14 / 24 | 600 |
| markdown-base (+ italic) | 14 / 24 | 400 |
| markdown-base-strong (+ italic) | 14 / 24 | 600 |
| markdown-table / table-head | 13 / 22 (secondary size) | 400 / 500 |
| markdown-small (+ strong 600, italic) | 12 / 20 | 400 |
| markdown-code (inline, mono) | 12 / 19 | 400 |
| markdown-code-block (mono) | 11 / 19 | 400 |
| markdown-code-block-small (tool rows) | 11 / 16 | 400 |

Secondary size = `min(size-1, max(13, size-2))` (10->9, 14->13, 22->20). Code tokens shift by `codeSize - 11`.
Buttons: md 14/22, sm 12/18 (`ui-primitives/src/Button.module.css`). Menu items line-height 20px.

---

## 3. Corner radius

Sources: `docs/ui-radius.md`, `packages/client/ui-theme/src/styles/base.css`, `corner-shape.css`.

| Token | Value | Role (H = outer height) |
|---|---|---|
| --dsw-radius-xs | 4px | Small detail, H<20: keycaps, inline code, tiny controls |
| --dsw-radius-sm | 8px | Compact control H20–28: small buttons, compact icon buttons, compact menu items |
| --dsw-radius-md | 12px | Standard control / single-line cell H32–40: buttons, inputs, selects, nav rows |
| --dsw-radius-lg | 16px | Large control / grouped content: multiline cells, nested form groups, code blocks, diffs, preview/file cards |
| --dsw-radius-xl | 20px | Independent content card: settings cards, message bubbles, guide entry cards |
| --dsw-radius-panel | 28px | Main enclosing surface: composer, dialogs, main/floating panels |

Component mapping:
- Button sm H28/R8, md H36/R12 (all variants; hover/pressed/disabled never change radius). Inputs H32/R12, selectors H36/R12.
- Menu (standard): outer R16, 4px padding, items R12. Compact menu: outer R12, 4px padding, items R8.
- SegmentedControl: outer R12, 4px inset, segments R8. SegmentedTabs: outer R16, 4px inset (md H42; sm H34 with 2px inset), segments R12.
- Code blocks, diffs, changed-file / delivered-file / plan / task / attachment cards, hover previews: R16. Their 40px icon tiles: R10 (documented exception).
- Settings cards, message bubbles, guide entries: R20. Modal dialog: R28 (width min(380px,100%), `Modal.module.css`).
- Composer statistics / context-occupancy trigger: R8.
- Nested: concentric inner R = max(0, outer R − inset) (e.g. R16 − 4 = R12). A separate card inside a surface picks its own tier, not subtraction. Flush children inside a clipping parent use R0; edge-docked surfaces R0 on shared edges.
- **Superellipse**: all rounded corners use `corner-shape: superellipse(1.5)` (between circle=1 and squircle=2) where supported. Android: approximate with a custom `Shape` (e.g. a smoothed/“squircle” `RoundedCornerShape` path, or androidx.graphics.shapes `RoundedPolygon` with smoothing ~0.6); the app uses plain `RoundedCornerShape` (what non-supporting browsers render).
- **Capsules/circles**: avatars, status dots = 50%; pills/full-round tracks = 999px; these must keep circular arcs (`corner-shape: round`) -> `CircleShape` / `RoundedCornerShape(50)`. Don't make small buttons capsules just because they're short. `Pill` and `Tag` primitives are capsules.

---

## 4. Elevation, hairline, masks, menu material

Sources: `packages/client/ui-theme/src/styles/gradient-shadow-text.css`, `docs/web-styling.md`, `docs/ui-radius.md`, `ui-primitives/src/MenuSurface.module.css`, `Modal.module.css`.

Elevated surfaces (menus, popovers, modals, panels, floating buttons, composer) use `border: 0` + a box-shadow whose **first layer is a 0.5px hairline stroke**:

```
--dsw-elevation-stroke:    0 0 0 0.5px var(--dsw-elevation-stroke-color)   /* default border-l4; dark menus rebind to l3; menus use l1, inputs l2 */
--dsw-elevation-panel:     stroke, 0 3px 8px 0 rgba(0,0,0,.03), 0 0 16px 0 rgba(0,0,0,.02)
--dsw-elevation-prominent: stroke, 0 3px 8px 0 rgba(0,0,0,.04), 0 0 20px 0 rgba(0,0,0,.05)   /* dialogs */
--dsw-elevation-soft:      stroke, 0 4px 16px 0 rgba(0,0,0,.03), 0 0 24px 0 rgba(0,0,0,.03)  /* composer */
```
In dark mode the soft glows are nearly invisible; the stroke carries separation. Never combine a neutral `border-*` border with an elevation shadow.

Legacy shadows: lv1 `0 2px 4px rgba(0,0,0,.05)`; lv1-blur `0 4px 12px rgba(0,0,0,.02)`; lv2 `0 4px 12px rgba(0,0,0,.02), 0 2px 8px rgba(0,0,0,.04)`; lv3 `0 0 1px rgba(0,0,0,.2), 0 0 4px rgba(0,0,0,.02), 0 12px 32px rgba(0,0,0,.08)`.

- Flat surfaces (settings cards, inputs, outline buttons, dividers, separators, markdown hr): **0.5px solid neutral border** (one device pixel). Settings card = R20 + 0.5px `border-l4` + `bg-layer-2`. Outline button = inset 0.5px `border-l3`. Dashed affordances and state-colored borders stay 1px.
- Modal: scrim `bg-mask-1`, **no blur** (`--dsw-mask-blur: none`); dialog = `bg-layer-2`, R28, `elevation-prominent`, 20px gap, 24px viewport padding; fade-in 200ms.
- Menu material: fill `--dsw-menu-surface-fill` (light #F8F9FA @58%, dark #43454A @45%) + `--dsw-menu-backdrop-filter: blur(40px) saturate(150%)`, R16 (compact R12), on an isolated layer. Group header fill #F8F9FA @94% / #303136 @94%. Overlays without backdrop blur (`--dsw-specific-menu`) use the menu fill; on macOS a 94%-opaque fill (#F8F9FA / #303136). Android: use a near-opaque fill (@94%) unless you implement `RenderEffect` blur on API 31+.
- Focus ring: 2px outline, color `state-business-primary` (#4176E6 / #7AAAFF); shown only for keyboard modality (`focus.css`).

---

## 5. Motion

Sources: `packages/client/ui-theme/src/styles/base.css`, `ui-primitives/src/*.module.css`, `ui-primitives/README.md`.

| Token | Value |
|---|---|
| --ds-ease-in-out | cubic-bezier(0.4, 0, 0.2, 1) (= M3 `FastOutSlowInEasing`; used ~43×) |
| --ds-transition-duration-fast | 100ms |
| --ds-transition-duration | 200ms |
| --ds-transition-duration-slow | 300ms |

Observed component timings: Tooltip in 150ms ease-in-out; Switch thumb 120ms `ease`; SegmentedControl indicator 160ms `ease`, label color 120ms; AnimatedRows 200ms glides, 100ms enter/exit fades; HoverCard fade 100ms; ConnectionIndicator fade-out 150ms; TextShimmer 300ms delay, 1s sweep, 500ms rest, 15° tilt; skeleton pulse 2s cubic-bezier(0.36,0,0.64,1). Other local curves: enter cubic-bezier(0.22,1,0.36,1) / (0.2,0.8,0.2,1) / (0.16,1,0.3,1); exit cubic-bezier(0.4,0,1,1).

Reduced motion: every animated primitive has `@media (prefers-reduced-motion: reduce) { animation: none / transition: none }` (Modal, Tooltip, HoverCard, SegmentedControl/Tabs, TextShimmer, DisclosureRow, ZoomControls, ConnectionIndicator, DetailDocument). Android: honor `Settings.Global.ANIMATOR_DURATION_SCALE == 0` -> snap.

---

## 6. Spacing and icons

**Spacing**: no spacing token scale exists; components use literal px on a 2/4px grid. Common values: 2, 4 (menu padding, segmented inset, button icon gap), 6/8 (menu item padding 6×8), 10 (sm button h-padding), 14 (md button h-padding), 20 (dialog gap), 24 (modal viewport padding, hover-card side inset). Suggested Android scale: 2, 4, 6, 8, 12, 16, 20, 24 dp. Control heights: 20 (switch, 36×20), 24 (DisclosureRow), 28, 32, 34 (menu item min), 36, 42.

**Icons**: no third-party icon library (no lucide/heroicons/etc. in package.json). Custom in-house SVG sprite in `packages/client/ui-primitives/src/icons/` (`ICON_SYMBOLS`, ids like `dsw-icon-search-outline-regular`), rendered via `<use>` with `currentColor`. Each glyph in two weights: `Regular` = 1px stroke, `Medium` = 1.3px stroke (same geometry); common sizes 14/16px; `FileTypeIcon` 28px; plugin/guide artwork 36×36 fixed palette; `FishLogo`, `BrandWordmark` brand marks; `LinkIconMedium` 14px site/category glyphs. Android: the sprite symbols are exported to `dsw_*` VectorDrawables (`tools/ui/icons.py`) rather than substituted with Material Symbols.

---

## 7. Component catalog (ui-primitives) and conventions

Source: `packages/client/ui-primitives/README.md#component-catalog`.

| Export | Purpose |
|---|---|
| Button | Action; variants primary / ghost / outline (inset hairline) / toolbar; sizes sm H28, md H36 |
| Switch | Two-state toggle 36×20; disabled = 50% opacity |
| SegmentedControl | Equal-width tablist with sliding indicator for switching panel modes |
| Checkbox | Labeled controlled native checkbox |
| Input | Single-line text entry |
| InlineEditor | Autogrow multi-line inline editor (≤6 lines; Enter saves, Esc cancels) |
| ZoomControls | Floating document zoom controls |
| Menu, MenuItemButton | Dropdown with data rows, separators, group labels, submenus, trailing shortcut hints |
| MenuGroup | Accessible sticky-heading groups for custom menus/listboxes |
| Pill | Selectable capsule for view switchers / filters |
| SegmentedTabs | Equal-width tabs with sliding indicator (md 42px, sm 34px) |
| Tag | Read-only capsule badge, 8 tones |
| PathLabel | Single-line file path, dim directories, left-fade truncation |
| StateDot | 10px status dots (done green / warning amber / error red / idle grey) + 14px ongoing spinner |
| ConnectionIndicator | Inline connection-recovery pill (outage / retry / recovered) |
| DisclosureRow | 24px compact expandable row (tertiary -> secondary on hover) |
| AnimatedRows | Keyed list reorder animation |
| useRowDragPreview | Drag preview for rows |
| TextShimmer | Highlight sweep over "in progress" row text |
| Modal | Centered dialog over mask |
| RiskConfirmation | Sensitive action gated behind a checkbox |
| Tooltip | Hover/focus text bubble |
| HoverCard(Group) | Copyable hover preview card (≤420px tall) |
| DraggableImage, ImageLightbox | Image gestures; modal image viewer |
| Toast | Transient top-center banner |
| SettingsForm / ValueField / SecretField (+ model) | Plugin settings page frame and staged-edit fields |
| JsonTree, JsonBlock | Read-only JSON inspection |
| MarkdownText, CodeBlock | Untrusted GFM + TeX + highlighted code |
| DetailDocument | Long markdown section with skeleton / error / retry |
| UserMessageBubble | User message background, radius (R20), inset, typography |
| UserTextRun, ReferenceChip | Raw inline text and resolved reference chips |
| CommandText | Selectable command text |
| TerminalBlock, ReadBlock, DiffBlock, SearchBlock, WebBlock | Agent tool-output cards |
| icons/*, FishLogo, BrandWordmark, Reference/Link/Permission icons | Glyphs and brand marks |
| PluginArtwork*, GuideArtwork* | Fixed-palette 36×36 artwork |
| FileTypeIcon | 28px category-colored file glyphs |

Conventions (`docs/web-styling.md`, `docs/ui-radius.md`):
- Neutral borders/dividers = **0.5px** hairline; elevated surfaces use the shadow-stroke instead of a border.
- Links (`--dsw-alias-link`): **weight 500, no underline at rest**, dotted underline offset 3px on hover/focus; text-leading links get a 14px `LinkIconMedium` glyph. Compact "Thinking" markdown uses tertiary color + resting dotted underline.
- Disabled buttons: opacity 0.4; disabled switch 0.5.
- Hover styles only on hover-capable pointers; pressed/selected/focus independent. Touch never opens hover previews.
- Primary button = monochrome fill (`brand-primary`) with `label-primary-foreground` text; ghost = transparent + `interactive-bg-hover/active` overlays.
- Hover/pressed overlays are tinted translucent (#263148 @6/10% light, white @8/14% dark), analogous to M3 state layers.

---

## 8. Compose / Material 3 mapping

As implemented: `Color.kt` (`DshColors`, `schemeOf`), `Theme.kt` (`DshType`, M3 `Typography`, `Radius`, `Hairline`, reduced motion), `Primitives.kt`. Dynamic color is off.

**ColorScheme** (Light / Dark)

| M3 role | DSH source | Light | Dark |
|---|---|---|---|
| primary | brand-primary / button-primary-fill | #0F1115 | #F9FAFB |
| onPrimary | label-primary-foreground | #FFFFFF | #0F1115 |
| primaryContainer | button-primary-dimmed | #EBEEF2 | #43454A |
| onPrimaryContainer | label-primary | #0F1115 | #F9FAFB |
| secondary (accent) | state-business-primary / link | #4176E6 | #7AAAFF |
| onSecondary | — | #FFFFFF | #0F1115 |
| secondaryContainer | state-business-tertiary (also nav active accent) | #E4EDFD | #34415B |
| onSecondaryContainer | label-primary-bluish | #0E3074 | #F9FAFB |
| tertiary | brand-primary-new-color | #4176E6 | #5686FE |
| background / surface | bg-base | #FFFFFF | #151517 |
| onBackground / onSurface | label-primary | #0F1115 | #F9FAFB |
| onSurfaceVariant | label-secondary | #61666B | #CFD3D6 |
| surfaceDim / surfaceContainerLowest | sidebar-fill / bg-base | #F9FAFB / #FFFFFF | #1B1B1C / #151517 |
| surfaceContainerLow | bg-layer-1 | #FFFFFF | #232324 |
| surfaceContainer | bg-layer-2 (cards, dialogs) | #FFFFFF | #2C2C2E |
| surfaceContainerHigh | bg-layer-3 | #FFFFFF | #353638 |
| surfaceContainerHighest | bg-module-platform / selector | #F5F6F7 | #353638 |
| surfaceVariant | interactive-bg-hover-solid | #F1F3F5 | #353638 |
| inverseSurface / inverseOnSurface | toast-bg / toast-label | #353638 / #FFFFFF | #43454A / #FFFFFF |
| inversePrimary | link (dark value) | #7AAAFF | #4176E6 |
| outline | border-l4 | #000000 @16% (≈#D6D6D7 on white) | #FFFFFF @20% |
| outlineVariant | border-l2 | #000000 @10% | #FFFFFF @12% |
| error / onError | state-error-primary | #EC1313 / #FFFFFF | #F25A5A / #0F1115 |
| errorContainer | red-100 / red-900 | #FEE2E2 | #570C0C |
| scrim | bg-mask-1 | #000000 @24% | #000000 @50% |
| surfaceTint | (disable tonal elevation) | Transparent | Transparent |

An extended `DshColors` (CompositionLocal `LocalDshColors`) holds what M3 lacks: labelTertiary, labelCaption, link, success/successContainer (#22C55E / #E6FAED|#233C2C), warn/warnLabel/warnContainer, idle, interactiveHover/Pressed overlays, borderL1–L4, bubble, sidebar, menu fill, code block, diff colors, selection (#3B82F6 @40% -> `TextSelectionColors`).

**Typography** (Typography roles, system font family; weights 400/500/600/700)

| M3 role | DSH token |
|---|---|
| headlineSmall | xl-24 (24/32, 600) |
| titleLarge | l-20 (20/28, 500) / markdown-h1 21/30 700 |
| titleMedium | base-strong-16 (16/24, 500) |
| titleSmall | s-strong-14 (14/22, 500) |
| bodyLarge | base-16 (16/24, 400) |
| bodyMedium | s-14 (14/22, 400); markdown body 14/24 |
| bodySmall | xs-13 (13/20, 400) |
| labelLarge | s-strong-14 (14/22, 500) — md button |
| labelMedium | xxs-strong-12 (12/18, 500) — sm button |
| labelSmall | xxxs-strong-11 (11/14, 500) |
Plus markdown/code styles (h1–h4, base, table, small, code 12/19, codeBlock 11/19 mono).

**Shapes** (`Radius`): extraSmall = 4dp, small = 8dp, medium = 12dp, large = 16dp, extraLarge = 28dp (panel/dialog/composer/bottom sheet); plus `xl = 20dp` (settings cards, bubbles) and `tile = 10dp`. `CircleShape` for avatars/dots/pills/tags. No superellipse.

**Elevation**: tonal elevation 0; a `Hairline` (0.5dp) `outline` border plus a very soft shadow (≈2–3dp, alpha 3–5%) for menus/dialogs/composer; flat cards: 0.5dp `outline` border, no shadow.

**Motion**: `tween(200, easing = FastOutSlowInEasing)` default; fast 100ms, slow 300ms.
