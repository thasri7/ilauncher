package com.custom.keyboard.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import com.custom.keyboard.models.TileSize
import kotlin.math.ceil

/** Hosts ordinary Android home-screen widgets inside tiles. */
class WidgetTiles(context: Context) {
    companion object {
        private const val HOST_ID = 0x4d455452 // "METR"
    }

    private val appContext = context.applicationContext
    val manager: AppWidgetManager = AppWidgetManager.getInstance(appContext)
    private val host = AppWidgetHost(appContext, HOST_ID)

    fun providers(): List<AppWidgetProviderInfo> =
        manager.installedProviders.sortedBy { label(it).lowercase() }

    fun label(info: AppWidgetProviderInfo): String = info.loadLabel(appContext.packageManager) ?: info.provider.packageName

    fun icon(context: Context, info: AppWidgetProviderInfo): Drawable? = try {
        info.loadIcon(context, context.resources.displayMetrics.densityDpi)
    } catch (_: Exception) {
        null
    }

    /** The widget's own preview picture, when it ships one. */
    fun preview(context: Context, info: AppWidgetProviderInfo): Drawable? = try {
        info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)
    } catch (_: Exception) {
        null
    }

    fun allocate(): Int = host.allocateAppWidgetId()

    /** The tile size a widget should start at, from its recommended cells or its minimum size. */
    fun defaultSize(info: AppWidgetProviderInfo, pitchPx: Float): TileSize {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            // Target cells refer to a ~4-column home screen; our cell is half of a medium tile.
            return when {
                info.targetCellHeight >= 3 -> TileSize.LARGE
                info.targetCellWidth >= 3 -> TileSize.WIDE
                info.targetCellWidth <= 1 && info.targetCellHeight <= 1 -> TileSize.MEDIUM
                else -> if (info.targetCellWidth >= 2 && info.targetCellHeight <= 1) TileSize.WIDE else TileSize.MEDIUM
            }
        }
        if (pitchPx <= 0f) return TileSize.MEDIUM
        val cols = ceil(info.minWidth / pitchPx).toInt()
        val rows = ceil(info.minHeight / pitchPx).toInt()
        return when {
            rows > 2 -> TileSize.LARGE
            cols > 2 -> TileSize.WIDE
            else -> TileSize.MEDIUM
        }
    }

    /** Sizes this widget can be shown at: big enough for its minimum, and only resizable directions. */
    fun allowedSizes(id: Int, pitchPx: Float): List<TileSize> {
        val info = manager.getAppWidgetInfo(id) ?: return TileSize.entries
        if (pitchPx <= 0f) return TileSize.entries
        val base = defaultSize(info, pitchPx)
        val canW = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val canH = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val minW = if (canW && info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val minH = if (canH && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return TileSize.entries.filter { s ->
            val okW = s.cols * pitchPx >= minW * 0.85f && (canW || s.cols == base.cols)
            val okH = s.rows * pitchPx >= minH * 0.85f && (canH || s.rows == base.rows)
            okW && okH
        }.ifEmpty { listOf(base) }
    }

    fun delete(id: Int) {
        if (id < 0) return
        try {
            host.deleteAppWidgetId(id)
        } catch (_: Exception) {
        }
    }

    /** True when Android lets us bind without asking (it asks once per provider otherwise). */
    fun bindIfAllowed(id: Int, info: AppWidgetProviderInfo): Boolean = try {
        manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
    } catch (_: Exception) {
        false
    }

    fun bindRequest(id: Int, info: AppWidgetProviderInfo): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)

    fun needsConfiguration(id: Int): Boolean = manager.getAppWidgetInfo(id)?.configure != null

    /** Starts the widget's own setup screen; the result arrives in onActivityResult. */
    fun startConfiguration(activity: Activity, id: Int, requestCode: Int): Boolean = try {
        host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null)
        true
    } catch (_: Exception) {
        false
    }

    fun createView(context: Context, id: Int): AppWidgetHostView? {
        val info = manager.getAppWidgetInfo(id) ?: return null
        return try {
            host.createView(context, id, info)
        } catch (_: Exception) {
            null
        }
    }

    /** Tells the widget how big its tile is so it can pick a matching layout. */
    fun updateSize(id: Int, widthDp: Int, heightDp: Int) {
        try {
            manager.updateAppWidgetOptions(id, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
            })
        } catch (_: Exception) {
        }
    }

    fun startListening() {
        try {
            host.startListening()
        } catch (_: Exception) {
        }
    }

    fun stopListening() {
        try {
            host.stopListening()
        } catch (_: Exception) {
        }
    }
}
