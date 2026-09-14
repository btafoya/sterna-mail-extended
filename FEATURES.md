# Features

What Sterna Mail does, what is planned, and what is only an idea. How any of it
works is in [ARCHITECTURE.md](ARCHITECTURE.md).

**Status key**

- ✅ **Done**, shipped.
- 🔜 **Planned**, next up.
- 💡 **Proposed**, not scheduled.
- ⭐ **JMAP-native**, RFC 8620/8621 gives it almost for free.

Status is read against `main`, not against the latest release (1.5.4), so a ✅ can
be ahead of the version you have installed.

---

## What's next

Tiers 1 to 4 of the original roadmap are shipped. What is left:

- 🔜 Gmail / Google OAuth2. The XOAUTH2 plumbing is done; the blocker is Google's
  verification for restricted Gmail scopes. Until then, Gmail works with an
  app-specific password.
- 💡 Search-result highlights (JMAP `SearchSnippet`).
- 💡 Quick filters for starred-only and has-attachment.
- 💡 Folder subscribe, and drag to reorder.
- 💡 IMAP CONDSTORE incremental sync.
- 💡 DNS SRV autodiscovery (`_jmap._tcp`).
- 💡 Fuller TalkBack audit and touch-target review.
- 💡 S/MIME.
- 💡 Calendar conflict detection (needs calendar read access).

---

## Protocols

- ✅ **JMAP** (RFC 8620/8621), the primary backend.
- ✅ **IMAP + SMTP**, hand-rolled, at parity with JMAP. One pooled connection per
  account, and IDLE push on a connection of its own.

## Reading & triage

- ✅ Message list and reader, HTML in a WebView with remote content blocked.
- ✅ Plain-text bodies keep their paragraphs and unwrap `format=flowed` (RFC 3676).
- ✅ Trailing quoted history folds behind a "Quoted text" button (HTML only).
- ✅ ⭐ Conversation threading, the server's threads on JMAP, rebuilt from
  `References` on IMAP. Can be turned off for a flat list.
- ✅ Offline reading, and paged lists that load older mail as you scroll.
- ✅ Mark read or unread, star, archive, delete, with an Undo snackbar.
- ✅ Configurable swipe actions; long-press for multi-select and bulk actions.
- ✅ Sort by date, subject, sender, unread-first or starred-first; mark all read.
- ✅ Pull to refresh.
- ✅ Folder drawer, to open any mailbox of the account.
- ✅ Optional "mark as read when deleting", so deleted mail does not sit unread in
  the Trash.
- ✅ Quick filter for unread only.
- ✅ Snooze a message until later.
- ✅ Report spam and not-spam.
- ✅ Unarchive on reply (JMAP only).
- ✅ Empty trash, behind Undo, limited to what the folder held when you confirmed.
- ✅ Print a message or save it as a PDF, remote images still blocked.
- ✅ Save a message as `.eml`, byte for byte as the server holds it.
- ✅ One-gesture unsubscribe (RFC 2369 / RFC 8058); the request is shown before it
  is sent.
- ✅ Confirmation before replying to 5 or more recipients, and before signing out.

## Organisation & search

- ✅ Server-side search as you type, with instant results from the local cache.
- ✅ In the unified inbox, search covers every account and merges the results.
- ✅ Advanced filters: from, subject, has attachment, date, starred only.
- ✅ Unified inbox across accounts, date-sorted, each row naming its account.
- ✅ Mail by sender: gather one address's mail, see how much there is, delete it in
  one go, and optionally add a server-side rule for the future.
- ✅ Move a message to another account's folder. This is a copy confirmed on the
  target before the original goes to Trash, so it has no Undo.
- ✅ Folder management: create, rename, delete, nested folders as a tree.
- ✅ Per-folder settings on their own screen, including hiding a folder from this
  device while its mail stays searchable.
- ✅ An Archive folder is created on first archive when the account has none.

## Composing & sending

- ✅ Compose and send on both protocols; opens `mailto:` links from other apps.
- ✅ Reply, reply-all and forward, quoted and threaded.
- ✅ Forward as attachment (`message/rfc822`).
- ✅ Drafts saved to the server; closing with unsaved edits asks first.
- ✅ Attachments: pick, send, download and open. Inline `cid:` images render.
- ✅ An attached message opens in a sheet, with nothing written to disk.
- ✅ Rich text: bold, italic, underline, strikethrough, lists, links.
- ✅ Undo send, then an Outbox that survives app death and retries when the network
  comes back, with its own screen for waiting, sending and failed mail.
- ✅ Schedule send, with a screen to cancel a pending one. No attachments yet.
- ✅ "Forgot attachment?" reminder, in every UI language.
- ✅ Recipient autocomplete from cached and opt-in device contacts, as removable
  chips, with address validation.
- ✅ Several sending identities per account, each with its own signature, chosen
  from a From picker.
- ✅ Read-receipt requests and responses, both off by default, never sent without a
  tap.

## Accounts & setup

- ✅ Several accounts, add, switch and sign out. Credentials are encrypted with the
  AndroidKeyStore.
