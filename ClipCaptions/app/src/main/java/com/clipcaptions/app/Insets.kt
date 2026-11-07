package com.clipcaptions.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps a screen's content clear of the status bar, navigation bar and camera cutout (Android 15
 * draws apps behind them), keeping [side] and [top] as extra space inside.
 */
internal fun padForSystemBars(view: View, side: Int, top: Int) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(bars.left + side, bars.top + top, bars.right + side, bars.bottom + side / 3)
        insets
    }
}
