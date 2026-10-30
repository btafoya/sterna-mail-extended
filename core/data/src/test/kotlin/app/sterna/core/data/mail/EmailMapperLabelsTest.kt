package app.sterna.core.data.mail

import app.sterna.core.data.db.ReadAndStar
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `$label:<id>` keywords another JMAP client writes survive the cache (#274), ids as the server
 * wrote them; every other non-standard keyword is still dropped.
 */
class EmailMapperLabelsTest {

    private fun received(keywords: Map<String, Boolean>) = Email(
        id = "e1",
        accountId = "accA",
        subject = "Quarterly figures",
        receivedAt = "2026-10-28T09:00:00Z",
        keywords = keywords,
    )

    @Test fun labelsGoThroughTheRowAndComeBackAsKeywords() {
        val email = received(
            linkedMapOf(
                "\$seen" to true,
                "\$label:red" to true,
                "\$junk" to true,
                "\$label:work/clients" to true,
                "\$flagged" to true,
                "\$label:Read Later" to true,
            ),
        )

        val back = email.toEntity("accA", "inbox").toEmail()

        assertEquals(
            "a label lost at the row is a label the list and the message never show, and that Sterna " +
                "cannot hand back unchanged; `\$junk` has no column and is still dropped",
            mapOf(
                "\$seen" to true,
                "\$flagged" to true,
                "\$label:red" to true,
                "\$label:work/clients" to true,
                "\$label:Read Later" to true,
            ),
            back.keywords,
        )
        assertEquals(
            "the server's order, case and spaces are kept as written",
            listOf("\$label:red", "\$label:work/clients", "\$label:Read Later"),
            back.keywords.keys.filter { it.startsWith("\$label:") },
        )
    }

    @Test fun aLabelHeldFalseIsNotALabel() {
        val back = received(mapOf("\$label:red" to false, "\$label:blue" to true)).toEntity("accA", "inbox").toEmail()

        assertEquals(mapOf("\$label:blue" to true), back.keywords)
    }

    @Test fun aLabelledRowReachesTheCachedBodyThroughTheShippedRead() {
        JdbcMailCache().use { cache ->
            cache.upsert(listOf(received(mapOf("\$seen" to true, "\$label:red" to true, "\$label:Read Later" to true)).toEntity("accA", "inbox")))

            val row = cache.readAndStarOf("accA", "e1")

            assertEquals(
                "the read behind `readAndStarOf` no longer hands back the row's labels: a cached body " +
                    "reconciled against it never shows a label set since it was fetched",
                ReadAndStar(seen = true, flagged = false, answered = false, forwarded = false, labelsJson = "[\"red\",\"Read Later\"]"),
                row,
            )
            val cached = MessageBody(
                email = received(mapOf("\$seen" to true)).copy(
                    textBody = listOf(EmailBodyPart(partId = "text")),
                    bodyValues = mapOf("text" to EmailBodyValue(value = "figures attached")),
                ),
                inlineImages = emptyMap(),
            )
            assertEquals(
                mapOf("\$seen" to true, "\$label:red" to true, "\$label:Read Later" to true),
                reconcileCachedFlags(cached = cached, row = row).email.keywords,
            )
        }
    }

    @Test fun aMessageWithoutLabelsStoresAnEmptyList() {
        assertEquals("[]", received(mapOf("\$seen" to true)).toEntity("accA", "inbox").labelsJson)
    }
}
