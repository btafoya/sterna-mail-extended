# Design

This document defines Sterna's visual and motion language. It is the reference
for building the Compose UI so screens stay consistent. It complements
[ARCHITECTURE.md](ARCHITECTURE.md) (technical) and [FEATURES.md](FEATURES.md)
(scope).

## Direction

**Material Expressive, kept restrained.** I use Material 3 Expressive's
components and motion physics, but dial back colour and bounce. The feel I am
after is **sober, elegant, and responsive (réactif)**: lively, never busy.

Three principles:

1. **Hierarchy comes from space and type, not colour.** One accent colour, lots
   of neutral surface.
2. **Privacy shapes the visuals.** No network requests just to render a list
   (see avatars).
3. **Motion is fast and damped.** Everything ≤ 250ms, ease-out, no overshoot.

## Visual language

### Colour

- **Sterna's own palette is the default**: Arctic (light) and Pelagic (dark),
  cold and desaturated, with a **single accent** for actions. It is what makes
  the app look the same, recognisably Sterna, on every device. Material You
  (wallpaper-derived colour) is an **opt-in setting**, not the direction. No
  multi-colour cards.
- Convey hierarchy through elevation and spacing rather than hue.
- Dark mode uses a near-black `surfaceContainerLowest`, elegant and
  power-frugal on an OLED panel.

### Typography

- Material 3 type scale. Lean on contrast: sender in `titleMedium` (medium
  weight), preview in `bodyMedium` with `onSurfaceVariant`. That contrast is what
  reads as "elegant" without ornament.

### Shape & spacing

- Moderate corner radii (12–16dp). **No card per message row**: use airy rows
  separated by whitespace. Reserve a card for the opened message only: there,
  one tonal card under the header gathers the strips above the body (encryption,
  remote images, mailing list, read receipt, unverified sender), each a line of
  at least 48dp, and is absent when none has anything to say.
- 8dp grid, generous 16dp margins. Sobriety comes from the whitespace.

### Avatars (privacy)

- No remote photos by default (consistent with blocking remote content).
- Use **coloured monograms**: initial plus a colour derived from a hash of the
  address. Sober, and no network leak.

## Components

| Component | Treatment |
|---|---|
| Top bar | a fixed `TopAppBar` titled with the folder, and under it the account; as actions, a filter funnel first, then sort, search and an overflow menu. The funnel opens a menu of checkboxes, one per filter the view offers (unread, starred, has attachment; no unread in the unread view), which combine and leave the menu open; while any offered filter is on the funnel takes the primary colour and carries a dot; when the list has a reader beside it, the funnel and the sort are folded into the overflow menu as a filters section and a sort section, and the overflow button carries the dot instead; hamburger opens the folder drawer below 1200 dp of window width; from 1200 dp there is no hamburger, the drawer is permanent. Settings sits at the foot of that drawer rather than in the bar. |
| Message row | monogram (a setting) · sender + time · subject · preview (0 to 5 lines, a setting) · an account chip, tinted with the account's own colour when one is set, where rows come from several accounts (unified inbox, search) · paperclip and favourite star at the end. **Unread is shown by weight, never by a status dot**: sender, time and subject go bold, and the row also takes `surfaceContainerHighest` unless the reader turns that background off, because bold alone read too faint in the dark scheme. |
| Folder drawer | `ModalNavigationDrawer` (the M3 drawer I kept) below 1200 dp of window width, `PermanentNavigationDrawer` with the same sheet from 1200 dp: account header, folder tree, selected item in `secondaryContainer`. From 600 dp the list and the open message share the window side by side (list 40%, never under 280 dp); the open row takes `primaryContainer`. |
| Compose FAB | `FloatingActionButton` (extended) that shrinks to icon-only on scroll. |
| Opened message | The one place a card is used; HTML in a WebView with remote content blocked. In a rich body the TRAILING quoted block is folded into a native `<details>` and shows a "Quoted text" button instead: a tap opens it, and the state is not remembered, so the message reopens on what is new. One block only, and only with something visible above it, so an interleaved reply and a body that is nothing but a quote are both left alone. Native markup, no script, so nothing here needs JavaScript in that WebView. |

**Read receipts, on both sides.** Asking for one is a per-message entry in the composer's overflow
menu, not an icon of its own: it is a choice read before it is ticked rather than a glyph one
recognises. Answering someone else's request is a strip on the opened message: it asks, one tap puts
an answer in the outbox, and nothing leaves without that tap. The single switch that governs the
strip sits in Privacy & security, is off by default, and has no position that answers on its own.

## Motion

Golden rule for "réactif": everything **≤ 250ms**, `FastOutSlowIn`, **no
overshoot**. Material 3 Expressive springs are welcome, but tuned **damped** (no
bounce) so it stays alive yet sober.

