package com.custom.keyboard.launcher

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/** Page transitions between Start and All apps. */
object PageTransformers {
    fun forName(name: String): ViewPager2.PageTransformer = when (name) {
        "cube" -> CubePageTransformer()
        "depth" -> DepthPageTransformer()
        else -> MetroSlideTransformer()
    }

    /** Transformers leave properties behind on the pages; clear them before switching. */
    fun apply(pager: ViewPager2, name: String) {
        (pager.getChildAt(0) as? RecyclerView)?.let { rv ->
            for (i in 0 until rv.childCount) {
                rv.getChildAt(i)?.apply {
                    translationX = 0f
                    rotationY = 0f
                    scaleX = 1f
                    scaleY = 1f
                    alpha = 1f
                }
            }
        }
        pager.setPageTransformer(forName(name))
        pager.requestTransform()
    }
}

/** Windows 10 Mobile: the outgoing page lags behind (parallax) and fades as the next one slides over. */
class MetroSlideTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        when {
            position <= -1f || position >= 1f -> page.alpha = 0f
            position <= 0f -> {
                page.alpha = 1f + position * 0.8f
                page.translationX = -position * page.width * 0.35f
            }
            else -> {
                page.alpha = 1f
                page.translationX = 0f
            }
        }
    }
}

/** The incoming page rises from behind the current one. */
class DepthPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        when {
            position <= -1f || position >= 1f -> page.alpha = 0f
            position <= 0f -> {
                page.alpha = 1f
                page.translationX = 0f
                page.scaleX = 1f
                page.scaleY = 1f
            }
            else -> {
                page.alpha = 1f - position
                page.translationX = page.width * -position
                val scale = 0.8f + 0.2f * (1f - abs(position))
                page.scaleX = scale
                page.scaleY = scale
            }
        }
    }
}
