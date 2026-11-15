package de.ofen.raspi

import de.ofen.app.Control
import de.ofen.app.Platform
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Raspberry Pi: Einstellungen als Datei, Meldungen gehen an die gekoppelten Handys. */
class RaspiPlatform(private val dir: File) : Platform {
    private val file = File(dir, "config.json")

    init { dir.mkdirs() }

    override val kind = "raspi"
    override val deviceName: String get() = Control.myName()
    override val osType = "Linux"
    override val osVersion: String = System.getProperty("os.version") ?: ""

    override fun loadConfig(): String = try { if (file.exists()) file.readText() else "{}" } catch (e: Exception) { "{}" }

    /** Erst in eine Hilfsdatei schreiben, dann umbenennen – ein Stromausfall zerstört so nie die Einstellungen */
    override fun saveConfig(json: String) {
        val tmp = File(dir, "config.json.tmp")
        tmp.writeText(json)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ifaces() = try { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback } } catch (e: Exception) { emptyList() }

    override fun broadcastAddrs(): List<String> =
        ifaces().flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast?.hostAddress }.distinct()

    override fun vpnActive(): Boolean = false

    override fun onHomeNet(stoveIp: String): Boolean {
        val pre = stoveIp.split(".").take(3)
        return ifaces().flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .any { it.hostAddress.split(".").take(3) == pre }
    }

    override fun notifyState(title: String, text: String) {
        log("$title – $text")
        Control.event("state", title, text)
    }

    override fun alert(id: Int, title: String, text: String) {
        log("WARNUNG $title – $text")
        Control.event("alert", title, text, id)
    }

    override fun clearAlert(id: Int) = Control.event("clear", "", "", id)

    companion object {
        fun log(msg: String) = println("${java.time.LocalDateTime.now().withNano(0)}  $msg")
    }
}