| Interaction | Compose implementation | Duration / curve |
|---|---|---|
| List → message (under 600 dp) | `fadeIn` / `fadeOut` cross-fade; falls back to instant under reduced motion or when the fade-crash sentinel has latched | 200ms fade, `FastOutSlowIn` |
| List → reading pane (600 dp and up) | none: the message replaces the pane's content with no fade, because a fade over a live WebView is the crash window the sentinel guards (#10) | instant |
| Row appearance | `animateItem()` on `LazyColumn` | default, subtle |
| Swipe actions | `SwipeableEmailRow`, action icon fades in, and out with the row | tracks the finger |
| FAB collapse | FAB `expanded` | native |
| Delete / archive by swipe | the row flies off the way the finger sent it and leaves the list without waiting on the server; a refused write flies it back | 300ms, `FastOutSlowIn` |
| Pull-to-refresh | `PullToRefreshBox`, `TernRefreshIndicator` settles with no bounce | native, short |

## Settings & secondary screens

This covers everything that is not the inbox or a message: preferences and
account management. It never says what is built and what is not: that register
is [FEATURES.md](FEATURES.md), and there is only one of it.

### Model

Settings is a **single hub** with global categories plus a **per-account
section**, the K-9/Gmail two-tier model. The folder drawer keeps only quick
account switching and folders; full account management lives in Settings. The hub
itself stays short and scannable (icon · title · summary of the current value);
depth lives in detail screens. This is **progressive disclosure**: complete
configuration is reachable, but general settings stay on the surface.

### Information architecture

```
Settings (hub)
├─ Accounts                     → per-account detail, add an account, server settings
├─ App
│   ├─ Appearance               → language, theme, dynamic colour, message list
│   ├─ Reading and writing      → conversations, message, swipe actions, signature
│   ├─ Notifications            → delivery, new mail, notification content, quiet hours
│   ├─ Privacy & security       → app lock, links, read receipts, quoted dates,
│   │                             remote images, recipient suggestions
│   ├─ Storage                  → on-device usage, cached messages, mailbox quota, maintenance
│   └─ Backup & restore         → export / import the app's settings
├─ This account · <name>        → server-side, and only for the account in use
│   ├─ Vacation responder       → auto-reply while you're away
│   └─ Filters                  → server-side rules (Sieve)
└─ About                        → version, source, licence, author
```

`App`, the account group and `About` are section headers, not screens: About is
four rows the hub draws itself (version, source, licence, author). The account
group carries the name of the account in use, so switching account is what
changes which server its two screens write to.

### Global vs per-account

| Per-account (Accounts → detail) | Global (top-level categories) |
|---|---|
| Display name, account colour, signature override, per-account notifications, server info, **Sign out** | Theme, density, dynamic colour, signature behaviour, swipe actions, remote-image policy, read receipts, link safety, app lock, cache/sync, about |

Sign out lives in the per-account detail (not the inbox top bar), consistent with
the hub model.

### Components

Reuse the existing `SettingSwitch`; add a small, consistent kit:

| Component | Role |
|---|---|
| `SettingsCategoryRow` | Hub row: icon, title, value-summary, chevron. |
| `SettingsSection` | Accent-tinted header grouping rows in a detail screen. |
| `SettingSwitch` *(existing)* | Boolean toggle. |
| `SettingChoiceRow` + `SettingChoiceDialog` | Single choice (theme, image policy, app lock). |
| `AccountRow` | Monogram + label + email (reuses the monogram avatar). |

The full kit lives in `SettingsComponents.kt`; this table lists only the main pieces.

### Visual & motion rules

- **Hub:** a search field on top, then a `Column` of category rows in a `verticalScroll`, **no
  cards**, 16dp margins, icon tint `onSurfaceVariant`, summary line in `bodyMedium` /
  `onSurfaceVariant`.
- **Hub search:** a typed query replaces the category rows with matching settings, each with its
  path in `bodySmall` below it (the rubric, or "Accounts › <account>"). Words match in any order,
  without case or accents; `ł`, `ø`, `đ`, `ß` are not folded. It covers the rubrics, the settings of
  Appearance, Reading, Notifications and Privacy, and those of the current account only; the
  Vacation and Filters fields are reached through their rubric. A tapped result opens its screen,
  scrolls to the row and tints it once, like the message list on return; no tint under reduced
  motion.
- **Detail screens:** sectioned lists; section headers in the single accent
  (`labelLarge`); separation by whitespace, not boxes; `LargeTopAppBar` that
  collapses on scroll.
- Controls use the accent colour; single-choice settings use an M3 dialog or
  bottom sheet.
- Motion follows the table above: ≤ 250ms, `FastOutSlowIn`, damped (no overshoot).

### Storage note

What the device holds is a **bounded mirror** of the mailbox, never a copy of
it: every store has its bound, and signing out purges the account. The rules
are in [Storage & retention](ARCHITECTURE.md#storage--retention). Storage
figures are about the device; the server's mailbox quota is a separate number.

App preferences are backed by a reactive DataStore `SettingsRepository`
(`Flow` per setting), separate from `AccountStore`, which keeps accounts,
credentials, and per-account metadata. Both are listed in
[Storage & retention](ARCHITECTURE.md#storage--retention).
