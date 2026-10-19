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
    fun addSet(s: SetEntry) = viewModelScope.launch { d.addSet(s); Widgets.refresh(getApplication()) }
    fun del(s: SetEntry) = viewModelScope.launch { d.delSet(s); Widgets.refresh(getApplication()) }
    fun putW(day: Long, kg: Float) = viewModelScope.launch { d.putW(BodyWeight(day, kg)); Widgets.refresh(getApplication()) }
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
    Scaffold(bottomBar = { NavigationBar { listOf("Entreno", "Progreso", "Peso").forEachIndexed { i, t ->
        NavigationBarItem(tab == i, { tab = i }, {}, label = { Text(t) }) } } }) { p ->
        Column(Modifier.padding(p).padding(16.dp).statusBarsPadding()) { if (tab != 1) DayBar(day) { day = it }; when (tab) { 0 -> Train(vm, day); 1 -> Stats(vm); else -> Weight(vm, day) } }
    }
}

@Composable fun F(v: String, l: String, m: Modifier = Modifier, num: Boolean = true, c: (String) -> Unit) =
    OutlinedTextField(v, c, modifier = m, label = { Text(l) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (num) KeyboardType.Decimal else KeyboardType.Text))

@Composable fun Chips(l: List<Exercise>, sel: Exercise?, on: (Exercise) -> Unit) =
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(l) { FilterChip(sel?.id == it.id, { on(it) }, { Text(it.name) }) } }

@Composable fun Train(vm: VM, today: Long) {
    val exs by vm.ex.collectAsState(); val all by vm.sets.collectAsState()
    var sel by remember { mutableStateOf<Exercise?>(null) }
    val cur = exs.find { it.id == sel?.id } ?: exs.firstOrNull()
    var dlg by remember { mutableStateOf(false) }
    var v by remember { mutableStateOf("") }; var kg by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Chips(exs, cur) { sel = it } }
        TextButton({ dlg = true }) { Text("+ Ejercicio") }
    }
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
    Chips(exs, cur) { sel = it }
    val pr = mine.maxWithOrNull(compareBy({ it.extraKg }, { it.value }))
    Text(if (pr == null) "Sin registros" else "PR: ${pr.value}${if (cur.isTime) " s" else " reps"}${if (pr.extraKg > 0) " con +${pr.extraKg}kg" else ""} (${LocalDate.ofEpochDay(pr.day)})",
        Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    Text("Mejor set por día", Modifier.padding(top = 8.dp))
    Chart(mine.groupBy { it.day }.toSortedMap().map { (_, l) -> l.maxOf { it.value }.toFloat() })
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
    OutlinedButton({ save.launch("calistenia.json") }, Modifier.fillMaxWidth()) { Text("Exportar datos (JSON)") }
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
