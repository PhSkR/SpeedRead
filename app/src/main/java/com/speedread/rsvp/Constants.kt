package com.speedread.rsvp

import android.graphics.Bitmap

/**
 * Constants used throughout the SpeedRead application
 */
object Constants {
    
    // Document size limits
    const val MAX_DOCUMENT_SIZE_BYTES = 104_857_600 // 100MB in bytes
    const val MAX_DOCUMENT_SIZE_CHARS = 10_000_000 // 10 million characters
    const val LARGE_DOCUMENT_THRESHOLD = 100_000   // 100k characters
    const val MAX_TITLE_LENGTH = 255
    // Upper bound on inline content length eligible for launch-time auto-resume.
    const val MAX_AUTO_RESUME_CONTENT_CHARS = 50_000_000 // 50 million characters
    
    // Performance settings
    const val WORDS_PER_PAGE_ESTIMATION = 250
    const val PAGE_BREAK_SCAN_LIMIT = 100

    // Diagnostic threshold for the RSVP word-hold overshoot log: a word held on screen
    // longer than its scheduled delay plus this margin is logged with both durations so
    // scheduled-delay causes (timing settings) can be told apart from wall-clock stalls
    // (main-thread contention). Margin absorbs normal Handler/vsync jitter.
    const val WORD_HOLD_OVERSHOOT_LOG_THRESHOLD_MS = 150L

    // Main-thread stall watchdog (debug diagnostic, see MainThreadStallWatchdog).
    // THRESHOLD: heartbeat lateness that counts as a stall — below typical word delays so
    // reader-visible stalls are always sampled. POLL: granularity of the blocked-duration
    // measurement after detection. MAX_WAIT: give up measuring (thread may be deadlocked).
    // SAMPLE_INTERVAL: idle spacing between heartbeats. STACK_FRAMES: frames logged per stall.
    const val STALL_WATCHDOG_THRESHOLD_MS = 200L
    const val STALL_WATCHDOG_POLL_MS = 50L
    const val STALL_WATCHDOG_MAX_WAIT_MS = 5_000L
    const val STALL_WATCHDOG_SAMPLE_INTERVAL_MS = 100L
    const val STALL_WATCHDOG_STACK_FRAMES = 16
    const val SAMPLE_BASED_COUNT_THRESHOLD = 10_000_000 // 10 million chars
    const val SAMPLE_SIZE_FOR_COUNTING = 100_000       // 100k chars
    
    // Default settings
    const val DEFAULT_WPM = 250
    const val DEFAULT_CHUNK_SIZE = 1

    // WPM slider bounds (shared between Options and Reading screens)
    const val WPM_SLIDER_MIN = 100f
    const val WPM_SLIDER_MAX = 1000f
    const val WPM_SLIDER_STEP = 10f

    // Timing settings
    const val DEFAULT_COMMA_PAUSE = 150      // milliseconds
    const val DEFAULT_PERIOD_PAUSE = 300     // milliseconds
    const val DEFAULT_SEMICOLON_PAUSE = 200  // milliseconds
    const val DEFAULT_COLON_PAUSE = 200      // milliseconds
    const val DEFAULT_QUESTION_PAUSE = 350   // milliseconds
    const val DEFAULT_EXCLAMATION_PAUSE = 350 // milliseconds
    const val DEFAULT_LINE_BREAK_PAUSE = 100 // milliseconds
    const val DEFAULT_PARAGRAPH_PAUSE = 500  // milliseconds

    // Word length timing slider bounds
    const val MIN_WORD_LENGTH_BASELINE = 2
    const val MAX_WORD_LENGTH_BASELINE = 10
    const val WORD_LENGTH_BASELINE_STEP = 1
    const val MIN_WORD_LENGTH_SCALING_PERCENT = 1
    const val MAX_WORD_LENGTH_SCALING_PERCENT = 25
    const val WORD_LENGTH_SCALING_PERCENT_STEP = 1

