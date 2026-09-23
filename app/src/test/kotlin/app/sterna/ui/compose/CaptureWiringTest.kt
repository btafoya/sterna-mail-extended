package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint for "Take a photo" in the composer (#233), not a behaviour test: it reads the
 * manifest, `file_paths.xml` and `ComposeCapture.kt` as text. Holes that apply: (3) a pinned line
 * says nothing about its position in the callback or its execution order; (2) nothing about the
 * expression around a pinned call; (4) nothing here holds
 * the value the saved state actually restores after the process is killed.
 */
class CaptureWiringTest {

    @Test fun `the capture folder is shared through the FileProvider`() {
        val lines = repoFile("app/src/main/res/xml/file_paths.xml").readLines().map { it.trim() }
        val required = "<cache-path name=\"$CAPTURE_DIR\" path=\"$CAPTURE_DIR/\" />"
        assertTrue(
            "file_paths.xml must declare exactly this line, built from CAPTURE_DIR:\n  $required\n" +
                "Without it FileProvider.getUriForFile throws for every photo, and the camera has " +
                "nowhere to write.",
            required in lines,
        )
    }

    @Test fun `the manifest never declares the camera permission`() {
        val offenders = repoFile("app/src/main/AndroidManifest.xml").readLines()
            .filter { "android.permission.CAMERA" in it }
            .map { it.trim() }
        assertEquals(
            "a declared but ungranted CAMERA permission makes Android refuse ACTION_IMAGE_CAPTURE " +
                "with a SecurityException; the camera app holds its own. Found:",
            emptyList<String>(), offenders,
        )
    }

    @Test fun `the photo's target outlives the process through saved state`() {
        val lines = repoFile(SCREEN).readLines().map { it.trim() }
        assertTrue(
            "$SCREEN must hold the photo's file name with exactly this line:\n  $SAVED_TARGET\n" +
                "The camera app often runs long enough for Android to kill this process. A plain " +
                "`remember` or a ViewModel field comes back empty with the result, and the photo " +
                "is never attached.",
            SAVED_TARGET in lines,
        )
        assertTrue(
            "the TakePicture callback must read that saved name back:\n  $READ_BACK",
            READ_BACK in lines,
        )
        assertTrue(
            "the name must be written before the camera opens:\n  $WRITE_TARGET",
            WRITE_TARGET in lines,
        )
    }

    @Test fun `the camera opens only past the offline check`() {
        assertTrue(
            "the gesture must ask the ViewModel first, with exactly this line:\n  $GUARDED_LAUNCH",
            GUARDED_LAUNCH in repoFile(SCREEN).readLines().map { it.trim() },
        )
    }

    @Test fun `stageOutgoing decides by the same rule as the offline check`() {
        assertTrue(
            "stageOutgoing must decide local or upload through stagesAttachmentLocally, the rule " +
                "captureRefusedOffline asks, with exactly this line:\n  $STAGE_RULE",
            STAGE_RULE in repoFile(VIEW_MODEL).readLines().map { it.trim() },
        )
    }

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?.let { File(it, path) }
            ?: error("cannot locate $path from ${File("").absolutePath}")

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeCapture.kt"
        const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        const val WRITE_TARGET = "captureName = file.name"
        const val GUARDED_LAUNCH = "if (viewModel.captureMayLaunch()) {"
        const val STAGE_RULE = "return if (forceLocal || stagesAttachmentLocally(credentials.protocol, _pgpMode.value)) {"
        const val SAVED_TARGET = "var captureName by rememberSaveable { mutableStateOf<String?>(null) }"
        const val READ_BACK = "val name = captureName ?: return@rememberLauncherForActivityResult"
    }
}
