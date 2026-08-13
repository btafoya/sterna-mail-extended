# Privacy

**Short version: Sterna collects nothing and has no server of its own. It talks
to the mail server you configure, and to a short list of other hosts, listed
below, each of them because you asked for it. Everything else stays on your
device.**

Sterna is free software (GPLv3) with no business model built on your data. There
is no Sterna account, no Sterna server, and no company in the middle. This document
explains exactly what that means, in plain terms. Last updated: 2026-08-13.

## What Sterna does NOT do

- **No analytics or telemetry.** No usage statistics, no event tracking, nothing
  phoned home — ever.
- **No advertising and no ad SDKs.**
- **No third-party trackers.** The app contains no tracking or profiling
  libraries.
- **No crash reporting to anyone.** Crashes are not sent off your device.
- **No Google services.** Sterna does **not** use Firebase, Google Play Services,
  or Firebase Cloud Messaging (FCM). New mail is pushed from your own server to
  your phone over JMAP EventSource, IMAP IDLE or UnifiedPush, so the app runs
  fully on de-Googled devices.
- **No proprietary dependencies.** Sterna is built only from free/open-source
  libraries.

## Data stored on your device

Everything Sterna keeps lives locally on your phone. None of it is collected, and none
of it reaches the Sterna project, which has no server to receive it. What does leave the
device goes only to the hosts listed under **Network connections** below: your
credentials to your mail server, which is how it knows you, and, on an OAuth account,
the stored refresh token to your provider's sign-in service, which is where a new access
token comes from. The rest stays here:

- **Account credentials** — stored encrypted, protected by the Android KeyStore.
- **Cached mail** — messages, threads, and mailboxes are cached locally so the
  app works offline. This cache is removed when you sign the account out.
- **Attachments you open** — an attachment you open is written to the app's own
  storage until the cache is cleared, including one that arrived encrypted, since
  it has to be decrypted to be opened. The cache is pruned when you open the next
  attachment, and only then: files older than 30 days go first, then the oldest
  files until what is left fits under 200 MB. Nothing prunes it in the background,
  so if you open no further attachment, the last one stays on the device until you
  clear the cache or sign the account out. You can clear it at any time from
  Settings, under Storage, with **Clear cache**, and signing the account out
  clears it too.
- **Settings** — your preferences (e.g. notification scope).

This data is removed when you remove the account or uninstall the app, subject to
normal Android storage behaviour.

## Network connections

Sterna has no server of its own, so every connection it makes goes to a host you
chose, or to one named in a message you received. There are five, each with what
sets it off.

1. **Your mail server**, over **JMAP** or over **IMAP/SMTP**, depending on what
   your account uses. This is where mail is read, searched, sent and synced, and
   where a JMAP EventSource or an IMAP IDLE connection is held open, or a
   periodic check made, to learn about new mail.
2. **The domain of the address you type**, while you are adding an account, so
   you do not have to know your server's name. Sterna requests
   `/.well-known/jmap` over HTTPS on that domain and on its conventional `mail.`,
   `jmap.` and `api.` subdomains, and each of those requests carries the address
   and password you just typed, in an HTTPS `Authorization` header. This is how
   JMAP autodiscovery works: a candidate only counts if it hands back a session
   for your account, which it cannot do without authenticating you. So each of
   those four hosts, all derived from your own address's domain, is offered your
   credentials, and so is any host one of them redirects to. The probe stops at
   the first candidate that returns a session holding a mail account. Answering
   is not enough to stop it: a host that refuses the sign-in, or that serves
   something which is not a JMAP session, is passed over and the next candidate
   is tried. It happens on the setup screen only, never afterwards.
3. **Your provider's sign-in service**, and only if your account signs in with
   OAuth. It is the service that asks you to approve the sign-in and hands back
   the token. Sterna also goes back to it on its own: an access token expires, so
   the stored refresh token is sent to the same token endpoint for a fresh one
   whenever a sync, a send or a push check needs a valid token, screen off and
   nobody watching. Accounts using a
   password or an app password never reach it.
4. **The unsubscribe address the sender put in a newsletter**, and only when you
   tap Unsubscribe on that message. Sterna sends that address one HTTPS request
   whose content is fixed by the standard for one-click unsubscribe (RFC 8058).
   It carries none of your credentials, nothing is added to it about you, and no
   page is loaded from it.
5. **The hosts a sender chose for the remote images in their message**, and only
   for a message whose remote content you allowed. That is your doing, once per
   message or once per sender; see "Remote content is blocked by default" below
   for what allowing lets through.

Beyond these five, Sterna opens no connection of its own initiative: no update
check, no license check, no reporting endpoint.

