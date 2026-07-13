package com.printagent.android

import org.json.JSONObject
import java.io.ByteArrayOutputStream

object EscPos {

    private val INIT = byteArrayOf(0x1B, 0x40)
    private val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    private val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
    private val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    private val REVERSE_ON = byteArrayOf(0x1D, 0x42, 0x01)
    private val REVERSE_OFF = byteArrayOf(0x1D, 0x42, 0x00)
    private val SIZE_NORMAL = byteArrayOf(0x1D, 0x21, 0x00)
    private val SIZE_DOUBLE = byteArrayOf(0x1D, 0x21, 0x11)
    private val CUT_PARTIAL = byteArrayOf(0x1D, 0x56, 0x01)
    // Ancho por defecto en caracteres. 58mm ≈ 32, 80mm ≈ 48. Configurable desde la app.
    const val WIDTH_58MM = 32
    const val WIDTH_80MM = 48
    private const val FEED_BEFORE_CUT = "\n\n\n\n\n\n"

    // Mínimo de líneas de contenido para que el ticket sea agarrable / pinchable en cocina.
    // ~24 líneas × 3mm/línea ≈ 72mm; sumado al FEED_BEFORE_CUT da un ticket total de ~9cm.
    private const val MIN_TICKET_LINES = 24

    fun buildTestTicket(width: Int = WIDTH_80MM): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(INIT)
        val dash = "-".repeat(width)

        out.write(ALIGN_CENTER)
        out.write(BOLD_ON)
        out.write(SIZE_DOUBLE)
        out.writeAscii("PRINT AGENT\n")
        out.write(SIZE_NORMAL)
        out.write(BOLD_OFF)
        out.writeAscii("Test de impresion\n")
        out.writeAscii("$dash\n")

        out.write(ALIGN_LEFT)
        out.writeAscii("1 x Hamburguesa clasica\n")
        out.writeAscii("2 x Papas fritas\n")
        out.writeAscii("1 x Coca Cola 500ml\n")

