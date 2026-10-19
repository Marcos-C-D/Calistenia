package com.marcos.calistenia
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

class ConfigActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0); if (id == 0) { finish(); return }
        val a = this
        val multi = AppWidgetManager.getInstance(a).getAppWidgetInfo(id)?.provider?.className?.endsWith("WMulti") == true
        setContent { MaterialTheme(darkColorScheme()) { Surface(Modifier.fillMaxSize()) {
            val ex by Db.get(a).exercises().collectAsState(emptyList())
            val opts = listOf("w" to "Peso corporal") + ex.map { "e${it.id}" to it.name }
            val sel = remember { mutableStateListOf<String>() }
            fun done() {
                prefs(a).edit().putString("w$id", sel.joinToString(",")).apply()
                thread { upd(a, AppWidgetManager.getInstance(a), id)
                    runOnUiThread { setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish() } }
            }
            Column(Modifier.statusBarsPadding().padding(16.dp)) {
                Text(if (multi) "Elige las series a mostrar" else "Elige una serie", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f)) { items(opts) { (k, n) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (multi) { if (k in sel) sel.remove(k) else sel.add(k) } else { sel.clear(); sel.add(k); done() }
                    }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (multi) Checkbox(k in sel, null); Text(n, Modifier.padding(start = 8.dp)) } } }
                if (multi) Button({ done() }, Modifier.fillMaxWidth(), enabled = sel.isNotEmpty()) { Text("Guardar") }
            }
        } } }
    }
}
