package com.marcos.calistenia
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class VM(app: Application) : AndroidViewModel(app) {
    private val d = Db.get(app)
    val ex = d.exercises().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sets = d.sets().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val w = d.weights().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun addEx(n: String, t: Boolean) = viewModelScope.launch { d.addEx(Exercise(name = n, isTime = t)) }
    fun delEx(e: Exercise) = viewModelScope.launch { d.delSetsOf(e.id); d.delEx(e); Widgets.refresh(getApplication()) }
    fun upEx(e: Exercise) = viewModelScope.launch { d.upEx(e); Widgets.refresh(getApplication()) }
    fun addSet(s: SetEntry) = viewModelScope.launch { d.addSet(s); Widgets.refresh(getApplication()) }
    fun del(s: SetEntry) = viewModelScope.launch { d.delSet(s); Widgets.refresh(getApplication()) }
    fun putW(day: Long, kg: Float) = viewModelScope.launch { d.putW(BodyWeight(day, kg)); Widgets.refresh(getApplication()) }
    fun import(txt: String, done: (Boolean) -> Unit) = viewModelScope.launch {
        done(runCatching {
            val o = JSONObject(txt); val map = HashMap<Long, Long>(); val cur = ex.value.toMutableList()
            val oe = o.getJSONArray("ejercicios")
            for (i in 0 until oe.length()) {
                val e = oe.getJSONObject(i); val n = e.getString("nombre"); val t = e.getBoolean("tiempo")
                val id = cur.find { it.name == n }?.id ?: d.addEx(Exercise(name = n, isTime = t)).also { cur.add(Exercise(it, n, t)) }
                map[e.getLong("id")] = id
            }
            val have = sets.value.map { listOf(it.exerciseId, it.day, it.value, it.extraKg, it.note) }.toMutableSet()
            val os = o.getJSONArray("sets")
            for (i in 0 until os.length()) {
                val s = os.getJSONObject(i); val eid = map[s.getLong("ejercicioId")] ?: continue
                val day = LocalDate.parse(s.getString("fecha")).toEpochDay(); val v = s.getInt("valor")
                val kg = s.getDouble("kgExtra").toFloat(); val nt = s.getString("nota")
                if (have.add(listOf(eid, day, v, kg, nt))) d.addSet(SetEntry(exerciseId = eid, day = day, value = v, extraKg = kg, note = nt))
            }
            val ow = o.getJSONArray("pesoCorporal")
            for (i in 0 until ow.length()) { val w = ow.getJSONObject(i); d.putW(BodyWeight(LocalDate.parse(w.getString("fecha")).toEpochDay(), w.getDouble("kg").toFloat())) }
            Widgets.refresh(getApplication())
        }.isSuccess)
    }
    fun json(): String = JSONObject()
        .put("ejercicios", JSONArray(ex.value.map { JSONObject().put("id", it.id).put("nombre", it.name).put("tiempo", it.isTime) }))
        .put("sets", JSONArray(sets.value.map { JSONObject().put("ejercicioId", it.exerciseId).put("fecha", LocalDate.ofEpochDay(it.day).toString())
            .put("valor", it.value).put("kgExtra", it.extraKg.toDouble()).put("nota", it.note) }))
        .put("pesoCorporal", JSONArray(w.value.map { JSONObject().put("fecha", LocalDate.ofEpochDay(it.day).toString()).put("kg", it.kg.toDouble()) }))
        .toString(2)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { MaterialTheme(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App(viewModel()) } } }
    }
}

@Composable fun App(vm: VM) {
    var tab by remember { mutableIntStateOf(0) }
    var day by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    Scaffold(bottomBar = { NavigationBar { listOf("Entreno", "Progreso", "Peso", "Todos").forEachIndexed { i, t ->
        NavigationBarItem(tab == i, { tab = i }, {}, label = { Text(t) }) } } }) { p ->
        Column(Modifier.padding(p).padding(16.dp).statusBarsPadding()) { if (tab == 0 || tab == 2) DayBar(day) { day = it }; when (tab) { 0 -> Train(vm, day); 1 -> Stats(vm); 2 -> Weight(vm, day); else -> All(vm) } }
    }
}

@Composable fun F(v: String, l: String, m: Modifier = Modifier, num: Boolean = true, c: (String) -> Unit) =
    OutlinedTextField(v, c, modifier = m, label = { Text(l) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (num) KeyboardType.Decimal else KeyboardType.Text))

@Composable fun Chips(l: List<Exercise>, sel: Exercise?, on: (Exercise) -> Unit) =
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(l) { FilterChip(sel?.id == it.id, { on(it) }, { Text(it.name) }) } }

