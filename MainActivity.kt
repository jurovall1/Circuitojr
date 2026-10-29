@file:OptIn(ExperimentalMaterial3Api::class)

package com.rutas.circuito

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private val Blue = Color(0xFF1A73E8)
private val Green = Color(0xFF2E7D32)

/** Selector de archivos que pide permiso de escritura persistente (para guardar en el mismo Excel). */
class OpenDocRW : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osm")
            osmdroidTileCache = File(cacheDir, "osm/tiles")
        }
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue)) { RoutesApp(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.onShared(it) }
    }
}

@Composable
fun RoutesApp(vm: MainViewModel) {
    val stops by vm.stops.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Stop?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    val importL = rememberLauncherForActivityResult(OpenDocRW()) { it?.let { u -> vm.pendingImport = u } }
    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX)) {
        it?.let { u -> vm.exportXlsx(u, template) }
    }
    val gpxL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) {
        it?.let { u -> vm.exportGpx(u) }
    }

    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Ruta") })
                        FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Tabla") })
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menú") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Importar Excel") }, onClick = {
                            menu = false
                            importL.launch(arrayOf(XLSX, "application/octet-stream"))
                        })
                        DropdownMenuItem(text = { Text("Guardar copia en Excel") }, onClick = {
                            menu = false; template = false; exportL.launch("ruta.xlsx")
                        })
                        DropdownMenuItem(text = { Text("Completar comuna y coordenadas") }, onClick = {
                            menu = false; vm.completeMissing()
                        })
                        DropdownMenuItem(text = { Text("Plantilla vacía") }, onClick = {
                            menu = false; template = true; exportL.launch("plantilla.xlsx")
                        })
                        DropdownMenuItem(text = { Text("Exportar GPX") }, onClick = {
                            menu = false; gpxL.launch("ruta.gpx")
                        })
                        if (vm.linkedUri != null) {
                            DropdownMenuItem(text = { Text("Desvincular Excel") }, onClick = {
                                menu = false; vm.unlink()
                            })
                        }
                        DropdownMenuItem(text = { Text("Borrar todo") }, onClick = {
                            menu = false; confirmClear = true
                        })
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) RouteTab(stops, vm, onAdd = { showForm = true }, onEdit = { editing = it })
            else TableTab(stops, vm, onAdd = { showForm = true })
        }
    }

    val shared = vm.sharedPrefill
    if (showForm || editing != null || shared != null) {
        StopDialog(
            initial = editing ?: shared,
            vm = vm,
            onDismiss = { showForm = false; editing = null; vm.sharedPrefill = null }
        )
    }

    if (vm.pendingImport != null) {
        AlertDialog(
            onDismissRequest = { vm.pendingImport = null },
            title = { Text("Importar Excel") },
            text = { Text("¿Reemplazar la ruta actual o combinar con las paradas existentes?") },
            confirmButton = { TextButton(onClick = { vm.importPending(true) }) { Text("Reemplazar") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.importPending(false) }) { Text("Combinar") }
                    TextButton(onClick = { vm.pendingImport = null }) { Text("Cancelar") }
                }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Borrar todo") },
            text = { Text("Se eliminarán todas las paradas (y las filas del Excel vinculado).") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearAll() }) { Text("Borrar", color = Color.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } }
        )
    }
}

// ======================= PESTAÑA RUTA (mapa + panel inferior estilo Circuit) =======================

