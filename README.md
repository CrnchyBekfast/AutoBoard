# AutoBoard

AutoBoard is a fork of [HeliBoard](https://github.com/HeliBorg/HeliBoard) — a privacy-conscious, open-source Android keyboard — extended with an on-device LLM for context-aware next-word prediction. A fine-tuned Gemma 3 1B model reads everything visible on screen via the Accessibility API and predicts your next word based on the full conversational context, the app you are typing in, and what you have already typed.

## How it works

The suggestion strip operates in two phases:

1. **Instant** — HeliBoard's existing n-gram dictionary fires synchronously and populates the strip immediately.
2. **Async LLM** — a background coroutine runs a single forward pass through the model and updates the strip with ranked predictions when ready (~2–4 s on a real arm64 device).

The LLM sees a structured prompt built from three sources:

```
<|app|>com.whatsapp
<|ctx|>
[other] hey did you finish the col106 lab
[me] nah still stuck on the
<|/ctx|>
[me] im stuck on the av<|cursor|>
```

- `<|app|>` — package name of the foreground app so the model adapts register (messaging vs. email vs. search).
- `<|ctx|> … <|/ctx|>` — all text visible on screen pulled from the Accessibility API, excluding the keyboard's own UI.
- Trailing text — up to 300 characters immediately before the cursor in the active field.

## LLM Integration — Technical Changes

All changes are additive on top of HeliBoard. The existing ndk-build pipeline for binary dictionaries is untouched.

### `:llama` Gradle module

A new Android library module (`llama/`) wraps llama.cpp via CMake and builds `libllama_jni.so` for `arm64-v8a` and `x86_64`. llama.cpp is included as a git submodule pinned to the commit used for training.

Key CMake details:
- Metal, OpenMP, BLAS, native CPU tuning, tests, examples, and the server binary are all disabled.
- Android's Bionic libc lacks `posix_madvise()` below API 26. A force-included shim header maps it to `madvise()` and defines the missing `POSIX_MADV_*` constants, keeping `minSdk = 21`.

### JNI bridge (`llama_jni.cpp`)

Three functions exposed over JNI:

| Function | Description |
|---|---|
| `loadModel(path)` → `Long` | Loads GGUF, allocates `llama_model` + `llama_context` (n_ctx = 512, CPU-only), returns opaque handle |
| `predict(handle, prompt, topK)` → `String[]` | Clears KV cache, runs one decode pass, argsorts logits, decodes top-200 tokens, filters to lowercase alphabetic words, returns top-K |
| `freeModel(handle)` | Frees context and model |

### Kotlin layer

| File | Role |
|---|---|
| `llama/…/LlamaInference.kt` | `object` with `external` declarations; loads `libllama_jni.so` |
| `latin/llama/LlamaPredictor.kt` | Coroutine wrapper on `Dispatchers.IO`; cancels stale jobs; delivers results on `Dispatchers.Main` |
| `latin/llama/PromptBuilder.kt` | Assembles the structured prompt from package name, screen text, and cursor context |
| `latin/llama/ScreenContextService.kt` | `AccessibilityService`; iterates all non-IME windows via `getWindows()` and collects visible text |
| `latin/llama/ModelDownloader.kt` | Streams the GGUF from HuggingFace over `HttpURLConnection`, writes to a `.tmp` file, atomically renames on completion |

### HeliBoard core modifications

- **`Suggest.kt`** — `@Volatile var mLlamaWords` field; LLM entries prepend the suggestion container at score `Int.MAX_VALUE / 2` so they sort above n-gram results.
- **`InputLogic.java`** — on every suggestion-strip update where no word is being composed, fires `LlamaPredictor.predict()` with the current prompt.
- **`LatinIME.java`** — owns the `LlamaPredictor` lifecycle (create in `onCreate`, shutdown in `onDestroy`); loads the model on startup if present.

## Model

**[RitwikSehrawat/gemma-3-1b-kbd-q4km](https://huggingface.co/RitwikSehrawat/gemma-3-1b-kbd-q4km)** — Gemma 3 1B fine-tuned for keyboard autocomplete, quantized to Q4_K_M GGUF (762 MB).

### Fine-tuning

Base model: `google/gemma-3-1b`. Fine-tuned with QLoRA (rank 16, all linear layers) using [Unsloth](https://github.com/unslothai/unsloth) on a single RTX 3060 (6 GB VRAM). Training used `adamw_8bit`, effective batch size 32, over 100 K sampled examples.

Six custom special tokens were added to Gemma's vocabulary (new vocab size: 262 151):

```
<|app|>  <|ctx|>  <|/ctx|>  <|cursor|>  [me]  [other]
```

### Quantization

```bash
llama.cpp/llama-quantize \
    gemma-3-1b-kbd-unsloth-f16.gguf \
    gemma-3-1b-kbd-q4km.gguf \
    Q4_K_M
```

## Corpus Construction

A purpose-built ~3 B token conversational corpus was assembled from five sources, since no existing dataset matched the register diversity needed for keyboard autocomplete.

**Sources.** Reddit comment dumps (via academictorrents), Enron email, OpenSubtitles, HackerNews comments, and MS MARCO queries — ~90 GB raw.

**Filtering & thread reconstruction.** Reddit dumps were reconstructed into comment trees using `parent_id` linkage across 29 subreddits with per-subreddit keep fractions calibrated by quality. Subtitles were kept as flat turn records; email went through aggressive body extraction stripping headers, quoted chains, and signatures.

**App-context tagging.** Every example was tagged with the `<|app|>` category. Reddit was split per-subreddit: conversational subs (r/CasualConversation, r/relationship_advice, r/AskMen, etc.) → `messaging`; content-comment subs (r/movies, r/books) → `social`. HackerNews → `social`, email → `email`, subtitles → `messaging`, MS MARCO → `search`.

**Example generation.** For each `[me]` turn, 3–5 random cursor positions were sampled to produce `(prompt, completion)` pairs. ~5–10 % synthetic typo injection (QWERTY-adjacent swaps, transpositions, dropped/doubled letters) was applied. Short sub-15-character comments were capped at ~25 % of examples. Total: **297 M examples** across 33 source files.

**Tokenization & packing.** Sequences were packed to length 384. Output: `train_packed.jsonl.gz` (19 GB) and `eval_packed.jsonl.gz` (304 MB). Training consumed the JSONL.gz directly.

## Building

**Prerequisites:** Android Studio, NDK 27, CMake 3.22+.

```bash
git clone --recurse-submodules https://github.com/<you>/AutoBoard
```

The `--recurse-submodules` flag pulls llama.cpp at the pinned commit automatically. Open the cloned directory in Android Studio and build.

### Loading the model

The model is not bundled in the APK. On first launch the app will prompt to download it from HuggingFace (762 MB). For development you can push it directly:

```bash
adb push gemma-3-1b-kbd-q4km.gguf /data/local/tmp/gemma.gguf
adb shell run-as helium314.keyboard.debug mkdir -p files/models
adb shell run-as helium314.keyboard.debug cp /data/local/tmp/gemma.gguf files/models/gemma-3-1b-kbd-q4km.gguf
```

Force-stop and relaunch the keyboard after pushing.

### Enabling screen context

Go to **Settings → Accessibility → AutoBoard Screen Context → Enable**. Without this the model predicts from cursor context alone; with it the full on-screen conversation is included in the prompt.

## License

AutoBoard inherits HeliBoard's license. See [LICENSE](LICENSE) and [LICENSES](LICENSES/).

## Credits

Built on [HeliBoard](https://github.com/HeliBorg/HeliBoard) by Helium314 and contributors, itself based on AOSP / OpenBoard. LLM inference via [llama.cpp](https://github.com/ggerganov/llama.cpp) by Georgi Gerganov.
