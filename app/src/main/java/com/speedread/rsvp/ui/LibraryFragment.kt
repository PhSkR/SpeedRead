package com.speedread.rsvp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.speedread.rsvp.DocumentsManager
import com.speedread.rsvp.FolderFileAdapter
import com.speedread.rsvp.LibraryDocumentAdapter
import com.speedread.rsvp.LibraryViewModel
import com.speedread.rsvp.ReadingViewModel
import com.speedread.rsvp.ThemeManager
import com.speedread.rsvp.PageViewActivity
import com.speedread.rsvp.R
import com.speedread.rsvp.data.bookmark.BookmarkSource
import com.speedread.rsvp.data.document.SavedDocument
import com.speedread.rsvp.databinding.FragmentLibraryBinding
import com.speedread.rsvp.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private val libraryViewModel: LibraryViewModel by activityViewModels()
    private val readingViewModel: ReadingViewModel by activityViewModels() // Inject ReadingViewModel to load documents

    @Inject
    lateinit var documentsManager: DocumentsManager

    @Inject
    lateinit var themeManager: ThemeManager

    private lateinit var libraryDocumentAdapter: LibraryDocumentAdapter
    private lateinit var folderFileAdapter: FolderFileAdapter

    private var shouldShowSaveDialogOnImport = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { 
            shouldShowSaveDialogOnImport = true
            // Pass the original filename to the import method
            // This part needs to be handled by a TextImportController or similar
            // For now, directly call readingViewModel.importFromFile
            readingViewModel.importFromFile(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLibraryTab()
    }

    private fun setupLibraryTab() {
        // Setup library document adapter
        libraryDocumentAdapter = LibraryDocumentAdapter(
            onDocumentClick = { document ->
                if (themeManager.isPageViewOnlyMode()) {
                    openPageViewWithDocument(document)
                } else {
                    confirmAndLoadDocument(document)
                }
            },
            onDocumentLongClick = { document ->
                showDocumentActionsDialog(document)
            }
        )

        // Setup folder file adapter
        folderFileAdapter = FolderFileAdapter { folderFile ->
            val uri = documentsManager.getFileUri(folderFile.file)
            uri?.let {
                shouldShowSaveDialogOnImport = true
                // Pass the original filename to the import method
                readingViewModel.importFromFileWithOriginalName(it, folderFile.name)
                navigateToReadingTab()
            }
        }

        // Setup RecyclerViews
        with(binding) {
            documentsRecyclerView.adapter = libraryDocumentAdapter
            documentsRecyclerView.layoutManager = LinearLayoutManager(requireContext())

            folderFilesRecyclerView.adapter = folderFileAdapter
            folderFilesRecyclerView.layoutManager = LinearLayoutManager(requireContext())

            // Setup button click listeners
            btnOpenDocumentsFolder.setOnClickListener { openDocumentsFolder() }
            btnRefreshLibrary.setOnClickListener { refreshLibrary() }
            btnManageDocuments.setOnClickListener { showDocumentManagementDialog() }

            // Setup search functionality
            searchEditText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    libraryViewModel.updateSearchQuery(s?.toString() ?: "")
                }
            })
        }

        // Initial load
        refreshLibrary()

        // Observe saved documents. Must be scoped to viewLifecycleOwner and wrapped in
        // repeatOnLifecycle so the collect stops at onDestroyView — otherwise Room's Flow
        // re-emits after we've navigated to the Reading tab (markDocumentAsAccessed runs
        // at the tail of loadSavedDocumentInternal, updating the row) and the collector
        // hits `binding.emptyLibraryText` with `_binding == null`, crashing the app.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                libraryViewModel.allSavedDocuments.collect {
                    libraryDocumentAdapter.submitList(it)
                    binding.emptyLibraryText.visibility = if (it.isEmpty())
                        View.VISIBLE else View.GONE
                }
            }
        }

        // Create guide file in documents folder and display folder path
        viewLifecycleOwner.lifecycleScope.launch {
            documentsManager.createGuideFile()
            // Display the documents folder path
            val documentsFolder = documentsManager.getDocumentsFolder()
            // View may have been destroyed during the suspend above (tab switch).
            if (_binding == null) return@launch
            binding.documentsFolderPath.text = documentsFolder.absolutePath
        }
    }

    private fun openDocumentsFolder() {
        // Use the proper file picker launcher that handles import
        filePickerLauncher.launch("*/*")
        Toast.makeText(requireContext(), "Select files to import into RVSPReader", Toast.LENGTH_LONG).show()
    }

    private fun refreshLibrary() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val folderFiles = documentsManager.scanDocumentsFolder()
                if (_binding == null) return@launch
                folderFileAdapter.submitList(folderFiles)
                binding.emptyFolderText.visibility = if (folderFiles.isEmpty())
                    View.VISIBLE else View.GONE

                // Provide user feedback when refresh is manually triggered
                // This logic needs to be adapted as there's no tabLayout.selectedTabPosition anymore
                // For now, always show toast on manual refresh
                val message = if (folderFiles.isNotEmpty()) {
                    "Library refreshed - Found ${folderFiles.size} file(s)"
                } else {
                    "Library refreshed - No documents found"
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Logger.e("LibraryFragment", "Library refresh failed", e)
                if (_binding == null) return@launch
                folderFileAdapter.submitList(emptyList())
                binding.emptyFolderText.visibility = View.VISIBLE
                Toast.makeText(requireContext(), "Error refreshing library", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDocumentActionsDialog(document: SavedDocument) {
        val pageViewOnly = themeManager.isPageViewOnlyMode()
        val favoriteLabel = if (document.isFavorite) getString(R.string.unfavorite) else getString(R.string.favorite)

        val reloadLabel = getString(R.string.reload_document)
        val actions = buildList {
            if (!pageViewOnly) add(getString(R.string.load_document))
            add(getString(R.string.switch_to_page_view))
            add(favoriteLabel)
            add(reloadLabel)
            add(getString(R.string.delete_document))
        }.toTypedArray()

        val sizeKB = document.fileSize / 1024.0

        val infoText = "${getString(R.string.word_count_format, document.wordCount)}\n" +
                "${getString(R.string.file_size_format, sizeKB)}\n\n" +
                "Preview:\n${document.content.take(200)}"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(document.title)
            .setMessage(infoText)
            .setItems(actions) { _, which ->
                val action = actions[which]
                when (action) {
                    getString(R.string.load_document) -> confirmAndLoadDocument(document)
                    getString(R.string.switch_to_page_view) -> openPageViewWithDocument(document)
                    favoriteLabel -> libraryViewModel.toggleDocumentFavorite(document)
                    reloadLabel -> {
                        libraryViewModel.invalidateDocumentCache(document)
                        Toast.makeText(requireContext(), "Cache cleared — will re-process on next open", Toast.LENGTH_SHORT).show()
                    }
                    getString(R.string.delete_document) -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Delete Document")
                            .setMessage("Are you sure you want to delete this document?")
                            .setPositiveButton("Delete") { _, _ ->
                                libraryViewModel.deleteSavedDocument(document)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmAndLoadDocument(document: SavedDocument) {
        // Use cached wordCount instead of regex-splitting the full content on the main
        // thread — populated at save time on the IO dispatcher.
        val totalWords = document.wordCount
        val message = if (document.lastReadPosition > 0 && totalWords > 0) {
            val percent = ((document.lastReadPosition.toLong() * 100) / totalWords).toInt()
                .coerceIn(0, 100)
            getString(
                R.string.confirm_load_message_with_progress,
                document.title,
                percent,
                document.lastReadPosition,
                totalWords
            )
        } else {
            getString(R.string.confirm_load_message, document.title)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm_load_title)
            .setMessage(message)
            .setPositiveButton(R.string.load) { _, _ ->
                readingViewModel.loadSavedDocument(document)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.document_loaded_toast, document.title),
                    Toast.LENGTH_SHORT
                ).show()
                navigateToReadingTab()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun navigateToReadingTab() {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.readingFragment) {
            navController.navigate(R.id.readingFragment)
        }
    }

    private fun openPageViewWithDocument(document: SavedDocument) {
        val intent = PageViewActivity.createIntent(
            context = requireContext(),
            documentId = document.id,
            currentPosition = 0 // Start from beginning for saved documents
        )

        // Start PageViewActivity and listen for result
        // pageViewLauncher.launch(intent) // This launcher is in ReadingFragment, need to pass result back
        startActivity(intent)
    }

    private fun showDocumentManagementDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val documents = libraryViewModel.getAllSavedDocuments()
            if (documents.isEmpty()) {
                Toast.makeText(requireContext(), "No saved documents to manage", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val documentTitles = documents.map { it.title }.toTypedArray()
            val checkedItems = BooleanArray(documents.size) { false }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Manage Documents")
                .setMultiChoiceItems(documentTitles, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton("Select All") { _, _ ->
                    showDocumentManagementDialogWithAllSelected()
                }
                .setNegativeButton("Delete Selected") { _, _ ->
                    val selectedDocuments = documents.filterIndexed { index, _ -> checkedItems[index] }
                    if (selectedDocuments.isNotEmpty()) {
                        showDeleteDocumentsConfirmationDialog(selectedDocuments)
                    } else {
                        Toast.makeText(requireContext(), "No documents selected", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton("Cancel", null)
                .show()
        }
    }

    private fun showDocumentManagementDialogWithAllSelected() {
        viewLifecycleOwner.lifecycleScope.launch {
            val documents = libraryViewModel.getAllSavedDocuments()
            val documentTitles = documents.map { it.title }.toTypedArray()
            val checkedItems = BooleanArray(documents.size) { true }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Manage Documents")
                .setMultiChoiceItems(documentTitles, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton("Delete Selected") { _, _ ->
                    val selectedDocuments = documents.filterIndexed { index, _ -> checkedItems[index] }
                    if (selectedDocuments.isNotEmpty()) {
                        showDeleteDocumentsConfirmationDialog(selectedDocuments)
                    } else {
                        Toast.makeText(requireContext(), "No documents selected", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showDeleteDocumentsConfirmationDialog(documents: List<SavedDocument>) {
        val message = if (documents.size == 1) {
            "Delete \"${documents[0].title}\"?"
        } else {
            "Delete ${documents.size} selected documents?"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Deletion")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                libraryViewModel.deleteDocuments(documents)
                Toast.makeText(requireContext(), 
                    if (documents.size == 1) "Document deleted" else "${documents.size} documents deleted", 
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}