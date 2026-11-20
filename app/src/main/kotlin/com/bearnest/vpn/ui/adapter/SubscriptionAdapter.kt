package com.bearnest.vpn.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bearnest.vpn.R
import com.bearnest.vpn.data.SubscriptionEntity
import com.bearnest.vpn.databinding.ItemSubscriptionBinding

/** Подписка + количество серверов (для мета-строки). */
data class SubListItem(
    val sub: SubscriptionEntity,
    val serverCount: Int
)

/**
 * Список подписок на экране «Подписки»:
 * имя, URL, число серверов + кнопки обновить/клонировать/удалить.
 */
class SubscriptionAdapter(
    private val onRefresh: (SubscriptionEntity) -> Unit,
    private val onClone: (SubscriptionEntity) -> Unit,
    private val onDelete: (SubscriptionEntity) -> Unit
) : ListAdapter<SubListItem, SubscriptionAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SubListItem>() {
            override fun areItemsTheSame(a: SubListItem, b: SubListItem) =
                a.sub.id == b.sub.id
            override fun areContentsTheSame(a: SubListItem, b: SubListItem) =
                a == b
        }
    }

    inner class VH(private val b: ItemSubscriptionBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(item: SubListItem) {
            val ctx = b.root.context
            val sub = item.sub
            b.tvSubName.text = sub.name.ifEmpty { sub.url }
            b.tvSubUrl.text = sub.url
            b.tvSubMeta.text = ctx.getString(R.string.sub_servers_count, item.serverCount)
            b.btnRefresh.setOnClickListener { onRefresh(sub) }
            b.btnClone.setOnClickListener { onClone(sub) }
            b.btnDelete.setOnClickListener { onDelete(sub) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemSubscriptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
