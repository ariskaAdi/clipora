package app.absentmotion.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.absentmotion.R
import app.absentmotion.media.DeviceMedia
import app.absentmotion.media.cachedMediaThumbnail
import app.absentmotion.media.deviceMedia
import app.absentmotion.media.mediaThumbnail
import app.absentmotion.platform.BackHandler
import app.absentmotion.platform.sheetDialogProperties
import app.absentmotion.ui.editor.OrderedPick
import app.absentmotion.ui.editor.TextIcons

/** Colours for the CapCut-style screens: near-black surfaces, white text, a cyan selection accent. */
internal object Cap {
    val Bg = Color(0xFF0B0B0C)
    val Surface = Color(0xFF1B1B1E)
    val Surface2 = Color(0xFF2A2A2E)
    val Line = Color(0x1FFFFFFF)
    val Text = Color(0xFFFFFFFF)
    val Dim = Color(0x99FFFFFF)
    val Faint = Color(0x4DFFFFFF)
    val Accent = Color(0xFF22D3EE)
    val OnAccent = Color(0xFF00171C)
    val Banner = Brush.linearGradient(listOf(Color(0xFFB8F0F7), Color(0xFF8ED8FB), Color(0xFF5DB4FA)))
}

private enum class GalleryKind { All, Videos, Photos }

/**
 * A full-screen gallery in the style of CapCut's import screen.
 *
 * - The album name at the top opens a list of albums that drops down over the grid.
 * - Tapping a tile adds it to the selection; the badge shows its place in the order.
 * - The tray at the bottom lists the picks in order; [onDone] receives them in that order.
 */
@Composable
internal fun GalleryPicker(
    onClose: () -> Unit,
    onDone: (List<OrderedPick>) -> Unit,
    onBlank: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    Dialog(onClose, sheetDialogProperties()) {
        GalleryPickerBody(onClose, onDone, onBlank, busy)
    }
}

@Composable
private fun GalleryPickerBody(onClose: () -> Unit, onDone: (List<OrderedPick>) -> Unit, onBlank: (() -> Unit)?, busy: Boolean) {
    val context = LocalContext.current
    var albumsOpen by remember { mutableStateOf(false) }
    BackHandler { if (albumsOpen) albumsOpen = false else onClose() }

    val wanted = remember {
        if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val partial = android.os.Build.VERSION.SDK_INT >= 34
    fun granted(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    fun access() = wanted.all(::granted) || (partial && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    var allowed by remember { mutableStateOf(access()) }
    var revision by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        allowed = access()
        revision++
    }
    val request = remember { (wanted + listOfNotNull(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED.takeIf { partial })).toTypedArray() }
    LaunchedEffect(Unit) { if (!allowed) permission.launch(request) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        val now = access()
        if (now != allowed) { allowed = now; revision++ }
    }

    val media by produceState<List<DeviceMedia>?>(null, allowed, revision) { value = if (allowed) deviceMedia(context) else emptyList() }
    var album by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf(GalleryKind.All) }
    val picked = remember { mutableStateListOf<DeviceMedia>() }
    val shown = remember(media, album, kind) {
        media.orEmpty().filter {
            (album == null || it.albumId == album) && when (kind) {
                GalleryKind.All -> true
                GalleryKind.Videos -> it.video
                GalleryKind.Photos -> !it.video
            }
        }
    }

    val others = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        onDone(picked.map { it.toPick() } + uris.map { OrderedPick(it) })
    }

    Column(Modifier.fillMaxSize().background(Cap.Bg).windowInsetsPadding(WindowInsets.statusBars)) {
        // Top bar: close, album dropdown, optional blank-project action.
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Box(Modifier.align(Alignment.CenterStart).size(52.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(HomeIcons.Close, stringResource(R.string.cancel), Modifier.size(24.dp), tint = Cap.Text)
            }
            val turn by animateFloatAsState(if (albumsOpen) 180f else 0f, tween(200), label = "albumArrow")
            Row(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp)).clickable(enabled = allowed) { albumsOpen = !albumsOpen }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val name = media.orEmpty().firstOrNull { it.albumId == album }?.album ?: stringResource(R.string.gallery_recents)
                Text(name, Modifier.widthIn(max = 200.dp), color = Cap.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp))
                Icon(HomeIcons.ChevronDown, stringResource(R.string.gallery_albums), Modifier.size(20.dp).rotate(turn), tint = Cap.Text)
            }
            if (onBlank != null) Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onBlank).padding(horizontal = 10.dp, vertical = 6.dp),
            ) { Text(stringResource(R.string.gallery_blank), color = Cap.Dim, fontSize = 14.sp) }
        }
        // Videos / Photos tabs.
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GalleryKind.entries.forEach { k ->
                val on = kind == k
                Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { kind = k }.padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(when (k) { GalleryKind.All -> R.string.gallery_all; GalleryKind.Videos -> R.string.gallery_videos; GalleryKind.Photos -> R.string.gallery_photos }),
                        color = if (on) Cap.Text else Cap.Dim, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(18.dp).height(2.dp).clip(CircleShape).background(if (on) Cap.Text else Color.Transparent))
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                others.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.gallery_other_apps), color = Cap.Accent, fontSize = 14.sp)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !allowed -> AccessPrompt(asked, { permission.launch(request) }, {
                    context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                })
                media == null -> Unit
                shown.isEmpty() -> Text(stringResource(R.string.gallery_empty), Modifier.align(Alignment.Center), color = Cap.Dim, fontSize = 15.sp)
                else -> LazyVerticalGrid(
                    GridCells.Fixed(4), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(shown, key = { it.uri.toString() }) { file ->
                        val order = picked.indexOf(file)
                        GalleryTile(file, order) { if (order >= 0) picked.remove(file) else picked.add(file) }
                    }
                }
            }
            // Album list drops down from under the top bar, over the grid.
            AlbumDropdown(albumsOpen, media.orEmpty(), album, onDismiss = { albumsOpen = false }) { album = it; albumsOpen = false }
        }

        PickTray(picked, busy, onRemove = { picked.remove(it) }, onAdd = { if (picked.isNotEmpty()) onDone(picked.map { it.toPick() }) })
    }
}

