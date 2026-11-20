package com.bearnest.vpn.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bearnest.vpn.R
import com.bearnest.vpn.data.AppSettings
import com.bearnest.vpn.databinding.FragmentSplitTunnelBinding
import com.bearnest.vpn.ui.adapter.BypassDomainAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplitTunnelFragment : Fragment() {

    private var _binding: FragmentSplitTunnelBinding? = null
    private val binding get() = _binding!!
    private lateinit var appSettings: AppSettings

    // [Fixed] Адаптер для кастомных доменов — подключён к recycler_custom_domains
    private lateinit var domainAdapter: BypassDomainAdapter

    // ── Поиск по кастомным доменам (лупа) ─────────────────────────────────
    private var searchQuery = ""
    private var lastCustomDomains: List<String> = emptyList()

    // ── Импорт / экспорт bypass-листа (SAF) ──────────────────────────────

    /** Экспорт: системный диалог «создать файл» → пишем домены построчно. */
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let { exportToUri(it) } }

    /** Импорт: системный выбор файла (.txt — домен на строку). */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFromUri(it) } }

    private val chipDomains = mapOf(
        R.id.chip_tinkoff   to setOf("tinkoff.ru", "tinkoff.com"),
        R.id.chip_sber      to setOf("sberbank.ru", "sber.ru", "sbbol.ru"),
        R.id.chip_alfa      to setOf("alfabank.ru", "alfabank.com"),
        R.id.chip_vtb       to setOf("vtb.ru", "vtb24.ru"),
        R.id.chip_raif      to setOf("raiffeisen.ru"),
        R.id.chip_gazprom   to setOf("gazprombank.ru", "gpb.ru"),
        R.id.chip_vtb24     to setOf("pochtabank.ru"),
        R.id.chip_gosuslugi to setOf("gosuslugi.ru", "esia.gosuslugi.ru"),
        R.id.chip_nalog     to setOf("nalog.ru", "lkfl.nalog.ru"),
        R.id.chip_mos       to setOf("mos.ru"),
        R.id.chip_cbr       to setOf("cbr.ru"),
        R.id.chip_sfr       to setOf("sfr.gov.ru", "pfr.gov.ru"),
        R.id.chip_gibdd     to setOf("gibdd.ru", "mvd.ru"),
        R.id.chip_ozon      to setOf("ozon.ru"),
        R.id.chip_wb        to setOf("wildberries.ru", "wb.ru"),
        R.id.chip_avito     to setOf("avito.ru"),
        R.id.chip_hh        to setOf("hh.ru"),
        R.id.chip_yandex    to setOf("yandex.ru", "yandex.net", "ya.ru"),
        R.id.chip_vk        to setOf("vk.com", "vk.ru"),
        R.id.chip_mail      to setOf("mail.ru", "bk.ru", "inbox.ru"),
        R.id.chip_ok        to setOf("ok.ru", "odnoklassniki.ru"),
        R.id.chip_mts       to setOf("mts.ru"),
        R.id.chip_beeline   to setOf("beeline.ru"),
        R.id.chip_megafon   to setOf("megafon.ru"),
        R.id.chip_tele2     to setOf("tele2.ru")
    )

    // Все домены из пресетов — нужно чтобы отфильтровать кастомные
    private val allPresetDomains: Set<String> by lazy {
        chipDomains.values.flatten().toSet()
    }

    private var updatingChips = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSplitTunnelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        appSettings = AppSettings(requireContext())

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupRecycler()   // [Fixed] инициализация RecyclerView

        // Лупа: фильтруем список кастомных доменов по подстроке
        binding.etSearch.doAfterTextChanged { s ->
            searchQuery = s?.toString()?.trim()?.lowercase().orEmpty()
            applyDomainFilter()
        }

        setupChipListeners()
        setupButtons()
        observeState()
    }

    // ── [Fixed] RecyclerView для кастомных доменов ────────────────────────────

    private fun setupRecycler() {
        domainAdapter = BypassDomainAdapter(
            onEdit = { domain -> showEditDomainDialog(domain) },
            onDelete = { domain ->
                lifecycleScope.launch {
                    appSettings.removeBypassDomain(domain)
                }
            }
        )
        binding.recyclerCustomDomains.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCustomDomains.adapter = domainAdapter
        binding.recyclerCustomDomains.itemAnimator = null
    }

    /** Фильтр списка кастомных доменов по поисковой строке. */
    private fun applyDomainFilter() {
        val filtered = if (searchQuery.isEmpty()) lastCustomDomains
            else lastCustomDomains.filter { it.contains(searchQuery) }
        domainAdapter.submitList(filtered)
        binding.tvCustomDomainsEmpty.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupChipListeners() {
        chipDomains.keys.forEach { chipId ->
            view?.findViewById<Chip>(chipId)?.setOnCheckedChangeListener { _, isChecked ->
                if (updatingChips) return@setOnCheckedChangeListener
                val domains = chipDomains[chipId] ?: return@setOnCheckedChangeListener
                lifecycleScope.launch {
                    if (isChecked) domains.forEach { appSettings.addBypassDomain(it) }
                    else           domains.forEach { appSettings.removeBypassDomain(it) }
                }
            }
        }
    }

    private fun setupButtons() {
        binding.switchBypassEnabled.setOnCheckedChangeListener(null)

        binding.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Сбросить всё?")
                .setMessage("Все домены будут удалены из bypass-листа.")
                .setPositiveButton("Сбросить") { _, _ ->
                    lifecycleScope.launch { appSettings.setBypassDomains(emptySet()) }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        binding.fabAddDomain.setOnClickListener { showAddDomainDialog() }

        // ── Импорт / экспорт ─────────────────────────────────────────
        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        }
        binding.btnExport.setOnClickListener {
            lifecycleScope.launch {
                if (appSettings.getBypassDomainsOnce().isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.bypass_export_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    exportLauncher.launch("bearnest-bypass.txt")
                }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    appSettings.splitTunnelEnabled.collect { enabled ->
                        binding.switchBypassEnabled.setOnCheckedChangeListener(null)
                        binding.switchBypassEnabled.isChecked = enabled
                        binding.switchBypassEnabled.setOnCheckedChangeListener { _, checked ->
                            lifecycleScope.launch { appSettings.setSplitTunnelEnabled(checked) }
                        }
                    }
                }

                launch {
                    appSettings.bypassDomainsJson.collect { json ->
                        val current = appSettings.parseDomainsJson(json)

                        // Счётчик
                        binding.tvDomainCount.text = if (com.bearnest.vpn.vpn.BearVpnService.isRunning) {
                            "Selected: ${current.size} — restart VPN"
                        } else {
                            "Selected domains: ${current.size}"
                        }

                        // Обновляем чипы без рекурсии
                        updatingChips = true
                        chipDomains.forEach { (chipId, domains) ->
                            val checked = domains.any { it in current }
                            view?.findViewById<Chip>(chipId)?.isChecked = checked
                        }
                        updatingChips = false

                        // [Fixed] Кастомные домены = все домены минус пресеты
                        // Отображаем в RecyclerView отсортированно (с учётом поиска)
                        lastCustomDomains = current
                            .filter { it !in allPresetDomains }
                            .sorted()
                        applyDomainFilter()
                    }
                }
            }
        }
    }

    // ── Нормализация домена (общая для add/edit/import) ─────────────────────

    private fun normalizeDomain(token: String): String =
        token.trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')

    private fun isValidDomain(d: String): Boolean =
        d.isNotBlank() && d.contains('.') && d.length >= 4

    // ── [Fixed] Диалог добавления домена ─────────────────────────────────────

    private fun showAddDomainDialog() {
        val til = TextInputLayout(requireContext()).apply {
            hint = "site.ru или site.com"
        }
        val et = TextInputEditText(requireContext()).apply {
            isSingleLine = false
            minLines = 1
            maxLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        til.addView(et)

        val container = FrameLayout(requireContext()).apply {
            val px = (24 * resources.displayMetrics.density).toInt()
            setPadding(px, 8, px, 0)
            addView(til)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Добавить домен")
            .setMessage("Можно несколько через пробел:\nsber.ru gosuslugi.ru nalog.ru")
            .setView(container)
            .setPositiveButton("Добавить") { _, _ ->
                val raw = et.text.toString().trim()
                if (raw.isBlank()) return@setPositiveButton

                val domains = raw
                    .split(" ", "\n", "\t")
                    .map { normalizeDomain(it) }
                    .filter { isValidDomain(it) }
                    .toSet()

                if (domains.isEmpty()) {
                    Toast.makeText(context, "Неверный формат. Пример: site.ru", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                lifecycleScope.launch {
                    var added = 0
                    var skipped = 0
                    domains.forEach { domain ->
                        if (appSettings.addBypassDomain(domain)) added++ else skipped++
                    }
                    val msg = when {
                        added > 0 && skipped > 0 -> "Добавлено: $added, уже было: $skipped"
                        added > 0                -> "Добавлено: $added"
                        else                     -> "Все домены уже были в списке"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()

        et.requestFocus()
    }

    // ── Диалог редактирования домена (карандаш рядом с корзиной) ────────────

    private fun showEditDomainDialog(oldDomain: String) {
        val til = TextInputLayout(requireContext()).apply {
            hint = "site.ru"
        }
        val et = TextInputEditText(requireContext()).apply {
            setText(oldDomain)
            setSelection(oldDomain.length)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        til.addView(et)

        val container = FrameLayout(requireContext()).apply {
            val px = (24 * resources.displayMetrics.density).toInt()
            setPadding(px, 8, px, 0)
            addView(til)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_domain_title)
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val newDomain = normalizeDomain(et.text.toString())
                if (!isValidDomain(newDomain)) {
                    Toast.makeText(context, "Неверный формат. Пример: site.ru", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newDomain == oldDomain) return@setPositiveButton
                lifecycleScope.launch {
                    // Замена: удаляем старый, добавляем новый
                    appSettings.removeBypassDomain(oldDomain)
                    appSettings.addBypassDomain(newDomain)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()

        et.requestFocus()
    }

    // ── Импорт / экспорт ─────────────────────────────────────────────────────

    private fun exportToUri(uri: android.net.Uri) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val domains = appSettings.getBypassDomainsOnce().sorted()
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(domains.joinToString("\n").toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("openOutputStream returned null")
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.bypass_export_done, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.bypass_import_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun importFromUri(uri: android.net.Uri) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: ""
                val domains = text
                    .split(Regex("[\\s,;]+"))
                    .map { normalizeDomain(it) }
                    .filter { isValidDomain(it) }
                    .toSet()
                var added = 0
                var skipped = 0
                domains.forEach { domain ->
                    if (appSettings.addBypassDomain(domain)) added++ else skipped++
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        ctx,
                        getString(R.string.bypass_import_result, added, skipped),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, R.string.bypass_import_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
