package com.homealert.android.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.homealert.android.data.model.DeliveryTier
import com.homealert.android.data.model.SnapshotEntry
import com.homealert.android.ui.theme.ArmedEmerald
import com.homealert.android.ui.theme.CardContainer
import com.homealert.android.ui.theme.FailoverAmber
import com.homealert.android.ui.theme.SkyCyanPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(
    snapshots: List<SnapshotEntry>,
    onRefresh: () -> Unit,
    onDeleteSnapshot: (SnapshotEntry) -> Unit
) {
    var selectedSnapshot by remember { mutableStateOf<SnapshotEntry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Captured Snapshots",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Pictures/HomeAlert • ${snapshots.size} files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Gallery")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (snapshots.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No motion snapshots captured yet.\nArmed triggers save full-res JPEGs to Pictures/HomeAlert.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(snapshots, key = { it.id }) { entry ->
                    SnapshotCard(
                        entry = entry,
                        onOpen = { selectedSnapshot = entry },
                        onDelete = { onDeleteSnapshot(entry) }
                    )
                }
            }
        }
    }

    selectedSnapshot?.let { entry ->
        FullScreenSnapshotViewer(
            entry = entry,
            onDismiss = { selectedSnapshot = null },
            onDelete = {
                onDeleteSnapshot(entry)
                selectedSnapshot = null
            }
        )
    }
}

@Composable
private fun SnapshotCard(
    entry: SnapshotEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap by rememberUriBitmap(entry.uri, maxSampleWidth = 480)
    val badgeColor = when (entry.deliveryTier) {
        DeliveryTier.WIFI -> ArmedEmerald
        DeliveryTier.TELCO_DATA -> SkyCyanPrimary
        DeliveryTier.TELCO_SMS -> FailoverAmber
        DeliveryTier.LOCAL_ONLY, DeliveryTier.QUEUED -> Color(0xFF94A3B8)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardContainer),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = entry.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US)
                        .format(Date(entry.timestampMillis)),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.deliveryStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.cameraLabel.ifBlank { "Camera" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete snapshot",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenSnapshotViewer(
    entry: SnapshotEntry,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val fullBitmap by rememberUriBitmap(entry.uri, maxSampleWidth = 1600)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp)
        ) {
            fullBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = entry.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.displayName, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        entry.deliveryStatus,
                        color = ArmedEmerald,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberUriBitmap(uri: Uri, maxSampleWidth: Int) =
    produceState<Bitmap?>(initialValue = null, uri, maxSampleWidth) {
        val context = LocalContext.current
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / sample > maxSampleWidth) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val raw = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@runCatching null

                val orientation = context.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL

                val degrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees == 0f) {
                    raw
                } else {
                    val matrix = Matrix().apply { postRotate(degrees) }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
                        if (it !== raw) raw.recycle()
                    }
                }
            }.getOrNull()
        }
    }