    // UI settings (RSVP reader)
    const val DEFAULT_FONT_SIZE = 32f
    const val MIN_FONT_SIZE = 12f
    const val MAX_FONT_SIZE = 72f

    // Landscape reader fills the full screen with no surrounding chrome, so the word can grow
    // significantly larger than the configured portrait size without overflowing. Applied
    // multiplicatively to the configured font size at display time — the configured value
    // itself is never mutated, so toggling orientation remains cheap and lossless.
    const val LANDSCAPE_FONT_SIZE_MULTIPLIER = 2f

    // Hold-to-play button visual feedback. Portrait uses scale animation; landscape compact
    // FAB uses alpha toggle (instant — not animated — so touch dispatch is never interrupted).
    const val HOLD_TO_PLAY_PRESS_SCALE = 0.92f
    const val HOLD_TO_PLAY_PRESS_TRANSLATION_Z = -8f
    const val HOLD_TO_PLAY_ANIM_DURATION_MS = 100L
    const val HOLD_TO_PLAY_HIDDEN_ALPHA = 0f
    const val HOLD_TO_PLAY_VISIBLE_ALPHA = 1f
    const val HOLD_TO_PLAY_MIN_PRESS_DURATION_MS = 180L

    // Play button translationZ when screen is dimmed (DP) to draw above the 16dp dim mask
    const val HOLD_TO_PLAY_DIMMED_BASE_TRANSLATION_Z_DP = 20f
    const val HOLD_TO_PLAY_DIMMED_PRESS_TRANSLATION_Z_DP = 10f

    // Tap-vs-hold threshold for the big portrait play FAB. Press releases shorter than this
    // count as a tap (toggle play/pause); presses held longer count as a hold (play while
    // held, pause on release). 250ms is short enough that a deliberate hold registers
    // immediately while still leaving a comfortable margin above the typical 50-150ms tap.
    const val HOLD_TO_PLAY_TAP_THRESHOLD_MS = 250L
    // Margin keeps the landscape compact FAB clear of the gesture-nav back-swipe zone.
    const val HOLD_TO_PLAY_COMPACT_MARGIN_DP = 24

    // Landscape compact FAB size. 50% larger than Material's mini (40dp) since the button
    // fades to alpha 0 during hold anyway — the extra surface area is only visible while
    // the user isn't actively reading, so it doesn't compete with the word display.
    const val LANDSCAPE_PLAY_BUTTON_SIZE_DP = 60

    // Page View display (independent from RSVP display)
    const val DEFAULT_PAGE_VIEW_FONT_SIZE = 18f
    const val MIN_PAGE_VIEW_FONT_SIZE = 10f
    const val MAX_PAGE_VIEW_FONT_SIZE = 48f

    // Default scroll mode for Page View. CONTINUOUS = single vertical scroll over the full
    // document (no per-page whitespace). PAGED = the original swipe-per-page behavior. Stored
    // here as a string so the persisted-pref reader can fall back to it without depending on
    // the engine enum at parse time.
    const val DEFAULT_PAGE_VIEW_SCROLL_MODE = "CONTINUOUS"

    // Page View Only mode: when enabled, documents open directly in Page View with no
    // RSVP controls, highlighting, or playback FABs — a distraction-free reading surface.
    const val DEFAULT_PAGE_VIEW_ONLY_MODE = false

    // Sticky content mode for PDF documents in Page View. TEXT (default) opens PDFs as
    // reflowed text — the app's primary reading surface; the toolbar's PDF toggle persists
    // PDF-first rendering for readers who prefer the native pages. AUTO = PDF-first with
    // text fallback (historical behavior, still honored if persisted).
    const val DEFAULT_PAGE_VIEW_CONTENT_MODE = "TEXT"
    const val PAGE_VIEW_CONTENT_MODE_TEXT = "TEXT"
    const val PAGE_VIEW_CONTENT_MODE_PDF = "PDF"

    // SharedPreferences file for Page View reading positions — separate from RSVP's
    // lastReadPosition so each mode tracks its own progress independently.
    const val PAGE_VIEW_POSITIONS_PREFS_NAME = "page_view_positions"

