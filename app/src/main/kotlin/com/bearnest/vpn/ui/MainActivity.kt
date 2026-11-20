package com.bearnest.vpn.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bearnest.vpn.R
import com.bearnest.vpn.databinding.ActivityMainBinding
import com.bearnest.vpn.ui.fragments.HomeFragment
import com.bearnest.vpn.ui.fragments.LogFragment
import com.bearnest.vpn.ui.fragments.ServerListFragment
import com.bearnest.vpn.ui.fragments.SettingsFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    val vm: MainViewModel by viewModels()
    lateinit var vpnPermLauncher: ActivityResultLauncher<Intent>

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* нет дополнительных действий */ }

    // ── Локаль — синхронное чтение из SharedPreferences ──────────────────────
    override fun attachBaseContext(newBase: Context) {
        val prefs  = newBase.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lang   = prefs.getString(KEY_LANG, "ru") ?: "ru"
        val locale = java.util.Locale(lang)
        val config = newBase.resources.configuration
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Применяем тему ДО setContentView — все View подхватят нужные цвета
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setTheme(themeResId(prefs.getString(KEY_THEME, "catppuccin") ?: "catppuccin"))

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vpnPermLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) vm.connect(vpnPermLauncher)
            else Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setupBottomNav()
        if (savedInstanceState == null) {
            showTab(TAB_HOME)
            handleTileIntent(intent)
        }

        // Snackbar для ошибок из ViewModel
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.error.collect { error ->
                    if (!error.isNullOrEmpty()) {
                        Snackbar.make(binding.coordinatorRoot, error, Snackbar.LENGTH_SHORT).show()
                        vm.clearError()
                    }
                }
            }
        }
    }

    // ── [QuickTiles] Открытие из плитки в шторке ─────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTileIntent(intent)
    }

    /**
     * Плитка не может сама показать системный диалог разрешения VPN —
     * она открывает приложение с EXTRA_REQUEST_VPN. Показываем диалог здесь;
     * после «ОК» существующий vpnPermLauncher сам вызовет vm.connect().
     */
    private fun handleTileIntent(intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_REQUEST_VPN, false)) return
        intent.removeExtra(EXTRA_REQUEST_VPN)   // не повторять при повороте экрана
        val permIntent = VpnService.prepare(this)
        if (permIntent != null) vpnPermLauncher.launch(permIntent)
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val tag = when (item.itemId) {
                R.id.nav_home     -> TAB_HOME
                R.id.nav_servers  -> TAB_SERVERS
                R.id.nav_settings -> TAB_SETTINGS
                R.id.nav_logs     -> TAB_LOGS
                else              -> return@setOnItemSelectedListener false
            }
            showFragmentByTag(tag)
            true
        }
    }

    /**
     * Переключение вкладки.
     * Фрагменты создаются один раз и потом просто show/hide — без пересоздания.
     * Это исключает любые лаги при переключении.
     */
    fun showTab(tag: String) {
        val navItemId = when (tag) {
            TAB_HOME     -> R.id.nav_home
            TAB_SERVERS  -> R.id.nav_servers
            TAB_SETTINGS -> R.id.nav_settings
            TAB_LOGS     -> R.id.nav_logs
            else         -> R.id.nav_home
        }
        // setOnItemSelectedListener не вызывается при programmatic select без listener
        binding.bottomNav.selectedItemId = navItemId
        showFragmentByTag(tag)
    }

    private fun showFragmentByTag(tag: String) {

        // Сбрасываем back stack (SplitTunnelFragment и любые другие sub-экраны).
        // Иначе при переключении таба они висят в стеке и ломают рендер.
        supportFragmentManager.popBackStackImmediate(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )

        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        // Скрываем все существующие фрагменты
        fm.fragments.forEach { tx.hide(it) }

        val existing = fm.findFragmentByTag(tag)
        if (existing != null) {
            tx.show(existing)
        } else {
            tx.add(R.id.fragment_container, createFragment(tag), tag)
        }
        tx.commitNow() // commitNow — без следующего кадра, мгновенно
    }

    private fun createFragment(tag: String): Fragment = when (tag) {
        TAB_SERVERS  -> ServerListFragment()
        TAB_SETTINGS -> SettingsFragment()
        TAB_LOGS     -> LogFragment()
        else         -> HomeFragment()
    }

    // ── Тема ─────────────────────────────────────────────────────────────────

    fun themeResId(name: String): Int = when (name) {
        "midnight" -> R.style.Theme_BearNest_Midnight
        "forest"   -> R.style.Theme_BearNest_Forest
        "obsidian" -> R.style.Theme_BearNest_Obsidian
        "snow"     -> R.style.Theme_BearNest_Snow
        "ocean"    -> R.style.Theme_BearNest_Ocean
        "sunset"   -> R.style.Theme_BearNest_Sunset
        "sakura"   -> R.style.Theme_BearNest_Sakura
        "mint"     -> R.style.Theme_BearNest_Mint
        "amethyst" -> R.style.Theme_BearNest_Amethyst
        else       -> R.style.Theme_BearNest_Catppuccin
    }

    companion object {
        const val PREFS_NAME = "bearnest_lang"
        const val KEY_LANG   = "language"
        const val KEY_THEME  = "theme"
        const val TAB_HOME     = "home"
        const val TAB_SERVERS  = "servers"
        const val TAB_SETTINGS = "settings"
        const val TAB_LOGS     = "logs"

        /** [QuickTiles] Плитка просит показать диалог разрешения VPN и подключиться. */
        const val EXTRA_REQUEST_VPN = "com.bearnest.vpn.REQUEST_VPN"
    }
}
