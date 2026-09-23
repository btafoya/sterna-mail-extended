package app.sterna.core.data.settings

import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OutgoingDateZones], run: what a sending path gets when it asks. Below, the one source lint of
 * this switch in core/data, the last resort: `DataFactory` needs a Room database and an Android
 * `Context`, so nothing on the JVM can build it and watch the settings reach the senders. What the
 * senders do with the answer is executed by `ImapSentDateFollowsTheSwitchTest` and
 * `MailSenderDeliversOnBothProtocolsTest`.
 */
class OutgoingDateZonesTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test fun `a late first emission is awaited, never replaced by the default`() = runBlocking {
        val stored = flow {
            delay(150)
            emit(true)
        }
        assertEquals(ZoneOffset.UTC, OutgoingDateZones(stored, deviceZone = { kolkata }).atSend())
    }

    @Test fun `the device zone is asked at send time too`() = runBlocking {
        var device: ZoneId = kolkata
        val zones = OutgoingDateZones(MutableStateFlow(false), deviceZone = { device })
        assertEquals(kolkata, zones.atSend())
        device = ZoneId.of("America/Sao_Paulo")
        assertEquals(ZoneId.of("America/Sao_Paulo"), zones.atSend())
    }

    @Test fun `the factory hands the stored switch to both senders, as one instance`() {
        val factory = code(repoFile("core/data/src/main/kotlin/app/sterna/core/data/DataFactory.kt"))
        assertEquals(
            listOf("val outgoingDates = OutgoingDateZones(settings?.quotedDatesUtc ?: flowOf(false))"),
            factory.filter { "OutgoingDateZones(" in it },
        )
        assertEquals(
            "handed once to ImapMailService and once to MailRepository",
            listOf("outgoingDates = outgoingDates,", "outgoingDates = outgoingDates,"),
            factory.filter { "outgoingDates" in it && !it.startsWith("val ") },
        )
        val repository = code(repoFile("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"))
        assertEquals(
            "the repository's parameter, then its hand-over to MailSender",
            listOf("outgoingDates: OutgoingDateZones = OutgoingDateZones.DEVICE,", "outgoingDates = outgoingDates,"),
            repository.filter { "outgoingDates" in it },
        )
    }

    private fun code(file: File): List<String> =
        file.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull { it.isFile }
            ?: error("$path not found above ${File("").absoluteFile}")
}
