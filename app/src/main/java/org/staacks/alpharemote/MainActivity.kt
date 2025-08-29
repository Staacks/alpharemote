package org.staacks.alpharemote

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.staacks.alpharemote.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        const val NAVIGATE_TO_INTENT_EXTRA = "nav_to"
        const val TAG: String = "alpharemote"
        const val SELECTED_PAGE = "selected_page"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)

        navView.setupWithNavController(navController)

        var startPage = intent?.getIntExtra(NAVIGATE_TO_INTENT_EXTRA, R.id.navigation_camera) ?: R.id.navigation_camera
        startPage = savedInstanceState?.getInt(SELECTED_PAGE, startPage) ?: startPage
        navigateTo(startPage)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        navigateTo(intent.getIntExtra(NAVIGATE_TO_INTENT_EXTRA, R.id.navigation_camera))
    }

    fun navigateTo(id: Int) {
        binding.navView.selectedItemId = id
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SELECTED_PAGE, binding.navView.selectedItemId)
    }
}