**A UnifiedPush distributor** (ntfy, NextPush…) sits on the path too, when your
account uses that push transport, but it is not a host Sterna connects to. Sterna
hands the endpoint URL that your distributor issued to your mail server, and it is
your server that posts the new-mail signal there. On the phone, the distributor app
passes the signal to Sterna through Android, not over the network. The signal holds
no message content, so the distributor cannot read your mail or see who wrote to
you; it does see that push traffic is going to that account, and when. Whether the
signal is encrypted is your server's doing: Sterna gives the server WebPush keys
for it, and refuses the distributor outright when the keys are missing, falling
back to the direct connection, but it does accept a signal that arrives
unencrypted. The mail itself is always fetched from your own server.

Some addresses in the app are **links, not connections**: your provider's
app-password page, the OAuth approval page, and the project's repository and
F-Droid pages open in your browser when you tap them. That is your browser
talking to those sites; Sterna never contacts them in the background.

There is no intermediary. Your mail never passes through any infrastructure
operated by the Sterna project.

## Remote content is blocked by default

Many emails contain remote images and **tracking pixels** that report back to the
sender when (and sometimes where) you open a message. Sterna **blocks remote
content in messages by default**, so simply opening an email does not leak that
you read it. Inline images that are part of the message itself are rendered from
the message, not fetched from the network.

You can lift the block yourself, in two ways. **Show images** loads the remote
content of the message you are reading, that once only. **Always show images from
this sender** adds the sender to a list, kept in Settings under Privacy &
security as **Remote images**, and every later message from that address shows
its remote content without asking. That list can be edited or emptied there at
any time.

Allowing a sender lifts the block for **every** message they send you. Every
remote image those messages contain is then fetched, tracking pixels
included: Sterna does not sort the good from the bad once loading is allowed, and
nothing in the app filters those pixels out. Allow a sender only when you accept
that they learn you opened the message.

The rest of a message's remote content stays blocked whatever you allow. Sterna
puts a content policy on every message it renders, which permits images and media
and nothing else: no scripts, no iframes, no plugins, no remote fonts, no form
submissions. JavaScript is off in the message view at all times.

## What your mail server can see

Sterna cannot make your email provider private. Whatever server you connect to
necessarily processes your messages and sees your requests — that is true of any
email client. If this matters to you, connect Sterna to a server you trust or one
you run yourself (for example, a self-hosted [Stalwart](https://stalw.art/)
instance). Choosing and trusting your provider is the one privacy decision Sterna
leaves in your hands.

## Permissions and why they are needed

Sterna's own manifest declares seven permissions, the minimum the app needs:

| Permission | Why |
|---|---|
| `INTERNET` | Reach your mail provider over the network. |
| `ACCESS_NETWORK_STATE` | Notice whether the device is online. It drives the offline state, the resync once the connection comes back, and whether a failed request is reported as being offline. Sterna reads the connection status, never what travels over it. |
| `POST_NOTIFICATIONS` | Show new-mail notifications (Android 13+). Optional — you can deny it. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the push connection alive to deliver new mail without Google's FCM. |
| `RECEIVE_BOOT_COMPLETED` | Hear that the device has finished booting, or that Sterna was updated in place, so the push connection is restored without opening the app. Granted at install; nothing is asked of you. |
| `READ_CONTACTS` | Optional and **off by default.** It is requested in two places, both on your say-so: the recipient-autocomplete switch in Settings, and a one-time offer the first time you write a message, which you can decline (it is then never offered again). Contacts are only ever read locally to suggest addresses while composing, and are never uploaded. |

The installed app carries three permissions more than that table, and the installed
list is the one F-Droid and Android's app-info screen show you. They are not written
in Sterna's manifest: the build merges the manifests of the libraries Sterna is
built from into the app's own, and these three arrive that way.

- `USE_BIOMETRIC`, from the `androidx.biometric` library, is the one Sterna
  genuinely uses. It backs the optional app lock, which asks for your fingerprint,
  face or screen PIN before Sterna opens. The lock is off until you turn it on in
  Settings, and no biometric data ever reaches the app: Android runs the check and
  answers yes or no.
- `USE_FINGERPRINT` comes from the same library. It is the pre-Android 9 form of
  that prompt, kept by the library for the Android releases that predate it.
- `WAKE_LOCK`, from the `androidx.work` library, lets a scheduled background check
  finish with the screen off. The library takes the lock for the length of the
  check and releases it.

None of the three reads anything about you. One more entry, ending in
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, is generated from Sterna's own package
name; it is signature-level, it guards Sterna's internal broadcasts, and no other
app can hold it.

Beyond all these, nothing is asked: no location, no storage outside the app's own
sandbox, no microphone, no camera.

## Changes to this policy

If this policy changes, the change will be visible in the project's Git history,
and the "Last updated" date above will be revised.

## Contact

Questions about privacy can be raised as an issue on the project repository. For
security-sensitive reports, see [SECURITY.md](SECURITY.md) when available.
