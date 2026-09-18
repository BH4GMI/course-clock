package courseclock.timetable.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

/** Widgets follow the desktop's system theme, independently of the app theme preference. */
object WidgetTheme {
    fun context(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        return context.createConfigurationContext(config)
    }

    fun color(views: RemoteViews, context: Context, viewId: Int, method: String, @ColorRes color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Resolve in the host alongside the panel background, including later theme changes.
            views.setColor(viewId, method, color)
        } else {
            views.setInt(viewId, method, ContextCompat.getColor(WidgetTheme.context(context), color))
        }
    }
}
