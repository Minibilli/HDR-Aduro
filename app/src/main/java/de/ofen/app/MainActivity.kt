package de.ofen.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationManagerCompat
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stove.init(AndroidPlatform.of(this))
        Notifier.channels(this)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()
        // Eigene Dialoge ohne Überschrift „Die Seite … meldet“
        web.webChromeClient = object : WebChromeClient() {
            private fun dlg() = android.app.AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            override fun onJsAlert(v: WebView?, url: String?, msg: String?, r: android.webkit.JsResult): Boolean {
                dlg().setMessage(msg).setPositiveButton("OK") { _, _ -> r.confirm() }
                    .setOnCancelListener { r.confirm() }.show(); return true
            }
            override fun onJsConfirm(v: WebView?, url: String?, msg: String?, r: android.webkit.JsResult): Boolean {
                dlg().setMessage(msg).setPositiveButton("OK") { _, _ -> r.confirm() }
                    .setNegativeButton("Abbrechen") { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }.show(); return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Ofen")
        web.setBackgroundColor(0)
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        OfenService.start(this) // läuft nur, wenn die Funktionen gestartet sind
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        OfenService.start(this) // Benachrichtigung der Überwachung jetzt sichtbar machen
    }

    // ------------------------------------------------------------------ Foto vom Typenschild -> Text
    private val REQ_PHOTO = 4711
    private var photoField: String? = null
    private var photoUri: Uri? = null

    private fun photoReply(text: String?, error: String?) {
        val f = photoField ?: return
        val js = "window.__photoText&&__photoText(${JSONObject.quote(f)},${if (text == null) "null" else JSONObject.quote(text)},${if (error == null) "null" else JSONObject.quote(error)})"
        web.post { web.evaluateJavascript(js, null) }
    }

    // ------------------------------------------------------------------ Eigenes Foto als Hintergrund der Statuskarte
    private val REQ_BG = 4712
    private var bgUri: Uri? = null
    private fun bgFile() = File(filesDir, "hintergrund.jpg")

    private fun bgDataUrl(): String {
        val f = bgFile()
        if (!f.exists()) return ""
        return "data:image/jpeg;base64," + android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
    }

    private fun bgReply(error: String?) {
        val js = "window.__bgSet&&__bgSet(${JSONObject.quote(if (error == null) bgDataUrl() else "")},${if (error == null) "null" else JSONObject.quote(error)})"
        web.post { web.evaluateJavascript(js, null) }
    }

    /** Foto laden, nach EXIF drehen, auf max. 1280 px verkleinern, als JPEG speichern */
    private fun bgSave(uri: Uri) {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= 1280) sample *= 2
        val bmp0 = contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("kein Bild")
        val rot = try {
            contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) { 0f }
        val scale = minOf(1f, 1280f / maxOf(bmp0.width, bmp0.height))
        val m = android.graphics.Matrix().apply { postScale(scale, scale); postRotate(rot) }
        val bmp = android.graphics.Bitmap.createBitmap(bmp0, 0, 0, bmp0.width, bmp0.height, m, true)
        bgFile().outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
    }

    @Deprecated("Activity-API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_BG) {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: bgUri ?: return
            Thread {
                try { bgSave(uri); bgReply(null) } catch (e: Exception) { bgReply("Foto konnte nicht gelesen werden (${e.javaClass.simpleName})") }
            }.start()
            return
        }
        if (requestCode != REQ_PHOTO) { super.onActivityResult(requestCode, resultCode, data); return }
        if (resultCode != RESULT_OK) { photoReply(null, null); return }
        val uri = data?.data ?: photoUri ?: run { photoReply(null, "Kein Foto erhalten"); return }
        try {
            val img = InputImage.fromFilePath(this, uri)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
                .addOnSuccessListener { t -> photoReply(t.text, null) }
                .addOnFailureListener { e -> photoReply(null, "Texterkennung nicht möglich (${e.message ?: e.javaClass.simpleName})") }
        } catch (e: Exception) {
            photoReply(null, "Foto konnte nicht gelesen werden (${e.javaClass.simpleName})")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.evaluateJavascript("window.onAppResume&&onAppResume()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.goBack&&goBack())||false") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    inner class Bridge {
        private val pool = Executors.newFixedThreadPool(3)

        /** Asynchrone Anfrage aus der Oberfläche; Antwort per window.__ofenResolve(id, json) */
        @JavascriptInterface
        fun request(id: Int, path: String, body: String?) {
            pool.execute {
                val res = Link.handle(path, body)   // gekoppelt mit Raspi: Anfragen gehen ggf. dorthin
                if (path == "config" && body != null && Stove.configured()) OfenService.start(this@MainActivity)
                val js = "window.__ofenResolve($id, ${JSONObject.quote(res.toString())})"
                web.post { web.evaluateJavascript(js, null) }
            }
        }

        @JavascriptInterface
        fun openApp(pkg: String): Boolean {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            return if (i != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            } else {
                // nicht installiert -> Store-Seite
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { }
                false
            }
        }

        @JavascriptInterface
        fun tick() {
            try {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) { }
        }

        /** App ganz beenden: Überwachung stoppen, nichts mehr automatisch, kein Start mit dem Handy */
        /** „Funktionen starten“ auf der Hauptseite */
        @JavascriptInterface
        fun startFunctions() {
            Stove.appOn()
            runOnUiThread { OfenService.start(this@MainActivity) }
        }

        @JavascriptInterface
        fun quitApp() {
            pool.execute {
                try { Stove.appQuit() } catch (e: Exception) { }
                runOnUiThread {
                    OfenService.stop(this@MainActivity)
                    NotificationManagerCompat.from(this@MainActivity).cancelAll()
                    finishAndRemoveTask()
                }
            }
        }

        /** Hintergrundfoto: Kamera oder Galerie; Ergebnis per window.__bgSet(dataUrl, fehler) */
        @JavascriptInterface
        fun pickBackground() {
            runOnUiThread {
                try {
                    val file = File(cacheDir, "hintergrund_neu.jpg").apply { delete() }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    bgUri = uri
                    val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val pick = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                    val chooser = Intent.createChooser(pick, "Foto für den Hintergrund").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
                    @Suppress("DEPRECATION")
                    startActivityForResult(chooser, REQ_BG)
                } catch (e: Exception) { bgReply("Kamera/Galerie nicht verfügbar (${e.javaClass.simpleName})") }
            }
        }

        @JavascriptInterface
        fun backgroundPhoto(): String = try { bgDataUrl() } catch (e: Exception) { "" }

        @JavascriptInterface
        fun clearBackground() { bgFile().delete() }

        /** Typenschild fotografieren (oder Foto wählen) und Text erkennen; Ergebnis per window.__photoText(feld, text, fehler) */
        @JavascriptInterface
        fun photoText(field: String) {
            runOnUiThread {
                photoField = field
                try {
                    val file = File(cacheDir, "typenschild.jpg").apply { delete() }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    photoUri = uri
                    val cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val pick = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                    val chooser = Intent.createChooser(pick, "Foto vom Typenschild").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
                    @Suppress("DEPRECATION")
                    startActivityForResult(chooser, REQ_PHOTO)
                } catch (e: Exception) {
                    photoReply(null, "Kamera nicht verfügbar (${e.javaClass.simpleName})")
                }
            }
        }

        /** Barcode/QR-Code scannen; Ergebnis per window.__scanResult(feld, text, fehler) */
        @JavascriptInterface
        fun scanCode(field: String) {
            runOnUiThread {
                fun reply(text: String?, error: String?) {
                    val js = "window.__scanResult&&__scanResult(${JSONObject.quote(field)},${if (text == null) "null" else JSONObject.quote(text)},${if (error == null) "null" else JSONObject.quote(error)})"
                    web.evaluateJavascript(js, null)
                }
                try {
                    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).enableAutoZoom().build()
                    GmsBarcodeScanning.getClient(this@MainActivity, opts).startScan()
                        .addOnSuccessListener { b -> reply(b.rawValue ?: b.displayValue ?: "", null) }
                        .addOnCanceledListener { reply(null, null) }
                        .addOnFailureListener { e -> reply(null, "Scanner nicht verfügbar (${e.message ?: e.javaClass.simpleName}). Google-Play-Dienste aktuell?") }
                } catch (e: Exception) {
                    reply(null, "Scanner nicht verfügbar (${e.javaClass.simpleName})")
                }
            }
        }

        @JavascriptInterface
        fun vibrate() {
            try {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun bgInfo(): String {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return JSONObject()
                .put("battery_ok", pm.isIgnoringBatteryOptimizations(packageName))
                .put("notif_ok", Notifier.enabled(this@MainActivity))
                .put("version", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" })
                .toString()
        }

        @JavascriptInterface
        fun fixBattery() {
            try {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(i)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        @JavascriptInterface
        fun fixNotifications() {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(i)
        }
    }
}
