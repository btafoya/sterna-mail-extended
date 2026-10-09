# Security

Sterna is an email client: it displays mail written by strangers and talks to servers over the network. This page says how to report a problem, what Sterna defends against, and where that protection stops.

## Reporting a vulnerability

Please report privately rather than in a public issue: open a security advisory on the repository, or email the maintainer listed in the repository metadata. Include the affected version (`versionName` in `app/build.gradle.kts`) and a proof of concept if you have one (a crafted `.eml`, a server response, an intent). I will credit you once a fix ships, if you wish.

Test only against servers and accounts you own; a local Stalwart instance is the easiest target. Please give me a reasonable window before any public write-up: F-Droid and Obtainium users take time to update.

## Threat model

**In scope:** hostile email content (HTML, MIME, headers, attachments), a hostile mail server or network attacker, another app on the device, and someone with brief access to your phone.

**Out of scope:** the privacy of the mail provider you choose (see [PRIVACY.md](PRIVACY.md)), a rooted or compromised device, and attacks on a powered-off device beyond what the Android KeyStore resists.

## Hardening

### Network

- **TLS is verified on every connection**, certificate chain and hostname, for JMAP, IMAP, SMTP and ManageSieve. Credentials are sent only once the handshake is done. There is no "trust this certificate anyway" prompt.
- **Cleartext is never a default.** Sign-in offers only TLS or STARTTLS. The account editor allows an unencrypted connection, for a local bridge such as Proton Bridge on 127.0.0.1, and shows a warning when you pick it. JMAP and OAuth run over HTTPS only, and a redirect that changes scheme is not followed.
- **ManageSieve** (filters and auto-reply on IMAP accounts) requires STARTTLS. If the server does not offer it, or offers no password login once encrypted, the password is not sent.
- **OAuth token requests follow no redirect**, and the token endpoint a server advertises is accepted only over HTTPS, on the host that was asked, on your address's domain, or on the OpenID Connect issuer you typed yourself in the advanced fields (or a subdomain of any of them).
- **Autoconfiguration** follows redirects only within your address's domain.

Two choices deserve to be stated plainly:

