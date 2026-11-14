package com.phonepc.pairing.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.phonepc.pairing.data.ConnectionState
import com.phonepc.pairing.network.Protocol
import com.phonepc.pairing.service.PairingForegroundService
import com.phonepc.pairing.service.PcFsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * PC Files — the phone side of the PC file manager.
 *
 * Every path shown here came from the PC, and every action is validated again
 * on the PC before it touches the disk. The phone never builds a path from
 * user text, so there is nothing to traverse; the PC decides what exists and
 * what the Windows account may read or change.
 */
@Composable
fun PcFilesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by PairingForegroundService.pcFsResult.collectAsState()
    val receivedStatus by PairingForegroundService.fileReceived.collectAsState()
    val lastReceivedUri by PairingForegroundService.lastReceivedUri.collectAsState()
    var openWhenReceived by remember { mutableStateOf(false) }
    val connection by PairingForegroundService.connectionState.collectAsState()

    var currentPath by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<PcFsEntry>>(emptyList()) }
    var isPlaces by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var uploadPercent by remember { mutableStateOf(0) }
    var uploadSpeed by remember { mutableStateOf("") }
    var uploadEta by remember { mutableStateOf("") }

    // Search: runs on the PC (capped there), results replace the current list
    // until the user navigates again.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var searchAllDrives by rememberSaveable { mutableStateOf(false) }

    // Multi-select + clipboard (copy/cut are local until Paste).
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectMode by remember { mutableStateOf(false) }
    var clipPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var clipCut by remember { mutableStateOf(false) }

    val backStack = remember { mutableStateListOf<String>() }
    val forwardStack = remember { mutableStateListOf<String>() }
    var pendingRequest by remember { mutableStateOf("") }

    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PcFsEntry?>(null) }
    var deleteTargets by remember { mutableStateOf<List<PcFsEntry>>(emptyList()) }
    var permanentDelete by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun clearSelection() {
        selected = emptySet()
        selectMode = false
    }

    fun load(path: String?) {
        error = ""
        searchActive = false
        clearSelection()
        if (!PairingForegroundService.requestPcFs(Protocol.TYPE_PC_FS_LIST, path)) {
            error = "Not connected to the PC"
            loading = false
            return
        }
        pendingRequest = Protocol.TYPE_PC_FS_LIST
        loading = true
    }

    LaunchedEffect(Unit) { load(null) }
    LaunchedEffect(connection) {
        if (connection == ConnectionState.CONNECTED && entries.isEmpty()) load(currentPath)
    }

    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        if (!r.ok) {
            if (r.op == Protocol.TYPE_PC_FS_LIST || r.op == Protocol.TYPE_PC_FS_SEARCH) loading = false
            error = r.error.ifBlank { "The PC rejected that request" }
            return@LaunchedEffect
        }
        when (r.op) {
            Protocol.TYPE_PC_FS_LIST -> {
                loading = false
                error = ""
                entries = r.entries
                isPlaces = r.places
                currentPath = r.path
            }
            Protocol.TYPE_PC_FS_SEARCH -> {
                loading = false
                error = ""
                entries = r.entries
                searchActive = true
                val scopeLabel = if (searchAllDrives) "all drives" else "this folder"
                status = if (r.entries.isEmpty()) "No matches in $scopeLabel" else "${r.entries.size} matches in $scopeLabel"
            }
            Protocol.TYPE_PC_FS_MKDIR -> {
                status = "Folder created"
                load(currentPath)
            }
            Protocol.TYPE_PC_FS_RENAME -> {
                status = "Renamed"
                load(currentPath)
            }
            Protocol.TYPE_PC_FS_DELETE -> {
                status = if (permanentDelete) "Permanently deleted" else "Moved to the Recycle Bin"
                permanentDelete = false
                load(currentPath)
            }
            Protocol.TYPE_PC_FS_COPY -> {
                status = when {
                    r.failed > 0 -> "Copied ${r.copied}, ${r.failed} failed"
                    else -> "Copied ${r.copied} item(s)"
                }
                clearSelection()
                load(currentPath)
            }
            Protocol.TYPE_PC_FS_MOVE -> {
                status = when {
                    r.failed > 0 -> "Moved ${r.moved}, ${r.failed} failed"
                    else -> "Moved ${r.moved} item(s)"
                }
                clipPaths = emptyList()
                clearSelection()
                load(currentPath)
            }
            Protocol.TYPE_PC_FS_DOWNLOAD -> {
                status = "Downloading ${r.path?.substringAfterLast('\\') ?: "file"}…"
            }
        }
    }

    LaunchedEffect(receivedStatus) {
        if (receivedStatus.isNotBlank()) status = receivedStatus
    }

    // A file the user asked to open finishes downloading -> hand it to the
    // device's viewer. Downloads land in MediaStore, so this works for images,
    // video, audio, PDFs, Office files and anything else with a registered app.
    LaunchedEffect(lastReceivedUri) {
        val raw = lastReceivedUri ?: return@LaunchedEffect
        if (!openWhenReceived) return@LaunchedEffect
        openWhenReceived = false
        PairingForegroundService.clearLastReceivedUri()
        try {
            val uri = android.net.Uri.parse(raw)
            val mime = context.contentResolver.getType(uri) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(view, "Open with"))
        } catch (_: Exception) {
            error = "No app on this phone can open that file type"
        }
    }

    fun open(entry: PcFsEntry) {
        if (!entry.dir) return
        currentPath?.let { backStack.add(it) }
        forwardStack.clear()
        searchQuery = ""
        load(entry.path)
    }

    fun goBack() {
        if (backStack.isEmpty()) {
            if (!isPlaces) {
                forwardStack.add(currentPath ?: "")
                searchQuery = ""
                load(null)
            }
            return
        }
        val prev = backStack.removeAt(backStack.lastIndex)
        currentPath?.let { forwardStack.add(it) }
        searchQuery = ""
        load(prev)
    }

    fun goForward() {
        if (forwardStack.isEmpty()) return
        val next = forwardStack.removeAt(forwardStack.lastIndex)
        currentPath?.let { backStack.add(it) }
        searchQuery = ""
        load(next)
    }

    fun refresh() = load(currentPath)

    fun runSearch() {
        val q = searchQuery.trim()
        if (q.length < 2) {
            error = "Type at least 2 characters to search"
            return
        }
        error = ""
        val scope = if (searchAllDrives) null else currentPath
        val extra = JsonObject().apply {
            addProperty("query", q)
            if (scope != null) addProperty("path", scope)
        }
        if (!PairingForegroundService.requestPcFs(Protocol.TYPE_PC_FS_SEARCH, scope, extra = extra)) {
            error = "Not connected to the PC"
            return
        }
        loading = true
    }

    fun sendCopyMove(move: Boolean) {
        val paths = selected.toList()
        val dest = currentPath
        if (paths.isEmpty()) return
        if (dest == null) {
            error = "Open a folder first, then paste"
            return
        }
        status = if (move) "Moving ${paths.size} item(s)…" else "Copying ${paths.size} item(s)…"
        PairingForegroundService.requestPcFs(
            if (move) Protocol.TYPE_PC_FS_MOVE else Protocol.TYPE_PC_FS_COPY,
            dest,
            extra = JsonObject().apply {
                add("paths", com.google.gson.JsonArray().apply { paths.forEach { add(it) } })
                addProperty("dest", dest)
            }
        )
    }

    fun pasteClipboard() {
        val dest = currentPath ?: return
        if (clipPaths.isEmpty()) return
        status = if (clipCut) "Moving ${clipPaths.size} item(s)…" else "Copying ${clipPaths.size} item(s)…"
        PairingForegroundService.requestPcFs(
            if (clipCut) Protocol.TYPE_PC_FS_MOVE else Protocol.TYPE_PC_FS_COPY,
            dest,
            extra = JsonObject().apply {
                add("paths", com.google.gson.JsonArray().apply { clipPaths.forEach { add(it) } })
                addProperty("dest", dest)
            }
        )
    }

    // Upload straight into the folder the phone is looking at.
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val dest = currentPath ?: return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            uploadPercent = 0
            uploadSpeed = ""
            uploadEta = ""
            var ok = 0
            for ((index, uri) in uris.withIndex()) {
                status = "Uploading ${index + 1} of ${uris.size}…"
                var mark = System.currentTimeMillis()
                var markBytes = 0L
                val sent = withContext(Dispatchers.IO) {
                    uploadOneFile(context, uri, dest) { done, total ->
                        val now = System.currentTimeMillis()
                        uploadPercent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        if (now - mark >= 500) {
                            val rate = (done - markBytes) * 1000.0 / (now - mark)
                            uploadSpeed = formatRate(rate)
                            val remain = if (rate > 0 && total > done) ((total - done) / rate).toLong() else -1
                            uploadEta = if (remain >= 0) formatEta(remain) else "—"
                            markBytes = done
                            mark = now
                        }
                    }
                }
                if (sent) ok++
            }
            uploading = false
            uploadPercent = 0
            uploadSpeed = ""
            uploadEta = ""
            status = if (ok == uris.size) "Uploaded $ok file(s)" else "Uploaded $ok of ${uris.size} file(s)"
            load(currentPath)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        // ---- Toolbar: back / forward, location, refresh
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { goBack() }, enabled = backStack.isNotEmpty() || !isPlaces) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            IconButton(onClick = { goForward() }, enabled = forwardStack.isNotEmpty()) {
                Icon(Icons.Filled.ArrowForward, contentDescription = "Forward")
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = when {
                        searchActive -> "Search results"
                        isPlaces -> "This PC"
                        else -> (currentPath?.substringAfterLast('\\') ?: "PC")
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        searchActive -> "“${searchQuery.trim()}”"
                        isPlaces -> "All drives and folders"
                        else -> (currentPath ?: "")
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        // ---- Search
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it.take(80) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search files and folders", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = ""; load(currentPath) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodySmall
            )
            IconButton(onClick = { runSearch() }) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChipToggle(
                label = "This folder",
                selected = !searchAllDrives,
                onClick = { searchAllDrives = false }
            )
            AssistChipToggle(
                label = "All drives",
                selected = searchAllDrives,
                onClick = { searchAllDrives = true }
            )
            Spacer(Modifier.weight(1f))
            if (searchActive) {
                Text(
                    "${entries.size} shown",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        // ---- Breadcrumbs (tap a segment to jump back)
        if (!isPlaces && currentPath != null && !searchActive) {
            val parts = currentPath!!.split("\\").filter { it.isNotEmpty() }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "This PC",
                    fontSize = 11.sp,
                    color = BrandCyan,
                    modifier = Modifier.clickable { backStack.clear(); forwardStack.clear(); load(null) }
                )
                parts.forEachIndexed { i, part ->
                    Icon(
                        Icons.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    if (i == parts.lastIndex) {
                        Text(part, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(
                            part,
                            fontSize = 11.sp,
                            color = BrandCyan,
                            maxLines = 1,
                            modifier = Modifier.clickable {
                                val target = parts.take(i + 1).joinToString("\\")
                                forwardStack.clear()
                                currentPath?.let { backStack.add(it) }
                                load(if (Regex("^[A-Za-z]:").containsMatchIn(target)) "$target\\" else target)
                            }
                        )
                    }
                }
            }
        }

        // ---- Status / error line
        if (error.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                Text(
                    error,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        } else if (status.isNotBlank()) {
            Text(
                status,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(bottom = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (uploading) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                LinearProgressIndicator(
                    progress = { uploadPercent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    buildString {
                        append("$uploadPercent%")
                        if (uploadSpeed.isNotBlank()) append(" · $uploadSpeed")
                        if (uploadEta.isNotBlank()) append(" · $uploadEta left")
                    },
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
        }

        // ---- Selection/clipboard toolbar
        if (selectMode) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("${selected.size} selected", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        if (selected.isNotEmpty()) {
                            clipPaths = selected.toList()
                            clipCut = false
                            status = "Copied ${clipPaths.size} item(s) — open a folder and paste"
                            clearSelection()
                        }
                    }, enabled = selected.isNotEmpty()) { Text("Copy", fontSize = 12.sp) }
                    TextButton(onClick = {
                        if (selected.isNotEmpty()) {
                            clipPaths = selected.toList()
                            clipCut = true
                            status = "Ready to move ${clipPaths.size} item(s) — open a folder and paste"
                            clearSelection()
                        }
                    }, enabled = selected.isNotEmpty()) { Text("Cut", fontSize = 12.sp) }
                    TextButton(onClick = {
                        deleteTargets = entries.filter { selected.contains(it.path) }
                        permanentDelete = false
                    }, enabled = selected.isNotEmpty()) {
                        Text("Delete", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { clearSelection() }) { Text("Cancel", fontSize = 12.sp) }
                }
            }
        }

        // ---- List
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty() && !loading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            searchActive -> "Nothing matched that search"
                            isPlaces -> "The PC has not authorized file access"
                            else -> "This folder is empty"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (isPlaces) {
                        Text(
                            "Turn on Settings → PC file access on the PC",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { entry ->
                        PcFileRow(
                            entry = entry,
                            menuOpen = menuFor == entry.path,
                            selected = selected.contains(entry.path),
                            selectMode = selectMode,
                            onOpen = {
                                if (selectMode) {
                                    selected = if (selected.contains(entry.path)) selected - entry.path else selected + entry.path
                                } else if (entry.dir) open(entry) else {
                                    openWhenReceived = true
                                    PairingForegroundService.requestPcFs(Protocol.TYPE_PC_FS_DOWNLOAD, entry.path)
                                    status = "Opening ${entry.name}…"
                                }
                            },
                            onLongPress = {
                                selectMode = true
                                selected = selected + entry.path
                            },
                            onMenu = { menuFor = if (menuFor == entry.path) null else entry.path },
                            onDownload = {
                                menuFor = null
                                PairingForegroundService.requestPcFs(Protocol.TYPE_PC_FS_DOWNLOAD, entry.path)
                                status = "Requested ${entry.name}…"
                            },
                            onRename = { menuFor = null; renameTarget = entry },
                            onDelete = { menuFor = null; deleteTargets = listOf(entry); permanentDelete = false },
                            onCopy = {
                                menuFor = null
                                clipPaths = listOf(entry.path)
                                clipCut = false
                                status = "Copied — open a folder and paste"
                            },
                            onCut = {
                                menuFor = null
                                clipPaths = listOf(entry.path)
                                clipCut = true
                                status = "Ready to move — open a folder and paste"
                            }
                        )
                    }
                }
            }
        }

        // ---- Clipboard strip
        if (clipPaths.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (clipCut) "${clipPaths.size} item(s) to move" else "${clipPaths.size} item(s) copied",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { clipPaths = emptyList(); clipCut = false }) { Text("Clear", fontSize = 11.5.sp) }
                Button(
                    onClick = { pasteClipboard() },
                    enabled = !isPlaces && currentPath != null,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Paste here", fontSize = 11.5.sp)
                }
            }
        }

        // ---- Bottom actions
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showNewFolder = true },
                enabled = !isPlaces && currentPath != null && !searchActive,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("New folder", fontSize = 12.sp)
            }
            Button(
                onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                enabled = !isPlaces && currentPath != null && !uploading && !searchActive,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Upload here", fontSize = 12.sp)
            }
        }
    }

    // ---- New folder dialog
    if (showNewFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                showNewFolder = false
                if (currentPath != null) {
                    PairingForegroundService.requestPcFs(Protocol.TYPE_PC_FS_MKDIR, currentPath, name)
                    status = "Creating $name…"
                }
            }
        )
    }

    // ---- Rename dialog
    renameTarget?.let { target ->
        NameDialog(
            title = "Rename",
            initial = target.name,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                PairingForegroundService.requestPcFs(Protocol.TYPE_PC_FS_RENAME, target.path, name)
            }
        )
    }

    // ---- Delete confirmation (destructive, so never one tap, and the phone
    //      chooses Recycle Bin vs permanent explicitly)
    if (deleteTargets.isNotEmpty()) {
        val many = deleteTargets.size > 1
        AlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            title = { Text(if (many) "Delete ${deleteTargets.size} items?" else "Delete ${if (deleteTargets[0].dir) "folder" else "file"}?") },
            text = {
                Column {
                    Text(
                        if (many) "The selected items will be removed from the PC."
                        else "“${deleteTargets[0].name}” will be removed from the PC."
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { permanentDelete = false }
                    ) {
                        RadioButton(selected = !permanentDelete, onClick = { permanentDelete = false })
                        Text("Move to Recycle Bin", fontSize = 13.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { permanentDelete = true }
                    ) {
                        RadioButton(selected = permanentDelete, onClick = { permanentDelete = true })
                        Text("Delete permanently", fontSize = 13.sp)
                    }
                    if (permanentDelete) {
                        Text(
                            "This cannot be undone from the phone or the Recycle Bin.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targets = deleteTargets
                    deleteTargets = emptyList()
                    for (t in targets) {
                        PairingForegroundService.requestPcFs(
                            Protocol.TYPE_PC_FS_DELETE,
                            t.path,
                            extra = JsonObject().apply { addProperty("permanent", permanentDelete) }
                        )
                    }
                    status = if (permanentDelete) "Deleting permanently…" else "Moving to the Recycle Bin…"
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargets = emptyList() }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AssistChipToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(30.dp)
        ) { Text(label, fontSize = 11.sp) }
    } else {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.height(30.dp)
        ) { Text(label, fontSize = 11.sp) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PcFileRow(
    entry: PcFsEntry,
    menuOpen: Boolean,
    selected: Boolean,
    selectMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onMenu: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BrandCyan.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onOpen() },
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = when {
                entry.dir -> Icons.Filled.Folder
                entry.ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> Icons.Filled.Image
                entry.ext in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp") -> Icons.Filled.PlayArrow
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (entry.dir) BrandCyan else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val detail = buildString {
                if (entry.dir) {
                    append(if (entry.place == "drive") "Drive" else "Folder")
                } else {
                    append(entry.sizeLabel.ifBlank { formatBytes(entry.size) })
                    if (entry.ext.isNotBlank()) append(" · ${entry.ext.uppercase(Locale.getDefault())}")
                }
                if (entry.modified > 0) append(" · ${formatDate(entry.modified)}")
            }
            Text(
                detail,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Actions",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenu) {
                if (!entry.dir) {
                    DropdownMenuItem(
                        text = { Text("Open on phone", fontSize = 13.sp) },
                        onClick = onOpen
                    )
                    DropdownMenuItem(
                        text = { Text("Download to phone", fontSize = 13.sp) },
                        onClick = onDownload
                    )
                }
                if (entry.place != "shared" && entry.place != "drive") {
                    DropdownMenuItem(text = { Text("Rename", fontSize = 13.sp) }, onClick = onRename)
                }
                if (entry.place != "drive") {
                    DropdownMenuItem(text = { Text("Copy", fontSize = 13.sp) }, onClick = onCopy)
                    DropdownMenuItem(text = { Text("Cut (move)", fontSize = 13.sp) }, onClick = onCut)
                    DropdownMenuItem(text = { Text("Delete", fontSize = 13.sp) }, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val valid = value.isNotBlank() && !value.contains('\\') && !value.contains('/')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(120) },
                singleLine = true,
                label = { Text("Name") },
                isError = value.isNotBlank() && !valid
            )
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onConfirm(value.trim()) }, enabled = valid) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Streams one content:// URI to [destDir] on the PC. Returns true when sent. */
private fun uploadOneFile(
    context: Context,
    uri: Uri,
    destDir: String,
    onProgress: (sent: Long, total: Long) -> Unit
): Boolean {
    var fileName = "upload_${System.currentTimeMillis()}.bin"
    var fileSize = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) cursor.getString(nameIdx)?.let { fileName = it }
                if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
            }
        }
    } catch (_: Exception) { return false }

    val service = PairingForegroundService.activeServiceInstance ?: return false
    val transferId = UUID.randomUUID().toString()
    val meta = JsonObject().apply {
        addProperty("transferId", transferId)
        addProperty("filename", fileName)
        addProperty("totalSize", fileSize)
        addProperty("destDir", destDir)
    }
    if (!service.sendRawEnvelope(Protocol.TYPE_FILE_META, meta)) return false

    val buffer = ByteArray(64 * 1024)
    var totalSent = 0L
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                val slice = if (read == buffer.size) buffer else buffer.copyOf(read)
                val chunk = JsonObject().apply {
                    addProperty("transferId", transferId)
                    addProperty("chunkBase64", Base64.encodeToString(slice, Base64.NO_WRAP))
                }
                if (!service.sendRawEnvelope(Protocol.TYPE_FILE_CHUNK, chunk)) return false
                totalSent += read
                onProgress(totalSent, fileSize)
            }
        } ?: return false
    } catch (_: Exception) {
        return false
    }
    return true
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
}

private fun formatRate(bytesPerSecond: Double): String {
    if (bytesPerSecond <= 0) return ""
    return formatBytes(bytesPerSecond.toLong()) + "/s"
}

private fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "—"
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    val s = seconds % 60
    return if (m < 60) "${m}m ${s}s" else "${m / 60}h ${m % 60}m"
}

private fun formatDate(ms: Long): String {
    return try {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
    } catch (_: Exception) {
        ""
    }
}
