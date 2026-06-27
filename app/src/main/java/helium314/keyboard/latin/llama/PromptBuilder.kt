package helium314.keyboard.latin.llama

object PromptBuilder {
    private const val MAX_SCREEN_CHARS = 800
    private const val MAX_CURSOR_CHARS = 300

    fun build(packageName: String, textBefore: String, screenText: String = ""): String {
        val appLine = if (packageName.isNotEmpty()) "<|app|>$packageName\n" else ""
        val ctxBlock = if (screenText.isNotBlank())
            "<|ctx|>\n${screenText.take(MAX_SCREEN_CHARS)}\n<|/ctx|>\n"
        else
            ""
        val cursor = textBefore.takeLast(MAX_CURSOR_CHARS)
        return "$appLine$ctxBlock$cursor<|cursor|>"
    }
}
