package com.homealert.android.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.homealert.android.data.model.DeliveryTier
import com.homealert.android.data.model.SnapshotEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStoreRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val resolver: ContentResolver get() = context.contentResolver

    data class SavedCapture(
        val mediaUri: Uri,
        val fileName: String,
        val timestampMillis: Long,
        val compressedAlertFile: File
    )

    /**
     * Persists a full-resolution JPEG into MediaStore (Pictures/HomeAlert) and produces a
     * lightweight (~150-250 KB, max 1280px width) JPEG in app cache for fast cellular failover.
     */
    suspend fun saveSnapshotAndCreateAlertCopy(
        fullResJpegBytes: ByteArray,
        cameraLabel: String,
        motionPercent: Float
    ): SavedCapture = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val dateTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        val safeLabel = cameraLabel.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
        val fileName = "HomeAlert_${safeLabel}_$dateTag.jpg"

        val mediaUri = insertIntoMediaStore(fileName, fullResJpegBytes, timestamp)
        val compressedFile = createCompressedAlertCopy(fileName, fullResJpegBytes)

        settingsRepository.saveDeliveryMetadata(
            fileName = fileName,
            cameraLabel = cameraLabel,
            motionPercent = motionPercent,
            deliveryStatus = "Saving / Dispatching...",
            tier = DeliveryTier.QUEUED
        )

        SavedCapture(
            mediaUri = mediaUri,
            fileName = fileName,
            timestampMillis = timestamp,
            compressedAlertFile = compressedFile
        )
    }

    private fun insertIntoMediaStore(
        fileName: String,
        jpegBytes: ByteArray,
        timestampMillis: Long
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, timestampMillis / 1000L)
            put(MediaStore.Images.Media.DATE_TAKEN, timestampMillis)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/HomeAlert")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val homeAlertDir = File(picturesDir, "HomeAlert")
                if (!homeAlertDir.exists()) homeAlertDir.mkdirs()
                val legacyFile = File(homeAlertDir, fileName)
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, legacyFile.absolutePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values)
            ?: error("MediaStore insert returned null for $fileName")

        resolver.openOutputStream(uri)?.use { out ->
            out.write(jpegBytes)
            out.flush()
        } ?: error("Failed to open output stream for $uri")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun createCompressedAlertCopy(fileName: String, fullResJpegBytes: ByteArray): File {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(fullResJpegBytes, 0, fullResJpegBytes.size, boundsOpts)

        val maxWidth = 1280
        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > maxWidth * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(fullResJpegBytes, 0, fullResJpegBytes.size, decodeOpts)
            ?: error("Unable to decode captured JPEG")

        val oriented = applyExifOrientation(decoded, fullResJpegBytes)
        val scaled = if (oriented.width > maxWidth) {
            val targetHeight = (oriented.height.toFloat() * maxWidth / oriented.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(oriented, maxWidth, targetHeight, true).also {
                if (it !== oriented) oriented.recycle()
            }
        } else {
            oriented
        }

        // Iteratively target ~150-250 KB for fast cellular failover
        val targetMaxBytes = 230 * 1024
        var quality = 78
        val baos = ByteArrayOutputStream()
        do {
            baos.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            quality -= 8
        } while (baos.size() > targetMaxBytes && quality >= 40)

        scaled.recycle()

        val cacheDir = File(context.cacheDir, "alert_uploads").apply { mkdirs() }
        val outFile = File(cacheDir, "alert_$fileName")
        FileOutputStream(outFile).use { fos ->
            fos.write(baos.toByteArray())
            fos.flush()
        }
        return outFile
    }

    private fun applyExifOrientation(bitmap: Bitmap, jpegBytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(jpegBytes.inputStream()).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    suspend fun loadGallerySnapshots(): List<SnapshotEntry> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SnapshotEntry>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%Pictures/HomeAlert%")
        } else {
            arrayOf("HomeAlert_%")
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        resolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val dateTaken = cursor.getLong(takenCol)
                val dateAdded = cursor.getLong(addedCol) * 1000L
                val ts = if (dateTaken > 0L) dateTaken else dateAdded
                val uri = ContentUris.withAppendedId(collection, id)
                val meta = settingsRepository.getDeliveryMetadata(name)

                results += SnapshotEntry(
                    id = id,
                    uri = uri,
                    displayName = name,
                    timestampMillis = ts,
                    cameraLabel = meta?.cameraLabel ?: "Camera",
                    motionPercent = meta?.motionPercent ?: 0f,
                    deliveryStatus = meta?.deliveryStatus ?: "Saved Locally",
                    deliveryTier = meta?.tier ?: DeliveryTier.LOCAL_ONLY
                )
            }
        }
        results
    }

    suspend fun deleteSnapshot(entry: SnapshotEntry): Boolean = withContext(Dispatchers.IO) {
        val deletedRows = runCatching { resolver.delete(entry.uri, null, null) }.getOrDefault(0)
        if (deletedRows > 0) {
            settingsRepository.removeDeliveryMetadata(entry.displayName)
        }
        deletedRows > 0
    }
}