    // Page View floating overlay (toolbar + page indicator) auto-hide window. Matches typical
    // video-player chrome timing so the user can still reach the toolbar after pausing a swipe.
    const val PAGE_VIEW_OVERLAY_AUTO_HIDE_MS = 3_000L

    // Concurrent PDF page rendering is capped so a fast swipe through a large PDF cannot
    // stampede the decoder into OOM. Empirically sized for mid-range devices.
    const val PDF_CONCURRENT_RENDERS = 3

    // Number of PDF pages to pre-render on either side of the currently visible page. Larger
    // values keep swipes smooth at the cost of bitmap memory; smaller values save memory but
    // risk a momentary blank page when the user flicks across multiple pages.
    const val PDF_PRELOAD_RANGE = 2

    // Default pixel width for PDF rendering. Caller may override per-call. Bounded below by
    // PDF_RENDER_MIN_WIDTH/above by PDF_RENDER_MAX_WIDTH to prevent pathological bitmap sizes.
    const val PDF_RENDER_TARGET_WIDTH = 800
    const val PDF_RENDER_MIN_WIDTH = 100
    const val PDF_RENDER_MAX_WIDTH = 2000

    // Bitmap size ceiling for rendered PDF pages. 4096*4096 (16.7MP) is the conservative
    // texture-size cap for older GPUs; above this the Bitmap allocation would likely OOM
    // regardless of free heap.
    const val PDF_MAX_BITMAP_PIXELS = 4096 * 4096

    // Pixel format for rendered PDF page bitmaps. MUST be ARGB_8888: PdfRenderer.Page.render
    // rejects every other config with IllegalArgumentException("Unsupported pixel format") —
    // the previous RGB_565 memory optimisation made native PDF rendering fail on every
    // device. Keep the white pre-fill before rendering; the renderer composites onto the
    // existing pixels and an unfilled ARGB_8888 bitmap starts transparent.
    val PDF_RENDER_BITMAP_CONFIG: Bitmap.Config = Bitmap.Config.ARGB_8888

    // How many pages on either side of the currently visible page should retain their
    // rendered bitmap. Pages further away than this have their bitmap reference cleared
    // (eviction) so swiping through a long PDF cannot pin every visited page in memory.
    // Must be >= PDF_PRELOAD_RANGE so a freshly preloaded page is not immediately evicted
    // on the same navigation event.
    const val PDF_BITMAP_KEEP_RANGE = 4

    // Adaptive word-fit scaling: shrink font size so a long word fits on one line.
    // Safety margin compensates for non-linear glyph rendering (hinting/rounding) at smaller sizes.
    const val ADAPTIVE_FIT_SAFETY_MARGIN = 0.98f

    // Landscape tap-to-navigate zone split. A confirmed single-tap on the rsvpWordDisplay with
    // x < viewWidth * this fraction steps back one word; a tap with x >= the fraction steps
    // forward. 0.5 splits the screen evenly down the middle. Lives alongside scrubbing on the
    // same view — `onScroll` (any horizontal drag) still wins over a tap so a quick scrub never
    // gets misclassified as navigation.
    const val LANDSCAPE_TAP_NAV_LEFT_ZONE_FRACTION = 0.5f

    // Horizontal screen fraction at which the ORP (pivot letter) is anchored. 0.35 places the
    // pivot slightly left of center, matching the natural left-of-center fixation point of the
    // fovea during reading — every word's ORP lands in the same column so the eye can stay still
    // while words flip past. Must stay strictly in (0, 1) — the adaptive-fit math divides by both
    // anchor and (1 - anchor).
    const val ORP_ANCHOR_FRACTION = 0.45f
    
    // Colors
    const val DEFAULT_TEXT_COLOR = 0xFF000000.toInt() // Black
    const val DEFAULT_BACKGROUND_COLOR = 0xFFFFFFFF.toInt() // White
    
    // Progress and position
    const val AUTO_BOOKMARK_MIN_POSITION = 10

