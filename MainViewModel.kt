package com.rutas.circuito

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import kotlin.math.roundToInt

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    private val prefs = app.getSharedPreferences("rutas", Context.MODE_PRIVATE)
    private val cr: ContentResolver get() = getApplication<Application>().contentResolver

    val stops: StateFlow<List<Stop>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var roundTrip by mutableStateOf(prefs.getBoolean("round", true))
        private set
    var leg by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var linkedUri by mutableStateOf(prefs.getString("uri", null))
        private set
    var pendingImport by mutableStateOf<Uri?>(null)
    var sharedPrefill by mutableStateOf<Stop?>(null)

    fun setRoundTrip(v: Boolean) {
        roundTrip = v
        leg = 0
        prefs.edit().putBoolean("round", v).apply()
    }

    // ---------- Geocodificación ----------
    private data class Geo(val lat: Double, val lng: Double, val comuna: String)

    /** En Chile, Address.locality suele ser la comuna (ej. Maipú). */
    private val geoLocale: Locale = Locale.forLanguageTag("es-CL")

    /** Une dirección + comuna + país para ubicar mejor (ej. "Av. Pajaritos 1234, Maipú, Chile"). */
    private fun geocode(address: String, comuna: String = ""): Geo? = try {
        if (!Geocoder.isPresent()) null
        else {
            @Suppress("DEPRECATION")
            Geocoder(getApplication(), geoLocale)
                .getFromLocationName(listOf(address, comuna, "Chile").filter { it.isNotBlank() }.joinToString(", "), 1)
                ?.firstOrNull()?.let {
                    Geo(it.latitude, it.longitude, it.locality ?: it.subLocality ?: it.subAdminArea ?: "")
                }
        }
    } catch (e: Exception) {
        null
    }

    private fun reverseComuna(lat: Double, lng: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else {
            @Suppress("DEPRECATION")
            Geocoder(getApplication(), geoLocale).getFromLocation(lat, lng, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subLocality ?: it.subAdminArea }
        }
    } catch (e: Exception) {
        null
    }

    // ---------- Edición de paradas ----------
    fun save(stop: Stop, original: Stop? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            var s = stop
            val sameCoords = original != null && original.lat == s.lat && original.lng == s.lng
            val addressChanged = original != null && original.address != s.address && sameCoords
            val comunaEdited = original != null && original.comuna != s.comuna
            val relocate = addressChanged || (comunaEdited && sameCoords)
            if (s.address.isNotBlank() && (s.lat == null || s.lng == null || relocate)) {
                val g = geocode(s.address, if (addressChanged && !comunaEdited) "" else s.comuna)
                if (g != null) {
                    val newComuna =
                        if ((s.comuna.isBlank() || (addressChanged && !comunaEdited)) && g.comuna.isNotBlank()) g.comuna
                        else s.comuna
                    s = s.copy(lat = g.lat, lng = g.lng, comuna = newComuna)
                } else if (s.lat == null) message = "No se pudo ubicar la dirección"
            }
            if (s.comuna.isBlank()) {
                val la = s.lat
                val lo = s.lng
                if (la != null && lo != null) reverseComuna(la, lo)?.let { s = s.copy(comuna = it) }
            }
            if (s.id == 0L) dao.insert(s.copy(position = dao.getAll().size)) else dao.update(s)
            saveLinked()
        }
    }

    fun delete(s: Stop) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(s)
            renumber()
            saveLinked()
        }
    }

    fun move(s: Stop, delta: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val l = dao.getAll().toMutableList()
            val i = l.indexOfFirst { it.id == s.id }
            val j = i + delta
            if (i < 0 || j < 0 || j >= l.size) return@launch
            Collections.swap(l, i, j)
            dao.updateAll(l.mapIndexed { k, x -> x.copy(position = k) })
            saveLinked()
        }
    }

    fun toggleDone(s: Stop) {
        viewModelScope.launch(Dispatchers.IO) { dao.update(s.copy(done = !s.done)) }
    }

    fun optimize() {
        viewModelScope.launch(Dispatchers.IO) {
            val sorted = RouteOptimizer.optimize(dao.getAll(), roundTrip)
            dao.updateAll(sorted.mapIndexed { i, x -> x.copy(position = i) })
            leg = 0
            saveLinked()
            message = "Ruta optimizada (la primera parada es el inicio)"
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clear()
            saveLinked()
        }
    }

    private suspend fun renumber() {
        dao.updateAll(dao.getAll().mapIndexed { i, s -> s.copy(position = i) })
    }

    // ---------- Excel ----------
    private fun buildRows(l: List<Stop>): List<List<String>> {
        val head = XlsxIO.HEADERS + listOf("Distancia (km)", "Tiempo (min)")
        val body = l.mapIndexed { i, s ->
            val prev = l.getOrNull(i - 1)
            val km = if (prev != null) RouteOptimizer.legKm(prev, s) else 0.0
            listOf(
                s.name, s.address, s.comuna, s.lat?.toString() ?: "", s.lng?.toString() ?: "", s.notes,
                "%.1f".format(Locale.US, km), (km / 30.0 * 60).roundToInt().toString()
            )
        }
        return listOf(head) + body
    }

    private suspend fun saveLinked() {
        val u = linkedUri ?: return
        try {
            cr.openOutputStream(Uri.parse(u), "wt")!!.use { XlsxIO.write(it, buildRows(dao.getAll())) }
        } catch (e: Exception) {
            message = "No se pudo guardar en el Excel vinculado"
        }
    }

    fun unlink() {
        linkedUri = null
        prefs.edit().remove("uri").apply()
    }

    fun importPending(replace: Boolean) {
        val uri = pendingImport ?: return
        pendingImport = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                try {
                    cr.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                val rows = cr.openInputStream(uri)!!.use { XlsxIO.read(it) }
                fun norm(x: String) =
                    Normalizer.normalize(x.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                val header = rows.firstOrNull()
                val hasHeader = header != null && header.any {
                    norm(it) in setOf("nombre", "direccion", "comuna", "latitud")
                }
                // Con encabezado se leen las columnas por nombre (sirve también para Excel antiguos sin Comuna).
                val cols = mutableMapOf<String, Int>()
                if (hasHeader) header!!.forEachIndexed { i, h -> cols[norm(h)] = i }
                else listOf("nombre", "direccion", "comuna", "latitud", "longitud", "notas")
                    .forEachIndexed { i, k -> cols[k] = i }
                val body = (if (hasHeader) rows.drop(1) else rows)
                    .filter { r -> r.any { it.isNotBlank() } }
                val base = if (replace) 0 else dao.getAll().size
                var filled = 0
                message = "Importando y ubicando direcciones…"
                val list = body.mapIndexed { i, r ->
                    fun c(k: String) = r.getOrElse(cols[k] ?: -1) { "" }.trim()
                    var s = Stop(
                        position = base + i,
                        name = c("nombre").ifBlank { c("direccion") },
                        address = c("direccion"),
                        comuna = c("comuna"),
                        lat = c("latitud").replace(',', '.').toDoubleOrNull(),
                        lng = c("longitud").replace(',', '.').toDoubleOrNull(),
                        notes = c("notas")
                    )
                    val raw = s
                    if ((s.lat == null || s.lng == null) && s.address.isNotBlank()) {
                        geocode(s.address, s.comuna)?.let {
                            s = s.copy(lat = it.lat, lng = it.lng, comuna = s.comuna.ifBlank { it.comuna })
                        }
                    }
                    if (s.comuna.isBlank()) {
                        val la = s.lat
                        val lo = s.lng
                        if (la != null && lo != null) reverseComuna(la, lo)?.let { s = s.copy(comuna = it) }
                    }
                    if (s != raw) filled++
                    s
                }
                if (replace) dao.clear()
                dao.insertAll(list)
                linkedUri = uri.toString()
                prefs.edit().putString("uri", uri.toString()).apply()
                leg = 0
                if (filled > 0) saveLinked()
                message = "Importadas ${list.size} paradas" +
                    if (filled > 0) " (comuna y coordenadas completadas)" else ""
            } catch (e: Exception) {
                message = "No se pudo leer el Excel (¿es .xlsx?)"
            }
        }
    }

    /** Completa comuna y coordenadas de todas las paradas a las que les falten. */
    fun completeMissing() {
        viewModelScope.launch(Dispatchers.IO) {
            message = "Completando comunas y coordenadas…"
            var n = 0
            val updated = dao.getAll().map { st ->
                var s = st
                if ((s.lat == null || s.lng == null) && s.address.isNotBlank()) {
                    geocode(s.address, s.comuna)?.let { g ->
                        s = s.copy(lat = g.lat, lng = g.lng, comuna = s.comuna.ifBlank { g.comuna })
                    }
                }
                if (s.comuna.isBlank()) {
                    val la = s.lat
                    val lo = s.lng
                    if (la != null && lo != null) reverseComuna(la, lo)?.let { c -> s = s.copy(comuna = c) }
                }
                if (s != st) n++
                s
            }
            dao.updateAll(updated)
            saveLinked()
            message = if (n > 0) "Completadas $n paradas" else "No había nada que completar"
        }
    }

    fun exportXlsx(uri: Uri, template: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                cr.openOutputStream(uri, "wt")!!.use {
                    XlsxIO.write(it, if (template) listOf(XlsxIO.HEADERS) else buildRows(dao.getAll()))
                }
                message = if (template) "Plantilla guardada" else "Excel guardado"
            } catch (e: Exception) {
                message = "No se pudo guardar el archivo"
            }
        }
    }

    fun exportGpx(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder(
                    """<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="RutasCircuito" xmlns="http://www.topografix.com/GPX/1/1"><rte><name>Ruta</name>"""
                )
                dao.getAll().filter { it.lat != null && it.lng != null }.forEach {
                    sb.append("<rtept lat=\"${it.lat}\" lon=\"${it.lng}\"><name>${XlsxIO.esc(it.name)}</name></rtept>")
                }
                sb.append("</rte></gpx>")
                cr.openOutputStream(uri, "wt")!!.use { it.write(sb.toString().toByteArray()) }
                message = "GPX guardado"
            } catch (e: Exception) {
                message = "No se pudo guardar el GPX"
            }
        }
    }

    // ---------- Ubicación compartida desde Google Maps ----------
    fun onShared(text: String) {
        val m = Regex("(-?\\d{1,3}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)").find(text)
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("http") }
        sharedPrefill = Stop(
            name = lines.firstOrNull()?.take(60) ?: "",
            address = if (m == null) lines.joinToString(", ") else "",
            lat = m?.groupValues?.get(1)?.toDoubleOrNull(),
            lng = m?.groupValues?.get(2)?.toDoubleOrNull()
        )
    }
}