@Composable fun Train(vm: VM, today: Long) {
    val exs by vm.ex.collectAsState(); val all by vm.sets.collectAsState()
    val since = LocalDate.now().toEpochDay() - 30
    val fr = all.filter { it.day >= since }.groupingBy { it.exerciseId }.eachCount()
    val vis = exs.filter { !it.hidden }.sortedWith(compareByDescending<Exercise> { fr[it.id] ?: 0 }.thenBy { it.name.lowercase() })
    var sel by remember { mutableStateOf<Exercise?>(null) }
    val cur = vis.find { it.id == sel?.id } ?: vis.firstOrNull()
    var dlg by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var v by remember { mutableStateOf("") }; var kg by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Picker(vis, cur) { sel = it } }
        TextButton({ dlg = true }) { Text("+ Ejercicio") }
    }
    TextButton({ manage = true }) { Text("Gestionar ejercicios") }
    if (manage) Manage(vm) { manage = false }
    if (del && cur != null) AlertDialog({ del = false },
        confirmButton = { TextButton({ vm.delEx(cur); del = false; sel = null }) { Text("Eliminar") } },
        dismissButton = { TextButton({ del = false }) { Text("Cancelar") } },
        title = { Text("¿Eliminar ${cur.name}?") }, text = { Text("Se borrarán también todos sus sets.") })
    if (cur == null) { Text("Crea tu primer ejercicio con \"+ Ejercicio\"", Modifier.padding(top = 24.dp)); }
    else {
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            F(v, if (cur.isTime) "Segundos" else "Reps", Modifier.weight(1f)) { v = it }
            F(kg, "Kg extra", Modifier.weight(1f)) { kg = it }
        }
        F(note, "Nota (opcional)", Modifier.fillMaxWidth().padding(top = 8.dp), false) { note = it }
        Button({ v.toIntOrNull()?.let { vm.addSet(SetEntry(exerciseId = cur.id, day = today, value = it, extraKg = kg.toFloatOrNull() ?: 0f, note = note)); v = ""; note = "" } },
            Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Añadir set") }
    }
    Text("Sets del ${LocalDate.ofEpochDay(today)}", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    LazyColumn { items(all.filter { it.day == today }.reversed()) { s ->
        val e = exs.find { it.id == s.exerciseId }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${e?.name}: ${s.value}${if (e?.isTime == true) " s" else " reps"}${if (s.extraKg > 0) " +${s.extraKg}kg" else ""}${if (s.note.isNotBlank()) " · ${s.note}" else ""}", Modifier.weight(1f))
            TextButton({ vm.del(s) }) { Text("Borrar") }
        } } }
    if (dlg) { var n by remember { mutableStateOf("") }; var t by remember { mutableStateOf(false) }
        AlertDialog({ dlg = false }, confirmButton = { TextButton({ if (n.isNotBlank()) { vm.addEx(n.trim(), t); dlg = false } }) { Text("Crear") } },
            title = { Text("Nuevo ejercicio") }, text = { Column { F(n, "Nombre", num = false) { n = it }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(t, { t = it }); Text("Se mide en tiempo") } } }) }
}

@Composable fun Chart(v: List<Float>) {
    if (v.size < 2) { Text("Aún no hay datos suficientes", Modifier.padding(top = 16.dp)); return }
    val c = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(180.dp).padding(top = 16.dp)) {
        val mn = v.min(); val r = (v.max() - mn).takeIf { it > 0 } ?: 1f
        val pts = v.mapIndexed { i, y -> Offset(size.width * i / (v.size - 1), size.height * (1 - (y - mn) / r)) }
        for (i in 1 until pts.size) drawLine(c, pts[i - 1], pts[i], 4f)
        pts.forEach { drawCircle(c, 7f, it) }
    }
    Text("mín ${v.min()} · máx ${v.max()}")
}

