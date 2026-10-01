package app.sterna.core.data.text

import app.sterna.core.data.text.AttachmentKind.ARCHIVE
import app.sterna.core.data.text.AttachmentKind.AUDIO
import app.sterna.core.data.text.AttachmentKind.CALENDAR
import app.sterna.core.data.text.AttachmentKind.CONTACT
import app.sterna.core.data.text.AttachmentKind.DOCUMENT
import app.sterna.core.data.text.AttachmentKind.IMAGE
import app.sterna.core.data.text.AttachmentKind.MESSAGE
import app.sterna.core.data.text.AttachmentKind.OTHER
import app.sterna.core.data.text.AttachmentKind.PDF
import app.sterna.core.data.text.AttachmentKind.PRESENTATION
import app.sterna.core.data.text.AttachmentKind.SPREADSHEET
import app.sterna.core.data.text.AttachmentKind.TEXT
import app.sterna.core.data.text.AttachmentKind.VIDEO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Executes [attachmentKind] against pairs written by hand. The device's extension table is
 * injected, as for [attachmentMimeType], and holds only what each case needs.
 */
class AttachmentKindTest {

    private val table = mapOf(
        "jpg" to "image/jpeg",
        "pdf" to "application/pdf",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "mp3" to "audio/mpeg",
    )
    private val lookup: (String) -> String? = { table[it] }
    private val nothing: (String) -> String? = { null }

    private val declared = listOf(
        "image/png" to IMAGE,
        "image/svg+xml" to IMAGE,
        "application/pdf" to PDF,
        "audio/mpeg" to AUDIO,
        "audio/ogg" to AUDIO,
        "video/mp4" to VIDEO,
        "video/quicktime" to VIDEO,
        "application/zip" to ARCHIVE,
        "application/x-zip-compressed" to ARCHIVE,
        "application/x-7z-compressed" to ARCHIVE,
        "application/x-rar-compressed" to ARCHIVE,
        "application/vnd.rar" to ARCHIVE,
        "application/gzip" to ARCHIVE,
        "application/x-gzip" to ARCHIVE,
        "application/x-tar" to ARCHIVE,
        "application/x-bzip2" to ARCHIVE,
        "application/x-xz" to ARCHIVE,
        "text/plain" to TEXT,
        "text/markdown" to TEXT,
        "text/html" to TEXT,
        "application/msword" to DOCUMENT,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to DOCUMENT,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template" to DOCUMENT,
        "application/vnd.ms-word.document.macroEnabled.12" to DOCUMENT,
        "application/vnd.oasis.opendocument.text" to DOCUMENT,
        "application/vnd.oasis.opendocument.text-template" to DOCUMENT,
        "application/rtf" to DOCUMENT,
        "text/rtf" to DOCUMENT,
        "application/vnd.ms-excel" to SPREADSHEET,
        "application/vnd.ms-excel.sheet.macroEnabled.12" to SPREADSHEET,
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to SPREADSHEET,
        "application/vnd.oasis.opendocument.spreadsheet" to SPREADSHEET,
        "text/csv" to SPREADSHEET,
        "application/vnd.ms-powerpoint" to PRESENTATION,
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to PRESENTATION,
        "application/vnd.oasis.opendocument.presentation" to PRESENTATION,
        "message/rfc822" to MESSAGE,
        "text/calendar" to CALENDAR,
        "application/ics" to CALENDAR,
        "text/vcard" to CONTACT,
        "text/x-vcard" to CONTACT,
        "application/x-unheard-of" to OTHER,
    )

