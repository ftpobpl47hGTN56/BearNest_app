package com.bearnest.vpn.ui.fragments

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bearnest.vpn.R
import com.bearnest.vpn.databinding.FragmentSettingsBinding
import com.bearnest.vpn.databinding.ItemAboutRowBinding
import com.bearnest.vpn.ui.MainActivity
import com.bearnest.vpn.ui.MainViewModel
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            (activity as? MainActivity)?.showTab(MainActivity.TAB_HOME)
        }

        setupAboutRows()
        observeState()
        setupListeners()
    }

    private fun setupAboutRows() {
        bindRow(binding.rowVersion,   getString(R.string.version_label),   getString(R.string.app_version))
        bindRow(binding.rowEngine,    getString(R.string.engine_label),     getString(R.string.engine_value))
        bindRow(binding.rowProtocols, getString(R.string.protocols_label),  getString(R.string.protocols_value))
        bindRow(binding.rowReality,   getString(R.string.reality_label),    getString(R.string.reality_value))
        bindRow(binding.rowGithub,    getString(R.string.github_label),     getString(R.string.github_url))
    }

    private fun bindRow(b: ItemAboutRowBinding, label: String, value: String) {
        b.tvLabel.text = label
        b.tvValue.text = value
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    vm.theme.collect { theme ->
                        val chipId = when (theme) {
                            "midnight" -> R.id.chip_midnight
                            "forest"   -> R.id.chip_forest
                            "obsidian" -> R.id.chip_obsidian
                            "snow"     -> R.id.chip_snow
                            else       -> R.id.chip_catppuccin
                        }
                        if (binding.chipGroupTheme.checkedChipId != chipId)
                            binding.chipGroupTheme.check(chipId)
                    }
                }

                launch {
                    vm.language.collect { lang ->
                        val chipId = if (lang == "en") R.id.chip_lang_en else R.id.chip_lang_ru
                        if (binding.chipGroupLang.checkedChipId != chipId)
                            binding.chipGroupLang.check(chipId)
                    }
                }

                launch {
                    vm.subUrl.collect { url ->
                        val et = binding.etSubUrl
                        if (et.text.toString() != url) et.setText(url)
                        updateLoadButton()
                    }
                }

                launch {
                    vm.loading.collect { loading ->
                        binding.btnLoadSub.text = if (loading)
                            getString(R.string.loading)
                        else
                            getString(R.string.load_subscription)
                        updateLoadButton()
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        binding.chipGroupTheme.setOnCheckedStateChangeListener { _, checkedIds ->
            val key = when (checkedIds.firstOrNull()) {
                R.id.chip_midnight -> "midnight"
                R.id.chip_forest   -> "forest"
                R.id.chip_obsidian -> "obsidian"
                R.id.chip_snow     -> "snow"
                else               -> "catppuccin"
            }
            if (vm.theme.value != key) {
                vm.setTheme(key)
                prefs().edit().putString(MainActivity.KEY_THEME, key).apply()
                activity?.recreate()
            }
        }

        binding.chipGroupLang.setOnCheckedStateChangeListener { _, checkedIds ->
            val lang = if (checkedIds.firstOrNull() == R.id.chip_lang_en) "en" else "ru"
            if (vm.language.value != lang) {
                vm.setLanguage(lang)
                prefs().edit().putString(MainActivity.KEY_LANG, lang).apply()
                activity?.recreate()
            }
        }

        // [Fixed] TextWatcher — обновляет кнопку при каждом изменении текста
        binding.etSubUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateLoadButton() }
        })

        binding.btnLoadSub.setOnClickListener {
            val url = binding.etSubUrl.text?.toString()?.trim() ?: return@setOnClickListener
            if (url.isNotBlank()) vm.loadSubscription(url)
        }

        // ── Split Tunneling: переход на экран bypass-листа ────────────
        // btn_split_tunnel добавлен в fragment_settings_updated.xml из архива
        binding.btnSplitTunnel.setOnClickListener {
            parentFragmentManager
                .beginTransaction()
                .replace(R.id.fragment_container, SplitTunnelFragment())
                .addToBackStack("split_tunnel")
                .commit()
        }
    }

    /** [Fixed] Единый метод обновления enabled-состояния кнопки загрузки */
    private fun updateLoadButton() {
        val b = _binding ?: return
        b.btnLoadSub.isEnabled = !vm.loading.value && !b.etSubUrl.text.isNullOrBlank()
    }

    private fun prefs() =
        requireContext().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
