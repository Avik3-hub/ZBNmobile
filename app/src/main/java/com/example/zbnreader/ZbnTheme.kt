package com.example.zbnreader

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.View

data class ZbnPalette(
    val isLight: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceContainer: Int,
    val accent: Int,
    val accentText: Int,
    val amber: Int,
    val text: Int,
    val muted: Int,
    val border: Int,
    val disabledBackground: Int,
    val downloaded: Int,
    val error: Int,
    val blueprint: Int,
    val grid: Int
)

object ZbnTheme {
    const val PREF_LIGHT_THEME = "light_theme_enabled"

    fun isLight(context: Context): Boolean =
        context.getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
            .getBoolean(PREF_LIGHT_THEME, false)

    fun palette(context: Context): ZbnPalette = if (isLight(context)) LIGHT else DARK

    fun applySystemBars(activity: Activity, palette: ZbnPalette) {
        activity.window.statusBarColor = palette.background
        activity.window.navigationBarColor = palette.background
        var flags = activity.window.decorView.systemUiVisibility
        flags = if (palette.isLight) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        activity.window.decorView.systemUiVisibility = flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isStatusBarContrastEnforced = false
            activity.window.isNavigationBarContrastEnforced = false
        }
    }

    private val DARK = ZbnPalette(
        isLight = false,
        background = Color.parseColor("#080A0D"),
        surface = Color.parseColor("#14181D"),
        surfaceContainer = Color.parseColor("#20262D"),
        accent = Color.parseColor("#A8C7FA"),
        accentText = Color.parseColor("#071526"),
        amber = Color.parseColor("#F1B45B"),
        text = Color.parseColor("#F2F5F7"),
        muted = Color.parseColor("#89929C"),
        border = Color.parseColor("#343C45"),
        disabledBackground = Color.parseColor("#101317"),
        downloaded = Color.parseColor("#1A3852"),
        error = Color.parseColor("#4A2828"),
        blueprint = Color.parseColor("#4F8BC4"),
        grid = Color.parseColor("#101A24")
    )

    private val LIGHT = ZbnPalette(
        isLight = true,
        background = Color.parseColor("#EEF2F6"),
        surface = Color.parseColor("#FFFFFF"),
        surfaceContainer = Color.parseColor("#E3EAF2"),
        accent = Color.parseColor("#8FB8EE"),
        accentText = Color.parseColor("#10243C"),
        amber = Color.parseColor("#B56A16"),
        text = Color.parseColor("#17212B"),
        muted = Color.parseColor("#65717D"),
        border = Color.parseColor("#C6D0DA"),
        disabledBackground = Color.parseColor("#E2E7EC"),
        downloaded = Color.parseColor("#D7EAF8"),
        error = Color.parseColor("#F3DADA"),
        blueprint = Color.parseColor("#3979AD"),
        grid = Color.parseColor("#CEDBE7")
    )
}
