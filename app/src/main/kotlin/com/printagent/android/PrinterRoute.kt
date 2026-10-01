package com.printagent.android

import org.json.JSONObject

/**
 * Decide a qué impresora va cada job.
 *
 * La asignación producto → estación → impresora se administra en WAMA
 * (Estaciones de cocina + Impresoras de tickets). WAMA ya divide la venta en un job
 * por estación y cada job trae `kitchen_station.printer` con la IP de destino.
 *
 * Regla:
 * - Job con estación e impresora de red con IP → esa impresora.
 * - Cualquier otro caso (sin estación, estación sin impresora, impresora no-red)
 *   → la impresora por defecto configurada en la tablet. Así una instalación de una
 *   sola impresora sigue funcionando exactamente igual que antes.
 *
 * Nunca se desvía a otra impresora si la de la estación falla: un pedido de barra
 * impreso en cocina es peor que un pedido demorado con alerta.
 */
object PrinterRoute {

    data class Target(
        val ip: String,
        val port: Int,
        /** Nombre para mostrar en notificaciones y alertas. */
        val label: String,
        val fromStation: Boolean,
    ) {
        val key: String get() = "$ip:$port"
    }

    /** null = el job no tiene estación y tampoco hay impresora por defecto configurada. */
    fun resolve(job: JSONObject, defaultIp: String, defaultPort: Int): Target? {
        val printer = station(job)?.optJSONObject("printer")
        if (printer != null) {
            val type = printer.optString("connection_type").clean()
            val ip = printer.optString("ip_address").clean()
            if (ip != null && (type == null || type == "network")) {
                val port = printer.optString("port").clean()?.toIntOrNull() ?: 9100
                val label = printer.optString("name").clean() ?: ip
                return Target(ip, port, label, fromStation = true)
            }
        }
        if (defaultIp.isBlank()) return null
        return Target(defaultIp, defaultPort, "Impresora por defecto", fromStation = false)
    }

    /** Nombre de la estación de cocina del job, si WAMA la informó. */
    fun stationName(job: JSONObject): String? = station(job)?.optString("name").clean()

    private fun station(job: JSONObject): JSONObject? =
        job.optJSONObject("kitchen_station")
            ?: job.optJSONObject("content")?.optJSONObject("meta")?.optJSONObject("kitchen_station")

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