    // Title prefix for bookmarks created via the in-reader "quick" gestures (double-tap on a
    // word in Page View, double-tap on the RSVP word display). Centralised so the prefix stays
    // consistent across surfaces — the bookmark list filters / scans titles to surface "quick"
    // entries differently from named bookmarks, so a drift here would silently break that.
    const val BOOKMARK_QUICK_TITLE_PREFIX = "Quick Bookmark"

    // Database limits
    const val MAX_WORD_COUNT_SAFE_LIMIT = 1_000_000 // 1 million words
    
    // Delays and timeouts
    const val DOCUMENT_LOAD_DELAY = 100L // milliseconds
    const val STATUS_BAR_UPDATE_DELAY_SHORT = 500L // milliseconds
    const val STATUS_BAR_UPDATE_DELAY_LONG = 1500L // milliseconds
    
    // File handling
    const val FILE_READ_BUFFER_SIZE = 8192 // 8KB buffer
    const val MAX_FILENAME_LENGTH = 255

    // Supported document MIME types. Used by the file-share intent path (ACTION_SEND with
    // EXTRA_STREAM) so the type-sniff logic stays in lockstep with the manifest intent filters
    // that advertise the same set. Kotlin code references these; the manifest necessarily
    // duplicates the literal strings because XML cannot consume Kotlin constants — keep the two
    // surfaces in sync when adding a new supported type.
    const val MIME_TYPE_PDF = "application/pdf"
    const val MIME_TYPE_EPUB = "application/epub+zip"
    const val MIME_TYPE_TEXT_PLAIN = "text/plain"
    val SUPPORTED_SHARE_MIME_TYPES = arrayOf(
        MIME_TYPE_PDF,
        MIME_TYPE_EPUB,
        MIME_TYPE_TEXT_PLAIN
    )
    
    // Chunking settings
    const val DEFAULT_CHUNK_SIZE_CHARS = 10000  // 10k characters per chunk
    const val MAX_CHUNK_SIZE_CHARS = 50000     // 50k characters per chunk
    const val MIN_CHUNK_SIZE_CHARS = 1000      // 1k characters per chunk

    // Page View tokenization disk cache directory (under cacheDir)
    const val TOKEN_CACHE_DIR = "token_cache"
    // Bump BOTH versions whenever tokenization semantics change (e.g. the typographic-wrap
    // break suppression) so stale cached tokens/joined text with old break metadata are
    // discarded instead of bypassing the new tokenizer via loadPreTokenized. Bump
    // DISPLAY_CACHE_VERSION alone when only the display-text build changes (the token cache
    // is shared with RSVP and its content is unaffected).
    const val DISPLAY_CACHE_VERSION = 2
    const val TOKEN_CACHE_VERSION = 2

    // Page furniture suppression (Page View continuous mode). Printed page numbers and
    // repeated running headers/footers left in the extracted text by PDF import are blanked
    // from each page's CONTINUOUS display variant only — paged mode keeps them (furniture at
    // the edge of a discrete page reads naturally), and the token stream shared with
    // RSVP/TTS/bookmarks is never altered, so absolute word indices stay aligned. Detection
    // is anchored on imported PageBoundary edges; see PageArtifactDetector.
    const val PAGE_ARTIFACT_FILTERING_ENABLED = true
    // Minimum boundary-mapped pages before any detection runs — tiny documents lack the
    // repetition evidence the heuristics need.
    const val ARTIFACT_MIN_PAGES = 4
    // Tokens scanned inward from each page edge for a printed page number, and the largest
    // digit count accepted as one.
    const val ARTIFACT_PAGE_NUMBER_SCAN_TOKENS = 4
    const val ARTIFACT_PAGE_NUMBER_MAX_DIGITS = 4
    // A numeric edge token is only stripped when (printed value - PDF page number) is
    // identical on at least this many pages AND this fraction of all pages — in-body numbers
    // (years, quantities) do not form an arithmetic sequence aligned with page numbering.
    const val ARTIFACT_PAGE_NUMBER_MIN_MATCHES = 5
    const val ARTIFACT_PAGE_NUMBER_MIN_FRACTION = 0.4f
    // Longest running-header run considered, and how many pages must repeat the identical
    // normalized run at the same page edge before it is treated as furniture.
    const val ARTIFACT_HEADER_MAX_TOKENS = 8
    const val ARTIFACT_HEADER_MIN_REPEAT = 4
    // Single-token headers are riskier (short common words), so they additionally require
    // an all-caps token with a minimum letter count and more repetitions.
    const val ARTIFACT_HEADER_SINGLE_TOKEN_MIN_REPEAT = 6
    const val ARTIFACT_HEADER_SINGLE_TOKEN_MIN_LENGTH = 3

