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
    /** Socket fest ans WLAN binden, in dem die Zieladresse liegt – sonst schickt Android die Pakete
     *  evtl. über mobile Daten (z. B. Samsung „Zu mobilen Daten wechseln“ bei schwachem WLAN) */
    fun bindLocal(sock: java.net.DatagramSocket, target: String) {}

    /** Zeitstempel für Schutzzeiten und Abschaltzeit – eigener Speicher, damit saveConfig() sie nicht mit
     *  älteren Ständen überschreibt, und dauerhaft, damit sie einen Neustart überstehen */
    fun timeLoad(k: String): Long
    fun timeSave(k: String, v: Long)
    fun timeDel(k: String)

    /** Vorwarnung vor der Abschaltzeit (Android: mit Knopf „Diesmal nicht abschalten“) */
    fun shutoffWarn(hm: String) { alert(Alerts.SHUTOFF, "Ofen geht um $hm aus", "Abschaltzeit in 15 Minuten.") }

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
    const val SHUTOFF = 7
}
