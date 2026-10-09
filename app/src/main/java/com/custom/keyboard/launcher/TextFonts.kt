package com.custom.keyboard.launcher

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File

/** A font file the user picked for the Text page, copied into the app so it keeps working. */
object TextFonts {
    private var cached: Pair<Long, Typeface>? = null

    private fun file(context: Context) = File(context.filesDir, "fonts/text_font")

    fun hasCustom(context: Context) = file(context).exists()

    fun custom(context: Context): Typeface? {
        val f = file(context)
        if (!f.exists()) return null
        cached?.takeIf { it.first == f.lastModified() }?.let { return it.second }
        return runCatching { Typeface.createFromFile(f) }.getOrNull()?.also { cached = f.lastModified() to it }
    }

    /** Copies a .ttf / .otf and checks Android can read it. Returns false (and keeps nothing) if not. */
    fun import(context: Context, uri: Uri): Boolean {
        val target = file(context)
        val temp = File(target.parentFile, "text_font.tmp")
        return try {
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: return false
            Typeface.createFromFile(temp)
            temp.renameTo(target).also { cached = null }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    fun remove(context: Context) {
        file(context).delete()
        cached = null
    }
}
