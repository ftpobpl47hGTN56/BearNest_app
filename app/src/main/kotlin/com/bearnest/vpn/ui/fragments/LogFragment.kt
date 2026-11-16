package com.bearnest.vpn.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            (activity as? MainActivity)?.showTab(MainActivity.TAB_HOME)
        }
        binding.toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_copy_logs  -> { copyLogsToClipboard(); true }
                R.id.action_clear_logs -> { vm.clearLogs(); true }
                else -> false
            }
        }

        setupRecycler()
        observeLogs()
    }

    private fun setupRecycler() {
        adapter = LogAdapter()
        val lm = LinearLayoutManager(requireContext())
        binding.recyclerLogs.layoutManager = lm
        binding.recyclerLogs.adapter       = adapter
        binding.recyclerLogs.itemAnimator  = null   // без анимации вставок
    }

    private fun observeLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.logs.collect { logs ->
                    val wasAtBottom = isScrolledToBottom()
                    adapter.submitList(logs.toList()) {
                        // Автоскролл вниз только если были внизу до обновления
                        if (wasAtBottom && logs.isNotEmpty()) {
                            binding.recyclerLogs.scrollToPosition(adapter.itemCount - 1)
                        }
                    }
                    binding.tvEmpty.visibility       = if (logs.isEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerLogs.visibility  = if (logs.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun isScrolledToBottom(): Boolean {
        val lm = binding.recyclerLogs.layoutManager as? LinearLayoutManager ?: return true
        val last = lm.findLastVisibleItemPosition()
        return last >= adapter.itemCount - 2
    }

    private fun copyLogsToClipboard() {
        val text = vm.logs.value.joinToString("\n") { "[${it.time}] ${it.msg}" }
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("BearNest logs", text))
        Toast.makeText(requireContext(), R.string.copy, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
