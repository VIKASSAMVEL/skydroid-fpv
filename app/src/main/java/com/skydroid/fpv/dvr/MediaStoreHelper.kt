package com.skydroid.fpv.dvr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern Android Scoped Storage Helper.
 * Seamlessly manages saving MP4 flight recordings to Movies/SkyFPV
 * and flight snapshots to Pictures/SkyFPV on Android 10 through Android 15.
 */
object MediaStoreHelper {
    private const val TAG = "MediaStoreHelper"
    private const val ALBUM_NAME = "SkyFPV"

    data class VideoRecordTarget(
        val uri: Uri?,
        val fileDescriptor: ParcelFileDescriptor?,
        val fallbackFilePath: String?
    )

    /**
     * Prepares a new video record entry in MediaStore with IS_PENDING = 1
     */
    fun createVideoOutputTarget(context: Context): VideoRecordTarget? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "SKYFPV_$timeStamp.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME")
                put(MediaStore.Video.Media.IS_PENDING, 1)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, contentValues)

            if (uri != null) {
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                VideoRecordTarget(uri, pfd, null)
            } else {
                Log.e(TAG, "Failed to create MediaStore video record URI")
                null
            }
        } else {
            // Legacy Android fallback (API 26-28)
            val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM_NAME)
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val file = File(moviesDir, fileName)
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
            VideoRecordTarget(Uri.fromFile(file), pfd, file.absolutePath)
        }
    }

    /**
     * Finalizes the video entry so it instantly appears in Google Photos / Gallery
     */
    fun finalizeVideoTarget(context: Context, target: VideoRecordTarget) {
        try {
            target.fileDescriptor?.close()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.uri != null) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(target.uri, contentValues, null, null)
            }
            Log.d(TAG, "Flight recording successfully published to MediaStore: ${target.uri}")
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing video target", e)
        }
    }

    /**
     * Saves a snapshot photo directly to Pictures/SkyFPV in MediaStore
     */
    fun saveSnapshot(context: Context, bitmap: Bitmap): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "SKYFPV_SNAP_$timeStamp.jpg"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values) ?: return null

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } else {
                val picsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM_NAME)
                if (!picsDir.exists()) picsDir.mkdirs()
                val file = File(picsDir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot", e)
            null
        }
    }

    /**
     * Publishes a completed MP4 recording into Movies/SkyFPV in MediaStore.
     * Uses openOutputStream so Scoped Storage writes are 100% reliable across all Android versions.
     */
    fun saveVideoFile(context: Context, sourceFile: File): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "SKYFPV_$timeStamp.mp4"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                }

                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values) ?: return null

                context.contentResolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }

                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)

                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf("/storage/emulated/0/${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME/$fileName"),
                    arrayOf("video/mp4"),
                    null
                )
                Log.i(TAG, "Video published to MediaStore: $uri")
                uri
            } else {
                val moviesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM_NAME)
                if (!moviesDir.exists()) moviesDir.mkdirs()
                val destFile = File(moviesDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
                Uri.fromFile(destFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video to MediaStore", e)
            null
        }
    }
}
