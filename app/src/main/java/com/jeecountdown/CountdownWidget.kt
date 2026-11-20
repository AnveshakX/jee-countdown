package com.jeecountdown

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews

class CountdownWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        update(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> updateAll(context)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CountdownWidget::class.java))
            ids.forEach { update(context, mgr, it) }
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int) {
            val o = mgr.getAppWidgetOptions(id)
            val d = context.resources.displayMetrics.density
            var w = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) * d)
            var h = (o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) * d)
            val big = maxOf(w, h)
            if (big > 720f) {
                w *= 720f / big
                h *= 720f / big
            }
            val bmp = Bitmap.createBitmap(maxOf(1, w.toInt()), maxOf(1, h.toInt()), Bitmap.Config.ARGB_8888)
            Renderer(context.applicationContext).drawWidget(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat())
            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setImageViewBitmap(R.id.img, bmp)
            views.setOnClickPendingIntent(
                R.id.img,
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            mgr.updateAppWidget(id, views)
        }
    }
}