private fun DeviceMedia.toPick() = OrderedPick(uri, video = video, svg = svg)

@Composable
private fun rememberThumb(file: DeviceMedia): Bitmap? {
    val context = LocalContext.current
    val bitmap by produceState(cachedMediaThumbnail(file.uri), file.uri, file.modified) { value = mediaThumbnail(context, file.uri, file.video) }
    return bitmap
}

@Composable
private fun GalleryTile(file: DeviceMedia, order: Int, onToggle: () -> Unit) {
    val bitmap = rememberThumb(file)
    val on = order >= 0
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).background(Cap.Surface)
            .semantics { contentDescription = file.name; selected = on }
            .clickable(onClick = onToggle),
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (on) Box(Modifier.fillMaxSize().background(Color(0x66000000)).border(2.dp, Cap.Accent))
        if (file.video) Text(clock(file.durationMs), Modifier.align(Alignment.BottomEnd).padding(5.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                .then(if (on) Modifier.background(Cap.Accent) else Modifier.background(Color(0x33000000)).border(1.5.dp, Color.White, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Text("${order + 1}", color = Cap.OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AlbumDropdown(open: Boolean, media: List<DeviceMedia>, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(open, enter = expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(180))) {
        AlbumList(media, current, onPick)
    }
}

@Composable
private fun AlbumList(media: List<DeviceMedia>, current: String?, onPick: (String?) -> Unit) {
    val albums = remember(media) { media.groupBy { it.albumId }.map { (id, files) -> Triple(id, files.first(), files.size) } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).background(Cap.Bg)) {
        item(key = "recents") { AlbumRow(stringResource(R.string.gallery_recents), media.firstOrNull(), media.size, current == null) { onPick(null) } }
        items(albums, key = { it.first }) { (id, cover, count) -> AlbumRow(cover.album, cover, count, current == id) { onPick(id) } }
    }
}

@Composable
private fun AlbumRow(name: String, cover: DeviceMedia?, count: Int, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(72.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(Cap.Surface)) {
            cover?.let { c -> rememberThumb(c)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (on) Cap.Accent else Cap.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count", color = Cap.Dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PickTray(picked: List<DeviceMedia>, busy: Boolean, onRemove: (DeviceMedia) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Cap.Surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        AnimatedVisibility(picked.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            LazyRow(Modifier.fillMaxWidth().padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(picked, key = { _, it -> it.uri.toString() }) { i, file ->
                    Box(Modifier.size(58.dp)) {
                        Box(Modifier.align(Alignment.BottomStart).size(52.dp).clip(RoundedCornerShape(6.dp)).background(Cap.Surface2)) {
                            rememberThumb(file)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            Text("${i + 1}", Modifier.align(Alignment.BottomStart).background(Color(0x99000000), RoundedCornerShape(topEnd = 6.dp)).padding(horizontal = 4.dp),
                                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        val remove = stringResource(R.string.gallery_remove)
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(20.dp).clip(CircleShape).background(Color(0xE6000000))
                                .semantics { contentDescription = remove }.clickable { onRemove(file) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(HomeIcons.Close, null, Modifier.size(12.dp), tint = Color.White) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (picked.isEmpty()) stringResource(R.string.gallery_select_hint) else stringResource(R.string.gallery_selected, picked.size),
                Modifier.weight(1f), color = Cap.Dim, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2,
            )
            val ready = picked.isNotEmpty() && !busy
            Box(
                Modifier.height(40.dp).widthIn(min = 96.dp).clip(RoundedCornerShape(20.dp)).background(if (ready) Cap.Accent else Cap.Surface2)
                    .clickable(enabled = ready, onClick = onAdd).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (picked.isEmpty()) stringResource(R.string.gallery_add_plain) else stringResource(R.string.gallery_add, picked.size),
                    color = if (ready) Cap.OnAccent else Cap.Faint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AccessPrompt(asked: Boolean, onAllow: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(TextIcons.Import, null, Modifier.size(40.dp), tint = Cap.Dim)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.gallery_allow_title), color = Cap.Text, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Cap.Accent).clickable(onClick = if (asked) onSettings else onAllow).padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.gallery_allow), color = Cap.OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

internal fun clock(ms: Long): String {
    val s = (ms + 500) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
