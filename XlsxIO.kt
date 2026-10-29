package com.rutas.circuito

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Lector/escritor mínimo de .xlsx (una hoja, tabla simple). Sin dependencias externas. */
object XlsxIO {
    val HEADERS = listOf("Nombre", "Dirección", "Comuna", "Latitud", "Longitud", "Notas")
    private const val DECL = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun write(out: OutputStream, rows: List<List<String>>) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put("[Content_Types].xml", DECL +
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
                """<Default Extension="xml" ContentType="application/xml"/>""" +
                """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
                """</Types>""")
            put("_rels/.rels", DECL +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="$REL/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", DECL +
                """<workbook xmlns="$NS" xmlns:r="$REL"><sheets><sheet name="Ruta" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            put("xl/_rels/workbook.xml.rels", DECL +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")

            val sb = StringBuilder(DECL).append("""<worksheet xmlns="$NS"><sheetData>""")
            rows.forEachIndexed { r, row ->
                sb.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, v ->
                    if (v.isEmpty()) return@forEachIndexed
                    val ref = "${'A' + c}${r + 1}"
                    val numeric = r > 0 && c in setOf(3, 4, 6, 7) && v.toDoubleOrNull() != null
                    if (numeric) sb.append("<c r=\"$ref\"><v>$v</v></c>")
                    else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                }
                sb.append("</row>")
            }
            sb.append("</sheetData></worksheet>")
            put("xl/worksheets/sheet1.xml", sb.toString())
        }
    }

    fun read(input: InputStream): List<List<String>> {
        var sharedBytes: ByteArray? = null
        val sheets = sortedMapOf<String, ByteArray>()
        val sheetRe = Regex("xl/worksheets/sheet\\d+\\.xml")
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name
                if (n == "xl/sharedStrings.xml") sharedBytes = z.readBytes()
                else if (sheetRe.matches(n)) sheets[n] = z.readBytes()
                e = z.nextEntry
            }
        }
        val shared = sharedBytes?.let { parseShared(it) } ?: emptyList()
        val sheet = sheets.values.firstOrNull() ?: return emptyList()
        return parseSheet(sheet, shared)
    }

    private fun parser(bytes: ByteArray): XmlPullParser =
        Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }

    private fun parseShared(bytes: ByteArray): List<String> {
        val p = parser(bytes)
        val list = mutableListOf<String>()
        var sb: StringBuilder? = null
        var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb = StringBuilder()
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inT) sb?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "si" -> { list.add(sb?.toString() ?: ""); sb = null }
                }
            }
            ev = p.next()
        }
        return list
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        var any = false
        for (ch in ref) {
            if (ch in 'A'..'Z') { n = n * 26 + (ch - 'A' + 1); any = true } else break
        }
        return if (any) n - 1 else -1
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val p = parser(bytes)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var col = 0
        var type: String? = null
        var text = StringBuilder()
        var capture = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = mutableListOf()
                    "c" -> {
                        type = p.getAttributeValue(null, "t")
                        val idx = colIndex(p.getAttributeValue(null, "r") ?: "")
                        col = if (idx >= 0) idx else row.size
                        text = StringBuilder()
                    }
                    "v", "t" -> capture = true
                }
                XmlPullParser.TEXT -> if (capture) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val value = if (type == "s") shared.getOrElse(text.toString().trim().toIntOrNull() ?: -1) { "" }
                        else text.toString()
                        while (row.size < col) row.add("")
                        if (row.size == col) row.add(value)
                    }
                    "row" -> rows.add(row)
                }
            }
            ev = p.next()
        }
        return rows
    }
}
