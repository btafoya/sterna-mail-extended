package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SubmissionIdentity.choose], the rule executed, with its arguments pinned.
 */
class SubmissionIdentityTest {

    private val house = Identity(id = "i-house", name = "Théo", email = "theo@tb.pro")
    private val other = Identity(id = "i-other", name = "Théo B", email = "contact@tb.pro")

    /** The ordinary case: the chosen address IS a server identity, so the server derives it all. */
    @Test fun `a chosen address the server knows submits under it and names no envelope`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "contact@tb.pro",
            serverIdentities = listOf(house, other),
            onBehalf = false,
        )!!

        assertEquals("i-other", choice.identity.id)
        assertNull("the server derives the envelope from the matched identity", choice.envelopeMailFrom)
    }

    /**
     * The defect. The typed address has no server identity: the submission still goes out under
     */
    @Test fun `a chosen address the server does not know names it in the envelope`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo@mydomain.com",
            serverIdentities = listOf(house, other),
            onBehalf = false,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertEquals("theo@mydomain.com", choice.envelopeMailFrom)
    }

    /**
     * A delegated sub-account (issue #31) is the case where From and envelope differ on purpose,
     * verified against Stalwart. An envelope here would break a send that works today.
     */
    @Test fun `a delegated send never names an envelope`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo@mydomain.com",
            serverIdentities = listOf(house, other),
            onBehalf = true,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertNull("issue #31: the login's identity supplies the envelope on purpose", choice.envelopeMailFrom)
    }

    /** No chosen address: the default identity, exactly as before, and nothing named. */
    @Test fun `no chosen address falls back to the first identity with no envelope`() {
        val fromNull = SubmissionIdentity.choose(null, listOf(house, other), onBehalf = false)!!
        assertEquals("i-house", fromNull.identity.id)
        assertNull(fromNull.envelopeMailFrom)

        val fromBlank = SubmissionIdentity.choose("   ", listOf(house, other), onBehalf = false)!!
        assertEquals("i-house", fromBlank.identity.id)
        assertNull("a blank address chose nothing; it must not become a mailFrom", fromBlank.envelopeMailFrom)
    }

    /** An account with no server identity at all: the caller raises its own error. */
    @Test fun `an account with no server identity yields null`() {
        assertNull(SubmissionIdentity.choose("theo@tb.pro", emptyList(), onBehalf = false))
        assertNull(SubmissionIdentity.choose(null, emptyList(), onBehalf = false))
    }

    /**
     * Case and surrounding spaces are not a different address. `StoredAccount` dedupes
     */
    @Test fun `matching trims both sides and ignores case`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = " Ann@Example.ORG ",
            serverIdentities = listOf(house, Identity(id = "i-ann", email = "ann@example.org")),
            onBehalf = false,
        )!!

        assertEquals("i-ann", choice.identity.id)
        assertNull(choice.envelopeMailFrom)
    }

    /** …and the server's side of the comparison is trimmed too. */
    @Test fun `a server identity stored with spaces still matches`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "ann@example.org",
            serverIdentities = listOf(house, Identity(id = "i-ann", email = " ann@example.org ")),
            onBehalf = false,
        )!!

        assertEquals("i-ann", choice.identity.id)
        assertNull(choice.envelopeMailFrom)
    }

    /** The chosen address reaches the envelope trimmed, a `mail from:< a@b >` is not an address. */
    @Test fun `the envelope address is trimmed`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "  theo@mydomain.com  ",
            serverIdentities = listOf(house),
            onBehalf = false,
        )!!

        assertEquals("theo@mydomain.com", choice.envelopeMailFrom)
    }

    @Test fun `a sub-address of a server identity submits under that identity with no envelope`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo+news@tb.pro",
            serverIdentities = listOf(house, other),
            onBehalf = false,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertNull("issue #212: Stalwart answers forbiddenFrom to an envelope naming the tag",
            choice.envelopeMailFrom)
    }

    @Test fun `a sub-address on another domain still names an envelope`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo+news@other.com",
            serverIdentities = listOf(house, other),
            onBehalf = false,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertEquals("issue #172 still applies: another domain is another address",
            "theo+news@other.com", choice.envelopeMailFrom)
    }

    @Test fun `a sub-address with several plus signs folds at the first one`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo+a+b@tb.pro",
            serverIdentities = listOf(house, other),
            onBehalf = false,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertNull(choice.envelopeMailFrom)
    }

    @Test fun `an address whose local part is only a tag folds to nothing`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "+tag@tb.pro",
            serverIdentities = listOf(house, other),
            onBehalf = false,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertEquals("an empty local part is nobody's sub-address; no fixture pins the guard itself",
            "+tag@tb.pro", choice.envelopeMailFrom)
    }

    @Test fun `a sub-address match trims and ignores case too`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = " Theo+News@TB.pro ",
            serverIdentities = listOf(house, other),
            onBehalf = false,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertNull(choice.envelopeMailFrom)
    }

    @Test fun `a server identity that is itself a sub-address wins over the base it looks like`() {
        val tagged = Identity(id = "i-tag", email = "theo+x@tb.pro")
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo+x@tb.pro",
            serverIdentities = listOf(house, tagged),
            onBehalf = false,
        )!!

        assertEquals("the exact match wins; nothing is measured on what a server derives from it",
            "i-tag", choice.identity.id)
        assertNull(choice.envelopeMailFrom)
    }

    @Test fun `a delegated send of a sub-address still names no envelope`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo+news@tb.pro",
            serverIdentities = listOf(house, other),
            onBehalf = true,
        )!!

        assertEquals("i-house", choice.identity.id)
        assertNull("#31 and the fold agree here; the guard alone is pinned where nothing folds",
            choice.envelopeMailFrom)
    }

    @Test fun `a sub-address submits under the identity it matched, not the first one`() {
        val choice = SubmissionIdentity.choose(
            fromEmail = "theo+news@tb.pro",
            serverIdentities = listOf(other, house),
            onBehalf = false,
        )!!

        assertEquals("the fold must yield its own match, not `serverIdentities.first()`",
            "i-house", choice.identity.id)
        assertNull(choice.envelopeMailFrom)
    }
}