    // Inline figures (Page View). Figure regions detected at import are rendered from the
    // original PDF, cached as WebP under filesDir/FIGURE_CACHE_DIR/<contentHash>/, and shown
    // inline in the text flow. Region crops render at FIGURE_RENDER_TARGET_WIDTH px wide;
    // the tap-to-zoom viewer renders the full page at FIGURE_PAGE_RENDER_WIDTH.
    const val FIGURE_CACHE_DIR = "figures"
    // Object-replacement character (U+FFFC) mounted in page text where an inline figure renders.
    const val FIGURE_PLACEHOLDER_CHAR = '\uFFFC'
    const val FIGURE_RENDER_TARGET_WIDTH = 1080
    const val FIGURE_PAGE_RENDER_WIDTH = 1600
    const val FIGURE_WEBP_QUALITY = 90
    // In-memory LRU budget for decoded figure bitmaps shared across Page View holders.
    const val FIGURE_MEMORY_CACHE_BYTES = 24 * 1024 * 1024
    // Hard cap on a single rendered figure bitmap (tall plates), mirrors PDF_MAX_BITMAP_PIXELS
    // in spirit but sized for region crops.
    const val FIGURE_MAX_BITMAP_PIXELS = 2048 * 2048
    // Full-page figure viewer zoom bounds.
    const val FIGURE_VIEWER_MAX_ZOOM = 6f
    const val FIGURE_VIEWER_DOUBLE_TAP_ZOOM = 2.5f
    // Viewer dialog height as a fraction of the screen.
    const val FIGURE_VIEWER_HEIGHT_FRACTION = 0.72f

    // Schema / migration state
    const val PREF_NAME_SCHEMA_STATE = "schema_state"
    const val PREF_KEY_DATA_RESET_NOTICE = "data_reset_from_schema_upgrade"

    // Destructive fallback is permitted only from these legacy versions where no
    // schema JSON exists on disk to author a hand-written migration against. Any
    // upgrade originating from v11 or later requires a registered Migration.
    val LEGACY_DB_VERSIONS_WITHOUT_SCHEMA = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    // JSON serialization configuration
    object JsonConfig {
        const val PRETTY_PRINT = false
        const val IGNORE_UNKNOWN_KEYS = true
        const val ENCODE_DEFAULTS = true
    }

    // TTS (Listen mode) settings
    const val TTS_DEFAULT_RATE = 1.0f
    const val TTS_DEFAULT_PITCH = 1.0f
    const val TTS_MIN_RATE = 0.5f
    const val TTS_MAX_RATE = 3.0f
    const val TTS_MIN_PITCH = 0.5f
    const val TTS_MAX_PITCH = 2.0f
    // Step size for the inline ±speed selector under the play button on the Reading screen.
    // 0.10 = a noticeable but non-jarring change per tap; matches the slider's stepSize in
    // fragment_options.xml so taps feel consistent with the slider's detent positions.
    const val TTS_RATE_STEP = 0.10f
    // Tolerance for "is rate at min / max" comparisons that drive the ±button enable-state.
    // Repeated +0.10 / −0.10 nudges accumulate Float rounding drift (e.g. 0.5+0.1×15 lands at
    // 1.9999998, not exactly 2.0); a strict `rate < MAX` would mistakenly re-enable the
    // increase button after the cap. Sized smaller than half a step so the limit feels
    // "sticky" exactly at the bound and never one tap before.
    const val TTS_RATE_LIMIT_EPSILON = 0.001f

