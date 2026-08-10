package com.speedread.rsvp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.speedread.rsvp.ui.ReadingFragment
import com.google.android.material.snackbar.Snackbar
import com.speedread.rsvp.data.bookmark.SchemaResetNotifier
import com.speedread.rsvp.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject lateinit var schemaResetNotifier: SchemaResetNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Hide the status bar; swipe from the top edge reveals it transiently (the bar
        // overlays content, no layout resize). Nav bar stays visible because the bottom
        // navigation sits on top of it in portrait and needs the safe-area gesture inset.
        // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE is sticky: the bar auto-hides again after
        // a short delay, so the user doesn't have to manually dismiss it.
        val insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNavigation.setupWithNavController(navController)

        maybeShowSchemaResetNotice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val current = navHostFragment?.childFragmentManager?.primaryNavigationFragment
        if (current is ReadingFragment) {
            current.handleNewIntent(intent)
        }
    }

    private fun maybeShowSchemaResetNotice() {
        if (!schemaResetNotifier.consumeResetNotice()) return
        Snackbar
            .make(binding.root, R.string.schema_reset_notice, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.schema_reset_dismiss) { /* dismiss */ }
            .show()
    }
}