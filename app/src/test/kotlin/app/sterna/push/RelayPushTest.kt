package app.sterna.push

import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure UnifiedPush decisions, executed: who may register, what an endpoint means, what arms. */
class RelayPushTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    private val measured = 5 * minute + 33_000L
    private val url = "https://up.example.org/UP?token=abc"
    private val other = "https://up.example.org/UP?token=zzz"

    private fun state(
        status: UpStatus,
        endpoint: String? = null,
        subscriptionId: String? = null,
        since: Long = now,
        relayRequested: Boolean = false,
        lastDelivery: Long = 0L,
        createAttempts: Int = 0,
    ) = UpAccountState(
        deviceClientId = "dev-1",
        endpoint = endpoint,
        subscriptionId = subscriptionId,
        status = status,
        statusSinceMillis = since,
        relayRequested = relayRequested,
        lastDeliveryMillis = lastDelivery,
        createAttempts = createAttempts,
    )

    // ── shouldRegister: the relay gate ──────────────────────────────────────────────────

    @Test fun `an IMAP account nobody asked a relay for never registers, whatever its status`() {
        UpStatus.entries.forEach { status ->
            assertFalse(
                "IMAP without relayRequested must not register in $status",
                RelayPush.shouldRegister(
                    protocol = MailProtocol.IMAP,
                    relayRequested = false,
                    status = status,
                    statusSinceMillis = 0L,
                    now = now,
                ),
            )
        }
    }

    @Test fun `an IMAP account whose owner asked for an address registers once`() {
        assertTrue(
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.NONE, 0L, now),
        )
    }

    @Test fun `a published IMAP relay does not register again`() {
        assertFalse(
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.PUBLISHED, now - minute, now),
        )
    }

    @Test fun `an armed IMAP relay does not register again`() {
        assertFalse(
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.ACTIVE, now - minute, now),
        )
    }

    // ── shouldRegister: the three JMAP windows, unchanged ───────────────────────────────

    @Test fun `a JMAP account that never registered registers`() {
        assertTrue(RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.NONE, 0L, now))
    }

    @Test fun `a failed JMAP account waits out its cooldown`() {
        assertFalse(
            "14 minutes is inside the 15-minute cooldown",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.FAILED, now - 14 * minute, now),
        )
        assertTrue(
            "16 minutes is past it",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.FAILED, now - 16 * minute, now),
        )
    }

    @Test fun `a pending JMAP registration is only retried once it went stale`() {
        assertFalse(
            "1 minute in, the registration is still in flight",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.REGISTERING, now - minute, now),
        )
        assertTrue(
            "3 minutes in, it is stale",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.REGISTERING, now - 3 * minute, now),
        )
    }

    @Test fun `a verification in flight is given the server's window, not the distributor's`() {
        assertFalse(
            "3 minutes into a VERIFYING the server has simply not answered yet; re-registering " +
                "here is what recreated the subscription under its own pending verification",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.VERIFYING, now - 3 * minute, now),
        )
        assertFalse(
            "the bench measured 5 min 33 from PushSubscription to PushVerification on a server " +
                "that did verify: that latency must not count as stale",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.VERIFYING, now - measured, now),
        )
        assertTrue(
            "past 10 minutes the verification is genuinely lost and registering again is right; " +
                "the fix must not turn a loop into a state nothing ever leaves",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.VERIFYING, now - 11 * minute, now),
        )
    }

    @Test fun `each of the two waits gets its own window`() {
        assertEquals(
            "REGISTERING waits on the distributor, on this device: 2 minutes, unchanged",
            2 * minute,
            RelayPush.pendingGraceMs(UpStatus.REGISTERING),
        )
        assertEquals(
            "VERIFYING waits on the mail server, over the network and through a push relay: the " +
                "bench measured 5 min 33 for one that arrived, so the window is 10 minutes",
            10 * minute,
            RelayPush.pendingGraceMs(UpStatus.VERIFYING),
        )
    }

    @Test fun `a JMAP account ignores relayRequested entirely`() {
        assertEquals(
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.NONE, 0L, now),
            RelayPush.shouldRegister(MailProtocol.JMAP, true, UpStatus.NONE, 0L, now),
        )
        assertEquals(
            RelayPush.shouldRegister(MailProtocol.JMAP, false, UpStatus.ACTIVE, now - minute, now),
            RelayPush.shouldRegister(MailProtocol.JMAP, true, UpStatus.ACTIVE, now - minute, now),
        )
    }

    // ── endpointOutcome ─────────────────────────────────────────────────────────────────

    // The mounting carries a subscriptionId on purpose. Without one the `assertNull` below is
    // satisfied by the input rather than by the rule, and dropping `subscriptionId = null` from the
    // published copy() stays green, which is exactly what it did.
    @Test fun `an IMAP endpoint is published locally and talks to no server`() {
        val outcome = RelayPush.endpointOutcome(
            MailProtocol.IMAP,
            state(
                UpStatus.REGISTERING,
                since = now - minute,
                subscriptionId = "S-stale",
                relayRequested = true,
            ),
            url,
            now,
        )
        assertTrue("expected Relay, got $outcome", outcome is EndpointOutcome.Relay)
        val published = (outcome as EndpointOutcome.Relay).state
        assertEquals(url, published.endpoint)
        assertEquals(UpStatus.PUBLISHED, published.status)
        assertEquals(now, published.statusSinceMillis)
        assertNull("a relay account has no PushSubscription to hold", published.subscriptionId)
        assertTrue("the relay stays requested", published.relayRequested)
        assertEquals("dev-1", published.deviceClientId)
    }

    @Test fun `redelivering the same address does not knock an armed relay back to published`() {
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.IMAP,
                state(UpStatus.ACTIVE, endpoint = url, since = now - 3 * minute, relayRequested = true),
                url,
                now,
            ),
        )
    }

    @Test fun `redelivering the same address to a published relay changes nothing`() {
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.IMAP,
                state(UpStatus.PUBLISHED, endpoint = url, since = now - 3 * minute, relayRequested = true),
                url,
                now,
            ),
        )
    }

    @Test fun `a rotated relay address is published, not ignored`() {
        val outcome = RelayPush.endpointOutcome(
            MailProtocol.IMAP,
            state(UpStatus.ACTIVE, endpoint = other, since = now - 3 * minute, relayRequested = true),
            url,
            now,
        )
        assertTrue("expected Relay, got $outcome", outcome is EndpointOutcome.Relay)
        assertEquals(url, (outcome as EndpointOutcome.Relay).state.endpoint)
        assertEquals(UpStatus.PUBLISHED, outcome.state.status)
    }

    @Test fun `an endpoint that lands after the address was withdrawn is dropped`() {
        // The withdrawal writes none and relayRequested = false; a delivery already in flight then
        // arrives with a fresh URL. Publishing it would hand back the address the user just removed.
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.IMAP,
                state(UpStatus.NONE, endpoint = null, since = now, relayRequested = false),
                url,
                now,
            ),
        )
    }

    @Test fun `a JMAP endpoint goes to the subscription path`() {
        assertEquals(
            EndpointOutcome.Subscribe,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.REGISTERING, since = now - minute),
                url,
                now,
            ),
        )
    }

    @Test fun `a JMAP endpoint redelivered onto a live subscription is ignored`() {
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.ACTIVE, endpoint = url, subscriptionId = "S1", since = now - 3 * minute),
                url,
                now,
            ),
        )
    }

    @Test fun `a JMAP endpoint whose verification window lapsed is recreated`() {
        assertEquals(
            "3 minutes was 'lapsed' while both waits shared one 2-minute window, and it is not: " +
                "the bench measured 5 min 33 for a verification that did arrive, so Subscribe " +
                "here destroys the subscription it is waiting on and starts the loop again",
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", since = now - 3 * minute),
                url,
                now,
            ),
        )
        assertEquals(
            "a fresh VERIFYING is still in flight",
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", since = now - minute),
                url,
                now,
            ),
        )
        assertEquals(
            "past the server window it really is lost, and the subscription is recreated",
            EndpointOutcome.Subscribe,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", since = now - 11 * minute),
                url,
                now,
            ),
        )
    }

    @Test fun `the latency the bench measured leaves the pending subscription alone`() {
        assertEquals(
            "this is the whole defect of #195: at 5 min 33 the account was sent back through " +
                "destroy + create, so the verification the server was about to send landed on an " +
                "id that no longer existed",
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.JMAP,
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", since = now - measured),
                url,
                now,
            ),
        )
    }

    @Test fun `a relay account crosses both decisions exactly as before`() {
        listOf(UpStatus.PUBLISHED, UpStatus.ACTIVE).forEach { status ->
            assertFalse(
                "a $status relay never registers again, however old its stamp — the windows " +
                    "this branch moved are the JMAP subscription's, and a relay has none",
                RelayPush.shouldRegister(MailProtocol.IMAP, true, status, now - 30 * minute, now),
            )
            assertEquals(
                "and the same address redelivered onto a $status relay is still ignored",
                EndpointOutcome.Ignore,
                RelayPush.endpointOutcome(
                    MailProtocol.IMAP,
                    state(status, endpoint = url, relayRequested = true, since = now - 30 * minute),
                    url,
                    now,
                ),
            )
        }
        assertTrue(
            "a rotation onto a relay still publishes the new address",
            RelayPush.endpointOutcome(
                MailProtocol.IMAP,
                state(UpStatus.ACTIVE, endpoint = url, relayRequested = true, since = now - 30 * minute),
                other,
                now,
            ) is EndpointOutcome.Relay,
        )
    }

    // ── endpointOutcome: a rotation drops the old address's delivery stamp ───────────────

    @Test fun `a rotated relay address forgets the wake the old address had earned`() {
        val outcome = RelayPush.endpointOutcome(
            MailProtocol.IMAP,
            state(
                UpStatus.ACTIVE,
                endpoint = other,
                since = now - 20 * minute,
                relayRequested = true,
                lastDelivery = now - 5 * minute,
            ),
            url,
            now,
        )
        assertTrue("expected Relay, got $outcome", outcome is EndpointOutcome.Relay)
        val published = (outcome as EndpointOutcome.Relay).state
        assertEquals(url, published.endpoint)
        assertEquals(UpStatus.PUBLISHED, published.status)
        assertEquals(now, published.statusSinceMillis)
        assertEquals(
            "the old address's last delivery is not this address's",
            0L,
            published.lastDeliveryMillis,
        )
    }

    @Test fun `publishing an address arms nothing by itself`() {
        val outcome = RelayPush.endpointOutcome(
            MailProtocol.IMAP,
            state(UpStatus.REGISTERING, since = now - minute, relayRequested = true),
            url,
            now,
        )
        val published = (outcome as EndpointOutcome.Relay).state
        assertEquals("an address nobody posted to is PUBLISHED, not ACTIVE", UpStatus.PUBLISHED, published.status)
        assertEquals("nothing was ever delivered through it", 0L, published.lastDeliveryMillis)
    }

    @Test fun `redelivering the same address to a relay that has been posted to is ignored`() {
        assertEquals(
            EndpointOutcome.Ignore,
            RelayPush.endpointOutcome(
                MailProtocol.IMAP,
                state(
                    UpStatus.ACTIVE,
                    endpoint = url,
                    since = now - 20 * minute,
                    relayRequested = true,
                    lastDelivery = now - 5 * minute,
                ),
                url,
                now,
            ),
        )
    }

    // ── onDelivery: only a real delivery arms a relay ───────────────────────────────────

    @Test fun `the first payload on a published relay arms it and is stamped`() {
        val update = RelayPush.onDelivery(
            state(UpStatus.PUBLISHED, endpoint = url, since = now - 20 * minute, relayRequested = true),
            now,
        )
        assertNotNull("a first delivery must be persisted", update)
        update!!
        assertTrue("PUBLISHED → ACTIVE is the transport change", update.armed)
        assertEquals(UpStatus.ACTIVE, update.state.status)
        assertEquals(now, update.state.lastDeliveryMillis)
        assertEquals("the status changed, so its timestamp moves", now, update.state.statusSinceMillis)
        assertEquals(url, update.state.endpoint)
        assertTrue(update.state.relayRequested)
        assertNull("still no PushSubscription behind a relay", update.state.subscriptionId)
    }

    @Test fun `a later payload restamps an armed relay without announcing a transport change`() {
        val update = RelayPush.onDelivery(
            state(
                UpStatus.ACTIVE,
                endpoint = url,
                since = now - 20 * minute,
                relayRequested = true,
                lastDelivery = now - 5 * minute,
            ),
            now,
        )
        assertNotNull(update)
        update!!
        assertFalse("already armed: rebuilding every connection here would be a per-message storm", update.armed)
        assertEquals(UpStatus.ACTIVE, update.state.status)
        assertEquals(now, update.state.lastDeliveryMillis)
        assertEquals(
            "the status did not change, so its timestamp must not move",
            now - 20 * minute,
            update.state.statusSinceMillis,
        )
    }

    @Test fun `a payload that would change nothing is not written back`() {
        assertNull(
            RelayPush.onDelivery(
                state(
                    UpStatus.ACTIVE,
                    endpoint = url,
                    since = now - 20 * minute,
                    relayRequested = true,
                    lastDelivery = now,
                ),
                now,
            ),
        )
    }

    @Test fun `a JMAP payload never arms anything - only its server verification does`() {
        assertNull(
            RelayPush.onDelivery(
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", since = now - minute),
                now,
            ),
        )
        assertNull(
            RelayPush.onDelivery(
                state(UpStatus.ACTIVE, endpoint = url, subscriptionId = "S1", since = now - minute),
                now,
            ),
        )
    }

    @Test fun `an account holding a subscription is not a relay even if a relay was once asked for`() {
        assertNull(
            RelayPush.onDelivery(
                state(
                    UpStatus.VERIFYING,
                    endpoint = url,
                    subscriptionId = "S1",
                    since = now - minute,
                    relayRequested = true,
                ),
                now,
            ),
        )
    }

    @Test fun `a payload on a relay with no published address arms nothing`() {
        listOf(UpStatus.NONE, UpStatus.REGISTERING, UpStatus.FAILED).forEach { status ->
            assertNull(
                "a delivery must not arm a relay in $status",
                RelayPush.onDelivery(state(status, since = now - minute, relayRequested = true), now),
            )
        }
    }

    // ── mayUsePush: the one question the manager asks before it raises any UI ────────────

    @Test fun `only a JMAP account, or one whose owner asked, may touch push at all`() {
        assertTrue("JMAP always may", RelayPush.mayUsePush(MailProtocol.JMAP, false))
        assertTrue(RelayPush.mayUsePush(MailProtocol.JMAP, true))
        assertTrue("an IMAP account that asked for an address may", RelayPush.mayUsePush(MailProtocol.IMAP, true))
        assertFalse(
            "an IMAP account nobody asked a relay for must leave before the distributor picker",
            RelayPush.mayUsePush(MailProtocol.IMAP, false),
        )
    }

    // ── unifiedPushMissing: what the direct-connection line adds, and what it must not ───
    // Expected values are written out, never recomputed from the function's own expression.

    @Test fun `a JMAP account that is not registering is missing the push it should have`() {
        assertTrue(
            "JMAP may always use push, so a JMAP account that is not in flight has none",
            RelayPush.unifiedPushMissing(MailProtocol.JMAP, false, UpStatus.FAILED, pending = false),
        )
        assertTrue(RelayPush.unifiedPushMissing(MailProtocol.JMAP, false, UpStatus.NONE, pending = false))
        assertTrue(RelayPush.unifiedPushMissing(MailProtocol.JMAP, false, UpStatus.VERIFYING, pending = false))
    }

    @Test fun `a healthy bring-up says nothing, which is the whole of issue 53`() {
        assertFalse(
            "while registration or verification is in flight the line stays bare",
            RelayPush.unifiedPushMissing(MailProtocol.JMAP, false, UpStatus.REGISTERING, pending = true),
        )
        assertFalse(RelayPush.unifiedPushMissing(MailProtocol.JMAP, false, UpStatus.VERIFYING, pending = true))
    }

    @Test fun `an IMAP account nobody asked a relay for earns no warning, ever`() {
        assertFalse(
            "it is legitimately direct: it never asked for UnifiedPush and nothing is missing",
            RelayPush.unifiedPushMissing(MailProtocol.IMAP, false, UpStatus.NONE, pending = false),
        )
        assertFalse(RelayPush.unifiedPushMissing(MailProtocol.IMAP, false, UpStatus.FAILED, pending = false))
    }

    @Test fun `an IMAP account whose owner asked for an address is missing it too`() {
        assertTrue(RelayPush.unifiedPushMissing(MailProtocol.IMAP, true, UpStatus.FAILED, pending = false))
        assertTrue(RelayPush.unifiedPushMissing(MailProtocol.IMAP, true, UpStatus.NONE, pending = false))
    }

    @Test fun `a published relay address is not a missing push, it is an address awaiting a post`() {
        assertFalse(
            "PUBLISHED reaches the direct branch because relayArmed wants ACTIVE, and the screen " +
                "draws that very address below this line: nothing is missing, nobody has posted yet",
            RelayPush.unifiedPushMissing(MailProtocol.IMAP, true, UpStatus.PUBLISHED, pending = false),
        )
    }

    @Test fun `an account whose record cannot be read claims nothing`() {
        assertFalse(RelayPush.unifiedPushMissing(null, true, UpStatus.FAILED, pending = false))
        assertFalse(RelayPush.unifiedPushMissing(null, false, UpStatus.NONE, pending = false))
    }

    @Test fun `the missing-push question reads its arguments in the declared order`() {
        assertTrue(RelayPush.unifiedPushMissing(MailProtocol.IMAP, true, UpStatus.FAILED, false))
        assertFalse(RelayPush.unifiedPushMissing(MailProtocol.IMAP, false, UpStatus.FAILED, true))
        assertFalse(RelayPush.unifiedPushMissing(MailProtocol.IMAP, true, UpStatus.PUBLISHED, false))
    }

    // ── onRelayRequested: a tap is not an automatic loop ─────────────────────────────────

    @Test fun `asking for an address in person clears the failure that would have muzzled it`() {
        // Remove address → unregister → onUnregistered writes failed, stamped now. Tapping
        // "Get an address" a second later used to land inside the 15-minute cooldown and do
        // nothing at all, in silence.
        val justFailed = state(UpStatus.FAILED, endpoint = url, since = now, relayRequested = true)
        assertFalse(
            "the automatic path is still held back — that is what the cooldown is for",
            RelayPush.shouldRegister(
                MailProtocol.IMAP, true, justFailed.status, justFailed.statusSinceMillis, now,
            ),
        )
        val asked = RelayPush.onRelayRequested(justFailed, now)
        assertEquals(UpStatus.NONE, asked.status)
        assertEquals(now, asked.statusSinceMillis)
        assertTrue(asked.relayRequested)
        assertTrue(
            "after an explicit request the very next register goes out",
            RelayPush.shouldRegister(MailProtocol.IMAP, true, asked.status, asked.statusSinceMillis, now),
        )
    }

    @Test fun `asking for an address on an account that never had one marks it requested`() {
        val asked = RelayPush.onRelayRequested(state(UpStatus.NONE, since = 0L), now)
        assertTrue(asked.relayRequested)
        assertEquals(UpStatus.NONE, asked.status)
        assertEquals(now, asked.statusSinceMillis)
        assertEquals("dev-1", asked.deviceClientId)
    }

    @Test fun `the cooldown still holds every path that is not a tap`() {
        assertFalse(
            "a FAILED relay one minute in must not re-register by itself",
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.FAILED, now - minute, now),
        )
        assertTrue(
            "and it may again once the 15 minutes are up",
            RelayPush.shouldRegister(MailProtocol.IMAP, true, UpStatus.FAILED, now - 16 * minute, now),
        )
    }

    @Test fun `picking a distributor clears the failure its own disappearance stamped`() {
        val lost = state(UpStatus.FAILED, endpoint = url, since = now - minute, relayRequested = true)
        assertFalse(
            "the automatic path is still held back — the cooldown is what keeps a lost " +
                "distributor from being retried at every worker cycle",
            RelayPush.shouldRegister(MailProtocol.IMAP, true, lost.status, lost.statusSinceMillis, now),
        )
        val chosen = RelayPush.onDistributorChosen(lost, now)
        assertNotNull("a FAILED account is the one case this exists for", chosen)
        assertEquals(UpStatus.NONE, chosen!!.status)
        assertEquals(
            "the stamp moves to the choice: it is when the status last changed, and leaving it on " +
                "the loss dates a NONE from an event that is over",
            now,
            chosen.statusSinceMillis,
        )
        assertTrue(
            "and the register that follows the choice goes out at once, on a relay account",
            RelayPush.shouldRegister(MailProtocol.IMAP, true, chosen.status, chosen.statusSinceMillis, now),
        )
        assertTrue(
            "and on a JMAP account, which is the one the reporter watched sit out 15 minutes",
            RelayPush.shouldRegister(MailProtocol.JMAP, false, chosen.status, chosen.statusSinceMillis, now),
        )
    }

    @Test fun `a distributor choice rearms FAILED and no other status`() {
        UpStatus.entries.filter { it != UpStatus.FAILED }.forEach { status ->
            assertNull(
                "rearming $status would restamp a status that never moved: ACTIVE and PUBLISHED " +
                    "would lose the transport they hold, REGISTERING/VERIFYING would have their " +
                    "stale-pending watchdog reset by a gesture that changed nothing for them",
                RelayPush.onDistributorChosen(state(status, since = now - minute, relayRequested = true), now),
            )
        }
    }

    @Test fun `picking a distributor claims no relay address`() {
        assertFalse(
            "a JMAP account never asked for a relay address; onRelayRequested would record that " +
                "it had, and the settings screen would offer to withdraw an address nobody has",
            RelayPush.onDistributorChosen(state(UpStatus.FAILED, since = now), now)!!.relayRequested,
        )
        assertTrue(
            "and an account that did ask keeps its claim",
            RelayPush.onDistributorChosen(
                state(UpStatus.FAILED, since = now, relayRequested = true), now,
            )!!.relayRequested,
        )
    }

    @Test fun `picking a distributor moves the status, its stamp and the spent budget`() {
        val prev = UpAccountState(
            deviceClientId = "dev-1",
            endpoint = url,
            subscriptionId = "sub-9",
            status = UpStatus.FAILED,
            expiresAtMillis = now + 7 * minute,
            statusSinceMillis = now - minute,
            relayRequested = true,
            lastDeliveryMillis = now - 2 * minute,
            createAttempts = 3,
        )
        val chosen = RelayPush.onDistributorChosen(prev, now)!!
        assertEquals("the endpoint is cleared by a rotation, not by a choice", url, chosen.endpoint)
        assertEquals("the subscription is the server's, and it outlives a distributor", "sub-9", chosen.subscriptionId)
        assertEquals("dev-1", chosen.deviceClientId)
        assertEquals(now + 7 * minute, chosen.expiresAtMillis)
        assertEquals(
            "the last wake really happened; forgetting it would show 'never woken' on screen",
            now - 2 * minute,
            chosen.lastDeliveryMillis,
        )
        assertEquals(
            "the spent create budget goes back to zero on this gesture, and nothing else in the " +
                "app can put it back. A distributor hands the same instance the SAME endpoint " +
                "(ntfy and NextPush both), so a user who re-picks the distributor that was lost " +
                "gets the address whose budget is already spent: markFailed keeps the count and " +
                "keeps the endpoint, so the 15-minute cooldown lets shouldRegister through, the " +
                "give-up fires again, and the account is FAILED for ever with no gesture left. " +
                "This is the same reason FAILED is cleared here, applied to the other half.",
            0,
            chosen.createAttempts,
        )
        assertFalse(
            "and the rule that reads the count agrees: the next endpoint delivery may create again",
            RelayPush.createBudgetSpent(chosen, url),
        )
    }

    @Test fun `three creations for one address are allowed, and the fourth is not`() {
        (0..2).forEach { done ->
            assertFalse(
                "after $done creation(s) the account may still create another: the cap is what " +
                    "stops the hammering, and setting it below 3 gives up on a server that only " +
                    "verifies now and then",
                RelayPush.createBudgetSpent(
                    state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", createAttempts = done),
                    url,
                ),
            )
        }
        assertTrue(
            "three subscriptions created for one address and not one verification: the account is " +
                "given up on, which shows as FAILED where an eternal VERIFYING showed nothing",
            RelayPush.createBudgetSpent(
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", createAttempts = 3),
                url,
            ),
        )
    }

    @Test fun `a budget spent on one address says nothing about the next one`() {
        val spent = state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", createAttempts = 3)
        assertTrue(RelayPush.createBudgetSpent(spent, url))
        assertFalse(
            "the count is per endpoint: the user picking another distributor gets another address, " +
                "and carrying the old address's spent budget over means push never comes back",
            RelayPush.createBudgetSpent(spent, other),
        )
    }

    @Test fun `a creation counts against the address it was made for`() {
        assertEquals(
            "a first subscription on an account that has no address yet",
            1,
            RelayPush.createAttemptsFor(state(UpStatus.REGISTERING), url),
        )
        assertEquals(
            "the same address again: this is the third go",
            3,
            RelayPush.createAttemptsFor(
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", createAttempts = 2),
                url,
            ),
        )
        assertEquals(
            "a rotated address starts its own count, whatever the old one had spent",
            1,
            RelayPush.createAttemptsFor(
                state(UpStatus.VERIFYING, endpoint = url, subscriptionId = "S1", createAttempts = 3),
                other,
            ),
        )
    }

    // ── publishedAddress: a dead address is not an address ───────────────────────────────

    @Test fun `a published relay shows its address, and an armed one keeps showing it`() {
        assertEquals(
            url,
            RelayPush.publishedAddress(
                state(UpStatus.PUBLISHED, endpoint = url, relayRequested = true),
            ),
        )
        assertEquals(
            url,
            RelayPush.publishedAddress(
                state(UpStatus.ACTIVE, endpoint = url, relayRequested = true, lastDelivery = now),
            ),
        )
    }

    @Test fun `an address the distributor no longer serves is not shown`() {
        // markFailed and onUnregistered both keep the endpoint field; showing it would put a dead
        // address on screen as a live one, ready to be pasted into a relay that posts into a void.
        listOf(UpStatus.NONE, UpStatus.REGISTERING, UpStatus.VERIFYING, UpStatus.FAILED).forEach { status ->
            assertNull(
                "an endpoint in $status is not an address to show",
                RelayPush.publishedAddress(state(status, endpoint = url, relayRequested = true)),
            )
        }
    }

    @Test fun `an address nobody asked for is not shown either`() {
        assertNull(
            "the subsystem enrols a distributor silently, so an endpoint can exist unasked-for",
            RelayPush.publishedAddress(state(UpStatus.PUBLISHED, endpoint = url, relayRequested = false)),
        )
    }

    @Test fun `an account with no record at all has no address`() {
        assertNull(RelayPush.publishedAddress(null))
        assertNull(RelayPush.publishedAddress(state(UpStatus.PUBLISHED, relayRequested = true)))
    }
}
