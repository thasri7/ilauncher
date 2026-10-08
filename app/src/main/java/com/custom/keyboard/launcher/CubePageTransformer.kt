package com.custom.keyboard.launcher

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

class CubePageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        page.cameraDistance = page.width * 30f

        when {
            position < -1f -> {
                page.alpha = 0f
            }
            position <= 0f -> {
                page.alpha = 1f - abs(position) * 0.15f
                page.pivotX = page.width.toFloat()
                page.pivotY = page.height * 0.5f
                page.rotationY = 90f * position
            }
            position <= 1f -> {
                page.alpha = 1f - abs(position) * 0.15f
                page.pivotX = 0f
                page.pivotY = page.height * 0.5f
                page.rotationY = 90f * position
            }
            else -> {
                page.alpha = 0f
            }
        }
    }
}
