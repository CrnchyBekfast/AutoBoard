package helium314.keyboard.latin.llama

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {

    const val MODEL_URL =
        "https://huggingface.co/RitwikSehrawat/gemma-3-1b-kbd-q4km/resolve/main/gemma-3-1b-kbd-q4km.gguf"

    private const val TAG = "ModelDownloader"
    private const val BUFFER_SIZE = 8 * 1024

    /**
     * Download the GGUF from [url] to [dest], calling [onProgress] with 0-100 as bytes arrive.
     * Writes to a temp file and atomically renames on success to avoid corrupt partial files.
     * Returns true on success, false on any error.
     */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parent, dest.name + ".tmp")

        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout    = 60_000
            conn.connect()

            if (conn.responseCode !in 200..299) {
                Log.e(TAG, "HTTP ${conn.responseCode} for $url")
                return false
            }

            val total = conn.contentLengthLong
            var downloaded = 0L
            var lastReportedPct = -1

            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = (downloaded * 100L / total).toInt()
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }

            tmp.renameTo(dest).also { ok ->
                if (!ok) Log.e(TAG, "Failed to rename $tmp to $dest")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            tmp.delete()
            false
        }
    }
}
