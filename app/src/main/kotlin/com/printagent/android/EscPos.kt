package com.printagent.android

import org.json.JSONObject
import java.io.ByteArrayOutputStream

object EscPos {

    private val INIT = byteArrayOf(0x1B, 0x40)
    private val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
    private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
    private val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
    private val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
    private val SIZE_NORMAL = byteArrayOf(0x1D, 0x21, 0x00)
    private val SIZE_DOUBLE = byteArrayOf(0x1D, 0x21, 0x11)
    private val CUT_PARTIAL = byteArrayOf(0x1D, 0x56, 0x01)
    private const val DIVIDER = "--------------------------------"
    private const val FEED_BEFORE_CUT = "\n\n\n\n\n\n"

    fun buildTestTicket(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(INIT)

        out.write(ALIGN_CENTER)
        out.write(BOLD_ON)
        out.write(SIZE_DOUBLE)
        out.writeAscii("PRINT AGENT\n")
        out.write(SIZE_NORMAL)
        out.write(BOLD_OFF)
        out.writeAscii("Test de impresion\n")
        out.writeAscii("$DIVIDER\n")

        out.write(ALIGN_LEFT)
        out.writeAscii("1 x Hamburguesa clasica\n")
        out.writeAscii("2 x Papas fritas\n")
        out.writeAscii("1 x Coca Cola 500ml\n")

        out.write(ALIGN_CENTER)
        out.writeAscii("$DIVIDER\n")
        out.writeAscii("Mesa 5 - Mozo: Demo\n")
        out.writeAscii(FEED_BEFORE_CUT)
        out.write(CUT_PARTIAL)
        return out.toByteArray()
    }

    fun buildJobTicket(content: JSONObject): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(INIT)

        val header = content.optString("header").takeIf { it.isNotBlank() }
        val subheader = content.optString("subheader").takeIf { it.isNotBlank() }
        val footer = content.optString("footer").takeIf { it.isNotBlank() }
        val items = content.optJSONArray("items")

        if (header != null) {
            out.write(ALIGN_CENTER)
            out.write(BOLD_ON)
            out.write(SIZE_DOUBLE)
            out.writeAscii("$header\n")
            out.write(SIZE_NORMAL)
            out.write(BOLD_OFF)
        }

        if (subheader != null) {
            out.write(ALIGN_CENTER)
            out.write(BOLD_ON)
            out.writeAscii("$subheader\n")
            out.write(BOLD_OFF)
        }

        out.write(ALIGN_LEFT)
        out.writeAscii("$DIVIDER\n")

        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val qty = item.optInt("qty", 1)
                val name = item.optString("name").trim()
                val notes = item.optString("notes").trim()
                out.writeAscii("$qty x $name\n")
                if (notes.isNotBlank()) {
                    out.writeAscii("  > $notes\n")
                }
            }
        }

        out.writeAscii("$DIVIDER\n")

        if (footer != null) {
            out.write(ALIGN_CENTER)
            out.writeAscii("$footer\n")
        }

        out.writeAscii(FEED_BEFORE_CUT)
        out.write(CUT_PARTIAL)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(s: String) {
        write(s.toByteArray(Charsets.ISO_8859_1))
    }
}
