package app.sterna.core.data.mail

import app.sterna.core.data.db.BODY_CACHE_CLEAR_SQL
import app.sterna.core.data.db.EMAILS_CREATE_SQL
import app.sterna.core.data.db.EMAIL_BODIES_CREATE_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.sql.DriverManager

/**
 * The upgrade purge of the cached message bodies: ONCE per threshold crossed, and nothing on any
 * launch that crosses none.
 *
 * Both halves matter and they pull in opposite directions. Not purging at that threshold leaves
 * a new reader feature invisible on the mail already in the cache — which, thanks to the
 * prefetch, is the twenty newest messages of the inbox, i.e. the newsletters this lot is about.
 * Purging on every version bump throws away every body on the first start after each update, for
 * ever: every message read again becomes a network round trip, and nothing already read opens
 * offline. The threshold buys the first without signing up for the second.
 */
class BodyCacheUpgradeTest {

    /**
     * ⛔ THE THRESHOLD ITSELF, written out as a literal — the parade `IncognitoImeOptionsTest`
     * already uses. Every other test in this file says `BODY_CACHE_PURGE_VERSION` on both sides
     * of its assertion, so the constant can be moved to any value at all and the whole file stays
     * green: the rules are about the SHAPE of the decision, not about which release needs it.
     *
     * Measured: raising it to 169 kept the suite green while moving the purge one release late —
     * so nobody purges on the arrival of 1.4.8, the release that taught the reader to look for
     * the unsubscribe headers, and the feature is invisible on precisely the cached newsletters
     * it was written for. 168 = the release after 1.4.7 (versionCode 167). A fact about a past
     * upgrade: it does not move when the version does.
     */
    @Test fun `the threshold is the release that needed it, by number`() {
        assertEquals(168, BODY_CACHE_PURGE_VERSION)
    }

    /**
     * ⛔ THE SECOND THRESHOLD, same parade, same reason. Every rule below is about the SHAPE of the
     * decision and would stay green at any value; this is the only test that says WHICH release.
     *
     * 170 = the `versionCode` of the cut that CARRIES #148, `versionCode` being 169 (1.4.9) as
     * this is written. Not "the next number": if #148 slips to 171 with no 170 published, 169 →
     * 171 crosses 170 anyway and purges. ⛔ The case that kills the fix is an INTERLEAVED release —
     * a corrective 1.4.10 taking 170 without #148: the 1.4.9 user purges for nothing, records 170,
     * receives #148 in 171, crosses no threshold and never sees the banner. `/release` must check
     * this literal against the `versionCode` of the cut that actually ships #148, and move both.
     */
    @Test fun `the second threshold is the release that needs it, by number`() {
        assertEquals(170, BODY_CACHE_PURGE_VERSION_2)
    }

    @Test fun `arriving at the threshold purges, and records the version it purged for`() {
        assertEquals(
            BODY_CACHE_PURGE_VERSION,
            bodyCachePurgeVersion(purgedForVersion = 0, currentVersion = BODY_CACHE_PURGE_VERSION),
        )
    }