- **Certificate authorities you install count as valid**, so a self-hosted server with its own authority works (#93). The other side: an authority pushed by an employer's managed profile, or installed by someone else with access to your phone, can intercept Sterna's connections too. K-9 Mail and FairEmail decide the same way. It applies app-wide, including remote images you choose to load.
- **Your credentials go wherever JMAP discovery leads.** Discovery tries your domain, then `mail.`, `jmap.` and `api.` under it, and follows redirects to another host, re-sending your credentials there (some real deployments, Fastmail among them, need this). The URLs in the session document receive them too, whatever host they name. Whoever answers HTTPS on those names receives your credentials, always over HTTPS.

### Reading mail

- The message view runs **no JavaScript**, has no JavaScript bridge, an opaque origin and no file access, and carries a Content-Security-Policy blocking scripts, frames and forms.
- **Remote content is blocked** until you load images or allow the sender, so no tracking pixel fires when you open a message.
- **Only `http`, `https`, `mailto`, `tel`, `sms` and `geo` links open**, and only on a tap. Tracking parameters are stripped from them.
- Parsers are bounded against hostile input (IMAP literal size, MIME depth and part count). Displayed headers are stripped of control characters and bidi overrides. Line breaks are refused in outgoing headers, SMTP addresses and IMAP commands, including those coming from a `mailto:` link or a notification quick reply.

### Data on the device

- **Passwords and OAuth refresh tokens** are encrypted with an AES-256-GCM key held in the Android KeyStore, bound to their account. The short-lived OAuth access token is not: it sits in the app's private storage, readable with root or ADB access to that storage, until it expires.
- **Cloud backup is disabled.** Device-to-device transfer (Android 12 and later) leaves out credentials, the message cache and settings, but other app files can travel to the new phone: local drafts, messages waiting in the outbox with their attachments, the attachments of scheduled messages, and the relay push address.
- **The app lock is a screen, not encryption.** It keeps someone holding your unlocked phone from reading your mail: content stays out of recents and screenshots, and the home-screen widget shows no sender or subject. The message cache (headers, bodies, search index) is **not encrypted**, so root, ADB access to the app's storage or a filesystem image reads it without meeting the lock. Only credentials stay behind the KeyStore.

### OpenPGP

- OpenPGP is delegated to an **external OpenPGP app**; private keys and passphrases never enter Sterna. **Sterna does not check which app that is**: any installed app offering the OpenPGP service can answer. Until you pick one, Sterna uses OpenKeychain if it is installed and otherwise one of the others, without asking, even when no account has OpenPGP turned on: public keys found in received mail are handed to it, and a signed message you open is sent to it for verification. Which OpenPGP app to trust is your call.
- **Decrypted text stays in memory**, never in the cache or the search index. Exceptions: an attachment you open stays decrypted in the app's cache until you clear it, and an attachment you save, or a message you print or save as PDF, leaves plaintext outside the app, where nothing Sterna clears can reach it.
- **The subject travels in the clear.** An encrypted message also carries the real subject inside the encryption (RFC 9788), which protects it from tampering, not from being read. A subject found inside is shown only in the reader; the list, notifications, replies and forwards keep the outer one, so it never leaves in the clear. Sterna does not warn when the two differ.
- **Public keys travel in an `Autocrypt:` header**, announced on every message sent from an account with a key set up, and imported into the OpenPGP app from received mail. Such a key makes encryption possible and proves nothing about who holds it. Keys are imported only from messages Sterna has downloaded in full, which on JMAP includes the newest Inbox messages before you open them. The key of a message the server dates more than 24 h ahead of the phone's clock is ignored. Nothing here turns encryption on by itself.

### S/MIME

- **The S/MIME private key stays in Android's keystore**: Sterna neither reads nor copies it and has the keystore use it to decrypt and to sign, and the decrypted text stays in memory, with the same exceptions as OpenPGP (an attachment you open or save, a message you print or save as PDF).
- **Only the certificate of a verified signature is kept.** A correspondent's certificate, used to encrypt to them, is kept only from a signed message whose signature checks out, chains to an authority Android trusts and is issued for the message's From address. A self-signed certificate Android does not trust, or the one of a signature that fails, is never kept. A message encrypted to a recipient with no such certificate, or with one that has expired, is not sent.
- **An encrypted message is also encrypted to your own certificate**, so the copy in Sent can be opened again.
- **The subject travels in the clear.** The envelope also names the certificate of every recipient, readable by each of them, so a message with Bcc recipients is not encrypted: the send is refused instead.
- **There is no revocation check.** A certificate its authority has revoked is still accepted, for a signature and to encrypt to, until it expires.

### Android platform surface

- Four components are exported by Sterna's own manifest:
  - `MainActivity` accepts `mailto:` links and shares, which only prefill the composer: only `content:` URIs are read, and nothing sends. It also follows the navigation extras of Sterna's own notifications and widgets, which any app can send too: they can open the unified inbox, or open a message, and so mark it read, when that app knows its identifier. It also receives the browser's return at the end of an OAuth sign-in, accepted only while a sign-in is in progress and only with the `state` that sign-in drew.
  - `BootReceiver`, `UnreadWidgetProvider` and `RecentMailWidgetProvider` listen only to system broadcasts and read nothing from the intent beyond its action.
- Everything else of Sterna's own is private, including the widget's row service (also guarded by `BIND_REMOTEVIEWS`) and the FileProvider, which shares two things: an attachment you open, read-only, and the file of a photo you take from the composer, which the camera app is allowed to write. That photo file is deleted once it is attached. Notification intents are immutable, except the quick reply's, which targets a private receiver.
- The libraries add five more exported components. `SystemJobService`, `DiagnosticsReceiver` and `ProfileInstallReceiver` require a permission only the system holds. The UnifiedPush connector's `MessagingReceiverImpl` and `RaiseToForegroundService` require none, as the UnifiedPush specification demands, so **any app can forge a push event**. A forged event carries no mail; it makes Sterna sync over its own authenticated connection. On an IMAP account that has set up a relay push address and is waiting for its first push, though, a forged event switches the account to UnifiedPush and stops IMAP IDLE until you remove the address or the distributor changes; once switched, it can also show a recent push that never came.