        out.write(ALIGN_CENTER)
        out.writeAscii("$dash\n")
        out.writeAscii("Mesa 5 - Mozo: Demo\n")
        out.writeAscii(FEED_BEFORE_CUT)
        out.write(CUT_PARTIAL)
        return out.toByteArray()
    }

    /**
     * Comanda de cocina con jerarquía para la línea caliente:
     * comercio → banner de estación → subheader + hora → mesa/mozo → comprobante →
     * ítems (con modificadores `+` y notas `!`) → contador + fecha.
     *
     * Recibe el job COMPLETO (no solo `content`) para poder usar `type`, `order_id`
     * y `created_at`, que viven a nivel del job.
     */
    fun buildJobTicket(job: JSONObject, width: Int = WIDTH_80MM): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(INIT)
        var lines = 0
        val solid = "=".repeat(width)

        val content = job.optJSONObject("content") ?: JSONObject()
        val meta = content.optJSONObject("meta") ?: JSONObject()

        val header = content.optString("header").takeIf { it.isNotBlank() }
        val subheader = content.optString("subheader").takeIf { it.isNotBlank() }
        val items = content.optJSONArray("items")

        val type = job.optString("type").takeIf { it.isNotBlank() }
        val orderId = job.optString("order_id").cleanOrNull()
        val createdAt = job.optString("created_at").takeIf { it.isNotBlank() }

        val mesa = meta.optString("mesa").cleanOrNull()
        val mozo = meta.optString("mozo").cleanOrNull()
        val cliente = meta.optJSONObject("customer")?.optString("name").cleanOrNull()
        // Nota global de la comanda ("Nota para cocina" del modal Enviar comanda).
        // WAMA la emite como `comanda_note`; dejamos `order_note` como respaldo.
        val orderNote = meta.optString("comanda_note").cleanOrNull()
            ?: meta.optString("order_note").cleanOrNull()
            ?: content.optString("comanda_note").cleanOrNull()
            ?: content.optString("order_note").cleanOrNull()
        val hora = meta.optString("hora").cleanOrNull()
        val comandaId = if (!meta.isNull("comanda_id")) meta.optInt("comanda_id").takeIf { it > 0 } else null
        val esAdicion = meta.optBoolean("es_adicion", false)

        // --- Encabezado: nombre del comercio (doble tamaño)
        if (header != null) {
            out.write(ALIGN_CENTER)
            out.write(BOLD_ON)
            out.write(SIZE_DOUBLE)
            out.writeAscii("$header\n")
            out.write(SIZE_NORMAL)
            out.write(BOLD_OFF)
            lines += 2
        }

        out.write(ALIGN_LEFT)

        // --- Banner de estación / tipo (video inverso, ancho completo)
        val station = when (type) {
            "kitchen" -> "COCINA"
            "bar" -> "BAR"
            "adicion" -> "ADICION"
            "receipt" -> "RECIBO"
            else -> if (esAdicion) "ADICION" else null
        }
        if (station != null) {
            out.write(REVERSE_ON)
            out.write(BOLD_ON)
            out.writeAscii(centerPad(spacedLabel(station), width) + "\n")
            out.write(BOLD_OFF)
            out.write(REVERSE_OFF)
            lines += 1
        }

        // --- Subheader (COMANDA/ADICION #N) + hora a la derecha
        if (subheader != null || hora != null) {
            out.write(BOLD_ON)
            out.writeAscii(row(subheader ?: "", hora ?: "", width) + "\n")
            out.write(BOLD_OFF)
            lines += 1
        }

        // --- Mesa · Mozo
        val whoLine = buildList {
            if (mesa != null) add("Mesa $mesa")
            if (mozo != null) add("Mozo: $mozo")
        }
        if (whoLine.isNotEmpty()) {
            out.writeAscii(whoLine.joinToString("  ") + "\n")
            lines += 1
        }

        // --- Cliente (si el API lo provee)
        if (cliente != null) {
            out.writeAscii("Cliente: $cliente\n")
            lines += 1
        }

        // --- Comprobante · Comanda
        val refLine = buildList {
            if (orderId != null) add("Comp $orderId")
            if (comandaId != null) add("Com #$comandaId")
        }
        if (refLine.isNotEmpty()) {
            out.writeAscii(refLine.joinToString("  ") + "\n")
            lines += 1
        }

        // --- Nota general del pedido (aplica a toda la comanda)
        if (orderNote != null) {
            out.write(BOLD_ON)
            out.writeAscii("Nota: $orderNote\n")
            out.write(BOLD_OFF)
            lines += 1
        }

        out.writeAscii("$solid\n")
        lines += 1

        // --- Ítems
        var itemCount = 0
        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val qty = item.optInt("qty", 1)
                val name = item.optString("name").trim()
                val notes = item.optString("notes").trim()
                val modifiers = item.optJSONArray("modifiers")
                itemCount += qty

                out.write(BOLD_ON)
                out.writeAscii("${qty}x $name\n")
                out.write(BOLD_OFF)
                lines += 1

                if (modifiers != null) {
                    for (j in 0 until modifiers.length()) {
                        val mod = modifiers.getJSONObject(j)
                        val modName = mod.optString("name").trim()
                        if (modName.isBlank()) continue
                        val modQty = mod.optInt("qty", 1)
                        val prefix = if (modQty != 1) "${modQty}x " else ""
                        out.writeAscii("  + $prefix$modName\n")
                        lines += 1
                    }
                }

                if (notes.isNotBlank()) {
                    out.write(BOLD_ON)
                    out.writeAscii("  ! $notes\n")
                    out.write(BOLD_OFF)
                    lines += 1
                }
            }
        }

        out.writeAscii("$solid\n")
        lines += 1

        // --- Pie: contador de ítems + fecha/hora
        val countLabel = if (itemCount == 1) "1 item" else "$itemCount items"
        val dateLabel = formatDate(createdAt, hora)
        out.write(BOLD_ON)
        out.writeAscii(row(countLabel, dateLabel, width) + "\n")
        out.write(BOLD_OFF)
        lines += 1

        val pad = (MIN_TICKET_LINES - lines).coerceAtLeast(0)
        out.writeAscii("\n".repeat(pad))
        out.writeAscii(FEED_BEFORE_CUT)
        out.write(CUT_PARTIAL)
        return out.toByteArray()
    }

    /** Centra `s` en un ancho fijo rellenando con espacios a ambos lados. */
    private fun centerPad(s: String, width: Int = WIDTH_80MM): String {
        if (s.length >= width) return s.take(width)
        val total = width - s.length
        val left = total / 2
        return " ".repeat(left) + s + " ".repeat(total - left)
    }

    /** Alinea `left` a la izquierda y `right` a la derecha dentro del ancho de línea. */
    private fun row(left: String, right: String, width: Int = WIDTH_80MM): String {
        val l = left.trim()
        val r = right.trim()
        if (r.isEmpty()) return l.take(width)
        if (l.length + 1 + r.length > width) {
            val maxLeft = (width - r.length - 1).coerceAtLeast(0)
            val lt = l.take(maxLeft)
            val gap = (width - lt.length - r.length).coerceAtLeast(1)
            return lt + " ".repeat(gap) + r
        }
        val gap = width - l.length - r.length
        return l + " ".repeat(gap) + r
    }

    /** "ADICION" -> "A D I C I O N" (separa letras para el banner). */
    private fun spacedLabel(s: String): String = s.trim().toCharArray().joinToString(" ")

    /** Formatea `created_at` (ISO8601) como "DD/MM" + hora local (`meta.hora` si viene). */
    private fun formatDate(createdAt: String?, hora: String?): String {
        val datePart = createdAt?.takeIf { it.length >= 10 }?.let {
            "${it.substring(8, 10)}/${it.substring(5, 7)}"
        }
        val timePart = hora ?: createdAt?.takeIf { it.length >= 16 }?.substring(11, 16)
        return listOfNotNull(datePart, timePart).joinToString(" ")
    }

    /** Trimea y descarta vacíos y el string literal "null" que a veces llega en JSON. */
    private fun String?.cleanOrNull(): String? =
        this?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private fun ByteArrayOutputStream.writeAscii(s: String) {
        write(s.toByteArray(Charsets.ISO_8859_1))
    }
}
