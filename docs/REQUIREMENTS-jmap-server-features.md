# Requirements: JMAP features from postmark2jmap

Status: brainstorm output, requirements only. Next: `/sc:design`, then `/sc:workflow`.
Source of server capabilities: `../postmarkapp2jmapserver` (README, DESIGN.md, `src/jmap*.rs`). Server has no codegraph index; gaps found by grep of method names and URNs against `core/` and `app/`.

## Goal
Make Sterna use the JMAP capabilities its reference server already exposes, without regressing the no-Google, privacy-first stance.

## Decisions (from discovery)
| Topic | Decision |
|---|---|
| Compat | Target generic JMAP drafts, gated by the session capability URN. Hidden on IMAP accounts and on JMAP servers that do not advertise it. |
| Calendar surface | In-app calendar only. No Android CalendarProvider, no calendar permission. |
| Calendar depth | Events and tasks, full CRUD, RSVP, participants. |
| Contacts | Add write (`ContactCard/set`, `AddressBook/*`) to the existing read sync. |
| Files | Browse, download, upload (rename/delete included). |
| Offline | Room cache synced by `/changes`; writes queue offline and replay, as mail does. |
| Reminders | Local on-device alarms from synced JSCalendar alarms, recurring included. |
| MDN | Ask per message; setting ask / always / never, default ask. |
| Also in scope | `Email/parse` (open attached .eml). |
| Out of scope | Deleted-item recovery (web UI only), `Blob/copy` forward-as-attachment, mailbox sharing UI, `x:` self-service methods, Android CalendarProvider sync, shared calendars, contact groups/photos. |

## Functional requirements
**F1 Calendars** (`urn:ietf:params:jmap:calendars`)
- Sync `Calendar`, `CalendarEvent` (JSCalendar events and tasks) with `/changes` and `/queryChanges`.
- Agenda and month views, event and task detail, create/edit/delete, participants, calendar visibility toggle.
- RSVP to an invite from the message card via `CalendarEvent/set` (server does iMIP). `CalendarEvent/parse` for invite attachments; keep the current `.ics` card as fallback when the capability is absent.
- `ParticipantIdentity` for choosing which identity replies.
- Client-side RRULE expansion (server does not expand), incl. exceptions/overrides.
- `CalendarEventNotification` is not a requirement now; reminders are local only.

**F2 Reminders**
- Schedule local alarms from JSCalendar `alarms`, recurring included; reschedule on sync, boot and time-zone change; own notification channel; respects Android exact-alarm limits.

**F3 Contacts write** (`urn:ietf:params:jmap:contacts`)
- Create, edit, delete `ContactCard`; choose `AddressBook`; changes queue offline. Existing read-only chips and sender-name matching keep working.

**F4 Files** (`urn:ietf:params:jmap:filenode`)
- Folder browse, open/download via blob, upload, rename, move, delete. Optional later: attach from Files in the composer.

**F5 MDN** (`urn:ietf:params:jmap:mdn`)
- On a message with `Disposition-Notification-To`: prompt, or follow the policy. Send via `MDN/send`; set `$mdnsent`. Compose-side "request receipt" toggle. Per-account override.

**F6 Deleted-item recovery**
- List and restore mail within the server retention window. Retention length is server config (`P2J_RECOVERY_DAYS`, default 30) and may not be discoverable; see open question 1.

**F7 Email/parse and Blob/***
- Open an attached `.eml` via `Email/parse` without downloading. Use `Blob/*` where it saves a download or upload.

## Non-functional requirements
- Capability gating: no UI, requests or permissions for an unadvertised URN. Account without it behaves exactly as today.
- No new permissions beyond notifications and exact alarms (alarms only if F2 is enabled).
- Data stays on device; calendar, contacts and file caches are included in app backup rules consistently with the existing contact cache policy; excluded from the settings backup file if they hold server data.
- Writes are idempotent and replayable; conflicts resolved by server `state` and `ifInState`, with a visible error on `stateMismatch`.
- Sync is incremental; a `cannotCalculateChanges` falls back to a full resync.
- Tested against the reference server and against spec-shaped fixtures. Follow the existing module split under `core/jmap` and `core/data`.
- Accessibility: all new screens work with TalkBack; reminders do not rely on colour.

## User stories and acceptance
1. As a user, I see my server calendar in the app and it matches the server. *Accept:* events from `CalendarEvent/query` render in agenda and month; a change on the server appears after push without a manual refresh.
2. As a user, I accept or decline an invite from the message. *Accept:* RSVP sets my participation status; the organizer receives a reply from the server; the card reflects the new state.
3. As a user, I create and edit events and tasks offline. *Accept:* edits queue, replay on reconnect, and a conflicting server change shows an error rather than silently overwriting.
4. As a user, I get an alarm for a recurring event with no network. *Accept:* the alarm fires at the right local time, including after reboot and a time-zone change.
5. As a user, I add or edit a contact and pick its address book. *Accept:* the card exists on the server and in composer suggestions.
6. As a user, I browse, download and upload files. *Accept:* folders and files match the server; upload shows progress and survives an app restart.
7. As a user, I decide whether a read receipt is sent. *Accept:* default asks; "never" sends nothing; "always" sends once and sets `$mdnsent`.
8. As a user, I restore a mistakenly deleted message. *Accept:* it returns to a chosen mailbox.
9. As a user on an IMAP or non-supporting JMAP account, nothing changes. *Accept:* no new menu entries, requests or permissions.

## Risks
- **Calendar is the largest unit** (events, tasks, recurrence, alarms, offline queue). Split into staged delivery in the design step.
- **Server not field-tested** (its README: not run against real clients). Expect server bugs surfacing as client bugs; test with fixtures too.
- **Draft instability**: JMAP Calendars, FileNode and Contacts are drafts; shapes may differ between servers. Capability gating limits the blast radius.
- Recurrence expansion and alarm scheduling on Android have many edge cases (DST, exact-alarm permission, doze).

## Design-phase corrections
- **F5 (MDN) dropped:** the server implements no `jmap:mdn`/`MDN/send`; Sterna already sends RFC 8098 receipts by mail.
- **F7 (`Email/parse`) dropped:** the server implements no `Email/parse` or `Blob/*`; only `CalendarEvent/parse` exists.
- **Recurrence** is JSCalendar `RecurrenceRule`, not RRULE text. See `DESIGN-jmap-server-features.md`.

## Resolved questions
1. **F6 dropped.** Server recovery is a web UI page only (`/ui/me/recover`, `recovery.rs`); JMAP sees an ordinary delete. Revisit if the server gains a JMAP method.
2. **F7 first flow:** open an attached `.eml` via `Email/parse`. Forward-as-attachment via `Blob/copy` deferred.
3. **F3 conflicts:** `ifInState` guard; `stateMismatch` shows an error and refetches. Fields: name, email, phone, address. No groups or photos in v1.
4. **Tasks:** an Events / Tasks toggle inside the one Calendar screen, no separate screen.
5. **F4:** composer attach-from-Files ships in the same release as browse/upload.
6. **Shared calendars excluded** (server has no calendar sharing yet).

## Open questions
None.
