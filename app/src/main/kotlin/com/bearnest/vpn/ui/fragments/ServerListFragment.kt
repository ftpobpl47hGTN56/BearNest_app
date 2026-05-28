package com.bearnest.vpn.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.bearnest.vpn.R
import com.bearnest.vpn.databinding.FragmentServerListBinding
import com.bearnest.vpn.ui.MainActivity
import com.bearnest.vpn.ui.MainViewModel
import com.bearnest.vpn.ui.adapter.ServerAdapter
import kotlinx.coroutines.launch

class ServerListFragment : Fragment() {

    private var _binding: FragmentServerListBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private lateinit var adapter: ServerAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecycler()
        observeState()
    }

    private fun setupToolbar() {
        // Обновляем title с количеством серверов после загрузки
        binding.toolbar.setNavigationOnClickListener {
            (activity as? MainActivity)?.showTab(MainActivity.TAB_HOME)
        }
        binding.toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_ping -> {
                    vm.pingAll()
                    true
                }
                R.id.action_auto_select -> {
                    vm.autoSelectBest()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecycler() {
        adapter = ServerAdapter { idx ->
            vm.selectServer(idx)
            // После выбора возвращаемся на главную
            (activity as? MainActivity)?.showTab(MainActivity.TAB_HOME)
        }
        binding.recyclerServers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerServers.adapter       = adapter
        binding.recyclerServers.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        // Отключаем стандартные анимации элементов — они тоже создают lag при обновлении
        binding.recyclerServers.itemAnimator = null
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.servers.collect { servers ->
                        adapter.submitList(servers)
                        binding.toolbar.title =
                            getString(R.string.servers_title_with_count, servers.size)
                    }
                }
                launch {
                    vm.selectedIdx.collect { idx ->
                        adapter.selectedIdx = idx
                    }
                }
                launch {
                    vm.pingProgress.collect { progress ->
                        val total = vm.servers.value.size.coerceAtLeast(1)
                        if (progress < 0) {
                            binding.pingProgressBar.visibility = View.GONE
                            adapter.pingingIdx = -1
                            // Переключаем кнопки
                            binding.toolbar.menu.findItem(R.id.action_ping)?.isEnabled        = true
                            binding.toolbar.menu.findItem(R.id.action_auto_select)?.isEnabled = true
                        } else {
                            binding.pingProgressBar.visibility = View.VISIBLE
                            binding.pingProgressBar.progress   = (progress * 100 / total)
                            adapter.pingingIdx = progress
                            binding.toolbar.menu.findItem(R.id.action_ping)?.isEnabled        = false
                            binding.toolbar.menu.findItem(R.id.action_auto_select)?.isEnabled = false
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