- ✅ Setup led by the address: one field, then only the secret that the answer
  needs. A manual host and port form is the fallback.
- ✅ Autodiscovery of `/.well-known/jmap`, and of the IMAP/SMTP settings a domain
  publishes for itself. No third-party configuration directory is contacted.
- ✅ IMAP presets for Gmail, Yahoo, iCloud, Fastmail, Proton Bridge, Yandex,
  Mail.ru, QQ Mail, 163 Mail, 126 Mail, Yeah Mail and Sina Mail, with the
  app-password reminder most of them need.
- ✅ OAuth2, device flow (RFC 8628) where the server offers one, authorization code
  with PKCE otherwise. Tokens are encrypted and refreshed, no password is handled.
- ✅ Outlook and Microsoft, over OAuth2 with XOAUTH2 on IMAP and SMTP. Personal
  accounts work; work and school accounts need their administrator's consent.
- ✅ ⭐ Several JMAP accounts under one login (RFC 8620 §1.6.2). A delegated or
  shared mailbox becomes its own account in the switcher, on the one credential.
- ✅ Per-account server settings with a Test connection button, an optional display
  name, and a colour that tints its avatar and its unified-inbox chip.
- ✅ Settings export and import as a JSON file. Accounts and passwords stay out.

## Sync, push & notifications

- ✅ ⭐ Incremental sync on JMAP; IMAP does a bounded re-query.
- ✅ ⭐ Push with no Google and no FCM: JMAP EventSource, or IMAP IDLE.
- ✅ ⭐ UnifiedPush, which drops the persistent connection and its permanent
  notification. An IMAP account can ride it through a relay address you publish
  into whatever relay you already trust; nothing of the mailbox travels through it.
- ✅ New-mail notifications, for the current account or for all of them. The
  per-account switch governs the background fetch itself, not just the notice.
- ✅ Notifications beyond the Inbox, with a watch switch per folder.
- ✅ Quick actions on a notification: reply inline, mark read, delete.
- ✅ Notifications bundled per account, and quiet hours at night.
- ✅ Push reconnects on its own and catches what it missed.
- ✅ A linked sub-account answers for its own switch rather than its login's. Its
  guaranteed delivery is the 30-minute cycle: a server does not push the changes of
  an account shared with you.
- ✅ Switching sync off hands back the push subscription. Two things are left
  standing on purpose: a subscription the server will not destroy, which expires on
  its own, and a relay address you have published, which only its own Remove button
  takes back.

## Privacy & security

- ✅ Remote images and tracking pixels blocked by default, with a per-sender
  allowlist.
- ✅ Tracking parameters stripped from tapped links.
- ✅ Optional confirmation before an external link opens, showing where it goes.
- ✅ App lock, biometric, falling back to the screen PIN, pattern or password.
- ✅ The composer asks the keyboard not to learn what is typed into it.
- ✅ No telemetry, stated and detailed in [PRIVACY.md](PRIVACY.md).

## Encryption

- ✅ OpenPGP through an external OpenPGP app: decrypt, verify, sign and encrypt
  (PGP/MIME) on both protocols. Decrypted content is never written to disk, except
  an attachment you open or save. Subjects are not encrypted. Guide:
  [ENCRYPTION.md](ENCRYPTION.md).

## UX & accessibility

- ✅ Material 3 in Sterna's own palette, Arctic light and Pelagic dark. Material You
  wallpaper colour is an opt-in toggle.
- ✅ List and reading pane side by side from 600 dp of window width; the folder
  drawer stays open beside them from 1200 dp.
- ✅ Theme toggle, list density, preview length and message text size.
- ✅ Contact avatars and sender monograms.
- ✅ Home-screen widgets for the unread count and for recent mail, both respecting
  the app lock.
- ✅ Settings grouped into Accounts, the app, and the account you are on.
- ✅ Storage screen with a per-account breakdown and Clear cache; the attachment
  cache is capped by size and age, and signing out purges that account.
- ✅ Per-account sync window, by age or by message count.
- ✅ Reply bar toggle, and a compact top bar naming the folder and the account.
- ✅ About screen: version and release date, source, license, author.
- ✅ Accessibility pass: screen-reader labels on icon-only controls, and text that
  scales with the system font size.
- ✅ Ten UI languages.
- ✅ Coastal line-art empty states.

## Server-side extras

- ✅ ⭐ Vacation responder (JMAP `VacationResponse`, RFC 8621), per account.
- ✅ ⭐ Mailbox quota (RFC 9425), shown in Settings → Storage.
- ✅ Server-side filters where the server supports Sieve (RFC 9661), or ManageSieve
  (RFC 5804) for a password-based IMAP account, built from a form. A script Sterna
  cannot read is never silently overwritten: the screen says so and asks before
  replacing it.
- ✅ Calendar invites: the first event of a `.ics` shown as a card above the body,
  parsed by a small built-in reader. "Add to calendar" hands it to your calendar
  app, so Sterna takes no calendar permission.
- ✅ RSVP to an invite, sending an iTIP reply to the organiser over the account's
  own mail path.