@Composable
fun RouteTab(stops: List<Stop>, vm: MainViewModel, onAdd: () -> Unit, onEdit: (Stop) -> Unit) {
    val ctx = LocalContext.current
    val (km, min) = RouteOptimizer.estimate(stops, vm.roundTrip)
    val urls = Nav.googleMapsUrls(stops.filter { !it.done }, vm.roundTrip)
    val startLabel = if (urls.size > 1) "Tramo ${vm.leg % urls.size + 1}/${urls.size}" else "Iniciar ruta"

    BottomSheetScaffold(
        scaffoldState = rememberBottomSheetScaffoldState(),
        sheetPeekHeight = 230.dp,
        sheetContent = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("${stops.size} paradas", style = MaterialTheme.typography.titleLarge)
                Text("%.1f km · %d min (estimado)".format(km, min), color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.roundTrip, onCheckedChange = { vm.setRoundTrip(it) })
                    Spacer(Modifier.width(8.dp))
                    Text("Volver al inicio", Modifier.weight(1f))
                    OutlinedButton(onClick = { vm.optimize() }) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Optimizar")
                    }
                }
                Button(
                    onClick = { startRoute(ctx, vm, urls) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(startLabel)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    itemsIndexed(stops, key = { _, s -> s.id }) { i, s ->
                        StopCard(i + 1, s, vm) { onEdit(s) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            MapPane(stops, vm.roundTrip, Modifier.fillMaxSize())
            Surface(
                onClick = onAdd,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Añadir parada")
                }
            }
        }
    }
}

fun startRoute(ctx: Context, vm: MainViewModel, urls: List<String>) {
    if (urls.isEmpty()) {
        vm.message = "Agrega al menos 2 paradas con ubicación"
        return
    }
    val i = vm.leg % urls.size
    Nav.open(ctx, urls[i])
    vm.leg = (i + 1) % urls.size
}

@Composable
fun StopCard(n: Int, s: Stop, vm: MainViewModel, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onEdit() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(if (s.done) Color.Gray else if (n == 1) Green else Blue),
            contentAlignment = Alignment.Center
        ) { Text("$n", color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.comuna.ifBlank { s.name.ifBlank { s.address } }, fontWeight = FontWeight.Bold, maxLines = 1,
                textDecoration = if (s.done) TextDecoration.LineThrough else null
            )
            if (s.address.isNotBlank()) Text(s.address, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
            if (s.notes.isNotBlank()) Text(s.notes, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
            if (s.lat == null || s.lng == null) Text("Sin ubicación", style = MaterialTheme.typography.bodySmall, color = Color.Red)
        }
        IconButton(onClick = { vm.move(s, -1) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Subir") }
        IconButton(onClick = { vm.move(s, 1) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Bajar") }
        Checkbox(checked = s.done, onCheckedChange = { vm.toggleDone(s) })
    }
}

// ======================= MAPA (osmdroid) =======================

private fun pinIcon(ctx: Context, n: Int, done: Boolean): Drawable {
    val d = ctx.resources.displayMetrics.density
    val size = (34 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.parseColor(if (done) "#9E9E9E" else if (n == 1) "#2E7D32" else "#1A73E8")
    c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, p)
    p.color = android.graphics.Color.WHITE
    p.style = Paint.Style.STROKE
    p.strokeWidth = 2 * d
    c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, p)
    p.style = Paint.Style.FILL
    p.textSize = 14 * d
    p.textAlign = Paint.Align.CENTER
    p.isFakeBoldText = true
    c.drawText("$n", size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
    return BitmapDrawable(ctx.resources, bmp)
}

@Composable
fun MapPane(stops: List<Stop>, roundTrip: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(0.0, 0.0))
        }
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            map.onPause()
            map.onDetach()
        }
    }
    val lastKey = remember { arrayOf("") }

    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        val geo = stops.mapNotNull { s ->
            val la = s.lat
            val lo = s.lng
            if (la != null && lo != null) GeoPoint(la, lo) else null
        }
        if (geo.size > 1) {
            m.overlays.add(Polyline().apply {
                setPoints(if (roundTrip) geo + geo.first() else geo)
                outlinePaint.color = android.graphics.Color.parseColor("#1A73E8")
                outlinePaint.strokeWidth = 8f
            })
        }
        stops.forEachIndexed { i, s ->
            val la = s.lat
            val lo = s.lng
            if (la != null && lo != null) {
                m.overlays.add(Marker(m).apply {
                    position = GeoPoint(la, lo)
                    icon = pinIcon(ctx, i + 1, s.done)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = s.name
                })
            }
        }
        m.invalidate()
        val key = geo.joinToString { "${it.latitude},${it.longitude}" }
        if (key != lastKey[0]) {
            lastKey[0] = key
            when {
                geo.size == 1 -> { m.controller.setZoom(15.0); m.controller.setCenter(geo[0]) }
                geo.size > 1 -> m.post { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo), true, 120) }
            }
        }
    })
}

