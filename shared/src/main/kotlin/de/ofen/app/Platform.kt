package de.ofen.app

/**
 * Alles, was vom Gerät abhängt (Android-Handy oder Raspberry Pi).
 * Der gemeinsame Kern (Stove, Hmip, Api, Control, Link) kennt nur diese Schnittstelle.
 */
interface Platform {
    /** "phone" (Android-App) oder "raspi" (Wandgerät mit Touchscreen) */
    val kind: String
    /** Name für Anzeige und Homematic, z. B. „Pixel 8“ oder „Raspi“ */
    val deviceName: String
    val osType: String
    val osVersion: String

    fun loadConfig(): String
    fun saveConfig(json: String)

    /** Broadcast-Adressen der lokalen Netze (ohne 255.255.255.255) */
    fun broadcastAddrs(): List<String>
    /** Läuft gerade ein VPN? */
    fun vpnActive(): Boolean
    /** Hängt das Gerät in einem lokalen Netz, das zur Ofen-IP passt (also zu Hause)? */
    fun onHomeNet(stoveIp: String): Boolean

    /** Zustandsmeldung (z. B. „Ofen: Normalbetrieb“) */
    fun notifyState(title: String, text: String)
    /** Warnung mit fester Nummer (ersetzt eine frühere mit derselben Nummer) */
    fun alert(id: Int, title: String, text: String)
    fun clearAlert(id: Int)
}

/** Nummern der Warnungen – gleich wie Notifier.ID_* in der Android-App */
object Alerts {
    const val WOOD = 2
    const val EARLY = 3
    const val HOPPER = 5
    const val CONTROL = 6
}
