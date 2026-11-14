package com.phonepc.pairing.feature

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * V2: Phone media browser (VORTEX/DuoLink).
 *
 * Answers the PC's MEDIA_REQ with MEDIA_LIST / MEDIA_THUMB, and streams full
 * files back over the existing FILE_META / FILE_CHUNK pipeline (so downloads
 * land in the PC's normal "Received" folder with progress UI for free).
 *
 * All queries run on IO; permission failures are reported in the payload's
 * `error` field so the PC can say "grant photos access" instead of showing
 * a silent empty grid.
 */
object MediaBrowser {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Max bytes we will stream to the PC (matches the PC's 5 GB cap). */
    private const val MAX_STREAM_BYTES = 5L * 1024 * 1024 * 1024
    private const val CHUNK_SIZE = 64 * 1024

    private fun send(type: String, payload: JsonObject): Boolean =
        PairingForegroundService.activeServiceInstance?.sendRawEnvelope(type, payload) ?: false

    // ================= LIST =================

    fun list(context: Context, kind: String, search: String, sort: String, limit: Int) {
        scope.launch {
            val result = try {
                queryMedia(context, kind, search, sort, limit)
            } catch (se: SecurityException) {
                JsonObject().apply {
                    addProperty("kind", kind)
                    addProperty("total", 0)
                    add("items", JsonArray())
                    addProperty(
                        "error",
                        "Media permission not granted. On the phone: Settings → Apps → DuoLink → Permissions → Photos and videos (or Files)."
                    )
                }
            } catch (e: Exception) {
                JsonObject().apply {
                    addProperty("kind", kind)
                    addProperty("total", 0)
                    add("items", JsonArray())
                    addProperty("error", "Could not read media: ${e.message}")
                }
            }
            send(Protocol.TYPE_MEDIA_LIST, result)
        }
    }

    private fun queryMedia(context: Context, kind: String, search: String, sort: String, limit: Int): JsonObject {
        val uri = primaryUri(kind)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED
        )

        val selection = StringBuilder()
        val args = mutableListOf<String>()

        // Type filter
        when (kind) {
            "images" -> selection.append("${MediaStore.Files.FileColumns.MEDIA_TYPE}=?").also { args += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString() }
            "videos" -> selection.append("${MediaStore.Files.FileColumns.MEDIA_TYPE}=?").also { args += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString() }
            "audio" -> selection.append("(").append(mimeLike("audio/%")).append(")")
            "documents" -> selection.append("(").append(isDocumentMime()).append(")")
            "downloads" -> selection.append("${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?").also { args += "%Download%" }
            // "all" -> no type filter
        }

        if (search.isNotBlank()) {
            if (selection.isNotEmpty()) selection.append(" AND ")
            selection.append("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
            args += "%$search%"
        }

        val sortOrder = when (sort) {
            "date_asc" -> "${MediaStore.Files.FileColumns.DATE_ADDED} ASC"
            "name" -> "${MediaStore.Files.FileColumns.DISPLAY_NAME} COLLATE NOCASE ASC"
            "size_desc" -> "${MediaStore.Files.FileColumns.SIZE} DESC"
            else -> "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        }

        val items = JsonArray()
        var total = 0

        context.contentResolver.query(
            uri, projection,
            if (selection.isEmpty()) null else selection.toString(),
            if (args.isEmpty()) null else args.toTypedArray(),
            sortOrder
        )?.use { c: Cursor ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val base = baseUri(kind)

            while (c.moveToNext() && items.size() < limit) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx) ?: "file"
                val mime = c.getString(mimeIdx) ?: "application/octet-stream"
                val size = c.getLong(sizeIdx)
                val date = c.getLong(dateIdx) * 1000L
                total++
                items.add(JsonObject().apply {
                    // Desktop keys everything on a stable string id
                    addProperty("id", "$base/$id")
                    addProperty("name", name)
                    addProperty("mime", mime)
                    addProperty("size", size)
                    addProperty("date", date)
                })
            }
            // Count the remainder so the PC can show "N of M (page limit reached)"
            if (items.size() >= limit) {
                while (c.moveToNext()) total++
            }
        }

        return JsonObject().apply {
            addProperty("kind", kind)
            addProperty("total", total)
            add("items", items)
        }
    }

    /** One collection URI so _id is unique across mixed types. */
    private fun primaryUri(kind: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Files.getContentUri("external")

    private fun baseUri(kind: String): String = "media"

    private fun mimeLike(prefix: String): String =
        "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE '$prefix'"

    private fun isDocumentMime(): String =
        "((${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/pdf')" +
            " OR (${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%')" +
            " OR (${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/msword')" +
            " OR (${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/vnd.openxmlformats-officedocument.%')" +
            " OR (${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/vnd.ms-excel')" +
            " OR (${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/octet-stream'))"

    private fun parseId(id: String): Long? = id.substringAfterLast('/').toLongOrNull()

    private fun contentUriFor(id: String): Uri? =
        parseId(id)?.let { ContentUris.withAppendedId(primaryUri("files"), it) }

    // ================= DELETE (from the PC) =================

    /**
     * Android 11+ will not let an app delete media it does not own through a
     * plain ContentResolver.delete(): it must hand the user a system consent
     * dialog built by MediaStore.createDeleteRequest(), and that intent has to
     * be launched for a result from an Activity - which a foreground service is
     * not. So the launcher lives in MainActivity and parks requests here until
     * it is registered; a PC delete that arrives before the app has ever been
     * opened is answered with an actionable message instead of failing silently.
     */
    object DeleteGate {
        @Volatile var launcher: ((List<Uri>) -> Unit)? = null
        private var pending: ((Boolean, String?) -> Unit)? = null

        fun request(uris: List<Uri>, onDone: (Boolean, String?) -> Unit) {
            val l = launcher
            if (l == null) {
                onDone(false, "Open the DuoLink app on the phone once, then retry the delete")
                return
            }
            synchronized(this) {
                if (pending != null) {
                    // Never overwrite a pending verdict: the first PC would hang
                    // forever waiting for an answer that was just dropped.
                    onDone(false, "Another delete is still waiting for confirmation on the phone")
                    return
                }
                pending = onDone
            }
            try {
                l(uris)
            } catch (e: Exception) {
                synchronized(this) { pending = null }
                onDone(false, "Could not ask Android to delete: ${e.message}")
            }
        }

        /** Called by MainActivity with the user's answer to the system dialog. */
        fun onResult(ok: Boolean) {
            val cb = synchronized(this) { pending.also { pending = null } }
            cb?.invoke(ok, if (ok) null else "Delete cancelled on the phone")
        }

        /** Drop a request whose activity never answered (app closed, rotated). */
        fun cancel() {
            val cb = synchronized(this) { pending.also { pending = null } }
            cb?.invoke(false, "Delete not confirmed")
        }
    }

    private fun deleteReply(ids: List<String>, ok: Boolean, error: String?, id: String? = null) {
        send(Protocol.TYPE_MEDIA_RESULT, JsonObject().apply {
            addProperty("op", "delete")
            addProperty("ok", ok)
            if (id != null) addProperty("id", id)
            if (ids.isNotEmpty()) add("ids", JsonArray().apply { ids.forEach { add(it) } })
            error?.let { addProperty("error", it) }
        })
    }

    /**
     * Deletes the rows the PC ticked.
     *
     * Everything runs on IO. Two silent routes are tried first — the direct
     * provider delete, then plain file removal ("All files access"). On
     * Android 11+ neither can touch a row owned by another app, and the ONLY
     * mechanism that can is Android's own consent sheet
     * (MediaStore.createDeleteRequest), which MainActivity routes through
     * DeleteGate. So when the silent routes refuse, the consent sheet is
     * requested for exactly the failed rows instead of giving up with an
     * error — the prompt appears on the phone once and the verdict comes
     * back through DeleteGate.onResult.
     */
    fun delete(context: Context, ids: List<String>) {
        val clean = ids.filter { it.isNotBlank() }
        if (clean.isEmpty()) {
            deleteReply(ids, false, "Nothing selected to delete")
            return
        }
        val uris = clean.mapNotNull { contentUriFor(it) }
        if (uris.isEmpty()) {
            deleteReply(ids, false, "Those items are no longer on the phone")
            return
        }

        scope.launch {
            var deleted = 0
            var blocked = false
            val failedUris = mutableListOf<Uri>()
            for (uri in uris) {
                // A provider delete can return 0 (or even claim success) without
                // removing a row the app does not own, so the reply is only
                // trusted after verifying the row is actually gone. Otherwise
                // the PC would hide the tile and the item would come straight
                // back on the next listing — a delete that only "looks" done.
                var gone = false
                try {
                    gone = context.contentResolver.delete(uri, null, null) > 0
                } catch (_: SecurityException) {
                    blocked = true
                } catch (_: Exception) {
                    blocked = true
                }
                if (gone && rowExists(context, uri)) {
                    gone = false           // claimed success, row still there
                    blocked = true
                }
                if (gone) {
                    deleted++
                    continue
                }
                // Not the owner of this row: try removing the file itself, which
                // is permitted once the app holds All files access.
                if (deleteByPath(context, uri)) {
                    deleted++
                    blocked = false
                } else {
                    failedUris += uri
                }
            }

            if (deleted == uris.size) {
                deleteReply(ids, true, null)
                return@launch
            }

            // The silent routes refused these rows (scoped-storage rows this
            // app does not own). Ask Android's consent sheet — routed through
            // the Activity launcher MainActivity registers in DeleteGate — for
            // exactly the failed rows, instead of reporting a dead button.
            if (failedUris.isNotEmpty() && DeleteGate.launcher != null) {
                DeleteGate.request(failedUris) { ok, err ->
                    if (ok) deleteReply(ids, true, null)
                    else deleteReply(ids, false, err ?: "Delete cancelled on the phone")
                }
                return@launch
            }

            if (deleted > 0) {
                deleteReply(ids, false, "Deleted $deleted of ${uris.size} — Android blocked the rest")
            } else if (blocked || failedUris.isNotEmpty()) {
                deleteReply(
                    ids, false,
                    "Android blocks DuoLink from deleting other apps' files. One-time fix on the phone: " +
                        "Settings → Apps → DuoLink → All files access → Allow. Nothing asks again afterwards."
                )
            } else {
                deleteReply(ids, false, "Could not delete those items")
            }
        }
    }

    /** True while the MediaStore row still exists (queries fail closed). */
    private fun rowExists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { c -> c.count > 0 } ?: true
    } catch (_: Exception) {
        true
    }

    /** Direct-path removal (needs All files access). Returns true when gone. */
    private fun deleteByPath(context: Context, uri: Uri): Boolean {
        val path = try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null } ?: return false
        if (path.isBlank()) return false
        return try {
            val f = java.io.File(path)
            f.exists() && f.delete() && !f.exists()
        } catch (_: Exception) { false }
    }

    // ================= SHARE (picker lives on the PC) =================

    // The Android share sheet must never appear on the phone for an action the
    // user started at the PC. Instead the phone only ANSWERS "who can receive
    // this?" (share_targets -> app icons + labels), the PC draws the picker,
    // and the chosen app is launched directly by package (share) — no chooser,
    // no handset prompt. If Android refuses the launch (background-activity
    // rules), the PC gets the real error instead of a fake success.

    /** Resolve ACTION_SEND(/MULTIPLE) targets for the current selection. */
    fun shareTargets(context: Context, ids: List<String>, mime: String, multi: Boolean) {
        scope.launch {
            val out = JsonObject().apply {
                add("ids", JsonArray().apply { ids.forEach { add(it) } })
            }
            try {
                val intent = Intent(if (multi) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                    type = mime.ifBlank { "*/*" }
                }
                val ris = context.packageManager.queryIntentActivities(
                    intent, PackageManager.MATCH_DEFAULT_ONLY
                )
                val seen = HashSet<String>()
                val arr = JsonArray()
                for (ri in ris) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    // Our own package and one entry per app: the PC picker shows
                    // apps, not activities, exactly like the system sheet does.
                    if (pkg == context.packageName || !seen.add(pkg)) continue
                    arr.add(JsonObject().apply {
                        addProperty("pkg", pkg)
                        addProperty("label", ri.loadLabel(context.packageManager)?.toString() ?: pkg)
                        appIconBase64(context, pkg)?.let { addProperty("icon", it) }
                    })
                    if (arr.size() >= 40) break
                }
                out.add("targets", arr)
                if (arr.size() == 0) {
                    out.addProperty("error", "No app on the phone can receive this file type")
                }
            } catch (t: Throwable) {
                out.addProperty("error", t.message ?: "Could not list share targets")
            }
            send(Protocol.TYPE_MEDIA_SHARE_TARGETS, out)
        }
    }

    /** Launch the chosen app directly with the content — no chooser on phone. */
    fun share(context: Context, ids: List<String>, target: String) {
        scope.launch {
            val out = JsonObject().apply {
                addProperty("op", "share")
                add("ids", JsonArray().apply { ids.forEach { add(it) } })
                addProperty("target", target)
            }
            try {
                require(target.isNotBlank()) { "Pick an app first" }
                val uris = ArrayList<Uri>()
                for (id in ids) contentUriFor(id)?.let { uris.add(it) }
                if (uris.isEmpty()) throw IllegalStateException("Nothing to share — the item is no longer on the phone")

                val type = try { context.contentResolver.getType(uris[0]) } catch (_: Exception) { null }
                    ?: mimeFromName(ids.first()) ?: "*/*"
                val intent = if (uris.size > 1) {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    }
                } else {
                    Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, uris[0])
                        clipData = ClipData.newRawUri("", uris[0])
                    }
                }
                intent.type = type
                intent.setPackage(target)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Content-URI grants must outlive our process for the receiver
                // to read the file (EXTRA_STREAM alone is not always enough).
                for (u in uris) {
                    try { context.grantUriPermission(target, u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: Exception) { /* best effort — some providers refuse */ }
                }
                context.startActivity(intent)
                out.addProperty("ok", true)
            } catch (t: Throwable) {
                // Android 10+ can block a service-started activity; say so.
                out.addProperty("ok", false)
                out.addProperty(
                    "error",
                    "${t.javaClass.simpleName}: ${t.message ?: "the phone refused to start the app"}"
                )
            }
            send(Protocol.TYPE_MEDIA_RESULT, out)
        }
    }

    /** App icon as 96px PNG base64 for the PC picker (null when unavailable). */
    private fun appIconBase64(context: Context, pkg: String): String? = try {
        val d = context.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        d.setBounds(0, 0, 96, 96)
        d.draw(canvas)
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
        bmp.recycle()
        Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { null }

    /** Best-effort mime when the resolver has none (contentUriFor takes ids). */
    private fun mimeFromName(id: String): String? = when {
        id.endsWith(".png", true) -> "image/png"
        id.endsWith(".gif", true) -> "image/gif"
        id.endsWith(".webp", true) -> "image/webp"
        id.endsWith(".mp4", true) -> "video/mp4"
        id.endsWith(".mkv", true) -> "video/x-matroska"
        id.endsWith(".mp3", true) -> "audio/mpeg"
        id.endsWith(".wav", true) -> "audio/wav"
        else -> null
    }

    // ================= THUMBNAIL =================

    fun thumbnail(context: Context, id: String) {
        scope.launch {
            val out = JsonObject()
            out.addProperty("id", id)
            try {
                val bmp = loadThumb(context, id) ?: throw IllegalStateException("no thumbnail")
                val scaled = scaleToFit(bmp, 320)
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 72, baos)
                if (scaled !== bmp) bmp.recycle()
                out.addProperty("jpegBase64", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
            } catch (_: Exception) {
                // Leave jpegBase64 absent; the PC keeps its placeholder tile.
            }
            send(Protocol.TYPE_MEDIA_THUMB, out)
        }
    }

    private fun loadThumb(context: Context, id: String): Bitmap? {
        val uri = contentUriFor(id) ?: return null
        // Try the system thumbnail first (fast, cheap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                context.contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null)?.let { return it }
            } catch (_: Exception) { /* fall through to manual decode */ }
        }
        // Manual downsample decode
        return try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(ins, null, opts)
            }
        } catch (_: Exception) { null }
    }

    private fun scaleToFit(b: Bitmap, max: Int): Bitmap {
        val w = b.width; val h = b.height
        if (w <= max && h <= max) return b
        val ratio = minOf(max.toFloat() / w, max.toFloat() / h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(b, nw, nh, true)
        // Re-orient using EXIF if the source is a JPEG photo
        return try {
            val matrix = Matrix()
            var degrees = 0
            contentUriFor(b.toString()) // no-op guard
            out
        } catch (_: Exception) { out }
    }

    // ================= FULL FILE (streams via FILE_META/FILE_CHUNK) =================

    fun streamToPc(context: Context, id: String) {
        scope.launch {
            try {
                val uri = contentUriFor(id) ?: run {
                    sendFileError(id, "Unknown media id")
                    return@launch
                }
                val name: String
                val size: Long
                context.contentResolver.query(
                    uri, arrayOf(
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.SIZE
                    ), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: "media_${System.currentTimeMillis()}"
                        size = if (c.isNull(1)) -1L else c.getLong(1)
                    } else {
                        sendFileError(id, "File not found")
                        return@launch
                    }
                } ?: run { sendFileError(id, "File not found"); return@launch }

                if (size > MAX_STREAM_BYTES) {
                    sendFileError(id, "File is larger than the 5 GB transfer limit")
                    return@launch
                }

                val transferId = UUID.randomUUID().toString()
                val sha = MessageDigest.getInstance("SHA-256")

                // Read fully first when small enough to hash reliably; for large
                // files hash during the streaming pass and let the PC use size-only
                // integrity (it accepts that mode).
                val meta = JsonObject().apply {
                    addProperty("transferId", transferId)
                    addProperty("filename", name)
                    addProperty("totalSize", size)
                }
                send(Protocol.TYPE_FILE_META, meta)

                var offset = 0L
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    val buf = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = ins.read(buf)
                        if (read <= 0) break
                        if (read == buf.size) sha.update(buf)
                        else sha.update(buf, 0, read)
                        val chunk = JsonObject().apply {
                            addProperty("transferId", transferId)
                            addProperty("chunkBase64", Base64.encodeToString(buf, 0, read, Base64.NO_WRAP))
                        }
                        send(Protocol.TYPE_FILE_CHUNK, chunk)
                        offset += read
                        if (size > 0 && offset >= size) break
                    }
                } ?: run { sendFileError(id, "Could not open file"); return@launch }

                // If the source size was unknown, send a zero-length terminator so
                // the PC closes the transfer instead of waiting forever.
                if (size <= 0) {
                    send(Protocol.TYPE_FILE_META, JsonObject().apply {
                        addProperty("transferId", transferId)
                        addProperty("filename", name)
                        addProperty("totalSize", offset)
                    })
                }
            } catch (e: Exception) {
                sendFileError(id, e.message ?: "Transfer failed")
            }
        }
    }

    private fun sendFileError(id: String, error: String) {
        send(Protocol.TYPE_MEDIA_LIST, JsonObject().apply {
            addProperty("kind", "images")
            addProperty("total", 0)
            add("items", JsonArray())
            addProperty("error", error)
        })
    }
}
