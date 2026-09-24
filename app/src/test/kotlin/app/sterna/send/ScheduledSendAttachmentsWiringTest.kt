package app.sterna.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, same shape and same reason as [ScheduledSendFireWiringTest]: the worker needs a
 * `Context` and WorkManager. What it calls is executed in `ScheduledSendAttachmentsTest`
 * (core/data); only the hand-over is pinned here, argument included.
 */
class ScheduledSendAttachmentsWiringTest {

    private fun linesOf(file: File): List<String> =
        file.readText().lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.isEmpty() }

    @Test fun `the worker reads the row's attachments inside the try and hands them to the outbox`() {
        val lines = linesOf(WORKER)
        val tryAt = lines.indexOf("return try {")
        assertTrue("no line reads exactly 'return try {' in the worker", tryAt >= 0)
        val handOver = lines.indexOf("repo.sending.enqueueSend(")
        assertTrue("no line reads exactly 'repo.sending.enqueueSend(' in the worker", handOver > tryAt)
        assertEquals(
            "the attachments are read between the try and the hand-over: above the try, a JMAP " +
                "upload that fails escapes doWork() uncounted instead of being retried and given " +
                "up through ScheduledSendRetry",
            listOf("val attachments = repo.sending.scheduledSendAttachments(credentials, row)"),
            lines.subList(tryAt + 1, handOver),
        )
        val call = lines.drop(handOver).takeWhile { it != ")" }
        assertEquals(
            "enqueueSend must be handed the row's attachments: without them the message leaves " +
                "at its hour stripped of every file, and the row that held them is then deleted",
            listOf("attachments = attachments,"),
            call.filter { it.startsWith("attachments") },
        )
    }

    @Test fun `the cancellation removes the row through the call that removes its files`() {
        assertEquals(
            "the cancel's row deletion must be MailSender.deleteScheduledSend, the one call that " +
                "also deletes the row's attachment directory",
            listOf("deleteRow = { repo.sending.deleteScheduledSend(it) },"),
            linesOf(CANCEL_CALLER).filter { it.startsWith("deleteRow") },
        )
    }

    private companion object {
        val WORKER = repoFile("app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt")
        val CANCEL_CALLER = repoFile("app/src/main/kotlin/app/sterna/ui/scheduled/ScheduledSendsViewModel.kt")

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot find $path from ${File("").absolutePath} — this test reads sources as " +
                        "text and needs a working directory inside the checkout",
                )
    }
}
