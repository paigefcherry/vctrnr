package app.vctrnr.storage

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import app.vctrnr.audio.Wav
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Saves takes as WAV files in the public Recordings folder:
 *
 *     Internal storage/Recordings/vctrnr/2026-11-03 18-42-07/take-001.wav
 *
 * One sub-folder per start→stop session. Files go through MediaStore, so no storage
 * permission is needed, and they show up in My Files, Samsung Voice Recorder's file list,
 * and any audio app. They stay put if the app is uninstalled.
 */
class RecordingStore(private val context: Context) {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vctrnr-save") }

    /** Folder name for a new session, e.g. "2026-11-03 18-42-07". */
    fun newSessionName(): String = LocalDateTime.now().format(SESSION_FORMAT)

    /** Writes the take on a background thread; [onDone] (background thread) gets true on success. */
    fun saveTake(session: String, takeNumber: Int, pcm: ShortArray, sampleRate: Int, onDone: (Boolean) -> Unit) {
        io.execute { onDone(write(session, takeNumber, pcm, sampleRate)) }
    }

    private fun write(session: String, takeNumber: Int, pcm: ShortArray, sampleRate: Int): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, String.format(Locale.ROOT, "take-%03d.wav", takeNumber))
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$RELATIVE_ROOT/$session/")
            put(MediaStore.MediaColumns.TITLE, "vctrnr $session take $takeNumber")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        var uri: Uri? = null
        return try {
            uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { it.write(Wav.encode(pcm, sampleRate)) }
                ?: error("Couldn't open $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Saving take $takeNumber failed", e)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            false
        }
    }

    /**
     * Opens Recordings/vctrnr in a file browser. Tries Samsung My Files first (the S23's
     * built-in file manager), then the system Files app. Returns false if neither could open.
     */
    fun openFolder(): Boolean {
        // Make sure there's something to open, even before the first take is saved.
        runCatching { rootDir.mkdirs() }

        val myFiles = Intent(SAMSUNG_MY_FILES_ACTION)
            .setPackage(SAMSUNG_MY_FILES_PACKAGE)
            .putExtra(SAMSUNG_MY_FILES_START_PATH, rootDir.absolutePath)
        if (launch(myFiles)) return true

        val documentUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$RELATIVE_ROOT")
        val filesApp = Intent(Intent.ACTION_VIEW)
            .setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
        if (launch(filesApp)) return true

        // Last resort: the system Files app at its home screen.
        val filesHome = Intent(Intent.ACTION_VIEW)
            .setDataAndType(DocumentsContract.buildRootUri(EXTERNAL_STORAGE_AUTHORITY, "primary"), DocumentsContract.Root.MIME_TYPE_ITEM)
        return launch(filesHome)
    }

    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to open $intent", e)
        false
    }

    companion object {
        private const val TAG = "vctrnr.Store"
        const val FOLDER_NAME = "vctrnr"

        /** "Recordings/vctrnr" relative to internal storage. */
        val RELATIVE_ROOT = "${Environment.DIRECTORY_RECORDINGS}/$FOLDER_NAME"

        /** Human-readable location for the UI. */
        val DISPLAY_PATH = "Internal storage › ${Environment.DIRECTORY_RECORDINGS} › $FOLDER_NAME"

        @Suppress("DEPRECATION") // Only used to hand a path to the file manager.
        private val rootDir: File
            get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS), FOLDER_NAME)

        private val SESSION_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")

        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val SAMSUNG_MY_FILES_PACKAGE = "com.sec.android.app.myfiles"
        private const val SAMSUNG_MY_FILES_ACTION = "samsung.myfiles.intent.action.LAUNCH_MY_FILES"
        private const val SAMSUNG_MY_FILES_START_PATH = "samsung.myfiles.intent.extra.START_PATH"
    }
}
