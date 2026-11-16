package it.pokervision.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val processing = AtomicBoolean(false)
    private var running = true
    private var lastRequest = 0L
    private var latest = ""
    private var lastPot: String? = null
    private var lastCall: String? = null
    private var lastPotAt = 0L
    private var lastCallAt = 0L
    private lateinit var status: TextView
    private lateinit var pot: TextView
    private lateinit var call: TextView
    private lateinit var action: TextView
    private lateinit var phase: TextView
    private lateinit var ocr: TextView

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "Permesso fotocamera necessario"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        pot = findViewById(R.id.pot)
        call = findViewById(R.id.call)
        action = findViewById(R.id.action)
        phase = findViewById(R.id.phase)
        ocr = findViewById(R.id.ocr)
        findViewById<Button>(R.id.toggle).setOnClickListener { button ->
            running = !running
            (button as Button).text = if (running) "Pausa lettura" else "Riprendi lettura"
            status.text = if (running) "Lettura attiva" else "In pausa"
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(Date())
            val file = File(filesDir, "rilevazioni.txt")
            file.appendText("\n[$stamp]\n$latest\n", Charsets.UTF_8)
            Toast.makeText(this, "Salvato in archivio interno: rilevazioni.txt", Toast.LENGTH_LONG).show()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = findViewById<PreviewView>(R.id.preview).surfaceProvider
            }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(1280, 720))
                .build()
            analyzer.setAnalyzer(executor) { proxy ->
                val now = SystemClock.elapsedRealtime()
                if (!running || now - lastRequest < 350 || !processing.compareAndSet(false, true)) {
                    proxy.close()
                    return@setAnalyzer
                }
                lastRequest = now
                val media = proxy.image
                if (media == null) {
                    processing.set(false)
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val items = result.textBlocks.flatMap { it.lines }
                        val raw = items.joinToString("\\n") { it.text }
                        latest = raw
                        val amount = Regex("""[€$]?\\s*(\\d{1,5}[,.]\\d{1,2})""")
                        val w = image.width.toFloat().coerceAtLeast(1f)
                        val h = image.height.toFloat().coerceAtLeast(1f)
                        fun nearbyAmount(keyword: Regex, region: (Float, Float) -> Boolean): String? {
                            val anchors = items.filter { line ->
                                val rect = line.boundingBox ?: return@filter false
                                keyword.containsMatchIn(line.text) &&
                                    region(rect.exactCenterX() / w, rect.exactCenterY() / h)
                            }
                            for (anchor in anchors) {
                                amount.find(anchor.text)?.groupValues?.getOrNull(1)?.let { return it }
                                val ar = anchor.boundingBox ?: continue
                                val neighbor = items.asSequence().filter { it !== anchor }.mapNotNull { line ->
                                    val rect = line.boundingBox ?: return@mapNotNull null
                                    val cx = rect.exactCenterX() / w
                                    val cy = rect.exactCenterY() / h
                                    if (!region(cx, cy)) return@mapNotNull null
                                    val dx = kotlin.math.abs(rect.exactCenterX() - ar.exactCenterX()) / w
                                    val dy = kotlin.math.abs(rect.exactCenterY() - ar.exactCenterY()) / h
                                    val v = amount.find(line.text)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                                    if (dx > .22f || dy > .07f) return@mapNotNull null
                                    Pair(v, dx + dy * 2f)
                                }.minByOrNull { it.second }
                                if (neighbor != null) return neighbor.first
                            }
                            return null
                        }
                        // Exclude log/chat and action buttons when searching pot.
                        val potValue = nearbyAmount(Regex("(?i)piatto|\\\\bpot\\\\b")) { x,y ->
                            x in .24f.. .78f && y in .12f.. .58f
                        }
                        // Locate the call button exclusively in lower-right of camera frame.
                        val callValue = nearbyAmount(Regex("(?i)chiama|call")) { x,y ->
                            x in .54f..1f && y in .70f..1f
                        }
                        val nowDisplay = SystemClock.elapsedRealtime()
                        if (potValue != null) { lastPot = potValue; lastPotAt = nowDisplay }
                        if (callValue != null) { lastCall = callValue; lastCallAt = nowDisplay }
                        pot.text = if (nowDisplay - lastPotAt < 1800 && lastPot != null)
                            "€${lastPot}" else "—"
                        call.text = if (nowDisplay - lastCallAt < 1800 && lastCall != null)
                            "€${lastCall}" else "—"
                        action.text = "Non classificata"
                        phase.text = "Carte: riconoscimento dedicato non disponibile"
                        ocr.text = raw
                        status.text = "Lettura attiva • OCR circa 3 volte/s"
                    }
                    .addOnFailureListener { e -> status.text = "Errore OCR: ${e.localizedMessage}" }
                    .addOnCompleteListener { processing.set(false); proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            status.text = "Fotocamera attiva"
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        executor.shutdown()
    }
}
