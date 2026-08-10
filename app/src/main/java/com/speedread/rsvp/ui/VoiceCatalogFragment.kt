package com.speedread.rsvp.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.speedread.rsvp.R
import com.speedread.rsvp.VoiceCatalogViewModel
import com.speedread.rsvp.databinding.FragmentVoiceCatalogBinding
import com.speedread.rsvp.tts.catalog.CatalogState
import com.speedread.rsvp.tts.catalog.VoiceCatalogEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VoiceCatalogFragment : Fragment() {

    private var _binding: FragmentVoiceCatalogBinding? = null
    private val binding get() = _binding!!

    private val viewModel: VoiceCatalogViewModel by viewModels()
    private lateinit var adapter: VoiceCatalogAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVoiceCatalogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        adapter = VoiceCatalogAdapter(object : VoiceCatalogAdapter.Callbacks {
            override fun onInstallClicked(entry: VoiceCatalogEntry) {
                viewModel.install(entry)
            }

            override fun onCancelClicked(entry: VoiceCatalogEntry) {
                viewModel.cancel(entry)
            }

            override fun onUninstallClicked(entry: VoiceCatalogEntry) {
                showUninstallConfirm(entry)
            }
        })

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh(forceRefresh = true)
        }

        binding.retryButton.setOnClickListener {
            viewModel.refresh(forceRefresh = true)
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
        })

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.catalogState.collect { state ->
                renderCatalogState(state)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.rows.collect { rows ->
                adapter.submitList(rows)
                renderEmptyState(rows.isEmpty())
            }
        }
    }

    private fun renderCatalogState(state: CatalogState) {
        when (state) {
            CatalogState.Loading -> {
                // Only show the centered spinner if we have nothing to display yet — once
                // we have a populated list a refresh uses the SwipeRefresh spinner instead.
                val haveRows = adapter.itemCount > 0
                binding.loadingIndicator.visibility = if (haveRows) View.GONE else View.VISIBLE
                binding.errorContainer.visibility = View.GONE
                if (haveRows) binding.swipeRefresh.isRefreshing = true
            }
            is CatalogState.Loaded -> {
                binding.loadingIndicator.visibility = View.GONE
                binding.errorContainer.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
            is CatalogState.Error -> {
                binding.loadingIndicator.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                // Suppress the inline error if we already have rows from cache — keep showing
                // them and only surface the failure if there's nothing to fall back to.
                if (adapter.itemCount == 0) {
                    binding.errorContainer.visibility = View.VISIBLE
                    binding.errorMessage.text =
                        getString(R.string.voice_catalog_error, state.message)
                } else {
                    binding.errorContainer.visibility = View.GONE
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.voice_catalog_error, state.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun renderEmptyState(isEmpty: Boolean) {
        val showEmpty = isEmpty &&
            viewModel.catalogState.value is CatalogState.Loaded &&
            binding.errorContainer.visibility != View.VISIBLE &&
            binding.loadingIndicator.visibility != View.VISIBLE
        binding.emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (showEmpty) View.GONE else View.VISIBLE
    }

    private fun showUninstallConfirm(entry: VoiceCatalogEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_catalog_uninstall_confirm_title)
            .setMessage(getString(R.string.voice_catalog_uninstall_confirm_message, entry.displayName))
            .setPositiveButton(R.string.voice_catalog_action_uninstall) { _, _ ->
                viewModel.uninstall(entry)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.voice_catalog_uninstalled_toast, entry.displayName),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
