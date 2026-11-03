package com.focuskiosk.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focuskiosk.R
import com.focuskiosk.databinding.ActivityAppPickerBinding
import com.focuskiosk.storage.SecureStorage
import com.focuskiosk.ui.ios.Ios
import com.focuskiosk.ui.ios.IosActionSheet
import com.focuskiosk.ui.ios.IosAlert
import com.focuskiosk.ui.ios.IosHud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Allowed Apps" — pushed from the main screen like a UINavigationController page.
 * Selection is saved to SecureStorage when leaving, and used when the lock starts.
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppPickerBinding
    private lateinit var adapter: AppSelectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.pickerRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, 0)
            b.rvApps.setPadding(0, 0, 0, bars.bottom + Ios.dp(this, 24f))
            insets
        }

        b.searchField.background = Ios.rounded(Ios.color(this, R.color.ios_fill), Ios.dpf(this, 10f))
        adapter = AppSelectAdapter { count -> b.tvPickerTitle.text = if (count == 0) "Allowed Apps" else "Allowed Apps ($count)" }
        b.rvApps.layoutManager = LinearLayoutManager(this)
        b.rvApps.adapter = adapter
        b.rvApps.itemAnimator = null
        b.rvApps.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                b.pickerHairline.alpha = if (recyclerView.canScrollVertically(-1)) 1f else 0f
            }
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })

        b.btnBack.setOnClickListener { close() }
        b.btnPresets.setOnClickListener { showPresets() }
        setupSearch()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.etSearch.hasFocus() || !b.etSearch.text.isNullOrEmpty()) endSearch() else close()
            }
        })

        val saved = SecureStorage.getPackageSet(this, SecureStorage.KEY_WHITELIST_PACKAGES) - packageName
        InstalledApps.cached()?.let { adapter.submitList(it) }
        adapter.setSelectedPackages(saved, regroup = true)
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { InstalledApps.load(this@AppPickerActivity, refresh = true) }
            adapter.submitList(apps)
        }
    }

    override fun onPause() {
        super.onPause()
        SecureStorage.putPackageSet(this, SecureStorage.KEY_WHITELIST_PACKAGES, adapter.getSelectedPackages())
    }

    private fun setupSearch() {
        b.etSearch.setOnFocusChangeListener { _, focused ->
            if (focused) b.btnCancelSearch.visibility = View.VISIBLE
        }
        b.etSearch.doAfterTextChanged {
            val q = it?.toString().orEmpty()
            b.btnClearSearch.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            adapter.filter(q)
        }
        b.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
        b.btnClearSearch.setOnClickListener { b.etSearch.text?.clear() }
        b.btnCancelSearch.setOnClickListener { endSearch() }
    }

    private fun endSearch() {
        b.etSearch.text?.clear()
        b.etSearch.clearFocus()
        b.btnCancelSearch.visibility = View.GONE
        hideKeyboard()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(b.etSearch.windowToken, 0)
    }

    private fun showPresets() {
        val presets = AppPresetManager.getPresets(this)
        val sheet = IosActionSheet(this).title("App Presets")
        sheet.option("Save Current Selection…") { savePreset() }
        presets.keys.sorted().forEach { name ->
            sheet.option("Use “$name”") {
                adapter.setSelectedPackages(presets[name].orEmpty(), regroup = true)
                IosHud.show(this, "Loaded")
            }
        }
        if (presets.isNotEmpty()) sheet.option("Delete a Preset…", destructive = true) { deletePreset() }
        sheet.show()
    }

    private fun savePreset() {
        val count = adapter.getSelectedPackages().size
        if (count == 0) {
            IosAlert(this).title("Nothing Selected").message("Choose at least one app first.")
                .action("OK", IosAlert.Style.PREFERRED).show()
            return
        }
        val alert = IosAlert(this).title("Save Preset").message("Save these $count apps so you can reuse them later.")
        val name = alert.field("Preset name")
        alert.action("Cancel", IosAlert.Style.CANCEL)
            .action("Save", IosAlert.Style.PREFERRED) {
                if (name.value.isNotEmpty()) {
                    AppPresetManager.savePreset(this, name.value, adapter.getSelectedPackages())
                    IosHud.show(this, "Saved")
                }
            }.show()
    }

    private fun deletePreset() {
        val sheet = IosActionSheet(this).title("Delete Preset")
        AppPresetManager.getPresets(this).keys.sorted().forEach { name ->
            sheet.option(name, destructive = true) { AppPresetManager.deletePreset(this, name) }
        }
        sheet.show()
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.ios_pop_in, R.anim.ios_pop_out)
    }
}
