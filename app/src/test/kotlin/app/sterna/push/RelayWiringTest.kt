package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Source lint over UnifiedPushManager.kt, PushController.kt and UnifiedPushStateStore.kt, read as
 *  text: it proves a line is written, never that it runs. [RelayPushTest] runs every rule that can
 *  be. Rules compare whole trimmed lines, except where they screen for what must be absent. */
class RelayWiringTest {

    // -- the promise of the branch: a relay account never speaks JMAP -----------------------------

    @Test fun `only a JMAP registration asks the mail server for a VAPID key`() {
        val body = managerBlock(ENSURE_REGISTERED)
        assertEquals(
            "the VAPID lookup must be written as '$EXPECTED_VAPID' — the whole line. pushVapidKey " +
                "is a JMAP session call: without the 'if (relay)' in front of it, an IMAP account " +
                "that asked for a relay address sends its credentials to a host that speaks IMAP, " +
                "on every single registration. This is what the branch is FOR.",
            listOf(EXPECTED_VAPID),
            body.filter { "pushVapidKey" in it },
        )
        assertEquals(
            "'relay' must be decided as '$EXPECTED_RELAY_FLAG' — the whole line. Inverting this " +
                "comparison inverts the guard above and nothing else in the file would say so.",
            listOf(EXPECTED_RELAY_FLAG),
            body.filter { it.startsWith("val relay ") },
        )
        assertEquals(
            "pushVapidKey may be named on exactly ONE line of the whole file: a second call site " +
                "is a JMAP request on a path this rule has never read.",
            1,
            managerLines().count { "pushVapidKey" in it },
        )
    }

    // -- the transport decision: only a real delivery moves an IMAP account off IDLE ----------

    @Test fun `the UnifiedPush arm of transportFor is the isActive line, whole`() {
        assertEquals(
            "the UnifiedPush arm of transportOf must be exactly '$EXPECTED_UNIFIED_PUSH_ARM' — " +
                "the whole line. This is THE line of the branch: the fact behind it is true for a " +
                "relay only once a payload really arrived ([RelayPush.onDelivery]), and widening " +
                "it by so much as an '|| up.relayEndpoint(id) != null' hands a merely PUBLISHED " +
                "account — an address exists, nobody has ever posted to it — from IMAP IDLE to a " +
                "dead endpoint, foreground service and all. [PerAccountDeliveryTest] runs these " +
                "arms; what it cannot see is which expression fills the fact, which is the rule " +
                "below. Lines found:\n" +
                transportForArms().filter { "UNIFIED_PUSH" in it }.joinToString("\n"),
            listOf(EXPECTED_UNIFIED_PUSH_ARM),
            transportForArms().filter { "UNIFIED_PUSH" in it },
        )
    }

    @Test fun `the UnifiedPush fact handed to the decision is the isActive call, whole`() {
        val lines = codeLines(CONTROLLER)
        assertEquals(
            "the decision is taken on facts read in PushController.deliveryFacts, so the arm " +
                "above only guards half of it: 'unifiedPushActive' must be filled by exactly " +
                "'$EXPECTED_UNIFIED_PUSH_FACT'. Widen it here and the arm stays word for word as " +
                "pinned while a merely published relay is handed a dead endpoint anyway.",
            listOf(EXPECTED_UNIFIED_PUSH_FACT),
            lines.filter { it.startsWith("unifiedPushActive") },
        )
        assertEquals(
            "and the account's own delivery mode is what the battery-saver arm is decided on: " +
                "'deliveryMode = container.accountStore.deliveryMode(credentials.id),'. Read the " +
                "global setting here instead and the per-account choice (#190) is dead, silently.",
            listOf("deliveryMode = container.accountStore.deliveryMode(credentials.id),"),
            lines.filter { it.startsWith("deliveryMode =") },
        )
        assertEquals(
            "the isLinked fact must be filled by exactly '$EXPECTED_IS_LINKED_FACT'. This is the " +
                "expression that closes #31: a linked sub-account is ACL-shared, the server never " +
                "puts its StateChanges on the login's EventSource, and the arm above turns that " +
                "fact into PERIODIC. Fill it with false — or with anything that stops asking the " +
                "STORE, credentials carry no such flag — and the arm stays pinned word for word " +
                "while every shared sub-account is handed a connection of its own that will " +
                "never deliver it a single message: mail arrives nowhere, and the screen reads " +
                "'direct connection'.",
            listOf(EXPECTED_IS_LINKED_FACT),
            lines.filter { it.startsWith("isLinked =") },
        )
        assertEquals(
            "and the protocol fact must be exactly '$EXPECTED_PROTOCOL_FACT': the credentials " +
                "this decision was asked about, not a constant and not another account's. It " +
                "chooses between the last two arms, so a hard MailProtocol.JMAP here opens an " +
                "EventSource against an IMAP server, and a hard IMAP puts a JMAP account on IDLE " +
                "against a host that does not speak it — in both cases the pinned arms are " +
                "untouched and no other test looks.",
            listOf(EXPECTED_PROTOCOL_FACT),
            lines.filter { it.startsWith("protocol =") },
        )
    }

    @Test fun `the battery-saver arm is decided on the account, seeded by the global setting`() {
        assertEquals(
            "the 'batterySaver' the arms read must be computed as " +
                "'$EXPECTED_BATTERY_SAVER_FACT'. A bare 'account.deliveryMode == " +
                "DeliveryMode.BATTERY_SAVER' answers false for every account that never chose, " +
                "which is every account on the update: a whole install of battery-saver users " +
                "wakes up holding live connections. Reading the global setting alone is the " +
                "defect this issue removes. Lines found:\n" +
                transportForBody().joinToString("\n"),
            listOf(EXPECTED_BATTERY_SAVER_FACT),
            transportForBody().filter { "resolveDeliveryMode" in it },
        )
    }

