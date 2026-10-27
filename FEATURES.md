# Features

What Sterna Mail does, what is planned, and what is only an idea. How any of it
works is in [ARCHITECTURE.md](ARCHITECTURE.md).

**Status key**

- ✅ **Done**, shipped.
- ⏸️ **Blocked**, waiting on a third party, not on code.
- 💡 **Proposed**, not scheduled.
- ⭐ **JMAP-native**, RFC 8620/8621 gives it almost for free.

Status is read against `main`, not against the latest release, so a ✅ can be ahead
of the version you have installed.

---

## What's next

Tiers 1 to 4 of the original roadmap are shipped. What is left:

- ⏸️ Gmail / Google OAuth2. The XOAUTH2 plumbing is done, but Google requires a
  paid, yearly security audit before an app can use the Gmail scopes, which is
  out of reach for this project. Gmail works with an app-specific password.

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
- ✅ With two accounts or more, tapping the list's title opens a menu to switch to
  another one.
- ✅ In the drawer, a folder's name is bold while its row shows an unread count; a
  setting in Appearance turns the bold off.
- ✅ Optional "mark as read when deleting", so deleted mail does not sit unread in
  the Trash.
- ✅ Quick filters for unread-only, starred-only and has-attachment, which combine.
- ✅ Snooze a message until later, or until a day and time you pick.
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
- ✅ On JMAP accounts, each server search result shows the passage of the message
  that contains the search term instead of its first lines.
- ✅ The searched words are highlighted in each result's preview or passage. When
  nothing was found and the server search could not finish, the search says so and
  offers Retry.
- ✅ Search filters as chips under the search bar: unread, starred and has attachment
  in one tap, and from, recipient (To or Cc), subject and a date range from the
  filters sheet. Each filter in use shows as a chip that removes it in one tap.
- ✅ Recent searches: the words you searched for are suggested again in the search
  bar. The list stays on the device, and it can be turned off or cleared in
  Privacy & security.
- ✅ Unified inbox across accounts, date-sorted, each row naming its account.
- ✅ An Unread entry in the folder drawer: the account's unread mail from every folder
  it shows, except Trash, Junk, Sent and Drafts. Opened from All inboxes, it covers
  every account, each row naming its account.
- ✅ A Starred entry in the folder drawer, behind a setting in Appearance that is off
  by default. It opens the search with the Starred filter set, which asks the
  server, for the account in hand, or for every account when opened from a view
  that covers them all.
- ✅ On an account with several addresses, the chip of a list row names the address
  a message arrived on, when its To or Cc says which. Where rows name their
  account, the address takes the place of the account's name.
- ✅ Mail by sender: gather one address's mail, see how much there is, delete it in
  one go, and optionally add a server-side rule for the future.
- ✅ Move a message to another account's folder. This is a copy confirmed on the
  target before the original goes to Trash, so it has no Undo.
- ✅ Folder management: create, rename, delete, nested folders as a tree.
- ✅ Per-folder settings on their own screen, including hiding a folder from this
  device while its mail stays searchable.
- ✅ Put your own folders in the order you choose, on this device. Standard folders
  stay at the top.
- ✅ The move-to-folder picker opens on up to three suggestions: the folders you recently
  and often picked there, led by the one you picked at least twice for this sender. Kept
  on this device only; typing in the filter hides them.
- ✅ An Archive folder is created on first archive when the account has none.

## Composing & sending

- ✅ Compose and send on both protocols; opens `mailto:` links from other apps.
- ✅ Reply, reply-all and forward, quoted and threaded.
- ✅ Forward as attachment (`message/rfc822`).
- ✅ Edit as new: reopen a message in the composer as a new message; the original stays where it is.
- ✅ Drafts saved to the server; closing with unsaved edits asks first.
- ✅ Attachments: pick (several files at once), take a photo, send, download and open, also from the composer before sending. Inline `cid:` images render.
- ✅ Save an attachment into a document you choose, or save all of a message's attachments at once into a folder you choose.
- ✅ An attached message opens in a sheet, with nothing written to disk.
- ✅ Rich text: bold, italic, underline, strikethrough, lists, links.
- ✅ Undo send, then an Outbox that survives app death and retries when the network
  comes back, with its own screen for waiting, sending and failed mail.
- ✅ Schedule send, with a screen to cancel a pending one.
- ✅ "Forgot attachment?" reminder, in every UI language.
- ✅ Recipient autocomplete from cached addresses, the sending account's server
  address book (read-only, on JMAP servers that publish one) and opt-in device
  contacts, as removable chips, with address validation.
- ✅ Several sending identities per account, each with its own signature, chosen
  from a From picker.
- ✅ Read-receipt requests and responses, both off by default, never sent without a
  tap.
- ✅ "New message" and "Unread" shortcuts on a long press of the app icon.

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
- ✅ OAuth2: authorization code with PKCE through the browser where the server
  asks clients to register (Sterna then registers itself at each sign-in) or has
  no device endpoint, device flow (RFC 8628) otherwise. The refresh token is
  encrypted and renewed (the short-lived access token is not), and no password
  is handled.
- ✅ Outlook and Microsoft, over OAuth2 with XOAUTH2 on IMAP and SMTP. Personal
  accounts work; work and school accounts need their administrator's consent.
