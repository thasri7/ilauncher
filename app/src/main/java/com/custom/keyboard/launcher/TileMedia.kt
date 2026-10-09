package com.custom.keyboard.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Pictures used by tiles (Photos slideshows, People photos, the Start background). Pictures are
 * picked with the system picker, downscaled and copied into app storage, so no storage
 * permission is needed and the tiles keep working if the original is deleted.
 */
object TileMedia {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun tileDir(context: Context, tileId: String): File =
        File(context.filesDir, "tile_media/${tileId.replace(Regex("[^A-Za-z0-9_-]"), "_")}")

    fun photos(context: Context, tileId: String): List<File> =
        tileDir(context, tileId).listFiles { f -> f.name.startsWith("photo_") }?.sortedBy { it.name }.orEmpty()

    fun portrait(context: Context, tileId: String): File? =
        File(tileDir(context, tileId), "portrait.jpg").takeIf { it.exists() }

    fun backgroundFile(context: Context): File = File(context.filesDir, "start_background.jpg")

    fun deleteTile(context: Context, tileId: String) {
        executor.execute { tileDir(context, tileId).deleteRecursively() }
    }

    /** Replaces a Photos tile's pictures. [onDone] gets how many were saved. */
    fun savePhotos(context: Context, tileId: String, uris: List<Uri>, onDone: (Int) -> Unit) {
        executor.execute {
            val dir = tileDir(context, tileId)
            dir.listFiles { f -> f.name.startsWith("photo_") }?.forEach { it.delete() }
            dir.mkdirs()
            var saved = 0
            uris.take(20).forEachIndexed { i, uri ->
                if (copyScaled(context, uri, File(dir, "photo_%02d.jpg".format(i)), 1280)) saved++
            }
            evictAll()
            main.post { onDone(saved) }
        }
    }

    fun savePortrait(context: Context, tileId: String, uri: Uri, onDone: (Boolean) -> Unit) {
        executor.execute {
            val dir = tileDir(context, tileId).apply { mkdirs() }
            val ok = copyScaled(context, uri, File(dir, "portrait.jpg"), 720)
            evictAll()
            main.post { onDone(ok) }
        }
    }

    fun saveBackground(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        val dm = context.resources.displayMetrics
        executor.execute {
            val ok = copyScaled(context, uri, backgroundFile(context), max(dm.widthPixels, dm.heightPixels))
            evictAll()
            main.post { onDone(ok) }
        }
    }

    /** Decodes [file] to roughly [maxSide] pixels, cached, delivered on the main thread. */
    fun loadAsync(file: File, maxSide: Int, callback: (Bitmap?) -> Unit) {
        val key = "${file.path}@$maxSide"
        cache.get(key)?.let {
            callback(it)
            return
        }
        executor.execute {
            val bitmap = decodeFile(file, maxSide)
            if (bitmap != null) cache.put(key, bitmap)
            main.post { callback(bitmap) }
        }
    }

    fun loadNow(file: File, maxSide: Int): Bitmap? {
        val key = "${file.path}@$maxSide"
        return cache.get(key) ?: decodeFile(file, maxSide)?.also { cache.put(key, it) }
    }

    private fun evictAll() = cache.evictAll()

    private fun decodeFile(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun copyScaled(context: Context, uri: Uri, target: File, maxSide: Int): Boolean = try {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the photo's EXIF rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val size = info.size
                val scale = maxSide.toFloat() / max(size.width, size.height)
                if (scale < 1f) decoder.setTargetSize((size.width * scale).toInt(), (size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
        if (bitmap == null) {
            false
        } else {
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            true
        }
    } catch (_: Exception) {
        false
    }
}
