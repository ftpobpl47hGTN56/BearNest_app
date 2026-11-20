package com.bearnest.vpn.ui.fragments

import android.content.Context
import android.os.Bundle
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

/**
 * Настройки — корневой экран с разделами:
 * Подписки / Темы / Байпас-лист + Язык + О приложении.
 */
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
                    vm.language.collect { lang ->
                        val chipId = if (lang == "en") R.id.chip_lang_en else R.id.chip_lang_ru
                        if (binding.chipGroupLang.checkedChipId != chipId)
                            binding.chipGroupLang.check(chipId)
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        // ── Разделы ──────────────────────────────────────────────────
        binding.rowSubscriptions.setOnClickListener {
            navigateTo(SubscriptionsFragment(), "subscriptions")
        }
        binding.rowThemes.setOnClickListener {
            navigateTo(ThemesFragment(), "themes")
        }
        binding.rowBypass.setOnClickListener {
            navigateTo(SplitTunnelFragment(), "split_tunnel")
        }

        // ── Язык ─────────────────────────────────────────────────────
        binding.chipGroupLang.setOnCheckedStateChangeListener { _, checkedIds ->
            val lang = if (checkedIds.firstOrNull() == R.id.chip_lang_en) "en" else "ru"
            if (vm.language.value != lang) {
                vm.setLanguage(lang)
                prefs().edit().putString(MainActivity.KEY_LANG, lang).apply()
                activity?.recreate()
            }
        }
    }

    private fun navigateTo(fragment: Fragment, tag: String) {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(tag)
            .commit()
    }

    private fun prefs() =
        requireContext().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