    // TTS chunking: keep utterances under the Android TextToSpeech 4000-char per-call limit
    // (getMaxSpeechInputLength) with margin for punctuation pause tokens some OEMs inject.
    // TTS_CHUNK_WORDS is the run-on backstop word-count ceiling — most utterances flush
    // earlier on paragraph breaks or sentence-ending '.', '!', '?' (see TtsChunker), so
    // hitting this cap only happens on text with no sentence terminators across hundreds
    // of words (rare). Sized large so the cap rarely fires; the char cap
    // (TTS_MAX_UTTERANCE_CHARS) still rules for technical text with long words.
    const val TTS_CHUNK_WORDS = 500
    const val TTS_MAX_UTTERANCE_CHARS = 3800

    // Number of chunks the NativeTtsEngine keeps queued in the Android TTS service ahead of
    // the currently-playing chunk. v1.14.4 introduced lookahead at 1 to mask synthesis-drain
    // gaps. v1.14.6 raises to 3: at 1.5x speech rate the onDone(N) → Main.immediate hop →
    // ensureQueueCovers IPC round-trip can race a fast-draining chunk N+1, so a deeper
    // queue gives more margin. A pause/seek bumps sessionGuid before tts.stop(), so
    // flushing three queued chunks via the session guard costs the same as flushing one.
    const val TTS_LOOKAHEAD_CHUNKS = 3

    // Debounce window for TTS seek requests during scrub-bar drag. Native TTS queue rebuilds
    // are expensive; without debouncing a finger-drag can thrash the speech service.
    const val TTS_SEEK_DEBOUNCE_MS = 200L

    // Some Android TTS implementations (notably Google TTS) populate their internal voice
    // catalogue asynchronously AFTER the synchronous OnInit callback fires, so the first
    // [engine.voices] read during init returns an empty set. NativeTtsEngine retries the
    // snapshot up to [TTS_VOICES_RETRY_ATTEMPTS] times spaced by [TTS_VOICES_RETRY_DELAY_MS]
    // until a non-empty list lands. Sized to cover the long-tail of slow OEM TTS engines
    // (~3s total budget) without delaying playback when the list is already populated.
    const val TTS_VOICES_RETRY_ATTEMPTS = 6
    const val TTS_VOICES_RETRY_DELAY_MS = 500L

    // Sampling interval for the periodic word-position auto-save during playback. The
    // pause()-driven save still covers manual pauses; this provides "save while playing" so
    // a process kill (swipe from recents) or backgrounded TTS-via-foreground-service close
    // never costs more than one interval's worth of position. 5 s balances DB write rate
    // against acceptable replay-overlap on resume (at 250 WPM ≈ 21 words; at TTS rates ≈ 12 words).
    const val AUTO_SAVE_POSITION_INTERVAL_MS = 5_000L

    // SharedPreferences file for TTS settings — kept separate from RSVP prefs so TTS and
    // visual-reader concerns stay independently resettable.
    const val TTS_PREFS_NAME = "tts_preferences"

    // Drop-in Neural voice folder layout. Resolved against context.getExternalFilesDir(...)
    // so no runtime permissions are needed and the folder is cleaned up on uninstall.
    const val TTS_MODEL_DIR_NAME = "tts_models"
    const val TTS_MODEL_README_FILENAME = "README.txt"
    const val TTS_NEURAL_REQUIRED_MODEL_FILE = "model.onnx"
    const val TTS_NEURAL_REQUIRED_TOKENS_FILE = "tokens.txt"
    const val TTS_NEURAL_ESPEAK_DATA_DIR = "espeak-ng-data"

