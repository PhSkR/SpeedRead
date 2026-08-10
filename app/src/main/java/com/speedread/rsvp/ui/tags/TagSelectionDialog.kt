package com.speedread.rsvp.ui.tags

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.speedread.rsvp.R
import com.speedread.rsvp.data.tags.Tag
import com.speedread.rsvp.data.tags.TagRepository
import com.speedread.rsvp.databinding.DialogTagSelectionBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TagSelectionDialog : DialogFragment() {
    
    @Inject
    lateinit var tagRepository: TagRepository
    
    private var _binding: DialogTagSelectionBinding? = null
    private val binding get() = _binding!!
    
    private var documentId: Long = 0
    private var onTagsUpdated: (() -> Unit)? = null
    
    private lateinit var availableTagsAdapter: AvailableTagsAdapter
    private lateinit var selectedTagsAdapter: SelectedTagsAdapter
    
    private var selectedTags = mutableSetOf<Tag>()
    
    companion object {
        fun newInstance(documentId: Long, onTagsUpdated: () -> Unit): TagSelectionDialog {
            val dialog = TagSelectionDialog()
            dialog.documentId = documentId
            dialog.onTagsUpdated = onTagsUpdated
            return dialog
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogTagSelectionBinding.inflate(layoutInflater)
        
        setupRecyclerViews()
        setupClickListeners()
        loadData()
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Manage Tags")
            .setView(binding.root)
            .setPositiveButton("Save") { _, _ ->
                saveTagChanges()
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
    
    private fun setupRecyclerViews() {
        availableTagsAdapter = AvailableTagsAdapter { tag ->
            if (selectedTags.add(tag)) {
                selectedTagsAdapter.addTag(tag)
                availableTagsAdapter.updateTagSelection(tag, true)
            }
        }
        
        selectedTagsAdapter = SelectedTagsAdapter { tag ->
            if (selectedTags.remove(tag)) {
                availableTagsAdapter.updateTagSelection(tag, false)
            }
        }
        
        binding.availableTagsRecyclerView.apply {
            layoutManager = FlexboxLayoutManager(requireContext())
            adapter = availableTagsAdapter
        }
        
        binding.selectedTagsRecyclerView.apply {
            layoutManager = FlexboxLayoutManager(requireContext())
            adapter = selectedTagsAdapter
        }
    }
    
    private fun setupClickListeners() {
        binding.createTagButton.setOnClickListener {
            showCreateTagDialog()
        }
        
        binding.tagSearchInput.setOnEditorActionListener { textView, _, _ ->
            val tagName = textView.text.toString().trim()
            if (tagName.isNotBlank()) {
                createAndAddTag(tagName)
                textView.text = ""
            }
            true
        }
    }
    
    private fun loadData() {
        lifecycleScope.launch {
            // Load existing tags for this document
            val existingTags = tagRepository.getTagsForDocument(documentId).first()
            selectedTags.addAll(existingTags)
            selectedTagsAdapter.setTags(existingTags)
            
            // Load all available tags
            val allTags = tagRepository.getAllTags().first()
            availableTagsAdapter.setTags(allTags, selectedTags)
        }
    }
    
    private fun showCreateTagDialog() {
        val input = EditText(requireContext())
        input.hint = "Tag name"
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Create New Tag")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val tagName = input.text.toString().trim()
                if (tagName.isNotBlank()) {
                    createAndAddTag(tagName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun createAndAddTag(tagName: String) {
        lifecycleScope.launch {
            val tag = tagRepository.createTag(tagName)
            if (tag != null && selectedTags.add(tag)) {
                selectedTagsAdapter.addTag(tag)
                availableTagsAdapter.addNewTag(tag, true)
            }
        }
    }
    
    private fun saveTagChanges() {
        lifecycleScope.launch {
            // Remove all existing tags first
            tagRepository.removeAllTagsFromDocument(documentId)
            
            // Add all selected tags
            for (tag in selectedTags) {
                tagRepository.addTagToDocument(documentId, tag.name)
            }
            
            onTagsUpdated?.invoke()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}