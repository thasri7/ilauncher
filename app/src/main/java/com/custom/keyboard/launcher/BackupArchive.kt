package com.custom.keyboard.launcher

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A complete Start backup in one .zip: every setting and every space's tiles (settings.json),
 * plus the pictures tiles use (photos, people, tile pictures, the Start background). Saving it
 * through the system file picker lets you keep it on Google Drive or any other cloud.
 */
object BackupArchive {
    private const val SETTINGS = "settings.json"
    private const val MEDIA = "tile_media/"
    private const val BACKGROUND = "start_background.jpg"

    fun write(context: Context, prefs: TilePreferences, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(SETTINGS))
            zip.write(prefs.exportJson().toByteArray())
            zip.closeEntry()
            val root = TileMedia.mediaRoot(context)
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry(MEDIA + f.relativeTo(root).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            TileMedia.backgroundFile(context).takeIf { it.exists() }?.let { f ->
                zip.putNextEntry(ZipEntry(BACKGROUND))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Restores a .zip from [write], or an older plain JSON backup. Returns false when the data
     * isn't a Start backup (nothing is changed then).
     */
    fun read(context: Context, prefs: TilePreferences, bytes: ByteArray): Boolean {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) return prefs.importJson(bytes.toString(Charsets.UTF_8))
        val files = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) files[entry.name] = zip.readBytes()
            }
        }
        val settings = files[SETTINGS] ?: return false
        if (!prefs.importJson(settings.toString(Charsets.UTF_8))) return false
        val root = TileMedia.mediaRoot(context).canonicalFile
        root.deleteRecursively()
        files.forEach { (name, data) ->
            when {
                name.startsWith(MEDIA) -> {
                    val target = File(root, name.removePrefix(MEDIA)).canonicalFile
                    // Never write outside the media folder, whatever the archive says.
                    if (target.path.startsWith(root.path + File.separator)) {
                        target.parentFile?.mkdirs()
                        target.writeBytes(data)
                    }
                }
                name == BACKGROUND -> TileMedia.backgroundFile(context).writeBytes(data)
            }
        }
        return true
    }

    fun readAll(input: InputStream): ByteArray = input.use { it.readBytes() }
}
