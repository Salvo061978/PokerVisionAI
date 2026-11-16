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
    private lateinit var status: TextView
    private lateinit var pot: TextView
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
                        val lines = result.textBlocks.flatMap { it.lines }.map { it.text }
                        val raw = lines.joinToString("\n")
                        latest = raw
                        val potValue = Regex("(?:Piatto|Pot)\\s*[:：]?\\s*[€$]?\\s*([0-9]+[.,][0-9]{1,2})", RegexOption.IGNORE_CASE)
                            .find(raw)?.groupValues?.get(1)
                        pot.text = "Piatto OCR: " + (potValue ?: "non rilevato")
                        phase.text = "Carte e fase: verifica manuale (v0.1)"
                        ocr.text = raw.ifBlank { "Nessun testo individuato. Avvicina il telefono e metti a fuoco." }
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
