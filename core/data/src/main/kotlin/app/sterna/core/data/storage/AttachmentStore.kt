package app.sterna.core.data.storage

import app.sterna.core.jmap.model.EmailBodyPart
import java.io.File
import java.security.MessageDigest

/**
 * The bytes of message parts kept on the phone, keyed by (account, message, part), and read before
 * the network by every download of a part (#157). Under `filesDir`, never `cacheDir`: Android
 * empties a cache directory on its own, and a part downloaded ahead to be read offline would be gone
 * exactly when needed. Bytes are kept as the server sent them, so an encrypted part stays
 * ciphertext.
 *
 * Layout: `<root>/<hex(accountId)>/<sha256(emailId)>/` holds `id` (the emailId, which a folder
 * purge and the orphan sweep read back), one file per part named `sha256(partKey)`, a `.skip`
 * beside a part the download ahead gave up on, and `settled` once every part of the message is
 * either stored or given up. A part, a skip and a mark die with their message's directory.
 */
class AttachmentStore(private val root: File) {

    /** Never an empty file: no part is stored empty, so one is a write that went wrong. */
    fun read(accountId: String, emailId: String, partKey: String): ByteArray? =
        partFile(accountId, emailId, partKey).takeIf { it.isFile && it.length() > 0 }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

    fun has(accountId: String, emailId: String, partKey: String): Boolean =
        partFile(accountId, emailId, partKey).let { it.isFile && it.length() > 0 }

    /** Written beside, then renamed: a read never sees a part cut short by a death mid-write. An
     *  empty [bytes] is a read that failed (`attachmentCouldNotBeRead`) and is not stored. */
    fun write(accountId: String, emailId: String, partKey: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val target = partFile(accountId, emailId, partKey)
        prepared(accountId, emailId)
        val staging = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            staging.writeBytes(bytes)
            if (!staging.renameTo(target)) error("Couldn't store the part.")
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun isSkipped(accountId: String, emailId: String, partKey: String): Boolean =
        File(messageDir(accountId, emailId), "${hash(partKey)}$SKIP_SUFFIX").isFile

    fun markSkipped(accountId: String, emailId: String, partKey: String) {
        File(prepared(accountId, emailId), "${hash(partKey)}$SKIP_SUFFIX").createNewFile()
    }

    fun isSettled(accountId: String, emailId: String): Boolean =
        File(messageDir(accountId, emailId), SETTLED).isFile

    fun markSettled(accountId: String, emailId: String) {
        File(prepared(accountId, emailId), SETTLED).createNewFile()
    }

    /** The emailIds the account has anything stored under. */
    fun emailIds(accountId: String): List<String> =
        accountDir(accountId).listFiles()?.mapNotNull { dir ->
            File(dir, ID).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        } ?: emptyList()

    fun delete(accountId: String, emailId: String) {
        messageDir(accountId, emailId).deleteRecursively()
    }

    /** One IMAP folder's messages: the ids [folderIdPrefix] followed by a UID, and no other folder
     *  whose name merely starts the same way. */
    fun deleteForFolder(accountId: String, folderIdPrefix: String) {
        for (emailId in emailIds(accountId)) {
            if (isOfFolder(emailId, folderIdPrefix)) delete(accountId, emailId)
        }
    }

    /** The orphan sweep's half for parts: a message whose list row is gone takes its parts along,
     *  as `deleteOrphans` does for its body. [listed] answers which of the ids it is given still
     *  have a row. */
    suspend fun deleteOrphans(
        accountId: String,
        spared: Collection<String>,
        listed: suspend (List<String>) -> Collection<String>,
    ) {
        val stored = emailIds(accountId)
        if (stored.isEmpty()) return
        val orphans = attachmentStoreOrphans(stored, listed(stored).toSet(), spared)
        orphans.forEach { delete(accountId, it) }
    }

    fun clearAccount(accountId: String) {
        accountDir(accountId).deleteRecursively()
    }

    fun clearAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun accountIds(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { unhex(it.name) } ?: emptyList()

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun accountDir(accountId: String) = File(root, hex(accountId))

    private fun messageDir(accountId: String, emailId: String) = File(accountDir(accountId), hash(emailId))

    private fun partFile(accountId: String, emailId: String, partKey: String) =
        File(messageDir(accountId, emailId), hash(partKey))

    private fun prepared(accountId: String, emailId: String): File {
        val dir = messageDir(accountId, emailId).apply { mkdirs() }
        val id = File(dir, ID)
        if (!id.isFile) id.writeText(emailId)
        return dir
    }

    private companion object {
        const val ID = "id"
        const val SETTLED = "settled"
        const val SKIP_SUFFIX = ".skip"

        fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        fun hex(value: String): String = value.toByteArray().joinToString("") { "%02x".format(it) }

        fun unhex(name: String): String? = runCatching {
            if (name.length % 2 != 0) return null
            String(ByteArray(name.length / 2) { name.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }.getOrNull()
    }
}

/** The store's directory under `filesDir`. */
const val ATTACHMENT_STORE_DIR = "attachment-store"

/** What names a part in the store: its section or JMAP part id, else its blob. Null for a part
 *  with neither, which is never stored. */
fun storedPartKeyOf(part: EmailBodyPart): String? =
    part.partId?.let { "part:$it" } ?: part.blobId?.let { "blob:$it" }

internal fun isOfFolder(emailId: String, folderIdPrefix: String): Boolean {
    if (!emailId.startsWith(folderIdPrefix)) return false
    val rest = emailId.substring(folderIdPrefix.length)
    return rest.isNotEmpty() && rest.all { it.isDigit() }
}

internal fun attachmentStoreOrphans(
    stored: Collection<String>,
    listed: Set<String>,
    spared: Collection<String>,
): List<String> = stored.filter { it !in listed && it !in spared }