    // Background TTS playback (foreground MediaSession service). The notification channel ID is
    // stable so upgrades re-use the user's channel preferences; the foreground notification ID
    // is a non-zero constant so startForeground() is idempotent across restarts of the service.
    const val TTS_NOTIFICATION_CHANNEL_ID = "speedread_tts_playback"
    const val TTS_NOTIFICATION_CHANNEL_NAME = "Listen Mode"
    const val TTS_NOTIFICATION_CHANNEL_DESC = "Shows TTS playback controls while reading aloud."
    const val TTS_FOREGROUND_NOTIFICATION_ID = 0x5EED
    const val TTS_MEDIA_SESSION_TAG = "SpeedReadTts"
    const val TTS_WAKE_LOCK_TAG = "SpeedRead::TtsWakeLock"
    // Lock-screen / BT-headset next / previous step size in words. Tuned to a roughly one-line
    // jump at typical TTS rates — users expect "skip" to move by a sentence-ish amount, not a
    // full chunk (which would overshoot the current paragraph).
    const val TTS_SKIP_STEP_WORDS = 10

    // Continuous-mode TTS follow-along snippet sizing — number of body LINES (post-wrap visual
    // lines) included on each side of the spoken word's body line. Picking by line count rather
    // than word count snaps the snippet's char range to body line boundaries, so the snippet's
    // preview TextView wraps identically to the body for those same characters and the visible
    // glyphs land pixel-for-pixel at body's natural positions. The earlier ±12-word sizing
    // landed the snippet's first word mid-body-line, causing the preview to wrap differently
    // than the body and a visible blank band to render between the snippet's last text line
    // and the body's next visible line — see Changelog 1.14.39.
    const val CONTINUOUS_TTS_PREVIEW_LINES_BEFORE = 2
    const val CONTINUOUS_TTS_PREVIEW_LINES_AFTER = 2

    // Piper voice catalog (in-app browse + install + uninstall + update). The catalog source
    // is Sherpa-ONNX's `tts-models` GitHub release because each voice ships there as a single
    // `vits-piper-<id>.tar.bz2` containing model.onnx + tokens.txt + espeak-ng-data/ + the
    // .onnx.json — exactly the layout TtsModelRegistry.scanNow() expects. Going to rhasspy/
    // piper-voices directly would force us to source tokens.txt and espeak-ng-data separately.
    // The asset-name prefix is what the catalog client filters on (every Piper asset is
    // `vits-piper-...`); changing this string would invisibly hide the entire catalog.
    const val VOICE_CATALOG_RELEASES_API_URL =
        "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models"
    const val VOICE_CATALOG_ASSET_PREFIX = "vits-piper-"
    const val VOICE_CATALOG_ASSET_SUFFIX = ".tar.bz2"

    // Disk cache for the parsed catalog so a transient offline state doesn't strand the user
    // with an empty list on relaunch. Cached under context.cacheDir; safe to evict at any time.
    // 24h TTL: the upstream release is updated rarely (months), and the catalog UI exposes a
    // pull-to-refresh path for users who need a fresh fetch sooner.
    const val VOICE_CATALOG_CACHE_FILENAME = "voice_catalog_cache.json"
    const val VOICE_CATALOG_CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    // GitHub asks for a User-Agent on API calls. Use a stable, non-PII-bearing string —
    // `SpeedRead-VoiceCatalog/<versionName>` would also leak version info we don't need to.
    const val VOICE_CATALOG_USER_AGENT = "SpeedRead-VoiceCatalog"
    const val VOICE_CATALOG_HTTP_CONNECT_TIMEOUT_MS = 15_000
    const val VOICE_CATALOG_HTTP_READ_TIMEOUT_MS = 30_000

    // Per-voice install metadata: written into the voice folder after a successful install so
    // that next-launch can detect "an update is available" by comparing remote size/timestamp
    // against the recorded install. The dot-prefix keeps it visually de-emphasized in file
    // managers and is ignored by TtsModelRegistry.parseVoiceFolder() (only model.onnx +
    // tokens.txt are required; extras are passed through).
    const val VOICE_INSTALL_METADATA_FILENAME = ".voice_metadata.json"

