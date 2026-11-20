package com.bearnest.vpn.ui.fragments

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bearnest.vpn.R
import com.bearnest.vpn.databinding.FragmentThemesBinding
import com.bearnest.vpn.databinding.ItemThemePreviewBinding
import com.bearnest.vpn.ui.MainActivity
import com.bearnest.vpn.ui.MainViewModel

/**
 * Экран выбора темы: сетка карточек с визуальным превью
 * (круги фон/поверхность/акцент) — 10 тем.
 */
class ThemesFragment : Fragment() {

    data class ThemeItem(
        val key: String,
        val nameRes: Int,
        val bgRes: Int,
        val surfaceRes: Int,
        val primaryRes: Int
    )

    companion object {
        val THEMES: List<ThemeItem> = listOf(
            ThemeItem("catppuccin", R.string.theme_name_catppuccin,
                R.color.catppuccin_background, R.color.catppuccin_surface, R.color.catppuccin_primary),
            ThemeItem("midnight", R.string.theme_name_midnight,
                R.color.midnight_background, R.color.midnight_surface, R.color.midnight_primary),
            ThemeItem("forest", R.string.theme_name_forest,
                R.color.forest_background, R.color.forest_surface, R.color.forest_primary),
            ThemeItem("obsidian", R.string.theme_name_obsidian,
                R.color.obsidian_background, R.color.obsidian_surface, R.color.obsidian_primary),
            ThemeItem("snow", R.string.theme_name_snow,
                R.color.snow_background, R.color.snow_surface, R.color.snow_primary),
            ThemeItem("ocean", R.string.theme_name_ocean,
                R.color.ocean_background, R.color.ocean_surface, R.color.ocean_primary),
            ThemeItem("sunset", R.string.theme_name_sunset,
                R.color.sunset_background, R.color.sunset_surface, R.color.sunset_primary),
            ThemeItem("sakura", R.string.theme_name_sakura,
                R.color.sakura_background, R.color.sakura_surface, R.color.sakura_primary),
            ThemeItem("mint", R.string.theme_name_mint,
                R.color.mint_background, R.color.mint_surface, R.color.mint_primary),
            ThemeItem("amethyst", R.string.theme_name_amethyst,
                R.color.amethyst_background, R.color.amethyst_surface, R.color.amethyst_primary)
        )
    }

    private var _binding: FragmentThemesBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentThemesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val adapter = ThemeAdapter(THEMES, vm.theme.value) { theme ->
            vm.setTheme(theme.key)
            requireContext()
                .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(MainActivity.KEY_THEME, theme.key).apply()
            activity?.recreate()
        }
        binding.recyclerThemes.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerThemes.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Адаптер карточек тем ────────────────────────────────────────────────

    private inner class ThemeAdapter(
        private val items: List<ThemeItem>,
        private val currentKey: String,
        private val onClick: (ThemeItem) -> Unit
    ) : RecyclerView.Adapter<ThemeAdapter.VH>() {

        inner class VH(val b: ItemThemePreviewBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemThemePreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val b = holder.b
            val ctx = b.root.context

            b.dotBg.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, item.bgRes))
            b.dotSurface.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, item.surfaceRes))
            b.dotPrimary.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, item.primaryRes))

            b.tvThemeName.text = ctx.getString(item.nameRes)

            // Выделение текущей темы рамкой colorPrimary
            val selected = item.key == currentKey
            b.root.strokeWidth = if (selected)
                (2 * ctx.resources.displayMetrics.density).toInt() else 0

            b.root.setOnClickListener { onClick(item) }
        }
    }
}
