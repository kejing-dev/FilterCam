package io.embodit.filtercam.platform

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import io.embodit.filtercam.domain.Img
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved photo in Pictures/FilterCam (design P8: filtered shots and their camera originals, paired by time stamp). */
data class GalleryItem(val uri: String, val name: String, val raw: Boolean, val takenMs: Long)

/**
 * The app's photos in the system gallery (design persistence.photos): MediaStore, RELATIVE_PATH Pictures/FilterCam/, JPEG
 * 92, `FilterCam_<yyyyMMdd_HHmmss>_<F01>.jpg` and the camera original `FilterCam_<same>_raw.jpg`, EXIF UserComment with
 * the filter id and strength. Android 10+ needs no storage permission for the app's own files. Imported pictures come
 * from the system picker (no READ_MEDIA_IMAGES).
 */
class PhotoRepo(private val context: Context) {
    private val resolver get() = context.contentResolver

    /** A debug hook (`save --es fail full|io`, design S26): the next save fails this way. */
    @Volatile var failNext: String? = null

    fun stamp(now: Date = Date()): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)

    /** Saves [img] as JPEG; returns the content uri. Throws on failure (design S26). */
    fun save(img: Img, name: String, comment: String): Uri {
        failNext?.let {
            failNext = null
            throw java.io.IOException("debug save fail $it")
        }
        val bmp = Bitmap.createBitmap(img.px, img.w, img.h, Bitmap.Config.ARGB_8888)
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FilterCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw java.io.IOException("insert returned null")
        try {
            resolver.openOutputStream(uri)?.use { out -> if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw java.io.IOException("compress failed") } ?: throw java.io.IOException("no output stream")
            runCatching {
                resolver.openFileDescriptor(uri, "rw")?.use { fd ->
                    val exif = ExifInterface(fd.fileDescriptor)
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
                    exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Filter Cam")
                    exif.saveAttributes()
                }
            }.onFailure { FcLog.w("SAVE exif: $it") }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        } finally {
            bmp.recycle()
        }
        return uri
    }

    fun delete(uri: String): Boolean = runCatching { resolver.delete(Uri.parse(uri), null, null) > 0 }.getOrElse {
        FcLog.w("GALLERY delete $uri: $it")
        false
    }

    /** Photos this app saved, newest first. */
    fun query(): List<GalleryItem> {
        val out = ArrayList<GalleryItem>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
        val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        runCatching {
            resolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), proj, sel, arrayOf("Pictures/FilterCam%"), "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nm = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dt = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), c.getLong(id))
                    val name = c.getString(nm) ?: ""
                    out += GalleryItem(uri.toString(), name, name.endsWith("_raw.jpg"), c.getLong(dt) * 1000)
                }
            }
        }.onFailure { FcLog.w("GALLERY query: $it") }
        // a filtered shot and its original sit next to each other (same time stamp), the filtered one first
        return out.sortedWith(compareByDescending<GalleryItem> { it.name.removeSuffix("_raw.jpg").substringBeforeLast('_').ifEmpty { it.name } }.thenBy { it.raw })
    }

    fun thumbnail(uri: String, w: Int, h: Int): Bitmap? = runCatching { resolver.loadThumbnail(Uri.parse(uri), Size(w, h), null) }.getOrNull()

    /** Decodes [uri] (gallery item or picked photo) with the long side ≤ [maxSide]. Throws if unreadable. */
    fun decode(uri: Uri, maxSide: Int): Img {
        val size = runCatching { resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } }.getOrNull() ?: -1L
        if (size > MAX_IMPORT_BYTES) throw java.io.IOException("too large: $size")
        val src = ImageDecoder.createSource(resolver, uri)
        val bmp =
            ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val k = maxOf(w, h).toFloat() / maxSide
                if (k > 1f) dec.setTargetSize((w / k).toInt().coerceAtLeast(1), (h / k).toInt().coerceAtLeast(1))
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        return Bitmaps.toImg(bmp)
    }

    fun displayName(uri: Uri): String =
        runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()
            ?: uri.lastPathSegment ?: "photo"

    companion object {
        const val MAX_IMPORT_BYTES = 50L * 1024 * 1024
    }
}

object Bitmaps {
    fun toImg(bmp: Bitmap): Img {
        val b = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Img(b.width, b.height, px)
    }

    fun toBitmap(img: Img): Bitmap = Bitmap.createBitmap(img.px, img.w, img.h, Bitmap.Config.ARGB_8888)

    /** RGB bytes of [img] letterboxed into [w] x [h] (the viewfinder's photo tensor, design E0 640 x 480). */
    fun rgbBytes(img: Img, w: Int, h: Int): ByteArray {
        val fit =
            if (img.w * h == img.h * w) img.resized(w, h)
            else {
                val k = minOf(w.toFloat() / img.w, h.toFloat() / img.h)
                img.resized((img.w * k).toInt().coerceAtLeast(1), (img.h * k).toInt().coerceAtLeast(1))
            }
        val out = ByteArray(w * h * 3) { 0x10 }
        val x0 = (w - fit.w) / 2
        val y0 = (h - fit.h) / 2
        for (y in 0 until fit.h) for (x in 0 until fit.w) {
            val c = fit.px[y * fit.w + x]
            val i = ((y + y0) * w + x + x0) * 3
            out[i] = (c shr 16).toByte()
            out[i + 1] = (c shr 8).toByte()
            out[i + 2] = c.toByte()
        }
        return out
    }

    fun fromRgb(bytes: ByteArray, w: Int, h: Int): Img {
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = bytes[i * 3].toInt() and 0xFF
            val g = bytes[i * 3 + 1].toInt() and 0xFF
            val b = bytes[i * 3 + 2].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Img(w, h, px)
    }
}
