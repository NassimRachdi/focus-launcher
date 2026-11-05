package fr.nassim.focuslauncher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Valeurs lues sur la prise OBD-II. null = non disponible. */
data class ObdData(
    val coolantC: Int? = null,
    val rpm: Int? = null,
    val loadPct: Int? = null,
    val volts: Float? = null,
    val dtcCount: Int? = null,
    val mil: Boolean? = null
)

/**
 * Client ELM327 minimal, en LECTURE SEULE (aucune commande d'écriture n'est envoyée au véhicule).
 * Tourne sur un thread dédié, ~1 rafraîchissement par seconde, reconnexion automatique.
 *
 * mode = "bt"   : dongle Bluetooth appairé dans les paramètres Android (nom contenant OBD/ELM/V-LINK…)
 * mode = "wifi" : dongle Wi-Fi ELM327 (192.168.0.10:35000 par défaut)
 */
class ObdClient(
    private val mode: String,
    private val onData: (ObdData) -> Unit,
    private val onStatus: (connected: Boolean, text: String) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var btSocket: BluetoothSocket? = null
    @Volatile private var tcpSocket: Socket? = null

    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "obd").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    fun stop() {
        running = false
        closeQuietly()
        thread?.interrupt()
        thread = null
    }

    private fun status(ok: Boolean, t: String) = ui.post { onStatus(ok, t) }

    private fun loop() {
        while (running) {
            try {
                connect()
                init()
                status(true, if (mode == "wifi") "Wi-Fi" else "Bluetooth")
                poll()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                status(false, e.message ?: "Déconnecté")
            } finally {
                closeQuietly()
            }
            try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
        }
    }

    private fun connect() {
        if (mode == "wifi") {
            val s = Socket()
            s.connect(InetSocketAddress("192.168.0.10", 35000), 4000)
            s.soTimeout = 3000
            tcpSocket = s
            input = s.getInputStream(); output = s.getOutputStream()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Bluetooth Android indisponible")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth désactivé")
        val dev = adapter.bondedDevices.firstOrNull { d ->
            val n = (d.name ?: "").uppercase()
            NAMES.any { n.contains(it) }
        } ?: throw IllegalStateException("Dongle non appairé")
        status(false, "Connexion…")
        adapter.cancelDiscovery()
        val sock = dev.createInsecureRfcommSocketToServiceRecord(SPP)
        btSocket = sock
        sock.connect()
        input = sock.inputStream; output = sock.outputStream
    }

    private fun init() {
        send("ATZ", 2500)
        for (c in arrayOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) send(c)
        send("0100", 6000) // déclenche la détection du protocole (peut être long la 1re fois)
    }

    private fun poll() {
        var n = 0
        var dtc: Int? = null
        var mil: Boolean? = null
        var volts: Float? = null
        var coolant: Int? = null
        while (running) {
            val t0 = System.currentTimeMillis()
            val rpm = pid("010C", 2)?.let { (it[0] * 256 + it[1]) / 4 }
            val load = pid("0104", 1)?.let { it[0] * 100 / 255 }
            if (n % 3 == 0) {
                coolant = pid("0105", 1)?.let { it[0] - 40 }
                volts = parseVolts(send("ATRV"))
            }
            if (n % 30 == 0) {
                pid("0101", 4)?.let { mil = (it[0] and 0x80) != 0; dtc = it[0] and 0x7F }
            }
            val d = ObdData(coolant, rpm, load, volts, dtc, mil)
            ui.post { onData(d) }
            n++
            val wait = 1000 - (System.currentTimeMillis() - t0)
            if (wait > 0) Thread.sleep(wait)
        }
    }

    /** Envoie un PID mode 01 et renvoie les octets de données, ou null si non supporté. */
    private fun pid(cmd: String, bytes: Int): IntArray? {
        val r = send(cmd).replace(" ", "")
        val head = "4" + cmd.substring(1)
        val i = r.indexOf(head)
        if (i < 0 || r.length < i + head.length + bytes * 2) return null
        return try {
            IntArray(bytes) { k -> r.substring(i + head.length + k * 2, i + head.length + k * 2 + 2).toInt(16) }
        } catch (_: NumberFormatException) { null }
    }

    private fun parseVolts(r: String): Float? =
        Regex("(\\d{1,2}\\.\\d)").find(r)?.value?.toFloatOrNull()

    /** Écrit une commande et lit jusqu'au prompt '>' */
    private fun send(cmd: String, timeoutMs: Long = 1500): String {
        val out = output ?: throw IllegalStateException("Non connecté")
        val inp = input ?: throw IllegalStateException("Non connecté")
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (inp.available() > 0) {
                val c = inp.read()
                if (c < 0) throw IllegalStateException("Liaison perdue")
                if (c.toChar() == '>') break
                sb.append(c.toChar())
            } else {
                Thread.sleep(10)
            }
        }
        if (System.currentTimeMillis() >= end && sb.isEmpty()) throw IllegalStateException("Pas de réponse")
        return sb.toString().trim().uppercase()
    }

    private fun closeQuietly() {
        try { btSocket?.close() } catch (_: Exception) {}
        try { tcpSocket?.close() } catch (_: Exception) {}
        btSocket = null; tcpSocket = null; input = null; output = null
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val NAMES = arrayOf("OBD", "ELM", "V-LINK", "VLINK", "OBDLINK", "VGATE", "KONNWEI", "VIECAR")
    }
}
