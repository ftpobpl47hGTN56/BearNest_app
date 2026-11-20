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
import com.bearnest.vpn.data.SubscriptionEntity
import com.bearnest.vpn.databinding.FragmentServerListBinding
import com.bearnest.vpn.model.ServerConfig
import com.bearnest.vpn.model.SubscriptionInfo
import com.bearnest.vpn.ui.MainActivity
import com.bearnest.vpn.ui.MainViewModel
import com.bearnest.vpn.ui.adapter.ServerListAdapter
import com.bearnest.vpn.ui.adapter.ServerListItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ServerListFragment : Fragment() {

    private var _binding: FragmentServerListBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private lateinit var adapter: ServerListAdapter

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
                R.id.action_refresh_all -> {
                    vm.refreshAllSubscriptions()
                    true
                }
                // Повторный клик по активному режиму — flip направления (cycleSort)
                R.id.action_sort_default -> { vm.cycleSort(MainViewModel.SORT_DEFAULT); true }
                R.id.action_sort_name    -> { vm.cycleSort(MainViewModel.SORT_NAME);    true }
                R.id.action_sort_ping    -> { vm.cycleSort(MainViewModel.SORT_PING);    true }
                else -> false
            }
        }
    }

    private fun setupRecycler() {
        adapter = ServerListAdapter(
            onServerClick = { server ->
                vm.selectServerByKey(server.selectionKey)
                // После выбора возвращаемся на главную
                (activity as? MainActivity)?.showTab(MainActivity.TAB_HOME)
            },
            onFavoriteClick = { server -> vm.toggleFavorite(server) },
            onHeaderClick   = { sub -> vm.toggleCollapsed(sub.id) },
            onRefreshSub    = { sub -> vm.refreshSubscription(sub.id) },
            onCloneSub      = { sub -> vm.cloneSubscription(sub.id) },
            onDeleteSub     = { sub -> confirmDeleteSub(sub) }
        )
        binding.recyclerServers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerServers.adapter       = adapter
        binding.recyclerServers.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        // Отключаем стандартные анимации элементов — они создают lag при обновлении
        binding.recyclerServers.itemAnimator = null
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Группировка: подписки → заголовки + серверы (пересборка при
                // смене сортировки, сворачивании, обновлении данных)
                launch {
                    combine(
                        vm.subscriptions, vm.servers, vm.sortMode, vm.sortDir
                    ) { subs, servers, _, _ -> buildItems(subs, servers) }
                        .collect { items ->
                            adapter.submitList(items)
                            binding.toolbar.title = getString(
                                R.string.servers_title_with_count,
                                vm.servers.value.size
                            )
                        }
                }
                launch {
                    vm.selectedKey.collect { adapter.selectedKey = it }
                }
                // Чек-состояние и стрелки направления в меню сортировки
                launch {
                    combine(vm.sortMode, vm.sortDir) { m, d -> m to d }
                        .collect { (mode, dir) -> updateSortMenu(mode, dir) }
                }
                launch {
                    vm.pingProgress.collect { progress ->
                        val total = vm.servers.value.size.coerceAtLeast(1)
                        if (progress < 0) {
                            binding.pingProgressBar.visibility = View.GONE
                            adapter.pingingKey = ""
                            binding.toolbar.menu.findItem(R.id.action_ping)?.isEnabled        = true
                            binding.toolbar.menu.findItem(R.id.action_auto_select)?.isEnabled = true
                        } else {
                            binding.pingProgressBar.visibility = View.VISIBLE
                            binding.pingProgressBar.progress   = (progress * 100 / total)
                            adapter.pingingKey =
                                vm.servers.value.getOrNull(progress)?.selectionKey ?: ""
                            binding.toolbar.menu.findItem(R.id.action_ping)?.isEnabled        = false
                            binding.toolbar.menu.findItem(R.id.action_auto_select)?.isEnabled = false
                        }
                    }
                }
            }
        }
    }

    /**
     * Собирает элементы списка: заголовок подписки + её серверы (если группа
     * не свёрнута). Серверы внутри группы — через vm.sortedServers()
     * (избранные наверху, ping<0 всегда в конце).
     */
    private fun buildItems(
        subs: List<SubscriptionEntity>,
        servers: List<ServerConfig>
    ): List<ServerListItem> {
        if (subs.isEmpty()) {
            // Данные до миграции — плоский список без групп
            return vm.sortedServers(servers).map { ServerListItem.Entry(it) }
        }
        val bySub = servers.groupBy { it.subscriptionId }
        val items = mutableListOf<ServerListItem>()
        subs.forEach { sub ->
            val list = bySub[sub.id].orEmpty()
            items += ServerListItem.Header(sub, list.size, usageMeta(sub))
            if (!sub.collapsed) {
                items += vm.sortedServers(list).map { ServerListItem.Entry(it) }
            }
        }
        // Серверы-сироты (подписка удалена, серверы ещё нет) — в конец списка
        val knownIds = subs.mapTo(hashSetOf<String>()) { it.id }
        val orphans = servers.filter { it.subscriptionId !in knownIds }
        if (orphans.isNotEmpty()) {
            items += vm.sortedServers(orphans).map { ServerListItem.Entry(it) }
        }
        return items
    }

    /** "↑↓ 1.2 GB / 100 GB" — мета в заголовке группы, как в Tauri-версии. */
    private fun usageMeta(sub: SubscriptionEntity): String {
        if (sub.total <= 0) return ""
        val info = SubscriptionInfo(
            upload = sub.upload, download = sub.download, total = sub.total
        )
        return "↑↓ ${info.usedDisplay} / ${info.totalDisplay}"
    }

    private fun updateSortMenu(mode: String, dir: Int) {
        val sub = binding.toolbar.menu.findItem(R.id.action_sort)?.subMenu ?: return
        val arrow = if (dir > 0) " ↑" else " ↓"
        fun apply(id: Int, baseRes: Int, active: Boolean, showArrow: Boolean) {
            sub.findItem(id)?.let { item ->
                item.title = getString(baseRes) + if (active && showArrow) arrow else ""
                item.isChecked = active
            }
        }
        apply(R.id.action_sort_default, R.string.sort_default,
            mode == MainViewModel.SORT_DEFAULT, showArrow = false)
        apply(R.id.action_sort_name, R.string.sort_name,
            mode == MainViewModel.SORT_NAME, showArrow = true)
        apply(R.id.action_sort_ping, R.string.sort_ping,
            mode == MainViewModel.SORT_PING, showArrow = true)
    }

    private fun confirmDeleteSub(sub: SubscriptionEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.action_delete_sub)
            .setMessage(getString(R.string.confirm_delete_sub, sub.name.ifEmpty { sub.url }))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                vm.deleteSubscription(sub.id)
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