    @Test fun `every declared type falls into its kind`() {
        val wrong = declared.mapNotNull { (type, kind) ->
            attachmentKind(type, "fichier", nothing).takeIf { it != kind }?.let { "$type -> $it, expected $kind" }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun `every kind is reached by at least one case`() {
        assertEquals(AttachmentKind.entries.toSet(), declared.map { it.second }.toSet())
    }

    /** A calendar, a contact, a CSV and an RTF are text to the type, and must not fall into it. */
    @Test fun `the particular text types win over the text family`() {
        assertEquals(CALENDAR, attachmentKind("text/calendar; method=REQUEST", "invite.ics", nothing))
        assertEquals(CONTACT, attachmentKind("text/vcard", "carte.vcf", nothing))
        assertEquals(SPREADSHEET, attachmentKind("text/csv", "export.csv", nothing))
        assertEquals(DOCUMENT, attachmentKind("text/rtf", "lettre.rtf", nothing))
    }

    @Test fun `parameters and case do not hide the type`() {
        assertEquals(PDF, attachmentKind("Application/PDF; name=rapport.pdf", "rapport.pdf", nothing))
        assertEquals(TEXT, attachmentKind("TEXT/PLAIN; charset=utf-8", "notes.txt", nothing))
        assertEquals(CALENDAR, attachmentKind("Text/Calendar; method=REQUEST", null, nothing))
    }

    @Test fun `a generic declaration is answered by the extension`() {
        assertEquals(SPREADSHEET, attachmentKind("application/octet-stream", "budget.xlsx", lookup))
        assertEquals(DOCUMENT, attachmentKind("application/octet-stream", "Lettre.DOCX", lookup))
        assertEquals(AUDIO, attachmentKind("binary/octet-stream", "bulletin.mp3", lookup))
    }

    @Test fun `no declared type is answered by the extension`() {
        assertEquals(IMAGE, attachmentKind(null, "photo.jpg", lookup))
        assertEquals(PDF, attachmentKind("", "rapport.pdf", lookup))
    }

    /** These three are named by this app even where the device's table knows nothing of them. */
    @Test fun `a message, an invitation and a card are recognised by name alone`() {
        assertEquals(MESSAGE, attachmentKind("application/octet-stream", "transfert.eml", nothing))
        assertEquals(CALENDAR, attachmentKind(null, "invitation.ICS", nothing))
        assertEquals(CONTACT, attachmentKind("application/octet-stream", "carte.vcf", nothing))
    }

    /** A declared type that says something is believed, as the viewer believes it. */
    @Test fun `a recognised declared type is not overruled by the name`() {
        assertEquals(TEXT, attachmentKind("text/plain", "notes.pdf", lookup))
        assertEquals(IMAGE, attachmentKind("image/jpeg", "photo.docx", lookup))
    }

    /** A declared type that says nothing to us leaves the name to speak. */
    @Test fun `an unrecognised declared type lets the name speak`() {
        assertEquals(PDF, attachmentKind("application/x-unheard-of", "rapport.pdf", lookup))
        assertEquals(MESSAGE, attachmentKind("application/x-unheard-of", "transfert.eml", nothing))
    }

    @Test fun `nothing to go on is other`() {
        assertEquals(OTHER, attachmentKind(null, null, lookup))
        assertEquals(OTHER, attachmentKind("application/octet-stream", "LISEZMOI", lookup))
        assertEquals(OTHER, attachmentKind("application/octet-stream", "donnees.xyz", lookup))
        assertEquals(OTHER, attachmentKind("*/*", "nom.", lookup))
    }

    @Test fun `the extension label is the extension in capitals`() {
        assertEquals("XYZ", attachmentExtensionLabel("donnees.xyz"))
        assertEquals("7Z", attachmentExtensionLabel("Dossier.7z "))
        assertEquals("TAR", attachmentExtensionLabel("sauvegarde.TAR"))
    }

    @Test fun `no extension, no label`() {
        assertNull(attachmentExtensionLabel("LISEZMOI"))
        assertNull(attachmentExtensionLabel("nom."))
        assertNull(attachmentExtensionLabel("nom.pd f"))
        assertNull(attachmentExtensionLabel(""))
        assertNull(attachmentExtensionLabel(null))
    }
}
