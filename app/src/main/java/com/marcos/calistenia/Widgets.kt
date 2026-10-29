package com.marcos.calistenia
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
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
fun idsOf(c: Context, id: Int) = prefs(c).getString("w$id", "")!!.split(",").filter { it.isNotBlank() }

data class Ser(val name: String, val color: Int, val pts: List<Pair<Long, Float>>)
val PAL = listOf(0xFF80CBC4, 0xFFFFB74D, 0xFF9FA8DA, 0xFFF48FB1, 0xFFAED581, 0xFFFFF176, 0xFF4FC3F7, 0xFFE57373, 0xFFBA68C8, 0xFFA1887F).map { it.toInt() }

fun buildSeries(ex: List<Exercise>, sets: List<SetEntry>, ws: List<BodyWeight>, ids: List<String>, idx: (String) -> Int = { ids.indexOf(it) }): List<Ser> =
    ids.mapNotNull { id ->
        val p: Pair<String, List<Pair<Long, Float>>>? =
            if (id == "w") "Peso" to ws.map { it.day to it.kg }
            else ex.find { "e${it.id}" == id }?.let { e -> e.name to sets.filter { it.exerciseId == e.id }.groupBy { it.day }.toSortedMap().map { (k, l) -> k to l.maxOf { it.value }.toFloat() } }
        p?.takeIf { it.second.isNotEmpty() }?.let { Ser(it.first, PAL[idx(id) % PAL.size], it.second) }
    }

fun loadSeries(ctx: Context, ids: List<String>): List<Ser> = runBlocking {
    val d = Db.get(ctx); buildSeries(d.exercises().first(), d.sets().first(), d.weights().first(), ids) }

fun drawChart(c: Canvas, W: Int, H: Int, ser: List<Ser>, legend: Boolean, bg: Boolean) {
    if (bg) c.drawRoundRect(0f, 0f, W.toFloat(), H.toFloat(), 40f, 40f, Paint().apply { color = 0xFF1C1B1F.toInt() })
    val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = Color.LTGRAY }
    if (ser.isEmpty()) { c.drawText("Sin datos todavía", 40f, H / 2f, t); return }
    val all = ser.flatMap { s -> s.pts.map { it.first } }; val d0 = all.min(); val d1 = maxOf(all.max(), d0 + 1)
    val l = 40f; val r = W - 40f; val top = if (legend) 80f else 30f; val bot = H - 45f
    var lx = 40f
    ser.forEach { s ->
        val mn = s.pts.minOf { it.second }; val mx = s.pts.maxOf { it.second }; val rg = if (mx > mn) mx - mn else 1f
        val pt = s.pts.map { (dy, v) -> PointF(l + (dy - d0) * (r - l) / (d1 - d0), bot - (v - mn) / rg * (bot - top)) }
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; strokeWidth = 5f }
        for (k in 1 until pt.size) c.drawLine(pt[k - 1].x, pt[k - 1].y, pt[k].x, pt[k].y, lp)
        pt.forEach { c.drawCircle(it.x, it.y, 7f, lp) }
        if (legend) { val lab = if (ser.size == 1) "${s.name}  ($mn–$mx)" else s.name
            t.color = s.color; c.drawText(lab, lx, 45f, t); lx += t.measureText(lab) + 28f }
    }
    t.color = Color.GRAY; t.textSize = 22f
    c.drawText(java.time.LocalDate.ofEpochDay(d0).toString(), l, H - 12f, t)
    val e = java.time.LocalDate.ofEpochDay(d1).toString(); c.drawText(e, r - t.measureText(e), H - 12f, t)
}

fun upd(ctx: Context, m: AppWidgetManager, id: Int) {
    val multi = m.getAppWidgetInfo(id)?.provider?.className?.endsWith("WMulti") == true
    val W = if (multi) 500 else 700; val H = 350
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    drawChart(Canvas(bmp), W, H, loadSeries(ctx, idsOf(ctx, id)), legend = !multi, bg = !multi)
    val v = RemoteViews(ctx.packageName, if (multi) R.layout.widget_multi else R.layout.widget)
    v.setImageViewBitmap(R.id.img, bmp)
    v.setOnClickPendingIntent(R.id.img, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
    if (multi) v.setRemoteAdapter(R.id.list, Intent(ctx, LegendService::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id); data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) })
    m.updateAppWidget(id, v)
    if (multi) m.notifyAppWidgetViewDataChanged(id, R.id.list)
}

class LegendService : RemoteViewsService() {
    override fun onGetViewFactory(i: Intent): RemoteViewsFactory = Fac(applicationContext, i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
}
class Fac(val c: Context, val id: Int) : RemoteViewsService.RemoteViewsFactory {
    var items = listOf<Ser>()
    override fun onCreate() {}
    override fun onDataSetChanged() { items = loadSeries(c, idsOf(c, id)) }
    override fun getCount() = items.size
    override fun getViewAt(i: Int) = RemoteViews(c.packageName, R.layout.legend_item).apply {
        setTextViewText(R.id.t, "● ${items[i].name}"); setTextColor(R.id.t, items[i].color) }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(i: Int) = i.toLong()
    override fun hasStableIds() = true
    override fun onDestroy() {}
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
