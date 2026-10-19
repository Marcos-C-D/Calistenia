package com.marcos.calistenia
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.widget.RemoteViews
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

object Db {
    @Volatile private var i: D? = null
    fun get(c: Context): D = i ?: synchronized(this) {
        i ?: Room.databaseBuilder(c.applicationContext, DB::class.java, "cal.db").build().d().also { i = it } }
}
fun prefs(c: Context) = c.getSharedPreferences("wid", 0)

fun render(ctx: Context, ids: List<String>): Bitmap {
    val W = 700; val H = 350
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
    c.drawRoundRect(0f, 0f, W.toFloat(), H.toFloat(), 40f, 40f, Paint().apply { color = 0xFF1C1B1F.toInt() })
    val d = Db.get(ctx)
    val (ex, sets, ws) = runBlocking { Triple(d.exercises().first(), d.sets().first(), d.weights().first()) }
    val cols = listOf(0xFF80CBC4, 0xFFFFB74D, 0xFF9FA8DA, 0xFFF48FB1, 0xFFAED581, 0xFFFFF176).map { it.toInt() }
    val ser = ids.mapNotNull { id ->
        if (id == "w") "Peso" to ws.map { it.day to it.kg }
        else ex.find { "e${it.id}" == id }?.let { e -> e.name to sets.filter { it.exerciseId == e.id }.groupBy { it.day }.toSortedMap().map { (k, l) -> k to l.maxOf { it.value }.toFloat() } }
    }.filter { it.second.isNotEmpty() }
    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = Color.LTGRAY }
    if (ser.isEmpty()) { c.drawText("Sin datos todavía", 40f, H / 2f, t); return bmp }
    val all = ser.flatMap { s -> s.second.map { it.first } }; val d0 = all.min(); val d1 = maxOf(all.max(), d0 + 1)
    val l = 40f; val r = W - 40f; val top = 80f; val bot = H - 45f
    var lx = 40f
    ser.forEachIndexed { i, (n, p) ->
        val col = cols[i % cols.size]; val mn = p.minOf { it.second }; val mx = p.maxOf { it.second }; val rg = if (mx > mn) mx - mn else 1f
        val pt = p.map { (dy, v) -> PointF(l + (dy - d0) * (r - l) / (d1 - d0), bot - (v - mn) / rg * (bot - top)) }
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col; strokeWidth = 5f }
        for (k in 1 until pt.size) c.drawLine(pt[k - 1].x, pt[k - 1].y, pt[k].x, pt[k].y, lp)
        pt.forEach { c.drawCircle(it.x, it.y, 7f, lp) }
        val lab = if (ser.size == 1) "$n  ($mn–$mx)" else n
        t.color = col; c.drawText(lab, lx, 45f, t); lx += t.measureText(lab) + 28f
    }
    t.color = Color.GRAY; t.textSize = 22f
    c.drawText(java.time.LocalDate.ofEpochDay(d0).toString(), l, H - 12f, t)
    val e = java.time.LocalDate.ofEpochDay(d1).toString(); c.drawText(e, r - t.measureText(e), H - 12f, t)
    return bmp
}

fun upd(ctx: Context, m: AppWidgetManager, id: Int) {
    val ids = prefs(ctx).getString("w$id", "")!!.split(",").filter { it.isNotBlank() }
    val v = RemoteViews(ctx.packageName, R.layout.widget)
    v.setImageViewBitmap(R.id.img, render(ctx, ids))
    v.setOnClickPendingIntent(R.id.img, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
    m.updateAppWidget(id, v)
}

open class BaseW : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val p = goAsync(); thread { ids.forEach { upd(c, m, it) }; p.finish() }
    }
    override fun onDeleted(c: Context, ids: IntArray) { prefs(c).edit().apply { ids.forEach { remove("w$it") } }.apply() }
}
class WSingle : BaseW()
class WMulti : BaseW()

object Widgets {
    fun refresh(c: Context) = thread {
        val m = AppWidgetManager.getInstance(c)
        listOf(WSingle::class.java, WMulti::class.java).forEach { k -> m.getAppWidgetIds(ComponentName(c, k)).forEach { upd(c, m, it) } }
    }
}
