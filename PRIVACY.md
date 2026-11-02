# Privacy

Last updated: 2026-11-02.

**Sterna collects nothing and has no server of its own.** It is free software (GPLv3): no account, no analytics, no ads, no trackers, no crash reporting, no Google services (no Firebase, no FCM, no Play Services), and only free/open-source libraries. Your mail never passes through anything run by the Sterna project.

## What stays on your phone

- **Account credentials**, encrypted with the Android KeyStore.
- **Cached mail**, so the app works offline.
- **Attachments you open.** Old ones are pruned (past 30 days or 200 MB) when you open the next one. Settings → Storage → **Clear cache** empties it.
- **Drafts and messages waiting to go out**, until your server has them.
- **A photo you take from the composer** stays in the app's cache while it is being attached, then it is handled like any other attachment. If the app is closed while the camera is open, the photo stays there until Settings → Storage → **Clear cache**.
- **Settings**, the **addresses you have written to** (for autocomplete) and the **senders you allow to load images**.
- **Your correspondents' S/MIME certificates**, each kept from a signed message they sent you, so that a message to them can be encrypted. Only the certificate is kept, with the address it is for.
- **A copy of your JMAP server's address book**, names and addresses only, for autocomplete, when the server publishes one. A shared or delegated mailbox gets none.
- **Your recent searches**, the words only, to suggest them again. The list never leaves the device, though the words of a search still go to your mail server when it searches there. Settings → Privacy & security → **Remember searches** stops keeping new ones, and **Clear search history** empties the list. Signing out of any account empties it too, since a search belongs to no account.

Everything is removed when you remove the account or uninstall the app, except:

- Settings, the addresses you have written to, the allowed senders and your correspondents' S/MIME certificates are not tied to an account. Uninstalling clears them.
- A message in the **Outbox** or **Scheduled** is not deleted with its account, because it may be the only copy. Delete it from those screens.
- Files you save yourself (an attachment, a `.eml`, a PDF) belong to the folder you picked. Sterna never deletes them, even when uninstalled. Attachments and PDFs of encrypted messages are saved decrypted; a `.eml` is saved as it arrived.

Settings → **Backup & restore** writes a file wherever you choose, and reads one back. It is plain JSON, not encrypted, and it holds your preferences, your account setup (server, identities, signatures, folder choices and the like) and the senders you allow to load images. It holds whether searches are remembered, never the searches themselves, and none of your correspondents' S/MIME certificates. Accounts that sign in with Microsoft, with OAuth or with an API token, and shared accounts, are not part of it. Passwords are not in it, so imported accounts have to be signed in again.

## Network connections

Sterna connects only to:

1. **Your mail server** (JMAP or IMAP/SMTP), to read, send, sync and wait for new mail. On JMAP it also reads your address book, about every 30 minutes, and never changes it. On IMAP it tells the server its name and version (the `ID` command) when the server supports it.
2. **Your address's domain, while you add an account**, to find your server for you:
   - *Without credentials:* the domain's autoconfig file (HTTPS, redirects only within the domain), TLS handshakes with `imap.`, `mail.` and `smtp.` names, and the OAuth discovery document on `mail.`, `jmap.` and `api.`.
   - *With your credentials,* when you leave the server field empty: JMAP autodiscovery sends your address and password, or your API token alone if you typed one, over HTTPS to `/.well-known/jmap` on the domain and on its `mail.`, `jmap.` and `api.` subdomains, and to wherever they redirect, until one of them returns your account. After an OAuth sign-in it sends the access token alone the same way, when the server field was empty or named one of those four hosts.
3. **Your provider's sign-in service**, for OAuth accounts: to approve the sign-in, then in the background to refresh the token. It must be on HTTPS, on your domain, on the host that was asked, or on the OpenID Connect issuer you typed yourself in the advanced fields (or a subdomain of any of them). The Microsoft option always uses `login.microsoftonline.com`.
4. **A newsletter's unsubscribe address**, only when you tap Unsubscribe. One-click unsubscribe (RFC 8058) sends one fixed request carrying nothing about you; otherwise the page opens in your browser, after a warning.
5. **Remote images**, only for a message or sender you allowed.

Nothing else: no update check, no license check, no reporting. Links you tap (app-password page, repository, F-Droid) open in your browser.

**UnifiedPush** (ntfy, NextPush…): the distributor app wakes Sterna without ever seeing mail, but it does see when your account gets push traffic. On JMAP, Sterna gives the distributor's address and encryption keys to your mail server, which sends the signal. On IMAP, you can get a relay address in the account's notification settings and give it to whatever you trust; anyone who has it can make Sterna check your server (see [SECURITY.md](SECURITY.md)).

## Remote content

Remote images and tracking pixels are **blocked by default**. **Show images** loads them for one message. **Always show images from this sender** loads them for every message from that sender, tracking pixels included; the list is in Settings → Privacy & security → **Remote images**. Plain-text reading mode loads nothing remote. Scripts, iframes, fonts and forms are always blocked.

## Your mail server

It sees your mail, as with any client. Use one you trust, or run your own.

## Permissions

Sterna's own manifest declares seven permissions:

| Permission | Why |
|---|---|
| `INTERNET` | Reach your mail server. |
| `ACCESS_NETWORK_STATE` | Know whether you are online. |
| `POST_NOTIFICATIONS` | New-mail notifications. Optional. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep push alive without Google. |
| `RECEIVE_BOOT_COMPLETED` | Restore push after a reboot or update. |
| `READ_CONTACTS` | Suggest recipients and show the names you saved for senders, locally. Optional, off by default. |

From the libraries Sterna is built with, the installed app carries three permissions more than that table: `USE_BIOMETRIC` and `USE_FINGERPRINT` for the optional app lock (Android does the check and only answers yes or no), and `WAKE_LOCK` so a background check can finish with the screen off. One more entry, ending in `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, is built from Sterna's own package name; it is signature-level, it guards Sterna's internal broadcasts, and no other app can hold it.

## Contact

Questions: open an issue. Security reports: see [SECURITY.md](SECURITY.md). Changes to this page are in the Git history.