    // Staging directory under filesDir where DownloadManager writes the .tar.bz2 before we
    // extract it into tts_models/. Kept separate so a partial download cannot pollute the
    // scanned voice tree. Cleared after each install (success or failure).
    const val VOICE_CATALOG_DOWNLOAD_STAGING_DIR = "voice_downloads"

    // Notification channel for download progress. Distinct from the playback channel so the
    // user can mute one without losing the other. Notification title shown to the user.
    const val VOICE_DOWNLOAD_CHANNEL_ID = "speedread_voice_downloads"
    const val VOICE_DOWNLOAD_CHANNEL_NAME = "Voice downloads"
    const val VOICE_DOWNLOAD_CHANNEL_DESC =
        "Progress notifications for Piper voice downloads from the in-app catalog."

    // Sampling period for per-word notification updates. Android NotificationManager rate-limits
    // notify() to ~5 updates/sec per id and silently sheds the rest (the system log carries
    // "Shedding notify (update) ... rate limit (5.0) exceeded"). At higher TTS rates per-word
    // updates exceed that and the visible notification subtitle stalls mid-playback. 250ms = 4Hz
    // sits comfortably under the limit while keeping the notification's "{n} / {total}" word
    // counter visibly responsive. State / title transitions bypass this throttle.
    const val TTS_NOTIFICATION_THROTTLE_MS = 250L

    // === Neural TTS frame-synced word advancement ===

    // AudioTrack playback-head poll interval. 25ms = 40Hz: finer than RSVP's typical word
    // cadence (200ms at 300 WPM) so word transitions land within one tick of the actual
    // audio frame. Battery-insignificant — the audio thread already runs the audio HAL
    // pipeline at far higher cost, and the poller resume + getPlaybackHeadPosition() JNI
    // read is sub-microsecond.
    const val TTS_NEURAL_PLAYBACK_POLL_INTERVAL_MS = 25L

    // Frames per AudioTrack.write() slice for Neural playback. WRITE_BLOCKING on a
    // MODE_STREAM track returns only as the data plays out and the JNI call cannot be
    // interrupted by coroutine cancellation, so slice size directly bounds how long
    // pause/seek/stop wait for the writer to notice the interrupt flag between slices:
    // 2048 frames is roughly 93ms at the 22050Hz Piper sample rate.
    const val TTS_NEURAL_WRITE_SLICE_FRAMES = 2048

    // Defensive cap on the post-write drain wait. AudioTrack.write() in WRITE_BLOCKING
    // mode returns when samples are queued, not when they have all played; the residual
    // buffer (~46ms at 22050Hz / 4096B) drains while the poller waits for the head to
    // reach totalFrames. 500ms swallows that drain plus generous headroom for vendor
    // stalls without risking a deadlock on broken AudioTrack implementations.
    const val TTS_NEURAL_PLAYBACK_DRAIN_TIMEOUT_MS = 500L

    // Per-word weight floor for the syllable-based timing heuristic. Even one-letter "a"
    // or punctuation-only tokens get a positive weight so the cumulative-threshold math
    // stays well-defined (no zero-weight words, no division by zero on the weight sum).
    const val TTS_NEURAL_MIN_WORD_WEIGHT = 1

    // Per-word weight cap. Syllable count is theoretically unbounded
    // ("antidisestablishmentarianism" = 12) but allowing one outlier to swallow >1/3 of
    // a typical utterance produces visible hangs. 6 keeps the longest real English words
    // proportionate while still giving them ~6x the screen time of "the". Pathological
    // inputs (OCR-fused tokens) are clipped here rather than warping the rest of the
    // utterance.
    const val TTS_NEURAL_MAX_WORD_WEIGHT = 6

    // Char-length weighting fallback for non-Latin scripts (CJK, Arabic, Devanagari, etc.)
    // where the vowel-group syllable heuristic does not apply. Scale 0.5 means a 4-char
    // CJK token weighs ~2 syllable-equivalents, comparable to a 2-syllable English word.
    // MIN / MAX bounds still apply post-scaling.
    const val TTS_NEURAL_NON_LATIN_CHAR_WEIGHT_SCALE = 0.5
}