// ======================= PESTAÑA TABLA (edición del Excel) =======================

@Composable
fun TableTab(stops: List<Stop>, vm: MainViewModel, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.linkedUri != null) "Excel vinculado: los cambios se guardan solos"
                else "Sin Excel vinculado (importa uno para guardar en él)",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, null)
                Text("Fila")
            }
        }
        Column(Modifier.horizontalScroll(rememberScrollState()).weight(1f)) {
            Row {
                HeaderCell("#", 40); HeaderCell("Nombre", 150); HeaderCell("Dirección", 220)
                HeaderCell("Comuna", 120); HeaderCell("Latitud", 100); HeaderCell("Longitud", 100); HeaderCell("Notas", 180)
            }
            LazyColumn {
                itemsIndexed(stops, key = { _, s -> s.id }) { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.width(40.dp).padding(start = 8.dp))
                        Cell(s.name, 150) { vm.save(s.copy(name = it), s) }
                        Cell(s.address, 220) { vm.save(s.copy(address = it), s) }
                        Cell(s.comuna, 120) { vm.save(s.copy(comuna = it), s) }
                        Cell(s.lat?.toString() ?: "", 100) { vm.save(s.copy(lat = it.replace(',', '.').toDoubleOrNull()), s) }
                        Cell(s.lng?.toString() ?: "", 100) { vm.save(s.copy(lng = it.replace(',', '.').toDoubleOrNull()), s) }
                        Cell(s.notes, 180) { vm.save(s.copy(notes = it), s) }
                        IconButton(onClick = { vm.delete(s) }) { Icon(Icons.Default.Delete, "Eliminar") }
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderCell(text: String, width: Int) {
    Text(text, Modifier.width(width.dp).padding(8.dp), fontWeight = FontWeight.Bold)
}

@Composable
fun Cell(value: String, width: Int, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.width(width.dp).padding(2.dp).border(1.dp, Color.LightGray).padding(8.dp)
            .onFocusChanged { if (!it.isFocused && text != value) onCommit(text) }
    )
}

// ======================= FORMULARIO =======================

@Composable
fun StopDialog(initial: Stop?, vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var comuna by remember { mutableStateOf(initial?.comuna ?: "") }
    var lat by remember { mutableStateOf(initial?.lat?.toString() ?: "") }
    var lng by remember { mutableStateOf(initial?.lng?.toString() ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    val isEdit = initial != null && initial.id != 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Editar parada" else "Nueva parada") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("Dirección") })
                OutlinedTextField(comuna, { comuna = it }, label = { Text("Comuna (ej. Maipú)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(lat, { lat = it }, Modifier.weight(1f), label = { Text("Latitud") }, singleLine = true)
                    OutlinedTextField(lng, { lng = it }, Modifier.weight(1f), label = { Text("Longitud") }, singleLine = true)
                }
                Text("Si dejas vacías las coordenadas o la comuna, se calculan con la dirección y la comuna.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notas") })
                if (isEdit && initial != null) {
                    Row {
                        TextButton(onClick = { Nav.openStop(ctx, initial) }) { Text("Navegar aquí") }
                        TextButton(onClick = { vm.delete(initial); onDismiss() }) { Text("Eliminar", color = Color.Red) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() || address.isNotBlank(),
                onClick = {
                    vm.save(
                        Stop(
                            id = initial?.id ?: 0L,
                            position = initial?.position ?: 0,
                            name = name.trim().ifBlank { address.trim() },
                            address = address.trim(),
                            comuna = comuna.trim(),
                            lat = lat.replace(',', '.').toDoubleOrNull(),
                            lng = lng.replace(',', '.').toDoubleOrNull(),
                            notes = notes.trim(),
                            done = initial?.done ?: false
                        ),
                        initial?.takeIf { it.id != 0L }
                    )
                    onDismiss()
                }
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
