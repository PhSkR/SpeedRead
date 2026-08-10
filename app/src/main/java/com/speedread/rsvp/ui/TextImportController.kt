package com.speedread.rsvp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.speedread.rsvp.Constants
import com.speedread.rsvp.ReadingViewModel
import com.speedread.rsvp.R
import com.speedread.rsvp.databinding.FragmentReadingBinding
import com.speedread.rsvp.data.parser.FileMetadata
import kotlinx.coroutines.launch
import java.util.Locale

class TextImportController(
    private val context: Context,
    private val binding: FragmentReadingBinding,
    private val viewModel: ReadingViewModel,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onFilePickerRequest: () -> Unit  // Callback to trigger file picker in MainActivity
) {
    
    fun showTextImportDialog() {
        val options = arrayOf(
            context.getString(R.string.paste_from_clipboard),
            context.getString(R.string.enter_text_manually),
            context.getString(R.string.import_from_file)
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.import_text))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.importFromClipboard()
                    1 -> showTextInputDialog()
                    2 -> onFilePickerRequest()
                }
            }
            .show()
    }
    
    private fun showTextInputDialog() {
        val editText = EditText(context).apply {
            hint = context.getString(R.string.enter_text_hint)
            minLines = 3
        }
        
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.enter_text))
            .setView(editText)
            .setPositiveButton(context.getString(R.string.load)) { _, _ ->
                val text = editText.text.toString()
                if (text.isNotBlank()) {
                    viewModel.loadText(text, com.speedread.rsvp.data.bookmark.BookmarkSource.MANUAL_TEXT)
                }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }
    
    fun triggerFilePicker(launcher: () -> Unit) {
        // Call the launcher function provided by MainActivity
        launcher()
    }
    
    fun handleFilePickerResult(uri: Uri?) {
        uri?.let { viewModel.importFromFile(it) }
    }
    
    fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                text?.let { routeSharedText(it) }
            }
            Intent.ACTION_SEND -> {
                // SEND can carry either a file URI (EXTRA_STREAM, used for PDF / EPUB / TXT
                // shares from cloud drives, file managers, browser downloads) or raw text
                // (EXTRA_TEXT, used by the system "Share text" sheet). EXTRA_STREAM takes
                // priority — a SEND intent with both fields is conventionally a file share
                // where EXTRA_TEXT is just a human-readable description. The mime-type guard
                // matches the SEND intent filters declared in AndroidManifest.xml so an
                // unexpected type can't slip past and hit a parser that doesn't support it.
                val streamUri = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )
                val type = intent.type
                if (streamUri != null && type != null &&
                    Constants.SUPPORTED_SHARE_MIME_TYPES.contains(type)
                ) {
                    viewModel.importFromFile(streamUri)
                } else if (type == Constants.MIME_TYPE_TEXT_PLAIN) {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    sharedText?.let { routeSharedText(it) }
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        viewModel.importFromUrl(uri.toString())
                    } else {
                        viewModel.importFromFile(uri)
                    }
                }
            }
        }
    }

    /**
     * When a browser shares a link via "Share -> SpeedRead", the payload is the bare URL as
     * text/plain. Speed-reading the URL string itself is never what the user wants, so a
     * lone http(s) URL routes to the URL importer. Mixed text (e.g. "Check this out: <url>")
     * stays on the existing loadText path — trying to split intent from prose is unreliable.
     */
    private fun routeSharedText(text: String) {
        val trimmed = text.trim()
        if (trimmed.matches(Regex("^https?://\\S+$"))) {
            viewModel.importFromUrl(trimmed)
        } else {
            viewModel.loadText(text, com.speedread.rsvp.data.bookmark.BookmarkSource.SHARED_TEXT)
        }
    }
    
    fun observeFileImportResult() {
        lifecycleScope.launch {
            viewModel.fileImportResult.collect { result ->
                result?.let {
                    when (it) {
                        is UiState.Loading -> {
                            showImportStatus(context.getString(R.string.importing_document))
                        }
                        is UiState.Success<FileMetadata> -> {
                            val textSizeBytes = viewModel.getCurrentTextSizeBytes()
                            val sizeKB = textSizeBytes / 1024.0
                            val sizeDisplay = when {
                                sizeKB < 1.0 -> "${textSizeBytes}B"
                                sizeKB < 1024.0 -> "${String.format(Locale.US, "%.1f", sizeKB)}KB"
                                else -> "${String.format(Locale.US, "%.1f", sizeKB / 1024.0)}MB"
                            }

                            val statusMessage = when (it.data.mimeType) {
                                "application/pdf" -> context.getString(R.string.import_complete) + " (PDF, $sizeDisplay)"
                                "application/epub+zip" -> context.getString(R.string.import_complete) + " (EPUB, $sizeDisplay)"
                                "text/plain" -> context.getString(R.string.import_complete) + " (Text, $sizeDisplay)"
                                else -> context.getString(R.string.import_complete) + " ($sizeDisplay)"
                            }
                            showImportStatus(statusMessage)

                            binding.importStatusCard.postDelayed({
                                hideImportStatus()
                                viewModel.clearFileImportResult()
                            }, 1500)
                        }
                        is UiState.Error -> {
                            showImportStatus(context.getString(R.string.import_failed) + ": ${it.message}", isError = true)
                            binding.importStatusCard.postDelayed({
                                hideImportStatus()
                                viewModel.clearFileImportResult()
                            }, 3000)
                        }
                    }
                }
            }
        }
    }
    
    private fun showImportStatus(message: String, isError: Boolean = false, isProgress: Boolean = false, progress: Int = 0) {
        binding.importStatusText.text = message
        binding.importStatusCard.visibility = android.view.View.VISIBLE
        
        // Set appropriate progress bar style
        if (isError) {
            binding.importProgressBar.isIndeterminate = false
            binding.importProgressBar.progress = 100
            binding.importProgressBar.setIndicatorColor(ContextCompat.getColor(context, android.R.color.holo_red_light))
        } else if (isProgress) {
            binding.importProgressBar.isIndeterminate = false
            binding.importProgressBar.progress = progress
            // Get primary color from theme
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
            binding.importProgressBar.setIndicatorColor(typedValue.data)
        } else {
            binding.importProgressBar.isIndeterminate = true
            // Get primary color from theme
            val typedValue = android.util.TypedValue()
            context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
            binding.importProgressBar.setIndicatorColor(typedValue.data)
        }
    }
    
    private fun hideImportStatus() {
        binding.importStatusCard.visibility = android.view.View.GONE
    }
}