@Composable fun Stats(vm: VM) {
    val exs by vm.ex.collectAsState(); val all by vm.sets.collectAsState()
    var sel by remember { mutableStateOf<Exercise?>(null) }
    val cur = exs.find { it.id == sel?.id } ?: exs.firstOrNull() ?: run { Text("Sin ejercicios todavía"); return }
    val mine = all.filter { it.exerciseId == cur.id }
    var selDay by remember(cur.id) { mutableStateOf<Long?>(null) }
    val u = if (cur.isTime) "s" else "reps"
    Chips(exs, cur) { sel = it }
    val pr = mine.maxWithOrNull(compareBy({ it.extraKg }, { it.value }))
    Text(if (pr == null) "Sin registros" else "PR: ${pr.value} $u${if (pr.extraKg > 0) " con +${pr.extraKg}kg" else ""} (${LocalDate.ofEpochDay(pr.day)})",
        Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
    val p = mine.groupBy { it.day }.toSortedMap().map { (k, l) -> k to l.maxOf { it.value }.toFloat() }
    Text("Mejor set por día · toca un punto", style = MaterialTheme.typography.bodySmall)
    ProgChart(p, p.indexOfFirst { it.first == selDay }, u) { selDay = p[it].first }
    selDay?.let { d ->
        Text(LocalDate.ofEpochDay(d).toString(), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        LazyColumn { items(mine.filter { it.day == d }.sortedBy { it.ts }.withIndex().toList()) { (i, st) ->
            Text("Set ${i + 1}: ${st.value} $u${if (st.extraKg > 0) " +${st.extraKg}kg" else ""}${if (st.note.isNotBlank()) " · ${st.note}" else ""}") } }
    }
}

@Composable fun ColumnScope.Weight(vm: VM, day: Long) {
    val ws by vm.w.collectAsState(); var kg by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        u?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(vm.json().toByteArray()) } } }
    Text("Peso corporal", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        F(kg, "Kg del día", Modifier.weight(1f)) { kg = it }
        Button({ kg.toFloatOrNull()?.let { vm.putW(day, it); kg = "" } }) { Text("Guardar") }
    }
    ws.lastOrNull()?.let { Text("Último: ${it.kg} kg (${LocalDate.ofEpochDay(it.day)})", Modifier.padding(top = 8.dp)) }
    Chart(ws.map { it.kg })
    Spacer(Modifier.weight(1f))
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { val t = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
            if (t != null) vm.import(t) { ok -> android.widget.Toast.makeText(ctx, if (ok) "Datos importados" else "Archivo no válido", android.widget.Toast.LENGTH_SHORT).show() } } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ save.launch("calistenia.json") }, Modifier.weight(1f)) { Text("Exportar JSON") }
        OutlinedButton({ load.launch(arrayOf("*/*")) }, Modifier.weight(1f)) { Text("Importar JSON") }
    }
}

@Composable fun DayBar(day: Long, set: (Long) -> Unit) {
    val c = LocalContext.current
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton({ set(day - 1) }) { Text("◀") }
        OutlinedButton({ val d = LocalDate.ofEpochDay(day)
            android.app.DatePickerDialog(c, { _, y, m, dd -> set(LocalDate.of(y, m + 1, dd).toEpochDay()) }, d.year, d.monthValue - 1, d.dayOfMonth).show() }) { Text(LocalDate.ofEpochDay(day).toString()) }
        TextButton({ set(day + 1) }) { Text("▶") }
        TextButton({ set(LocalDate.now().toEpochDay()) }) { Text("Hoy") }
    }
}

@Composable fun All(vm: VM) {
    val ex by vm.ex.collectAsState(); val sets by vm.sets.collectAsState(); val ws by vm.w.collectAsState()
    val opts = listOf("w" to "Peso") + ex.map { "e${it.id}" to it.name }
    var off by remember { mutableStateOf(setOf<String>()) }
    val ser = buildSeries(ex, sets, ws, opts.map { it.first }.filter { it !in off }) { id -> opts.indexOfFirst { it.first == id } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(opts) { (k, n) ->
        FilterChip(k !in off, { off = if (k in off) off - k else off + k }, {
            Row { Text("●", color = androidx.compose.ui.graphics.Color(PAL[opts.indexOfFirst { it.first == k } % PAL.size])); Text(" $n") } }) } }
    Text("Cada línea va a su propia escala (mín–máx)", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
    val bmp = remember(ser) { android.graphics.Bitmap.createBitmap(900, 500, android.graphics.Bitmap.Config.ARGB_8888).also {
        drawChart(android.graphics.Canvas(it), 900, 500, ser, legend = false, bg = false) } }
    androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().padding(top = 16.dp))
}

fun px(p: List<Pair<Long, Float>>, i: Int, l: Float, w: Float): Float {
    val sp = (p.last().first - p[0].first).toFloat()
    return if (sp <= 0f) l + w / 2 else l + w * (p[i].first - p[0].first) / sp
}

