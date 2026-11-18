package app.sterna

import app.sterna.core.data.account.ACCOUNT_PHOTO_DIR
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The account photos stay out of an Android backup and a device transfer, in all three blocks,
 * under the directory name the store writes to. Read as text: a rule file is not executed here, and
 * backups are off anyway (`android:allowBackup="false"`); these rules are the backstop.
 */
class AccountPhotoBackupRulesTest {

    private fun res(path: String): String {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("cannot locate the checkout from ${File("").absolutePath}")
        return File(root, "app/src/main/res/xml/$path").readText()
    }

    private val rule = """<exclude domain="file" path="$ACCOUNT_PHOTO_DIR/" />"""

    @Test fun `the photo directory is excluded from the backup and from both blocks of the transfer rules`() {
        assertEquals(
            "backup_rules.xml must exclude the account photo directory, `$rule`",
            1,
            res("backup_rules.xml").split(rule).size - 1,
        )
        assertEquals(
            "data_extraction_rules.xml must exclude it in cloud-backup AND device-transfer",
            2,
            res("data_extraction_rules.xml").split(rule).size - 1,
        )
    }
}
