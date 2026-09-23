package app.sterna.ui.compose

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.pgp.PgpMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * Whether an outgoing attachment is staged on the phone instead of uploaded to the server. IMAP has
 * no blob store; with PGP on, JMAP stages locally too, because the attachment travels inside the
 * signed or encrypted entity and uploading a plaintext blob would leak it for nothing.
 */
internal fun stagesAttachmentLocally(protocol: MailProtocol, pgpMode: PgpMode): Boolean =
    protocol == MailProtocol.IMAP || pgpMode != PgpMode.OFF

/**
 * Whether "Take a photo" must say "offline" instead of opening the camera. Asked before the camera
 * opens and not after: a refusal once the photo is taken throws the photo away. Asked through
 * [stagesAttachmentLocally], the rule `stageOutgoing` follows, so the two cannot disagree on which
 * attachment needs the network.
 */
internal fun captureRefusedOffline(protocol: MailProtocol, pgpMode: PgpMode, online: Boolean): Boolean =
    !online && !stagesAttachmentLocally(protocol, pgpMode)

/** Under `cacheDir`, declared as a `cache-path` in `file_paths.xml` so the camera app can write there. */
internal const val CAPTURE_DIR = "capture"

internal fun captureFileName(id: UUID): String = "photo-$id.jpg"

/** A camera app can answer "taken" and leave the file empty or absent; that is a cancel. */
internal fun captureLanded(taken: Boolean, size: Long): Boolean = taken && size > 0

internal const val CAPTURE_BINDING_WAIT_MS = 10_000L

/**
 * Whether the composer has bound what a photo's pose depends on: the sending account, the
 * attachments a reopened message brings back, and its PGP mode. After a process death the camera's
 * result arrives at the first composition, before `prepare` has run. Posed then, the photo goes
 * under the default account, is uploaded in clear past an encrypt-by-default padlock, and is
 * overwritten by the restored attachment list.
 *
 * `prepare` tracks a job launched on `Dispatchers.Main`, posted rather than immediate: it completes
 * once `prepare` has returned, so every job launched inside it is tracked before the binding can read
 * as settled. A pose still unsettled after [CAPTURE_BINDING_WAIT_MS] (an OpenPGP provider that never
 * answers) is staged on the phone: a mode that may be encrypt must never meet a plaintext upload.
 */
internal class ComposerBinding {
    private data class State(val opened: Boolean, val pending: Int)

    private val state = MutableStateFlow(State(opened = false, pending = 0))

    fun open() = state.update { it.copy(opened = true) }

    /** Keeps the binding unsettled until [job] completes, however it ends. */
    fun track(job: Job) {
        state.update { it.copy(pending = it.pending + 1) }
        job.invokeOnCompletion { state.update { it.copy(pending = it.pending - 1) } }
    }

    /** True once opened with nothing pending; false if [timeoutMs] passed first. */
    suspend fun awaitSettled(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { it.opened && it.pending == 0 } } != null
}

/**
 * The "Take a photo" gesture: checks [ComposeViewModel.captureMayLaunch], opens the camera on a fresh
 * file under [CAPTURE_DIR], attaches what comes back, and deletes the file in every outcome.
 */
@Composable
internal fun rememberTakePhoto(viewModel: ComposeViewModel): () -> Unit {
    val context = LocalContext.current
    // Saved state, not the ViewModel: the camera app often runs long enough for Android to kill this
    // process, and only saved state comes back with the result.
    var captureName by rememberSaveable { mutableStateOf<String?>(null) }
    // No CAMERA permission in the manifest: declared but not granted, Android refuses
    // ACTION_IMAGE_CAPTURE with a SecurityException. The camera app holds its own.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val name = captureName ?: return@rememberLauncherForActivityResult
        captureName = null
        val file = File(File(context.cacheDir, CAPTURE_DIR), name)
        val uri = if (captureLanded(taken, file.length())) runCatching { captureUri(context, file) }.getOrNull() else null
        if (uri != null) viewModel.attachCapture(uri) { file.delete() } else file.delete()
    }
    return {
        if (viewModel.captureMayLaunch()) {
            val file = File(File(context.cacheDir, CAPTURE_DIR).apply { mkdirs() }, captureFileName(UUID.randomUUID()))
            captureName = file.name
            try {
                camera.launch(captureUri(context, file))
            } catch (e: Exception) {
                // `ActivityNotFoundException` with no camera app, `IllegalArgumentException` from
                // `getUriForFile`: a tap on a menu entry must never take the composer down.
                captureName = null
                file.delete()
                viewModel.reportCaptureLaunchFailed(e)
            }
        }
    }
}

private fun captureUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