- ✅ ⭐ Several JMAP accounts under one login (RFC 8620 §1.6.2). A delegated or
  shared mailbox becomes its own account in the switcher, on the one credential.
- ✅ Per-account server settings with a Test connection button, an optional display
  name, and a colour that tints its avatar and its unified-inbox chip.
- ✅ An optional SMTP username and password on IMAP password accounts, for sending
  through a server that takes another login; blank means the IMAP login.
- ✅ Settings export and import as a JSON file, with the setup of password accounts;
  accounts that sign in with Microsoft, with OAuth or with an API token, and shared
  accounts, are not in it, and passwords stay out.

## Sync, push & notifications

- ✅ ⭐ Incremental sync on JMAP, and on IMAP servers that support CONDSTORE
  (only the changes are read); other IMAP servers get a bounded re-query.
- ✅ ⭐ Push with no Google and no FCM: JMAP EventSource, or IMAP IDLE.
- ✅ ⭐ UnifiedPush, which drops the persistent connection and its permanent
  notification. An IMAP account can ride it through a relay address you publish
  into whatever relay you already trust; nothing of the mailbox travels through it.
- ✅ New-mail notifications, for the current account or for all of them. The
  per-account switch governs the background fetch itself, not just the notice.
- ✅ Notifications beyond the Inbox, with a watch switch per folder.
- ✅ Quick actions on a notification: reply inline, mark read, delete.
- ✅ Notifications bundled per account, and quiet hours at night.
- ✅ A notification channel per account, so each can have its own sound in
  Android's settings.
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
  (PGP/MIME) on both protocols. Decrypted content, OpenPGP or S/MIME alike, is
  never written to disk, except an attachment you open or save, or a reply you
  confirm sending unencrypted. Subjects are not
  encrypted. Guide:
  [ENCRYPTION.md](ENCRYPTION.md).
- ✅ S/MIME on received messages, on both protocols: signature check against the
  Android certificate store, and decryption (RSA) with a certificate installed in
  Android's settings.
- ✅ S/MIME on sent messages: sign, or sign and encrypt (RSA, AES-256), with a
  certificate installed in Android's settings. A recipient's certificate is kept
  from a signed message of theirs whose signature checks out. Subjects are not
  encrypted, and encrypting refuses Bcc recipients.

## UX & accessibility

- ✅ Material 3 in Sterna's own palette, Arctic light and Pelagic dark. Material You
  wallpaper colour is an opt-in toggle.
- ✅ List and reading pane side by side from 600 dp of window width; the folder
  drawer stays open beside them from 1200 dp. A setting keeps the message beside
  the list always, only on a window wider than tall, or never.
- ✅ Theme toggle, list density, message beside the list, preview length and
  message text size.
- ✅ An opt-in setting lets the volume keys step the text size of an open message.
  Everywhere else they keep changing the volume.
- ✅ Contact avatars and sender monograms.
- ✅ Home-screen widgets for the unread count and for recent mail, both respecting
  the app lock.
- ✅ Settings grouped into Accounts, the app, and the account you are on.
- ✅ Storage screen with a per-account breakdown and Clear cache; the attachment
  cache is capped by size and age, and signing out purges that account.
- ✅ Per-account sync window, by age or by message count.
- ✅ Reply bar toggle, and a compact top bar naming the folder and the account.
- ✅ About screen: version and release date, source, license, author.
- ✅ Accessibility pass: controls carry a name and a state for screen readers,
  touch targets were reviewed against 48 dp, and text scales with the system
  font size.
- ✅ Eleven UI languages.
- ✅ Coastal line-art empty states.

## Server-side extras

- ✅ ⭐ Vacation responder (JMAP `VacationResponse`, RFC 8621), per account.
- ✅ ⭐ Mailbox quota (RFC 9425), shown in Settings → Storage.
- ✅ Server-side filters where the server supports Sieve (RFC 9661), or ManageSieve
  (RFC 5804) for a password-based IMAP account, built from a form: several conditions
  (all or any of sender, recipient, Cc, subject, mailing list, body, size) and several
  actions (move, copy, delete into the Trash, mark read, star, forward, stop later
  rules; a forward keeps the message in your inbox), what the server does not offer greyed. Sterna opens the account's active
  script whatever its name, shows the rules the form can hold as rules and every other
  block as a rule written by hand, editable as text, except a Bulwark rule the form
  cannot hold, which is edited in Bulwark; a Bulwark label is kept as it was.
  Rules are reordered by dragging
  or with Up and Down, since the server applies them from top to bottom. Each rule
  is saved to the server from its own editor, in place: the rest of the script is
  kept, and on an IMAP account Sterna's own auto-reply keeps skipping the senders
  its rules send to the Trash.
  Saving activates the script only when none is active or
  the active one is the server's auto-reply, after a warning.
- ✅ Calendar invites: the first event of a `.ics` shown as a card above the body,
  parsed by a small built-in reader. "Add to calendar" hands it to your calendar
  app, so Sterna takes no calendar permission.
- ✅ RSVP to an invite, sending an iTIP reply to the organiser over the account's
  own mail path.