    /** The negative half, `contains`, deliberately: it screens for what must be absent. */
    @Test fun `transportFor reads no address and no published state`() {
        val offenders = transportForBody().filter { line ->
            FORBIDDEN_IN_TRANSPORT.any { it in line }
        }
        assertEquals(
            "nothing inside transportOf may name any of " +
                FORBIDDEN_IN_TRANSPORT.joinToString(", ") + ". An address, a PUBLISHED state or " +
                "the mere fact that a relay was requested are all 'somebody might post here one " +
                "day'; the transport may only move on what already happened. Found:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the arms of transportFor are pinned whole and in order`() {
        assertEquals(
            "transportOf decides in one 'when', and its arms are pinned whole and in order. " +
                "isLinked first: a shared sub-account has no push of its own (#31) and would " +
                "otherwise inherit its login's UnifiedPush. batterySaver AFTER the UnifiedPush " +
                "arm and not before it: UnifiedPush costs Sterna nothing, so battery saver must " +
                "not knock an armed account down to a 30-minute poll. Reorder these and no other " +
                "test in the repo says a word.",
            EXPECTED_TRANSPORT_ARMS,
            transportForArms(),
        )
    }

    // -- the monitor: every state write that is not inside a coroutine is under it ----------------

    @Test fun `ensureRegistered still carries its monitor`() {
        val lines = managerLines()
        val at = lines.indexOfFirst { it == ENSURE_REGISTERED }
        check(at > 0) { "no '$ENSURE_REGISTERED' in UnifiedPushManager.kt" }
        assertEquals(
            "ensureRegistered must be annotated @Synchronized, on the line directly above its " +
                "signature. It IS the monitor every other rule in this file refers to: apply() " +
                "calls it from several places in quick succession (account switch, transport " +
                "callbacks), and without the annotation the load/save race registers the same " +
                "account many times over. Every 'synchronized(this)' below is synchronized with " +
                "THIS — take the annotation off and they all still compile, still lock, and " +
                "protect nothing from the caller they exist for.",
            "@Synchronized",
            lines[at - 1],
        )
    }

    @Test fun `no state write happens outside the monitor`() {
        val offenders = MONITORED.associateWith { signature ->
            outsideTheMonitor(managerBlock(signature)).filter { "store.save(" in it }
        }.filterValues { it.isNotEmpty() }
        assertEquals(
            "these functions read, decide and write the transport state from threads that are " +
                "not the caller's — the connector's binder thread (onMessage → recordDelivery, " +
                "onUnregistered), a failed coroutine (markFailed), the controller " +
                "(reconcileDistributorPresence) — while the settings screen and the endpoint " +
                "coroutine write under the monitor. Their whole read-decide-write must sit under " +
                "'synchronized(this)' too. recordDelivery is the one that hurts: it is the ONLY " +
                "write that arms the transport, and a rotation persisting {new address, " +
                "PUBLISHED, never woken} under the lock, interleaved with a delivery that read " +
                "'prev' before it, ends as {OLD address, ACTIVE, woken just now} — IMAP IDLE " +
                "dropped, a dead address on screen, and 'last wake: just now' under it. ⛔ The " +
                "lock covers state ONLY: no network call, no UnifiedPush.register/unregister and " +
                "no scope.launch may sit inside it. A write that FOLLOWS a server call is not " +
                "excused by that rule: it must re-read the state inside the monitor and merge " +
                "only the fields the call owns, which is what renewIfNeeded does and what the " +
                "rule below pins. ONE write after a server call is still outside this list — " +
                "onMessage's Verification arm, which saves a getOrCreate().copy(status = ACTIVE) " +
                "from inside a scope.launch — and it is not excused: it has not been converted " +
                "yet, that is all. Nor is this list an " +
                "inventory of the file's writes: it only ever looks for 'store.save(', so the " +
                "store.clear of teardown and unregisterOrphan is invisible to it. Writes found " +
                "outside the monitor:\n" + offenders.entries.joinToString("\n"),
            emptyMap<String, List<String>>(),
            offenders,
        )
    }

    @Test fun `the renewal merges onto the state it reads back, never onto its own snapshot`() {
        val body = managerBlock(RENEW_IF_NEEDED)
        val loads = body.filter { "store.load(" in it }
        assertEquals(
            "renewIfNeeded must read the state three times, on exactly these lines and in this " +
                "order: the snapshot that decides whether a renewal is due at all, then one " +
                "re-read on each arm of the round-trip. Lines found:\n" + loads.joinToString("\n"),
            EXPECTED_RENEW_LOADS,
            loads,
        )
        assertEquals(
            "the success arm must be these five lines, CONSECUTIVE and in this order: re-read, " +
                "decide, write, all inside one 'synchronized(this)'. Two different breakages " +
                "live here and nothing else in the repo sees either. Hand 'state' to the " +
                "decision instead of 'current' and the merge is built on a value older than a " +
                "round-trip to the mail server, so a FAILED stamped meanwhile by onUnregistered " +
                "or reconcileDistributorPresence is served back as ACTIVE. This path runs for " +
                "JMAP accounts only — it leaves at a null subscriptionId — so no relay address " +
                "is involved and the damage is the transport: isActive answers true, transportOf " +
                "picks UNIFIED_PUSH ahead of EVENT_SOURCE, the account never reopens its direct " +
                "connection and loses real time, while the status line goes on naming a " +
                "distributor that is gone. Mail itself still arrives, MailFetchWorker polling " +
                "every isActive account for exactly this reason. The second breakage is to split " +
                "these lines across two monitors, deciding in the first and writing in the " +
                "second: every per-line rule in this file then stays green — the lines all " +
                "exist, both saves sit under a monitor — while the lock is released between " +
                "deciding and writing, which is the race this whole rule exists to close. " +
                "Slice found:\n" + slice(body, EXPECTED_RENEW_SUCCESS_BLOCK).joinToString("\n"),
            EXPECTED_RENEW_SUCCESS_BLOCK,
            slice(body, EXPECTED_RENEW_SUCCESS_BLOCK),
        )
        assertEquals(
            "and the failure arm, the same five-line shape minus the returned value: re-read, " +
                "decide, write, consecutive, inside one monitor. Its decision refusing to write " +
                "is what leaves a fresher FAILED standing, so a lock released before the save " +
                "gives that FAILED away again. Slice found:\n" +
                slice(body, EXPECTED_RENEW_FAILURE_BLOCK).joinToString("\n"),
            EXPECTED_RENEW_FAILURE_BLOCK,
            slice(body, EXPECTED_RENEW_FAILURE_BLOCK),
        )
        assertEquals(
            "renewIfNeeded may open exactly two monitors, one per arm of the round-trip. A " +
                "third one is the split described above, written another way.",
            2,
            body.count { "synchronized(this" in it },
        )
        val expiry = body.filter { "renewedUntil" in it }
        assertEquals(
            "the expiry written back must be the date the SERVER granted, and its fallback must " +
                "be a fresh one — never the record's own. Fall back to 'state.expiresAtMillis' " +
                "and the state is saved with an expiry already inside RENEW_MARGIN_MS, so the " +
                "gate at the top of this function is due again on the very next worker cycle, " +
                "for that account, for ever: one renewal request per cycle per account, " +
                "indefinitely, with every other rule in this file green. Lines found:\n" +
                expiry.joinToString("\n"),
            EXPECTED_RENEW_EXPIRY,
            expiry,
        )
    }

    // -- the registration gates, in order ---------------------------------------------------------

    @Test fun `the gates of ensureRegistered are in this order`() {
        val expected = listOf(
            "if (accountStore.account(credentials.id)?.isLinked == true) return",
            "val known = store.load(credentials.id)",
            "if (!RelayPush.mayUsePush(credentials.protocol, known?.relayRequested ?: false)) return",
            "if (!ensureDistributor()) return",
            "if (!RelayPush.shouldRegister(",
        )
        assertEquals(
            "ensureRegistered must open with exactly these lines, in this order. Two rules meet " +
                "here and both are load-bearing: an account that may not use push AT ALL leaves " +
                "before ensureDistributor(), whose picker is the one piece of UI in this subsystem " +
                "(an IMAP account nobody asked a relay for must never raise it, and must leave no " +
                "prefs record either — hence store.load, not getOrCreate); and a JMAP account goes " +
                "THROUGH ensureDistributor() whatever the registration window then says, because " +
                "that call is what raises the picker at all. Put shouldRegister ahead of it and a " +
                "JMAP account marked FAILED the moment its distributor was uninstalled is asked " +
                "about the replacement no sooner than fifteen minutes later.",
            expected,
            managerBlock(ENSURE_REGISTERED).take(expected.size),
        )
    }

    @Test fun `the rearm is declared once and called from exactly one front`() {
        val found = managerLines().filter { "rearmFailedAccounts" in it }
        assertEquals(
            "rearmFailedAccounts may appear on exactly two lines of the file, in this order: the " +
                "call in distributorChosen, and its declaration. That call site is the only FRONT " +
                "there is — a distributor picked by hand, which no loop replays. " +
                "ensureDistributor looks like a second one and is not: the " +
                "connector deletes its saved distributor when the last registration goes " +
                "(UnifiedPush.unregister empties the table), so getAckDistributor and " +
                "getSavedDistributor answer null again with the distributor still installed, and " +
                "that function is replayed on later passes. Rearming there clears the FAILED stamp of " +
                "every account, including the ones the MAIL SERVER failed, which is the " +
                "fifteen-minute cooldown deleted; docs/PUSH-V2.md records what that cooldown " +
                "stopped, a register+create loop hammering the mail server at tens of Hz. Lines " +
                "found:\n" + found.joinToString("\n"),
            listOf(
                "rearmFailedAccounts()",
                "private fun rearmFailedAccounts() {",
            ),
            found,
        )
    }

    @Test fun `choosing a distributor rearms before the transport is re-evaluated`() {
        assertEquals(
            "distributorChosen is pinned whole and in order. The rearm must sit BEFORE " +
                "onTransportStateChanged: that callback is what runs apply(), and apply() reaches " +
                "the state through RelayPush.shouldRegister. Rearm after it and the accounts are " +
                "cleared just too late to be seen, so the user picks a distributor from the dialog " +
                "and the screen still reads 'direct connection' for fifteen minutes — measured on " +
                "the s7 with two accounts and two distributors reinstalled.",
            listOf(
                SAVE_DISTRIBUTOR,
                "_needsDistributorChoice.value = false",
                "rearmFailedAccounts()",
                "onTransportStateChanged?.invoke()",
            ),
            managerBlock(DISTRIBUTOR_CHOSEN),
        )
    }

    @Test fun `the rearm sweeps every account and writes each one back`() {
        assertEquals(
            "the body of rearmFailedAccounts is pinned whole, line by line and in order, because " +
                "nothing in this module can execute it (it needs a Context, an AccountStore and " +
                "the connector's statics) and two one-word edits are otherwise invisible to the " +
                "whole suite. Narrow the sweep — credentials.take(1).forEach — and push comes " +
                "back on ONE account while the others keep reading 'direct connection', which is " +
                "the reported symptom itself. Drop the '?.let { store.save(account.id, it) }' and " +
                "the fix becomes a complete no-op: the decision is computed and thrown away, the " +
                "three call lines still read right, and the monitor rule stays green precisely " +
                "because it only hunts store.save calls OUTSIDE synchronized — zero of them " +
                "satisfies it.",
            listOf(
                "val credentials = accountStore.allCredentials()",
                "synchronized(this) {",
                "credentials.forEach { account ->",
                "store.load(account.id)",
                "?.let { RelayPush.onDistributorChosen(it, System.currentTimeMillis()) }",
                "?.let { store.save(account.id, it) }",
                "}",
                "}",
            ),
            managerBlock(REARM),
        )
    }

    @Test fun `the distributor gate asks, enrols nobody and rearms nothing`() {
        val expected = listOf(
            "val gate = RelayPush.distributorGate(",
            "chosen = UnifiedPush.getAckDistributor(context) != null ||",
            "UnifiedPush.getSavedDistributor(context) != null,",
            "installed = UnifiedPush.getDistributors(context).size,",
            "declined = store.distributorDeclined(),",
            ")",
            "if (gate == DistributorGate.ASK) _needsDistributorChoice.value = true",
            "return gate == DistributorGate.READY",
        )
        val body = managerBlock(ENSURE_DISTRIBUTOR)
        assertEquals(
            "ensureDistributor is pinned whole: it asks RelayPush.distributorGate (the executed " +
                "rule, [RelayPushTest]) and does nothing else. The three arguments are all " +
                "load-bearing. 'chosen' must read BOTH connector lookups, or an acknowledged " +
                "distributor is asked about again on every pass. 'installed' is a count handed " +
                "to a pure function, which is what stops the count from being turned back into a " +
                "decision here. 'declined' is read on this path or nowhere: this function runs " +
                "on every apply(), so an unread refusal is a dialog for ever.",
            expected,
            body,
        )
        assertEquals(
            "and this function may name neither saveDistributor nor rearmFailedAccounts. " +
                "saveDistributor: enrolling the one installed distributor is exactly the " +
                "reported harm (#204), a subscription created on a server the owner never chose " +
                "and never heard named. rearmFailedAccounts: this is not a front, it is replayed. " +
                "The connector empties its distributors table on the last unregister, so a " +
                "teardown or a dropRelayAddress sends this function back here with the " +
                "distributor still installed; a rearm on this path would clear the FAILED of " +
                "accounts the MAIL SERVER refused, reopening their cooldown, and a distributor " +
                "answering UNREGISTERED to REGISTER would spin for ever, one repo.pushVapidKey " +
                "call per turn. docs/PUSH-V2.md records what that cooldown stopped. Found:\n" +
                body.joinToString("\n"),
            emptyList<String>(),
            body.filter { "saveDistributor" in it || "rearmFailedAccounts" in it },
        )
    }

    @Test fun `the nominal reconciliation rearms nothing`() {
        val offenders = managerBlock(RECONCILE).filter { "rearmFailedAccounts" in it }
        assertEquals(
            "reconcileDistributorPresence must never rearm. It is the nominal path: the " +
                "controller and MailFetchWorker call it on every apply and on every cycle, and " +
                "its early return is taken whenever the saved distributor is present. A rearm on " +
                "either side of that return runs for ever, which turns FAILED into a state no " +
                "account can stay in and puts the register+create loop back on the mail server. " +
                "Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `refusing the picker is written down, closing it by hand is not`() {
        assertEquals(
            "rememberDistributorRefusal is pinned whole and in order. The write is the whole " +
                "reason the picker may now be raised for a single installed distributor at all: " +
                "ensureDistributor runs on every apply(), so a refusal kept in memory only comes " +
                "back as a dialog on the next screen resume, for ever. It is stored device-wide, " +
                "the distributor being a choice about the device; and it is not a dead end, " +
                "switching an account's notifications back on clears it " +
                "(NotificationSwitchAsksAgainWiringTest), as does asking for a relay address.",
            listOf(
                "store.setDistributorDeclined(true)",
                "_needsDistributorChoice.value = false",
            ),
            managerBlock(REMEMBER_REFUSAL),
        )
        assertEquals(
            "hideDistributorChoiceForNow is the back gesture and the tap outside, and it must be " +
                "this one line: no write. A gesture that leaves the dialog is not an answer to " +
                "it, and writing the refusal there shuts UnifiedPush down device-wide until " +
                "somebody guesses at switching an account's sync off and back on. Nothing loops " +
                "on it either, ensureDistributor being reached only from an arming pass.",
            listOf("_needsDistributorChoice.value = false"),
            managerBlock(HIDE_CHOICE),
        )
        assertEquals(
            "and the refusal may be written true on exactly one line of the whole file: a second " +
                "writer is another path closing UnifiedPush without anybody having answered. " +
                "Lines found:\n" + managerLines().filter { "setDistributorDeclined(true)" in it }
                .joinToString("\n"),
            listOf("store.setDistributorDeclined(true)"),
            managerLines().filter { "setDistributorDeclined(true)" in it },
        )
        assertEquals(
            "and its opposite, allowDistributorChoice, is the whole way back: it writes false and " +
                "nothing else. Written true here, or left with a body that decides for itself, " +
                "the switch tapped back on confirms the refusal instead of lifting it and the " +
                "picker is gone for good on that device, with nothing on screen saying so.",
            listOf("store.setDistributorDeclined(false)"),
            managerBlock(ALLOW_CHOICE),
        )
    }

    @Test fun `enrolling on a distributor is written on one line of the whole file`() {
        val found = managerLines().filter { "saveDistributor" in it }
        assertEquals(
            "UnifiedPush.saveDistributor may be named on exactly one line of the whole file, the " +
                "one inside distributorChosen: saving a distributor is the enrolment itself, and " +
                "the picker is the only thing allowed to perform it (#204). Reading the body of " +
                "ensureDistributor alone left the promise open everywhere else: the same two " +
                "lines dropped into reconcileDistributorPresence, which PushController.apply " +
                "runs on every pass and MailFetchWorker on every cycle, enrol the single " +
                "installed distributor with nobody asked; the gate then reads chosen = true for " +
                "ever, the picker is never raised again, and every other rule here stays green. " +
                "Lines found:\n" + found.joinToString("\n"),
            listOf(SAVE_DISTRIBUTOR),
            found,
        )
        assertEquals(
            "and that line must be the one inside distributorChosen, not merely somewhere in the " +
                "file: an enrolment on a replayed path is the reported harm whatever it is " +
                "spelled like.",
            found,
            managerBlock(DISTRIBUTOR_CHOSEN).filter { "saveDistributor" in it },
        )
    }

    // -- the endpoint path stops at the relay ------------------------------------------------------

    @Test fun `an arriving endpoint is read, decided and written under the registration lock`() {
        val expected = listOf(
            "val (prev, outcome) = synchronized(this@UnifiedPushManager) {",
            "val current = store.getOrCreate(accountId)",
            "val decided = RelayPush.endpointOutcome(",
            "credentials.protocol, current, endpoint.url, System.currentTimeMillis(),",
            ")",
            "if (decided is EndpointOutcome.Relay) store.save(accountId, decided.state)",
            "current to decided",
            "}",
        )
        val body = managerBlock(ON_NEW_ENDPOINT)
        val at = body.indexOfFirst { it.startsWith("val (prev, outcome)") }
        assertEquals(
            "the Relay branch must read, decide and write under the SAME monitor as " +
                "ensureRegistered, in one block, compared whole. This coroutine runs on " +
                "Dispatchers.IO while the settings screen can be writing: read the state outside " +
                "the lock and a withdrawal that lands in between is overwritten by a state built " +
                "on a 'prev' from before it — the address the user just removed comes back, " +
                "published and marked as requested.",
            expected,
            if (at < 0) body.take(expected.size) else body.subList(at, minOf(at + expected.size, body.size)),
        )
    }

    @Test fun `the relay branch persists its own state and stops there`() {
        val expected = listOf(
            "is EndpointOutcome.Relay -> {",
            "Log.i(TAG, \"Relay address published for \$accountId\")",
            "onTransportStateChanged?.invoke()",
            "return@launch",
            "}",
        )
        val body = managerBlock(ON_NEW_ENDPOINT)
        val at = body.indexOfFirst { it == expected.first() }
        assertEquals(
            "the Relay branch of onNewEndpoint is pinned WHOLE, line by line and in order. The " +
                "'return@launch' is the load-bearing one: without it an IMAP account falls " +
                "straight through into destroyPushSubscription/createPushSubscription against a " +
                "host that speaks IMAP. Nothing in this module executes these lines.",
            expected,
            if (at < 0) body.take(expected.size) else body.subList(at, minOf(at + expected.size, body.size)),
        )
    }

    @Test fun `a subscription that never verified is given up on before anything is destroyed`() {
        val expected = listOf(
            "if (RelayPush.createBudgetSpent(prev, endpoint.url)) {",
            "Log.w(TAG, \"PushSubscription never verified after \${prev.createAttempts} creations for \$accountId\")",
            "markFailed(accountId)",
            "return@launch",
            "}",
        )
        val body = managerBlock(ON_NEW_ENDPOINT)
        assertEquals(
            "the give-up check of onNewEndpoint is pinned WHOLE, line by line: nothing in this " +
                "module can execute it (UnifiedPushManager needs a Context, an AccountStore and " +
                "the connector's statics), and the surviving lines read right on their own. " +
                "RelayPush.createBudgetSpent is the executed rule ([RelayPushTest]); drop the " +
                "'return@launch' and the account is marked FAILED and then subscribes anyway, " +
                "which is the hammering this guard exists to stop.",
            expected,
            slice(body, expected),
        )
        val giveUp = body.indexOfFirst { it == expected.first() }
        val destroy = body.indexOfFirst { "destroyPushSubscription" in it }
        val create = body.indexOfFirst { "repo.createPushSubscription(" in it }
        assertEquals(
            "the give-up must come BEFORE the destroy and before the create. Giving up while " +
                "destroying throws away the one path still open: a late PushVerification arriving " +
                "on the subscription that is still alive server-side takes the account to ACTIVE " +
                "by itself. Indices in onNewEndpoint — give-up $giveUp, destroy $destroy, " +
                "create $create.",
            listOf(true, true),
            listOf(giveUp in 0 until destroy, giveUp in 0 until create),
        )
    }

    @Test fun `a created subscription counts itself against the endpoint it was created for`() {
        val body = managerBlock(ON_NEW_ENDPOINT)
        assertEquals(
            "the merge must be handed exactly '$EXPECTED_CREATE_ARGS' — the whole line, in " +
                "this order. The counter itself moved into RelayPush.onSubscriptionCreated, " +
                "where [RelayPushTest] executes it; what no test can execute is which values " +
                "this file hands it. 'endpoint.url' is the address the subscription was just " +
                "created for, and the count is attributed to it so a rotated address starts its " +
                "own — hand 'prev.endpoint' and a new distributor's address inherits a spent " +
                "budget, so push never comes back at all. 'current, prev' in this order is the " +
                "whole point of the merge: swap them and it is built on a snapshot older than " +
                "two round-trips again, with every line of this file still reading right. Lines " +
                "found:\n" + body.filter { it.startsWith("current, prev") }.joinToString("\n"),
            listOf(EXPECTED_CREATE_ARGS),
            body.filter { it.startsWith("current, prev") },
        )
        assertEquals(
            "and the expiry handed over must be the date the SERVER granted, its fallback a " +
                "fresh one: '$EXPECTED_CREATE_EXPIRY'. A record saved with an expiry already " +
                "gone is renewed on every worker cycle, for that account, for ever.",
            listOf(EXPECTED_CREATE_EXPIRY),
            body.filter { it.startsWith("val createdUntil") },
        )
    }

    @Test fun `a created subscription is re-read, decided and written under the registration lock`() {
        val body = managerBlock(ON_NEW_ENDPOINT)
        assertEquals(
            "the write that follows a successful create must be these seven lines, CONSECUTIVE " +
                "and in this order: re-read, decide, write, all inside one 'synchronized'. The " +
                "re-read is store.load and not store.getOrCreate, which would recreate the " +
                "record of an account disconnected during the flight — subscribed, reading " +
                "VERIFYING, with the server pushing to it for seven days. Splitting these lines " +
                "across two monitors leaves every per-line rule in this file green while the " +
                "lock is released between deciding and writing, which is the race this block " +
                "exists to close. Slice found:\n" +
                slice(body, EXPECTED_CREATE_BLOCK).joinToString("\n"),
            EXPECTED_CREATE_BLOCK,
            slice(body, EXPECTED_CREATE_BLOCK),
        )
        assertEquals(
            "onNewEndpoint may open exactly two monitors: the one that decides what an arriving " +
                "endpoint means, and the one that merges the creation back. A third is that " +
                "split, written another way.",
            2,
            body.count { "synchronized(this" in it },
        )
    }

    @Test fun `a subscription the record refused is handed back to the server`() {
        val body = managerBlock(ON_NEW_ENDPOINT)
        assertEquals(
            "when the merge refuses, the subscription just created belongs to nobody: these six " +
                "lines are pinned whole and in order. Drop the destroy and the fix trades 'the " +
                "app believes in a subscription that is gone' for 'the server pushes to nobody " +
                "for seven days', the worse half of the same defect — while every other rule " +
                "here stays green, the refusal to write being all they look at. It is best " +
                "effort (runCatching) and it sits OUTSIDE the monitor, the lock covering state " +
                "and never a call out. Slice found:\n" +
                slice(body, EXPECTED_CREATE_REFUSED).joinToString("\n"),
            EXPECTED_CREATE_REFUSED,
            slice(body, EXPECTED_CREATE_REFUSED),
        )
    }

    @Test fun `a verification hands the budget back`() {
        val expected = listOf(
            "store.save(",
            "accountId,",
            "store.getOrCreate(accountId).copy(",
            "status = UpStatus.ACTIVE,",
            "statusSinceMillis = System.currentTimeMillis(),",
            "createAttempts = 0,",
            "),",
            ")",
        )
        assertEquals(
            "the write that enters ACTIVE is pinned whole, for the 'createAttempts = 0'. It is " +
                "the only reset there is: without it a subscription renewed months later carries " +
                "the creations of the one before it and is given up on early.",
            expected,
            slice(managerBlock(VERIFICATION_ARM), expected),
        )
    }

    @Test fun `giving up on an account hands no create budget back`() {
        assertEquals(
            "the body of markFailed is pinned whole, line by line. Add 'createAttempts = 0' to " +
                "that copy and the budget is handed back on every single failure: the account is " +
                "marked FAILED, the 15-minute cooldown lets RelayPush.shouldRegister through, the " +
                "counter is at zero again, and three more creations go out — for ever, which is " +
                "the hammering the give-up was added to stop. The only rule that reads markFailed " +
                "today is 'no state write happens outside the monitor', and that one asks WHERE " +
                "the store.save sits, never what is handed to it.",
            listOf(
                "synchronized(this) {",
                "store.load(accountId)?.let {",
                "store.save(accountId, it.copy(status = UpStatus.FAILED, " +
                    "statusSinceMillis = System.currentTimeMillis()))",
                "}",
                "}",
                "onTransportStateChanged?.invoke()",
            ),
            managerBlock(MARK_FAILED),
        )
    }

    @Test fun `the create counter is saved and loaded under the same key`() {
        val loaded = storeLines().filter { it.startsWith("createAttempts = prefs.") }
        val saved = storeLines().filter { ".putInt(" in it }
        assertEquals(
            "the createAttempts line of UnifiedPushStateStore.load is pinned whole. Lines found:\n" +
                loaded.joinToString("\n"),
            listOf(EXPECTED_CREATES_LOAD),
            loaded,
        )
        assertEquals(
            "and the one of save, whole. Delete this line and the builder chain still compiles, " +
                "load still answers 0, RelayPush.createBudgetSpent is never true again and the " +
                "give-up is entirely dead — the account recreates a subscription every cycle for " +
                "ever, which is the reported symptom made worse. Spelling the key differently on " +
                "the two sides has exactly the same effect and is quieter still, so both lines " +
                "are pinned and both must carry '.creates'. Nothing in this module can execute " +
                "the round trip: the store needs a Context, and there is neither Robolectric nor " +
                "an androidTest source set here. Lines found:\n" + saved.joinToString("\n"),
            listOf(EXPECTED_CREATES_SAVE),
            saved,
        )
    }

    @Test fun `the status stamp is saved and loaded under the same key`() {
        val lines = storeLines().filter { "\$accountId.since" in it }
        assertEquals(
            "the two lines that carry statusSinceMillis are pinned whole: the read and the " +
                "write, under one key, and nothing else in the store may name it. A one-letter " +
                "difference between them is the quietest breakage in this file: the record still " +
                "saves, load answers 0 for that field for ever, and everything that reads it " +
                "reads a state permanently stamped at the epoch. RelayPush.shouldRegister then " +
                "grants every FAILED account at once, its fifteen-minute cooldown deleted, and " +
                "every REGISTERING or VERIFYING record is stale on sight, so a healthy bring-up " +
                "is recreated under its own pending verification. Worse for this branch: " +
                "RelayPush.onSubscriptionCreated refuses to write when the stamp moved during " +
                "the call, which is how a fresher writer is recognised at all — two zeroes " +
                "always compare equal, so that refusal is dead and the creation overwrites the " +
                "writer it was meant to stand down for, which is the defect this whole branch " +
                "closes. Nothing in this module can execute the round trip: the store needs a " +
                "Context, and there is neither Robolectric nor an androidTest source set here. " +
                "Lines found:\n" + lines.joinToString("\n"),
            listOf(EXPECTED_SINCE_LOAD, EXPECTED_SINCE_SAVE),
            lines,
        )
    }

    @Test fun `the pending window is read from RelayPush and nowhere copied`() {
        val expected = listOf(
            "val state = store.load(accountId) ?: return false",
            "return (state.status == UpStatus.REGISTERING || state.status == UpStatus.VERIFYING) &&",
            "System.currentTimeMillis() - state.statusSinceMillis < RelayPush.pendingGraceMs(state.status)",
        )
        assertEquals(
            "isPending is pinned whole. It answers the same question as RelayPush.shouldRegister " +
                "— is this registration still in flight — and it must read the window from " +
                "RelayPush.pendingGraceMs, not from a constant of its own. Two copies of one " +
                "duration drift, and the drift is invisible: the transport keeps the direct " +
                "connection up for one window while the watchdog re-registers on another.",
            expected,
            managerBlock(IS_PENDING),
        )
    }

    // -- an address anyone may post to is not an authenticated channel ------------------------------

    @Test fun `a verification payload is answered only by an account that speaks JMAP`() {
        val expected = listOf(
            "val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)",
            "if (credentials.protocol != MailProtocol.JMAP) {",
            "Log.w(TAG, \"Push verification on a relay account — ignored: \$accountId\")",
            "return",
            "}",
        )
        assertEquals(
            "the Verification arm must open with exactly these lines, in this order. The relay " +
                "address is a topic ANYONE may write to: without this guard, a stranger who knows " +
                "the address posts {\"@type\":\"PushVerification\"} and Sterna hands the account's " +
                "credentials to jmapAuth against an IMAP host — the failure then calls markFailed, " +
                "so that stranger can switch the relay off for fifteen minutes, silently, as often " +
                "as they like; and on an OAuth account it burns a real token refresh first. ⛔ The " +
                "question is the PROTOCOL, not the stored subscriptionId: on a first subscription " +
                "that id is written only after createPushSubscription returns, so a server that " +
                "verifies promptly (Stalwart times the round trip out in about a minute) could be " +
                "answered by nobody and the account would sit in VERIFYING until it went stale. " +
                "The guard is here to keep a RELAY account out, and a relay account has no " +
                "protocol race to lose. The log line must not carry the payload.",
            expected,
            managerBlock(VERIFICATION_ARM).take(expected.size),
        )
    }

    @Test fun `nothing in the verification arm records a delivery`() {
        val offenders = managerBlock(VERIFICATION_ARM).filter { "recordDelivery" in it }
        assertEquals(
            "the Verification arm must never call recordDelivery: a JMAP account's ACTIVE is its " +
                "server's PushVerification, and a payload that merely arrived must not counterfeit " +
                "it. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    // -- only the two arms that carry mail stamp a delivery ------------------------------------------

    @Test fun `only the two arms that carry mail record a delivery`() {
        val body = managerBlock(ON_MESSAGE)
        assertEquals(
            "onMessage must call recordDelivery on exactly two lines, and both written as " +
                "'recordDelivery(accountId)'. It is the ONLY thing that arms a relay: a third " +
                "call site arms it on something that is not a delivery, and a missing one leaves " +
                "an account that really is being woken reading 'checked every 30 minutes' for " +
                "ever. Lines found:\n" + body.filter { "recordDelivery" in it }.joinToString("\n"),
            listOf("recordDelivery(accountId)", "recordDelivery(accountId)"),
            body.filter { "recordDelivery" in it },
        )
    }

    @Test fun `the change arm and the unparsed arm are pinned whole`() {
        val change = listOf(
            "is PushMessagePayload.Change -> {",
            "recordDelivery(accountId)",
            "enqueueForLogin(accountId)",
            "}",
        )
        val unparsed = listOf(
            "null -> {",
            "Log.i(TAG, \"Unparsed push payload (\${message.content.size}B, decrypted=\${message.decrypted})\")",
            "recordDelivery(accountId)",
            "enqueueForLogin(accountId)",
            "}",
        )
        val body = managerBlock(ON_MESSAGE)
        assertEquals(
            "the mail-carrying arm of onMessage is pinned whole: the delivery is stamped BEFORE " +
                "the fetch is fanned out, and nothing else happens in it. The stamp is " +
                "unconditional on purpose, a switched-off account included: a POST on a public " +
                "relay address had already woken the process before a line of Sterna ran, and " +
                "recordDelivery is the only thing that carries a relay from PUBLISHED to ACTIVE " +
                "(RelayPush.onDelivery holds that rule), so behind a sync gate the account that " +
                "comes back on would advertise an address nothing has ever confirmed. WHICH " +
                "accounts the wake reaches is not decided here but inside enqueueForLogin, one " +
                "by one: the switch is per account, and the sub-accounts riding this same " +
                "subscription (#31) each carry their own. Read:\n" +
                slice(body, change).joinToString("\n"),
            change,
            slice(body, change),
        )
        assertEquals(
            "the unparsed arm is pinned whole too: an undecryptable payload is still a wake, so " +
                "it is stamped and fanned out exactly like a change, and its log line carries " +
                "the SIZE and the decrypted flag only — never the payload. Read:\n" +
                slice(body, unparsed).joinToString("\n"),
            unparsed,
            slice(body, unparsed),
        )
    }

    @Test fun `the body of enqueueForLogin is pinned whole, fan-out then filter then schedule`() {
        val body = managerBlock(ENQUEUE_FOR_LOGIN)
        assertEquals(
            "enqueueForLogin holds the whole decision of which accounts a push wake puts work " +
                "on, so its body is pinned line by line and in order, each line written whole. " +
                "The first line is the fan-out of ONE subscription, the login plus the " +
                "sub-accounts linked to it (#31), and it must read linkedAccounts(loginId): " +
                "allCredentials() looks just as sensible and schedules a fetch for every account " +
                "on the device on every message. The second drops the accounts whose sync is " +
                "off, and it filters account by account rather than gating the call: the login " +
                "and its sub-accounts disagree in both directions, a login left on carrying " +
                "sub-accounts switched off (the defect this closes), and a login switched off " +
                "keeping its subscription alive for a sub-account left on (tearDownAfterSyncOff " +
                "refuses to hand it back), whose mail nothing else would fetch. The third is the " +
                "scheduling itself. Lines found:\n" + body.joinToString("\n"),
            listOf(
                "(listOf(loginId) + accountStore.linkedAccounts(loginId).map { it.id }).distinct()",
                ".filterNot { syncSwitchedOff(it) }",
                ".forEach { PushFetchWorker.enqueue(context, it) }",
            ),
            body,
        )
        assertEquals(
            "and PushFetchWorker may be named on exactly this one line of THIS file, written " +
                "whole: other schedulers exist (PushController enqueues the same worker), but " +
                "inside the manager every wake must reach the worker through the filtered " +
                "fan-out above, never beside it. Lines found:\n" +
                managerLines().filter { "PushFetchWorker" in it }.joinToString("\n"),
            listOf(".forEach { PushFetchWorker.enqueue(context, it) }"),
            managerLines().filter { "PushFetchWorker" in it },
        )
    }

    // -- the switch going off reaches both sides -------------------------------------------------

    @Test fun `apply carries both halves of the notifications switch, side by side`() {
        val body = applyBody()
        assertEquals(
            "PushController.apply must open with exactly these lines, in this order. Two sets, " +
                "two bases, and the arguments say which is which: the arm runs on 'watched' (the " +
                "current account alone until push-all is on) and the sweep is handed 'all', every " +
                "stored account. Handing the sweep 'watched' back is the defect this pin exists " +
                "for: the complement of a one-element set is empty, so the switch going off on an " +
                "account that is not the one on screen sweeps nothing, the subscription stays " +
                "live on the distributor and on the server, every message wakes the process, the " +
                "payload is decrypted, a job is scheduled, and PushFetchWorker throws it away on " +
                "arrival. One wake, one process, one job, no mail. The sweep takes one list and the " +
                "watched set is not among its arguments, so narrowing it back can only be " +
                "written here, as a changed argument. The sweep also sits at the " +
                "top level of the function, one line under the filter, and this rule pins that " +
                "PLACE: nested one level deeper, inside the 'else' arm that starts the foreground " +
                "service or behind an 'if (userInitiated)', it still names notificationsEnabled " +
                "and still reads as the fix, and it runs in one case out of two. Body read:\n" +
                body.take(EXPECTED_APPLY_OPENING.size + 2).joinToString("\n"),
            EXPECTED_APPLY_OPENING,
            body.take(EXPECTED_APPLY_OPENING.size),
        )
        assertEquals(
            "and notificationsEnabled may be named on exactly those two lines of the whole " +
                "body: a third reading of the switch is a decision this rule has never read.",
            listOf(EXPECTED_WATCHED_FILTER, EXPECTED_SYNC_OFF_SWEEP),
            body.filter { "notificationsEnabled" in it },
        )
    }

    @Test fun `an endpoint for a switched-off account is refused before any state is written`() {
        val expected = listOf(
            "val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)",
            "if (syncSwitchedOff(accountId)) return",
            "scope.launch {",
        )
        assertEquals(
            "onNewEndpoint must open with exactly these lines, in this order. The connector " +
                "redelivers the endpoint on every register and UnifiedPush.unregister is not " +
                "instant, so an endpoint already in flight lands after a sync-off teardown. " +
                "Without this gate it reaches store.getOrCreate, which writes a fresh record on " +
                "its own, and for JMAP it goes on down the Subscribe path and creates a LIVE " +
                "subscription on the mail server for an account the user just switched off. The " +
                "next apply() tears it down, the next endpoint rebuilds it: a create/destroy " +
                "cycle against the server that the 15-minute cooldown does not cover, that one " +
                "only guarding FAILED. The gate is before scope.launch, so it is outside the " +
                "monitor and adds no accountStore call under the lock.",
            expected,
            managerBlock(ON_NEW_ENDPOINT).take(expected.size),
        )
    }

    @Test fun `a verification for a switched-off account is refused before any state is written`() {
        val expected = listOf(
            "val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)",
            "if (credentials.protocol != MailProtocol.JMAP) {",
            "Log.w(TAG, \"Push verification on a relay account — ignored: \$accountId\")",
            "return",
            "}",
            "if (syncSwitchedOff(accountId)) return",
            "scope.launch {",
        )
        assertEquals(
            "the Verification arm must open with exactly these lines, in this order. Its " +
                "onSuccess calls store.getOrCreate and writes ACTIVE, so a verification arriving " +
                "for an account whose switch is off puts a torn-down account back on " +
                "UnifiedPush, records and status line and all. The protocol gate stays FIRST: it " +
                "is the one that stops a stranger posting to a relay topic, and its own log line " +
                "names the case.",
            expected,
            managerBlock(VERIFICATION_ARM).take(expected.size),
        )
    }

    @Test fun `the switched-off gate reads the account switch and writes nothing`() {
        assertEquals(
            "syncSwitchedOff must be exactly these lines. It reads accountStore, never the " +
                "UnifiedPush store, so it cannot create the very record it exists to prevent, " +
                "and it returns false when the switch is on: invert that comparison and both " +
                "connector paths refuse every account instead, which is push dead for everyone " +
                "with no crash and no message.",
            listOf(
                "if (accountStore.notificationsEnabled(accountId)) return false",
                "Log.i(TAG, \"UnifiedPush event for an account with sync off — ignored: \$accountId\")",
                "return true",
            ),
            managerBlock(SYNC_SWITCHED_OFF),
        )
    }

    @Test fun `the teardown after a switch-off decides under the monitor and acts outside it`() {
        val expected = listOf(
            "val due = synchronized(this) {",
            "val state = store.load(credentials.id)",
            "RelayPush.shouldTearDown(",
            "isLinked = accountStore.account(credentials.id)?.isLinked == true,",
            "hasPushState = state != null,",
            "linkedAccountStillOn = accountStore.keepsPushForLinkedAccount(credentials.id),",
            "relayRequested = state?.relayRequested == true,",
            ")",
            "}",
            "if (!due) return",
            "teardown(credentials)",
        )
        val body = managerBlock(TEARDOWN_AFTER_SYNC_OFF)
        assertEquals(
            "tearDownAfterSyncOff must be exactly these lines. The four facts are all " +
                "load-bearing: a linked sub-account owns no subscription (#31) and unregistering " +
                "on its id kills its login's; store.load and not getOrCreate, or an IMAP account " +
                "nobody asked a relay for gets a prefs record written on every pass; a login " +
                "with one sub-account still switched on keeps its subscription, or one switch " +
                "off ends the push of accounts nobody touched; and relayRequested, or the relay " +
                "address an IMAP user asked for and pasted into a third-party service by hand is " +
                "destroyed by this sweep, with no way back and nothing on screen (#177). The " +
                "read and the decision sit under ensureRegistered's monitor, teardown outside " +
                "it: it unregisters through the connector and launches a coroutine, neither of " +
                "which may run under the lock. The refusal is NOT cleared here, deliberately: " +
                "this runs only when shouldTearDown says yes, and an account that has never been " +
                "enrolled holds no record at all, so the one person the way back is for — she " +
                "refused the picker before her first registration — would never reach it. It " +
                "sits on the tap instead, see NotificationSwitchAsksAgainWiringTest. The third " +
                "fact is a CALL to the shared predicate and not the two lines it replaced " +
                "(linkedAccounts(id).any { notificationsEnabled(it.id) }): the fallback cycle " +
                "renews the logins this predicate keeps alive AND has in its watched set, so the " +
                "decision has to be one condition read from both ends. The two ends still " +
                "differ on the SET, this one running on every stored account and the cycle on " +
                "the watched ones, which is a gap of its own. Inlined here it stays green while " +
                "the " +
                "renewal end reads something else, which is how a login whose own sync was off " +
                "kept a record reading ACTIVE for a subscription the server had already dropped.",
            expected,
            body,
        )
        assertEquals(
            "and it may name neither onTransportStateChanged nor dropRelayAddress: the callback " +
                "runs apply(), which is this function's own caller, so invoking it here recurses; " +
                "dropRelayAddress refuses any account holding a subscription, which is every " +
                "account this sweep is about. Found:\n" + body.joinToString("\n"),
            emptyList<String>(),
            body.filter { "onTransportStateChanged" in it || "dropRelayAddress" in it },
        )
    }

    @Test fun `teardown leaves no state behind, on either side`() {
        assertEquals(
            "teardown is pinned whole: the record is cleared, the instance unregistered and the " +
                "server subscription destroyed. store.clear is the half that closes the loop " +
                "([RelayPushTest] runs the consequence): without it the record stays ACTIVE, " +
                "shouldRegister answers false for ever, and switching sync back on brings " +
                "nothing back while the status line still reads UnifiedPush over a dead " +
                "subscription. The clear comes AFTER the load, or the subscriptionId destroyed " +
                "on the server is read from an already emptied record. Read and clear sit under " +
                "the monitor, as ONE block: this is the writer the create path re-reads against, " +
                "and a clear slipped between that re-read and its save resurrects the record of " +
                "an account already signed out, subscription and all. UnifiedPush.unregister and " +
                "the scope.launch stay outside it — the lock covers state, never a call out.",
            listOf(
                "val state = synchronized(this) {",
                "val known = store.load(credentials.id)",
                "store.clear(credentials.id)",
                "known",
                "}",
                "UnifiedPush.unregister(context, credentials.id)",
                "val subscriptionId = state?.subscriptionId ?: return",
                "scope.launch { runCatching { repo.destroyPushSubscription(credentials, subscriptionId) } }",
            ),
            managerBlock(TEARDOWN),
        )
    }

    @Test fun `clearing a record removes every key of that account, whatever it is called`() {
        assertEquals(
            "UnifiedPushStateStore.clear must be exactly these lines, and the prefix must be " +
                "the account id and a dot, whole. Narrow it to any one family of keys and the " +
                "damage is silent: '\$accountId.sub' alone still destroys the server " +
                "subscription, but '.device' survives, so load() keeps answering a record, that " +
                "record is still ACTIVE, RelayPush.shouldRegister answers false for ever, the " +
                "status line reads 'Push: UnifiedPush (ntfy)' over a dead subscription, and " +
                "switching sync back on brings nothing back. The account falls to the 30-minute " +
                "poll for good. Nothing executes this: SharedPreferences needs a Context and " +
                "this module has no Robolectric, so the text is the only guard it has.",
            listOf(
                "val edit = prefs.edit()",
                "prefs.all.keys.filter { it.startsWith(\"\$accountId.\") }.forEach { edit.remove(it) }",
                "edit.apply()",
            ),
            blockOf(storeLines(), CLEAR, "UnifiedPushStateStore.kt"),
        )
    }

    @Test fun `the refusal is read and written through one named key`() {
        val lines = storeLines().filter { "DISTRIBUTOR_DECLINED_KEY" in it }
        assertEquals(
            "the three lines that carry the device-wide refusal are pinned whole: the read, the " +
                "write and the constant they share. Spell the key out twice instead, differently " +
                "on the two sides, and the read answers false for ever: the picker is refused, " +
                "the refusal is stored under a name nobody reads, and the dialog comes back on " +
                "every apply(). Nothing here can execute the round trip, SharedPreferences " +
                "needing a Context and this module having no Robolectric; [RelayPushTest] runs " +
                "the one thing that can be run, that the key carries no dot. Lines found:\n" +
                lines.joinToString("\n"),
            listOf(
                "fun distributorDeclined(): Boolean = prefs.getBoolean(DISTRIBUTOR_DECLINED_KEY, false)",
                "prefs.edit().putBoolean(DISTRIBUTOR_DECLINED_KEY, declined).apply()",
                "const val DISTRIBUTOR_DECLINED_KEY = \"distributor-declined\"",
            ),
            lines,
        )
    }

    // -- the two settings gates ----------------------------------------------------------------------

    @Test fun `asking for an address in person starts from zero, under the lock`() {
        val expected = listOf(
            "synchronized(this) {",
            "val state = store.getOrCreate(credentials.id)",
            "store.save(credentials.id, RelayPush.onRelayRequested(state, System.currentTimeMillis()))",
            "}",
            "allowDistributorChoice()",
            "ensureRegistered(credentials)",
        )
        assertEquals(
            "requestRelayAddress must be exactly these lines. RelayPush.onRelayRequested is what " +
                "clears the status: withdrawing an address unregisters the instance, the " +
                "distributor answers, onUnregistered writes FAILED stamped now — and tapping " +
                "'Get an address' a second later then did NOTHING for fifteen minutes without a " +
                "word on screen. And the read/write sits under the same monitor as " +
                "ensureRegistered, or it races the endpoint delivery on Dispatchers.IO. The " +
                "clear sits between them, outside the monitor and before the registration it " +
                "gates: this tap is the most consenting gesture in the app, and without it a " +
                "refused picker sends ensureDistributor to STAY, so the screen reads 'Waiting " +
                "for an address' for ever with a distributor installed and no dialog raised.",
            expected,
            managerBlock(REQUEST_RELAY),
        )
    }

    @Test fun `withdrawing an address refuses to touch an account that holds a subscription`() {
        val expected = listOf(
            "synchronized(this) {",
            "val state = store.load(credentials.id) ?: return",
            "if (state.subscriptionId != null) {",
            "Log.w(TAG, \"dropRelayAddress on an account holding a subscription — ignored: \${credentials.id}\")",
            "return",
            "}",
        )
        val body = managerBlock(DROP_RELAY)
        assertEquals(
            "dropRelayAddress must open with exactly these lines. It is teardown's twin without " +
                "the server call, which is only correct for an account that never held a " +
                "PushSubscription: called on one that does, it erases the id locally and leaves " +
                "the subscription alive on the server, posting to an endpoint that no longer " +
                "exists, and the next registration creates a second one. It is a public function; " +
                "'the screen only calls it for IMAP' is not a guard.",
            expected,
            body.take(expected.size),
        )
        assertEquals(
            "and the unregister must be the LAST thing it does, outside the lock: the connector " +
                "call is not state, and holding the monitor across it blocks ensureRegistered.",
            listOf(
                "UnifiedPush.unregister(context, credentials.id)",
                "onTransportStateChanged?.invoke()",
            ),
            body.takeLast(2),
        )
    }

    @Test fun `the address handed to the screen is a live one or none`() {
        assertEquals(
            "relayEndpoint must be written as '$EXPECTED_RELAY_ENDPOINT' — the whole line. " +
                "Returning the stored field whatever the status shows a DEAD address as a live " +
                "one: markFailed and onUnregistered both keep it, and the user copies it into a " +
                "relay that then posts into a void for ever. RelayPush.publishedAddress is the " +
                "executed rule ([RelayPushTest]); this line is the wiring to it.",
            listOf(EXPECTED_RELAY_ENDPOINT),
            managerLines().filter { it.startsWith("fun relayEndpoint") },
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /** The first slice of [body] as long as [expected], anchored on [expected]'s first line. */
    private fun slice(body: List<String>, expected: List<String>): List<String> {
        val at = body.indexOfFirst { it == expected.first() }
        if (at < 0) return body.take(expected.size)
        return body.subList(at, minOf(at + expected.size, body.size))
    }

    /** The body of the block opened by [opener] in UnifiedPushManager.kt. */
    private fun managerBlock(opener: String): List<String> =
        blockOf(managerLines(), opener, "UnifiedPushManager.kt")

    /** The whole body of `PushController.apply`, the pass that arms every account's transport. */
    private fun applyBody(): List<String> =
        blockOf(codeLines(CONTROLLER), APPLY, "PushController.kt")

    /** The whole body of `transportOf`, the container-free decision `transportFor` delegates to. */
    private fun transportForBody(): List<String> =
        blockOf(codeLines(DECISION), TRANSPORT_FOR, "DeliveryDecision.kt")

    /** The arms of `transportOf`'s `when`, in source order. */
    private fun transportForArms(): List<String> =
        blockOf(transportForBody(), "return when {", "transportOf")

    /** The lines of [body] that are not nested inside a `synchronized(this…) {` block. */
    private fun outsideTheMonitor(body: List<String>): List<String> {
        var depth = 0
        var monitorAt: Int? = null
        val out = mutableListOf<String>()
        for (line in body) {
            val opens = line.count { it == '{' }
            val closes = line.count { it == '}' }
            monitorAt?.let { if (depth - closes <= it) monitorAt = null }
            if (monitorAt == null) {
                if ("synchronized(this" in line && line.endsWith("{")) monitorAt = depth else out += line
            }
            depth += opens - closes
        }
        return out
    }

    /** The body of the block opened by [opener], closed by counting braces. */
    private fun blockOf(lines: List<String>, opener: String, where: String): List<String> {
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) {
            "no '$opener' in $where — it was renamed or reshaped, and this lint must be taught " +
                "the new shape rather than left green over code it never read"
        }
        var depth = lines[at].count { it == '{' } - lines[at].count { it == '}' }
        val out = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return out
            out += line
        }
        error("'$opener' is never closed in $where")
    }

    /** Trimmed lines, comment-only ones dropped so no rule is satisfied by prose. */
    private fun managerLines(): List<String> = codeLines(MANAGER)

    private fun storeLines(): List<String> = codeLines(STORE)

    private fun codeLines(file: File): List<String> =
        file.readLines().map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }

    private companion object {
        const val ENSURE_REGISTERED = "fun ensureRegistered(credentials: AccountCredentials) {"
        const val ON_NEW_ENDPOINT = "fun onNewEndpoint(accountId: String, endpoint: PushEndpoint) {"
        const val ON_MESSAGE = "fun onMessage(accountId: String, message: PushMessage) {"
        const val ENQUEUE_FOR_LOGIN = "private fun enqueueForLogin(loginId: String) {"
        const val VERIFICATION_ARM = "is PushMessagePayload.Verification -> {"
        const val REQUEST_RELAY = "fun requestRelayAddress(credentials: AccountCredentials) {"
        const val DROP_RELAY = "fun dropRelayAddress(credentials: AccountCredentials) {"
        const val TEARDOWN = "fun teardown(credentials: AccountCredentials) {"
        const val SYNC_SWITCHED_OFF = "private fun syncSwitchedOff(accountId: String): Boolean {"
        const val CLEAR = "fun clear(accountId: String) {"
        const val TEARDOWN_AFTER_SYNC_OFF = "fun tearDownAfterSyncOff(credentials: AccountCredentials) {"
        const val APPLY = "fun apply(context: Context, userInitiated: Boolean) {"
        const val ENSURE_DISTRIBUTOR = "private fun ensureDistributor(): Boolean {"
        const val DISTRIBUTOR_CHOSEN = "fun distributorChosen(packageName: String) {"
        const val REMEMBER_REFUSAL = "fun rememberDistributorRefusal() {"
        const val HIDE_CHOICE = "fun hideDistributorChoiceForNow() {"
        const val ALLOW_CHOICE = "fun allowDistributorChoice() {"
        const val RECONCILE = "fun reconcileDistributorPresence() {"
        const val RENEW_IF_NEEDED = "suspend fun renewIfNeeded(credentials: AccountCredentials) {"
        const val IS_PENDING = "fun isPending(accountId: String): Boolean {"
        const val REARM = "private fun rearmFailedAccounts() {"
        const val MARK_FAILED = "private fun markFailed(accountId: String) {"
        const val TRANSPORT_FOR =
            "internal fun transportOf(account: AccountDelivery, globalDelivery: DeliveryMode): Transport {"

        /** The seven state writers that run on a thread of their own. */
        val MONITORED = listOf(
            "private fun recordDelivery(accountId: String) {",
            "private fun markFailed(accountId: String) {",
            "fun onUnregistered(accountId: String) {",
            "fun reconcileDistributorPresence() {",
            "private fun rearmFailedAccounts() {",
            RENEW_IF_NEEDED,
            ON_NEW_ENDPOINT,
        )

        const val SAVE_DISTRIBUTOR = "UnifiedPush.saveDistributor(context, packageName)"

        val EXPECTED_RENEW_LOADS = listOf(
            "val state = store.load(credentials.id) ?: return",
            "val current = store.load(credentials.id) ?: return@synchronized null",
            "val current = store.load(credentials.id) ?: return@synchronized",
        )

        val EXPECTED_RENEW_SUCCESS_BLOCK = listOf(
            "val renewed = synchronized(this) {",
            "val current = store.load(credentials.id) ?: return@synchronized null",
            "val merged = RelayPush.onRenewed(current, state.subscriptionId, renewedUntil)",
            "if (merged != null) store.save(credentials.id, merged)",
            "merged",
        )

        val EXPECTED_RENEW_FAILURE_BLOCK = listOf(
            "synchronized(this) {",
            "val current = store.load(credentials.id) ?: return@synchronized",
            "val merged = RelayPush.onRenewFailed(current, System.currentTimeMillis())",
            "if (merged != null) store.save(credentials.id, merged)",
        )

        const val EXPECTED_CREATE_ARGS =
            "current, prev, endpoint.url, created.id, createdUntil, System.currentTimeMillis(),"

        const val EXPECTED_CREATE_EXPIRY =
            "val createdUntil = parseUtc(created.expires) ?: (System.currentTimeMillis() + EXPIRES_MS)"

        val EXPECTED_CREATE_BLOCK = listOf(
            "val merged = synchronized(this@UnifiedPushManager) {",
            "val current = store.load(accountId)",
            "val decided = RelayPush.onSubscriptionCreated(",
            EXPECTED_CREATE_ARGS,
            ")",
            "if (decided != null) store.save(accountId, decided)",
            "decided",
        )

        val EXPECTED_CREATE_REFUSED = listOf(
            "if (merged == null) {",
            "Log.w(TAG, \"PushSubscription created for a record \$accountId no longer holds\")",
            "created.id?.let { runCatching { repo.destroyPushSubscription(credentials, it) } }",
            "} else {",
            "Log.i(TAG, \"PushSubscription created for \$accountId, awaiting verification\")",
            "}",
        )

        val EXPECTED_RENEW_EXPIRY = listOf(
            "val renewedUntil = parseUtc(applied) ?: (System.currentTimeMillis() + EXPIRES_MS)",
            "val merged = RelayPush.onRenewed(current, state.subscriptionId, renewedUntil)",
        )

        const val EXPECTED_UNIFIED_PUSH_ARM = "account.unifiedPushActive -> Transport.UNIFIED_PUSH"

        val EXPECTED_TRANSPORT_ARMS = listOf(
            "account.isLinked -> Transport.PERIODIC",
            EXPECTED_UNIFIED_PUSH_ARM,
            "batterySaver -> Transport.PERIODIC",
            "account.protocol == MailProtocol.JMAP -> Transport.EVENT_SOURCE",
            "else -> Transport.IMAP_IDLE",
        )

        /** The one line that fills the UnifiedPush fact the arm above reads. */
        const val EXPECTED_UNIFIED_PUSH_FACT =
            "unifiedPushActive = container.unifiedPushManager.isActive(credentials.id),"

        /** The line that fills the fact the #31 arm reads, and the one the protocol arms read. */
        const val EXPECTED_IS_LINKED_FACT =
            "isLinked = container.accountStore.account(credentials.id)?.isLinked == true,"
        const val EXPECTED_PROTOCOL_FACT = "protocol = credentials.protocol,"

        const val EXPECTED_BATTERY_SAVER_FACT =
            "resolveDeliveryMode(account.deliveryMode, globalDelivery) == DeliveryMode.BATTERY_SAVER"

        val FORBIDDEN_IN_TRANSPORT =
            listOf("relayEndpoint", "PUBLISHED", "relayRequested", "publishedAddress")

        const val EXPECTED_VAPID =
            "val vapid = if (relay) null else runCatching { repo.pushVapidKey(credentials) }.getOrNull()"
        const val EXPECTED_RELAY_FLAG = "val relay = credentials.protocol != MailProtocol.JMAP"
        const val EXPECTED_ALL_ACCOUNTS = "val all = store.allCredentials()"
        const val EXPECTED_WATCHED_SET =
            "val watched = if (store.pushAllAccounts()) all else listOfNotNull(store.load())"
        const val EXPECTED_WATCHED_FILTER =
            "val accounts = watched.filter { store.notificationsEnabled(it.id) }"
        const val EXPECTED_SYNC_OFF_SWEEP =
            "accountsToTearDown(all) { store.notificationsEnabled(it) }"
        const val EXPECTED_SYNC_OFF_TEARDOWN = ".forEach { up.tearDownAfterSyncOff(it) }"
        const val EXPECTED_RELAY_ENDPOINT =
            "fun relayEndpoint(accountId: String): String? = RelayPush.publishedAddress(store.load(accountId))"

        val EXPECTED_APPLY_OPENING = listOf(
            "val appContext = context.applicationContext as Application",
            "val container = appContext.container",
            "val store = container.accountStore",
            "val up = container.unifiedPushManager",
            "up.reconcileDistributorPresence()",
            EXPECTED_ALL_ACCOUNTS,
            EXPECTED_WATCHED_SET,
            EXPECTED_WATCHED_FILTER,
            EXPECTED_SYNC_OFF_SWEEP,
            EXPECTED_SYNC_OFF_TEARDOWN,
            "accounts.forEach { up.ensureRegistered(it) }",
        )

        const val EXPECTED_SINCE_LOAD = "statusSinceMillis = prefs.getLong(\"\$accountId.since\", 0),"
        const val EXPECTED_SINCE_SAVE = ".putLong(\"\$accountId.since\", state.statusSinceMillis)"

        const val EXPECTED_CREATES_LOAD = "createAttempts = prefs.getInt(\"\$accountId.creates\", 0),"
        const val EXPECTED_CREATES_SAVE = ".putInt(\"\$accountId.creates\", state.createAttempts)"

        const val MANAGER_PATH = "app/src/main/kotlin/app/sterna/push/UnifiedPushManager.kt"
        const val CONTROLLER_PATH = "app/src/main/kotlin/app/sterna/push/PushController.kt"
        const val DECISION_PATH = "app/src/main/kotlin/app/sterna/push/DeliveryDecision.kt"
        const val STORE_PATH = "app/src/main/kotlin/app/sterna/push/UnifiedPushStateStore.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MANAGER_PATH).isFile }
                ?: error(
                    "cannot locate $MANAGER_PATH from ${File("").absolutePath} — this lint reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val MANAGER: File by lazy { File(root, MANAGER_PATH) }
        val CONTROLLER: File by lazy { File(root, CONTROLLER_PATH) }
        val DECISION: File by lazy { File(root, DECISION_PATH) }
        val STORE: File by lazy { File(root, STORE_PATH) }
    }
}