@Composable fun ProgChart(p: List<Pair<Long, Float>>, sel: Int, unit: String, onSel: (Int) -> Unit) {
    if (p.isEmpty()) { Text("Sin datos", Modifier.padding(top = 16.dp)); return }
    val c = MaterialTheme.colorScheme.primary; val c2 = MaterialTheme.colorScheme.tertiary
    val L = 110f; val R = 30f; val T = 30f; val B = 60f
    Canvas(Modifier.fillMaxWidth().height(260.dp).padding(top = 8.dp).pointerInput(p) {
        detectTapGestures { o -> val w = size.width - L - R
            onSel(p.indices.minByOrNull { Math.abs(px(p, it, L, w) - o.x) }!!) } }) {
        val w = size.width - L - R; val h = size.height - T - B
        var mn = p.minOf { it.second }; var mx = p.maxOf { it.second }
        if (mx - mn < 1f) { mn -= 1f; mx += 1f }
        val tp = android.graphics.Paint().apply { color = 0xFFBBBBBB.toInt(); textSize = 28f; isAntiAlias = true }
        val nc = drawContext.canvas.nativeCanvas
        for (k in 0..4) { val v = mn + (mx - mn) * k / 4; val y = T + h * (1 - k / 4f)
            drawLine(androidx.compose.ui.graphics.Color(0x33FFFFFF), androidx.compose.ui.geometry.Offset(L, y), androidx.compose.ui.geometry.Offset(L + w, y), 1f)
            nc.drawText(if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v), 4f, y + 10f, tp) }
        nc.drawText(unit, 4f, 22f, tp)
        val pts = p.indices.map { androidx.compose.ui.geometry.Offset(px(p, it, L, w), T + h * (1 - (p[it].second - mn) / (mx - mn))) }
        for (i in 1 until pts.size) drawLine(c, pts[i - 1], pts[i], 4f)
        pts.forEachIndexed { i, o -> drawCircle(if (i == sel) c2 else c, if (i == sel) 14f else 7f, o) }
        if (sel in pts.indices) drawLine(c2, androidx.compose.ui.geometry.Offset(pts[sel].x, T), androidx.compose.ui.geometry.Offset(pts[sel].x, T + h), 2f)
        val idx = listOf(0, p.size / 2, p.size - 1).distinct()
        idx.forEach { i -> val s = LocalDate.ofEpochDay(p[i].first).let { "${it.dayOfMonth}/${it.monthValue}" }
            nc.drawText(s, pts[i].x - tp.measureText(s) / 2, size.height - 12f, tp) }
    }
}

@Composable fun Picker(l: List<Exercise>, cur: Exercise?, on: (Exercise) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }) { Text((cur?.name ?: "Sin ejercicios") + " ▾", maxLines = 1) }
    if (open) { var q by remember { mutableStateOf("") }
        AlertDialog({ open = false }, confirmButton = { TextButton({ open = false }) { Text("Cerrar") } }, title = { Text("Ejercicio") },
            text = { Column { F(q, "Buscar", Modifier.fillMaxWidth(), false) { q = it }
                LazyColumn(Modifier.heightIn(max = 320.dp)) { items(l.filter { it.name.contains(q, true) }) { e ->
                    TextButton({ on(e); open = false }, Modifier.fillMaxWidth()) { Text(e.name) } } } } }) }
}

@Composable fun Manage(vm: VM, close: () -> Unit) {
    val exs by vm.ex.collectAsState()
    var ren by remember { mutableStateOf<Exercise?>(null) }; var del by remember { mutableStateOf<Exercise?>(null) }
    androidx.compose.ui.window.Dialog(close, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Gestionar ejercicios", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(close) { Text("Cerrar") } }
            LazyColumn { items(exs.sortedWith(compareBy({ it.hidden }, { it.name.lowercase() }))) { e ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(e.name + if (e.hidden) " (oculto)" else "")
                    Row { TextButton({ ren = e }) { Text("Renombrar") }
                        TextButton({ vm.upEx(e.copy(hidden = !e.hidden)) }) { Text(if (e.hidden) "Mostrar" else "Ocultar") }
                        TextButton({ del = e }) { Text("Eliminar") } } } } }
        } }
    }
    ren?.let { e -> var n by remember(e.id) { mutableStateOf(e.name) }
        AlertDialog({ ren = null }, confirmButton = { TextButton({ if (n.isNotBlank()) { vm.upEx(e.copy(name = n.trim())); ren = null } }) { Text("Guardar") } },
            dismissButton = { TextButton({ ren = null }) { Text("Cancelar") } }, title = { Text("Renombrar") }, text = { F(n, "Nombre", num = false) { n = it } }) }
    del?.let { e -> AlertDialog({ del = null }, confirmButton = { TextButton({ vm.delEx(e); del = null }) { Text("Eliminar") } },
        dismissButton = { TextButton({ del = null }) { Text("Cancelar") } }, title = { Text("¿Eliminar ${e.name}?") }, text = { Text("Se borrarán también todos sus sets.") }) }
}