    /**
     * ⛔ CROSSED, not equalled. Someone who skips the release that introduced the need — 1.4.6
     * straight to 1.4.9 — still arrives with a cache written before it, and must purge once.
     */
    @Test fun `jumping over the threshold purges on arrival`() {
        assertEquals(169, bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 169))
        assertEquals(200, bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 200))
    }

    /**
     * ⛔ THE BUG. Every version bump used to purge, for ever: `currentVersion > purgedForVersion`.
     * A purge is the answer to ONE need, not to the act of updating.
     *
     * ⚠ This test used to loop 169..171 and claim "after 168, never again". That premise is what
     * the second threshold changes: 170 now has a need of its own. What survives — and is the
     * whole point — is that a version bump which crosses NO threshold purges nothing, whatever
     * the gap between the two numbers.
     */
    @Test fun `a version bump that crosses no threshold never purges`() {
        assertNull(
            "168 is already purged for, and 169 has no need of its own",
            bodyCachePurgeVersion(purgedForVersion = 168, currentVersion = 169),
        )
        assertNull(bodyCachePurgeVersion(purgedForVersion = 171, currentVersion = 172))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 200, currentVersion = 999))
    }

    /**
     * ⛔ THE DEFECT THIS SECOND THRESHOLD ANSWERS, in bare literals on both sides — no constant,
     * so moving the constant cannot keep this green.
     *
     * Whoever ran 1.4.9 carries `purgedForVersion = 169`: past the only threshold there was, so
     * nothing crossed again, ever. Their cached bodies are `Email` rows serialised by a build that
     * did not know `dispositionNotificationTo`, and `openMessage` serves the cache before reading
     * anything off the message — so the read-receipt banner cannot offer itself on the mail it was
     * written for, and the prefetch keeps exactly those twenty messages warm.
     */
    @Test fun `the 1_4_9 install purges on arriving at the read-receipt release`() {
        assertEquals(
            "a 1.4.9 install must purge on arrival, or no read-receipt banner on mail already read",
            170,
            bodyCachePurgeVersion(purgedForVersion = 169, currentVersion = 170),
        )
    }

    /** ⛔ CROSSED, not equalled — for the second threshold too: 169 straight to 171 purges once. */
    @Test fun `jumping over the second threshold purges on arrival`() {
        assertEquals(171, bodyCachePurgeVersion(purgedForVersion = 169, currentVersion = 171))
        assertEquals(200, bodyCachePurgeVersion(purgedForVersion = 169, currentVersion = 200))
    }

    /** ⛔ Once per threshold. Having purged for 170, nothing purges again at 170, 171, or ever. */
    @Test fun `the second threshold purges once and never again`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 170, currentVersion = 170))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 170, currentVersion = 171))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 170, currentVersion = 999))
    }

    /** A fresh install lands past both thresholds and purges once — an empty cache, once. */
    @Test fun `a fresh install arriving at the second threshold purges exactly once`() {
        assertEquals(170, bodyCachePurgeVersion(purgedForVersion = 0, currentVersion = 170))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 170, currentVersion = 170))
    }

    /** The first threshold keeps behaving exactly as it did, literals on both sides. */
    @Test fun `the first threshold still behaves exactly as before`() {
        assertEquals(168, bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 168))
        assertEquals(169, bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 169))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 167))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 168, currentVersion = 168))
    }

    /** ⛔ The witness. Same version, second start: nothing to do. */
    @Test fun `the same version does not purge again`() {
        assertNull(
            bodyCachePurgeVersion(
                purgedForVersion = BODY_CACHE_PURGE_VERSION,
                currentVersion = BODY_CACHE_PURGE_VERSION,
            ),
        )
    }

    /** A build older than the threshold has nothing to do with it — it is not why the purge exists. */
    @Test fun `a version bump below the threshold does nothing`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 166, currentVersion = 167))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 0, currentVersion = 167))
    }

    /** A downgrade (a sideloaded older build) purges nothing: its own bodies are its own. */
    @Test fun `an older build does not purge`() {
        assertNull(bodyCachePurgeVersion(purgedForVersion = 167, currentVersion = 166))
        assertNull(bodyCachePurgeVersion(purgedForVersion = 169, currentVersion = 166))
    }

    /**
     * ⛔ What the purge is allowed to delete, executed against real in-memory SQLite and built
     * from the very statement the DAO ships (and the very schema the migrations create).
     *
     * The bodies are a cache: dropping them costs one refetch per message read. The message list
     * is not — it is what the app shows when it is offline, and rebuilding it means a full resync
     * of every folder. One clause too few in this statement, run automatically at the first
     * launch after an update, would be indistinguishable from data loss.
     */
    @Test fun `the purge empties the body cache and touches nothing else`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { st ->
                st.executeUpdate(EMAIL_BODIES_CREATE_SQL)
                st.executeUpdate(EMAILS_CREATE_SQL)
                st.executeUpdate(
                    "INSERT INTO email_bodies VALUES('m1','accA','{\"id\":\"m1\"}','{}',1000)",
                )
                st.executeUpdate(
                    "INSERT INTO email_bodies VALUES('m2','accB','{\"id\":\"m2\"}','{}',2000)",
                )
                st.executeUpdate(
                    "INSERT INTO emails VALUES('m1','accA','inbox',NULL,'Weekly digest',NULL," +
                        "NULL,NULL,NULL,0,0,0,1000)",
                )
            }

            db.createStatement().use { it.executeUpdate(BODY_CACHE_CLEAR_SQL) }

            assertEquals("every account's bodies go, not just one", 0, count(db, "email_bodies"))
            assertEquals("the message list must survive the purge", 1, count(db, "emails"))
        }
    }

    private fun count(db: java.sql.Connection, table: String): Int =
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }

    /**
     * The life of one install, played out: restarts, then update after update. One purge per
     * threshold CROSSED — at 168 and at 170 — and nothing on any other launch: not between the
     * two, not on a repeat of the same version, not after the last one.
     *
     * ⚠ The launch list and the expected purges are bare literals. Recomputing the rule here to
     * decide what to expect would make this test a copy of the decision, green whichever way the
     * condition is written.
     */
    @Test fun `the purge happens exactly once per threshold over the life of an install`() {
        var recorded = 0
        val purges = mutableListOf<Int>()
        val launches = listOf(166, 166, 167, 168, 168, 169, 170, 170, 171)
        for (version in launches) {
            bodyCachePurgeVersion(recorded, version)?.let {
                purges += it
                recorded = it
            }
        }
        assertEquals(listOf(168, 170), purges)
    }
}
