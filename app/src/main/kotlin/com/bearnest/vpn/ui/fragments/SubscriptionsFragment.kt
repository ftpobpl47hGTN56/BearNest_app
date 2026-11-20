package com.bearnest.vpn.ui.fragments

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
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.bearnest.vpn.R
import com.bearnest.vpn.data.SubscriptionEntity
import com.bearnest.vpn.databinding.FragmentSubscriptionsBinding
import com.bearnest.vpn.ui.MainViewModel
import com.bearnest.vpn.ui.adapter.SubListItem
import com.bearnest.vpn.ui.adapter.SubscriptionAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Экран «Подписки»: добавление по URL + список подписок
 * (обновить / клонировать / удалить).
 */
class SubscriptionsFragment : Fragment() {

    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private lateinit var adapter: SubscriptionAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubscriptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupRecycler()
        setupInput()
        observeState()
    }

    private fun setupRecycler() {
        adapter = SubscriptionAdapter(
            onRefresh = { vm.refreshSubscription(it.id) },
            onClone   = { vm.cloneSubscription(it.id) },
            onDelete  = { confirmDelete(it) }
        )
        binding.recyclerSubscriptions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSubscriptions.adapter = adapter
        binding.recyclerSubscriptions.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        )
        binding.recyclerSubscriptions.itemAnimator = null
    }

    private fun setupInput() {
        binding.etSubUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateAddButton() }
        })
        binding.btnAddSub.setOnClickListener {
            val url = binding.etSubUrl.text?.toString()?.trim() ?: return@setOnClickListener
            if (url.isNotBlank()) {
                vm.addOrRefreshSubscription(url)
                binding.etSubUrl.text?.clear()
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(vm.subscriptions, vm.servers) { subs, servers ->
                        subs.map { sub ->
                            SubListItem(
                                sub = sub,
                                serverCount = servers.count { it.subscriptionId == sub.id }
                            )
                        }
                    }.collect { items ->
                        adapter.submitList(items)
                        binding.tvEmpty.visibility =
                            if (items.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    vm.loading.collect { loading ->
                        binding.btnAddSub.text = if (loading)
                            getString(R.string.loading)
                        else
                            getString(R.string.add_subscription)
                        updateAddButton()
                    }
                }
            }
        }
    }

    private fun updateAddButton() {
        val b = _binding ?: return
        b.btnAddSub.isEnabled = !vm.loading.value && !b.etSubUrl.text.isNullOrBlank()
    }

    private fun confirmDelete(sub: SubscriptionEntity) {
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
