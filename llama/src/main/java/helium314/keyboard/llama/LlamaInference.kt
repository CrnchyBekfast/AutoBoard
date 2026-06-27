package helium314.keyboard.llama

object LlamaInference {
    init {
        System.loadLibrary("llama_jni")
    }

    /** Load model from [modelPath]. Returns an opaque handle (> 0) on success, 0 on failure. */
    external fun loadModel(modelPath: String): Long

    /**
     * Run a single forward pass for [prompt] and return the top-[topK] predicted next words.
     * Words are lowercase, alphabetic only. Returns an empty array if the model is not loaded
     * or inference fails.
     */
    external fun predict(handle: Long, prompt: String, topK: Int): Array<String>

    /** Free all native resources for the given [handle]. */
    external fun freeModel(handle: Long)
}
