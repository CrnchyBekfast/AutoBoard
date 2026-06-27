package helium314.keyboard.latin.llama

import android.util.Log
import helium314.keyboard.llama.LlamaInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages llama.cpp model lifecycle and async next-word prediction.
 *
 * [onResult] is invoked on the main thread whenever a prediction completes.
 * Results are top-5 lowercase alphabetic words in ranked order.
 */
class LlamaPredictor(private val onResult: (List<String>) -> Unit) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var handle: Long = 0L
    @Volatile private var lastPrompt: String = ""
    private var inferJob: Job? = null

    fun loadModel(path: String) {
        scope.launch {
            Log.i(TAG, "Loading model: $path")
            val h = LlamaInference.loadModel(path)
            if (h == 0L) Log.e(TAG, "Failed to load model from $path")
            else Log.i(TAG, "Model loaded successfully")
            handle = h
        }
    }

    /** Fire async inference for [prompt]. Only one inference runs at a time; stale requests cancel. */
    fun predict(prompt: String) {
        if (handle == 0L) return
        if (prompt == lastPrompt) return // context unchanged since last prediction
        lastPrompt = prompt

        inferJob?.cancel()
        inferJob = scope.launch {
            Log.d(TAG, "Prompt: $prompt")
            val t0 = System.currentTimeMillis()
            val words = LlamaInference.predict(handle, prompt, TOP_K).toList()
            val ms = System.currentTimeMillis() - t0
            Log.d(TAG, "Inference: ${ms}ms → $words")
            withContext(Dispatchers.Main) { onResult(words) }
        }
    }

    fun shutdown() {
        scope.cancel()
        val h = handle
        if (h != 0L) {
            handle = 0L
            LlamaInference.freeModel(h)
        }
    }

    companion object {
        private const val TAG = "LlamaPredictor"
        private const val TOP_K = 5
    }
}
