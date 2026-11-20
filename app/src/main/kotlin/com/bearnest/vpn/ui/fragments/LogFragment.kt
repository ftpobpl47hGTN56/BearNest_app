package com.bearnest.vpn.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bearnest.vpn.R
import com.bearnest.vpn.databinding.FragmentLogBinding
import com.bearnest.vpn.ui.MainActivity
import com.bearnest.vpn.ui.MainViewModel
import com.bearnest.vpn.ui.adapter.LogAdapter
import kotlinx.coroutines.launch

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private lateinit var adapter: LogAdapter

    /** null = показывать все категории. */
    private var filterCat: String? = null
    private var latestLogs: List<MainViewModel.LogEntry> = emptyList()
    private var textSizeSp = DEFAULT_TEXT_SIZE

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext()
            .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        textSizeSp = prefs.getFloat(KEY_LOG_TEXT_SIZE, DEFAULT_TEXT_SIZE)
            .coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)

        binding.toolbar.setNavigationOnClickListener {
            (activity as? MainActivity)?.showTab(MainActivity.TAB_HOME)
        }
        binding.toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_copy_logs  -> { copyLogsToClipboard(); true }
                R.id.action_clear_logs -> { vm.clearLogs(); true }
                R.id.action_zoom_in    -> { changeTextSize(+1f, prefs); true }
                R.id.action_zoom_out   -> { changeTextSize(-1f, prefs); true }
                else -> false
            }
        }

        // Фильтр по категориям: Все / Соединение / Xray / Приложение
        binding.chipGroupLogFilter.setOnCheckedStateChangeListener { _, checked ->
            filterCat = when (checked.firstOrNull()) {
                R.id.chip_log_conn -> MainViewModel.LOG_CONN
                R.id.chip_log_xray -> MainViewModel.LOG_XRAY
                R.id.chip_log_app  -> MainViewModel.LOG_APP
                else               -> null
            }
            applyLogs()
        }

        setupRecycler()
        observeLogs()
    }

    private fun setupRecycler() {
        adapter = LogAdapter().apply { textSizeSp = this@LogFragment.textSizeSp }
        val lm = LinearLayoutManager(requireContext())
        binding.recyclerLogs.layoutManager = lm
        binding.recyclerLogs.adapter       = adapter
        binding.recyclerLogs.itemAnimator  = null   // без анимации вставок
    }

    /** Кнопки +/−: масштаб текста логов, запоминается между запусками. */
    private fun changeTextSize(delta: Float, prefs: SharedPreferences) {
        textSizeSp = (textSizeSp + delta).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        prefs.edit().putFloat(KEY_LOG_TEXT_SIZE, textSizeSp).apply()
        adapter.textSizeSp = textSizeSp
        adapter.notifyDataSetChanged()
    }

    private fun observeLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.logs.collect { logs ->
                    latestLogs = logs
                    applyLogs()
                }
            }
        }
    }

    /** Применяет фильтр категории и отдаёт список в адаптер. */
    private fun applyLogs() {
        val filtered = if (filterCat == null) latestLogs
            else latestLogs.filter { it.cat == filterCat }
        val wasAtBottom = isScrolledToBottom()
        adapter.submitList(filtered) {
            // Автоскролл вниз только если были внизу до обновления
            if (wasAtBottom && filtered.isNotEmpty()) {
                binding.recyclerLogs.scrollToPosition(adapter.itemCount - 1)
            }
        }
        binding.tvEmpty.visibility       = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerLogs.visibility  = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun isScrolledToBottom(): Boolean {
        val lm = binding.recyclerLogs.layoutManager as? LinearLayoutManager ?: return true
        val last = lm.findLastVisibleItemPosition()
        return last >= adapter.itemCount - 2
    }

    private fun copyLogsToClipboard() {
        val text = vm.logs.value.joinToString("\n") {
            "[${it.time}] [${it.cat.uppercase()}] ${it.msg}"
        }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BearNest logs", text))
        Toast.makeText(requireContext(), R.string.copy, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_LOG_TEXT_SIZE = "log_text_size"
        private const val DEFAULT_TEXT_SIZE = 11f
        private const val MIN_TEXT_SIZE     = 8f
        private const val MAX_TEXT_SIZE     = 24f
    }
}
