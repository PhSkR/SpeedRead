package com.speedread.rsvp.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.speedread.rsvp.R
import com.speedread.rsvp.databinding.ItemVoiceCatalogBinding
import com.speedread.rsvp.tts.catalog.VoiceCatalogEntry
import com.speedread.rsvp.tts.catalog.VoiceCatalogRow
import com.speedread.rsvp.tts.catalog.VoiceInstallStatus

/**
 * RecyclerView adapter for the Piper voice catalog. One row per [VoiceCatalogRow]; the
 * action button label and click action both follow the row's [VoiceInstallStatus].
 *
 * The button never has more than one meaning at a time — the previous status's handler is
 * overwritten on every bind so a row that flips Downloading → Installed (after extract)
 * goes from "Cancel" to "Uninstall" without a stale listener.
 */
class VoiceCatalogAdapter(
    private val callbacks: Callbacks
) : ListAdapter<VoiceCatalogRow, VoiceCatalogAdapter.ViewHolder>(DIFF) {

    interface Callbacks {
        fun onInstallClicked(entry: VoiceCatalogEntry)
        fun onCancelClicked(entry: VoiceCatalogEntry)
        fun onUninstallClicked(entry: VoiceCatalogEntry)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVoiceCatalogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), callbacks)
    }

    class ViewHolder(
        private val binding: ItemVoiceCatalogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: VoiceCatalogRow, callbacks: Callbacks) {
            val ctx = binding.root.context
            val entry = row.entry
            binding.voiceName.text = entry.displayName
            binding.voiceSubtitle.text = buildSubtitle(ctx, entry)
            binding.statusLabel.text = describeStatus(ctx, row.status)

            // Progress bar (downloading) — only visible during the download phase.
            val downloading = row.status as? VoiceInstallStatus.Downloading
            if (downloading != null) {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.progress = downloading.progressPercent
            } else {
                binding.progressBar.visibility = View.GONE
            }

            // Action button: label + click handler are derived from status. Resetting both
            // every bind avoids a stale listener firing post-recycle.
            val (buttonLabel, action) = actionFor(ctx, row.status)
            binding.actionButton.text = buttonLabel
            binding.actionButton.isEnabled = action != null
            binding.actionButton.setOnClickListener {
                when (action) {
                    Action.INSTALL -> callbacks.onInstallClicked(entry)
                    Action.CANCEL -> callbacks.onCancelClicked(entry)
                    Action.UNINSTALL -> callbacks.onUninstallClicked(entry)
                    Action.RETRY -> callbacks.onInstallClicked(entry)
                    null -> Unit
                }
            }
        }

        private fun buildSubtitle(ctx: Context, entry: VoiceCatalogEntry): String {
            val sizeMb = entry.sizeBytes.toDouble() / 1_000_000.0
            val sizeText = ctx.getString(R.string.voice_catalog_size_mb, sizeMb)
            val parts = listOfNotNull(
                entry.languageCode.takeIf { it.isNotBlank() },
                entry.quality.takeIf { it.isNotBlank() },
                sizeText
            )
            return parts.joinToString(" · ")
        }

        private fun describeStatus(ctx: Context, status: VoiceInstallStatus): String = when (status) {
            VoiceInstallStatus.NotInstalled -> ctx.getString(R.string.voice_catalog_status_not_installed)
            VoiceInstallStatus.Installed -> ctx.getString(R.string.voice_catalog_status_installed)
            VoiceInstallStatus.UpdateAvailable -> ctx.getString(R.string.voice_catalog_status_update_available)
            is VoiceInstallStatus.Downloading -> ctx.getString(
                R.string.voice_catalog_status_downloading, status.progressPercent
            )
            VoiceInstallStatus.Extracting -> ctx.getString(R.string.voice_catalog_status_extracting)
            is VoiceInstallStatus.Failed -> "${ctx.getString(R.string.voice_catalog_status_failed)}: ${status.message}"
        }

        private fun actionFor(ctx: Context, status: VoiceInstallStatus): Pair<String, Action?> = when (status) {
            VoiceInstallStatus.NotInstalled ->
                ctx.getString(R.string.voice_catalog_action_install) to Action.INSTALL
            is VoiceInstallStatus.Downloading ->
                ctx.getString(R.string.voice_catalog_action_cancel) to Action.CANCEL
            VoiceInstallStatus.Extracting ->
                ctx.getString(R.string.voice_catalog_status_extracting) to null
            VoiceInstallStatus.Installed ->
                ctx.getString(R.string.voice_catalog_action_uninstall) to Action.UNINSTALL
            VoiceInstallStatus.UpdateAvailable ->
                ctx.getString(R.string.voice_catalog_action_update) to Action.INSTALL
            is VoiceInstallStatus.Failed ->
                ctx.getString(R.string.voice_catalog_action_retry) to Action.RETRY
        }

        private enum class Action { INSTALL, CANCEL, UNINSTALL, RETRY }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VoiceCatalogRow>() {
            override fun areItemsTheSame(old: VoiceCatalogRow, new: VoiceCatalogRow): Boolean =
                old.entry.assetName == new.entry.assetName

            override fun areContentsTheSame(old: VoiceCatalogRow, new: VoiceCatalogRow): Boolean =
                old == new
        }
    }
}
