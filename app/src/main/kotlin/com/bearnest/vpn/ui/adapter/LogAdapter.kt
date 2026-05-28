package com.bearnest.vpn.ui.adapter

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bearnest.vpn.R
import com.bearnest.vpn.ui.MainViewModel
import com.bearnest.vpn.ui.fragments.resolveAttr

class LogAdapter : ListAdapter<MainViewModel.LogEntry, LogAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(entry: MainViewModel.LogEntry) {
            val ctx = tv.context
            tv.text = "[${entry.time}] ${entry.msg}"
            tv.textSize = 11f
            tv.typeface = Typeface.MONOSPACE
            tv.setTextColor(
                when (entry.level) {
                    3    -> ctx.getColor(R.color.bear_red)
                    2    -> ctx.getColor(R.color.bear_yellow)
                    1    -> ctx.resolveAttr(com.google.android.material.R.attr.colorOnSurface)
                    else -> ctx.resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
                }
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 2, 0, 2) }
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MainViewModel.LogEntry>() {
            override fun areItemsTheSame(a: MainViewModel.LogEntry, b: MainViewModel.LogEntry) =
                a.time == b.time && a.msg == b.msg
            override fun areContentsTheSame(a: MainViewModel.LogEntry, b: MainViewModel.LogEntry) =
                a == b
        }
    }
}
