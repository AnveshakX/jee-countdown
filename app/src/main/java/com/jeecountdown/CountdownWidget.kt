package com.jeecountdown

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import java.time.ZonedDateTime

/**
 * Home-screen widget. Refresh sources:
 *  - system periodic update (30 min, a fallback only);
 *  - an inexact alarm just after every local midnight (the manifest cannot receive DATE_CHANGED on API 26+, so the
 *    day number would otherwise stay stale for up to 30 minutes);
 *  - settings/countdown changes made in the app;
 *  - TIME_SET and TIMEZONE_CHANGED (both are exempt from the implicit-broadcast limits).
 * After a reboot the system sends APPWIDGET_UPDATE, which re-arms the midnight alarm, so no BOOT_COMPLETED receiver
 * (or extra permission) is needed. The widget uses the same CountdownModel as every other surface.
 */
class CountdownWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        scheduleMidnightRefresh(context)
    }

    override fun onDisabled(context: Context) {
        cancelMidnightRefresh(context)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
        scheduleMidnightRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        update(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_MIDNIGHT_REFRESH, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> updateAll(context)
        }
    }

    companion object {
        private const val TAG = "CountdownWidget"
        const val ACTION_MIDNIGHT_REFRESH = "com.jeecountdown.action.MIDNIGHT_REFRESH"
        private const val REQ_MIDNIGHT = 1
        private const val MIDNIGHT_SLACK_MS = 1_000L  // land just after midnight, never just before
        private const val MAX_BITMAP_SIDE_PX = 720f   // keeps the RemoteViews payload small

        fun updateAll(context: Context) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, CountdownWidget::class.java))
            if (ids.isEmpty()) {
                cancelMidnightRefresh(app)
                return
            }
            ids.forEach { update(app, mgr, it) }
            scheduleMidnightRefresh(app)
        }

        private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, REQ_MIDNIGHT,
            Intent(context, CountdownWidget::class.java).setAction(ACTION_MIDNIGHT_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /**
         * Inexact on purpose: no SCHEDULE_EXACT_ALARM permission, no forced wake-up (RTC, not RTC_WAKEUP). If the
         * device is asleep at midnight the refresh runs when it next wakes, i.e. before anyone can look at the widget.
         */
        private fun scheduleMidnightRefresh(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val at = Cadence.nextMidnightMillis(ZonedDateTime.now()) + MIDNIGHT_SLACK_MS
                am.setAndAllowWhileIdle(AlarmManager.RTC, at, refreshIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule the midnight refresh", e)
            }
        }

        private fun cancelMidnightRefresh(context: Context) {
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(refreshIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Could not cancel the midnight refresh", e)
            }
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val app = context.applicationContext
            val renderer = Renderer(app)
            try {
                val o = mgr.getAppWidgetOptions(id)
                val d = app.resources.displayMetrics.density
                var w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) * d
                var h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) * d
                val big = maxOf(w, h)
                if (big > MAX_BITMAP_SIDE_PX) {
                    w *= MAX_BITMAP_SIDE_PX / big
                    h *= MAX_BITMAP_SIDE_PX / big
                }
                val bmp = Bitmap.createBitmap(maxOf(1, w.toInt()), maxOf(1, h.toInt()), Bitmap.Config.ARGB_8888)
                renderer.drawWidget(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat())
                val views = RemoteViews(app.packageName, R.layout.widget)
                views.setImageViewBitmap(R.id.img, bmp)
                views.setOnClickPendingIntent(
                    R.id.img,
                    PendingIntent.getActivity(
                        app, 0, Intent(app, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                mgr.updateAppWidget(id, views)
            } catch (e: Exception) {
                Log.w(TAG, "Widget $id update failed", e)
            } finally {
                renderer.release()
            }
        }
    }
}
