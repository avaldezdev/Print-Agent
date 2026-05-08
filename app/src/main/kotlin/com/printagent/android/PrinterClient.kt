package com.printagent.android

import java.net.InetSocketAddress
import java.net.Socket

object PrinterClient {

    fun send(ip: String, port: Int, bytes: ByteArray, connectTimeoutMs: Int = 5000, writeTimeoutMs: Int = 5000): Int {
        Socket().use { sock ->
            sock.soTimeout = writeTimeoutMs
            sock.connect(InetSocketAddress(ip, port), connectTimeoutMs)
            sock.getOutputStream().use { out ->
                out.write(bytes)
                out.flush()
            }
        }
        return bytes.size
    }
}
