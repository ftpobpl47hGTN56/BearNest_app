package com.bearnest.vpn.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bearnest.vpn.databinding.ItemBypassDomainBinding

class BypassDomainAdapter(
    private val onDelete: (String) -> Unit
) : ListAdapter<String, BypassDomainAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(
        private val binding: ItemBypassDomainBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(domain: String) {
            binding.tvDomain.text = domain
            binding.btnDelete.setOnClickListener { onDelete(domain) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemBypassDomainBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }
    }
}