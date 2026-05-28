package com.bearnest.vpn.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bearnest.vpn.R
import com.bearnest.vpn.databinding.ItemServerBinding
import com.bearnest.vpn.model.PingColor
import com.bearnest.vpn.model.ServerConfig
import com.bearnest.vpn.ui.fragments.resolveAttr

class ServerAdapter(
    private val onServerClick: (index: Int) -> Unit
) : ListAdapter<ServerConfig, ServerAdapter.ViewHolder>(DIFF) {

    /** Индекс сервера, который сейчас пингуется (-1 = никакой) */
    var pingingIdx: Int = -1
        set(value) {
            field = value
            notifyDataSetChanged()          // ping затрагивает весь список
        }

    /** Выбранный индекс */
    var selectedIdx: Int = -1
        set(value) {
            val old = field
            field = value
            if (old >= 0) notifyItemChanged(old)
            if (value >= 0) notifyItemChanged(value)
        }

    inner class ViewHolder(private val b: ItemServerBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(server: ServerConfig, position: Int) {
            val ctx = b.root.context

            // Выделение
            val isSelected = position == selectedIdx
            val dotColor = if (isSelected)
                ctx.resolveAttr(com.google.android.material.R.attr.colorPrimary)
            else
                ctx.resolveAttr(com.google.android.material.R.attr.colorSurfaceVariant)
            b.dotSelected.backgroundTintList = ColorStateList.valueOf(dotColor)

            // Имя
            b.tvServerName.text = server.toString()
            if (isSelected) b.tvServerName.setTypeface(null, android.graphics.Typeface.BOLD)
            else             b.tvServerName.setTypeface(null, android.graphics.Typeface.NORMAL)

            // Протокол
            val proto = server.protocol.lowercase()
            b.tvProtocol.text = proto.uppercase()
            val protoColor = when (proto) {
                "vless"  -> ctx.getColor(R.color.bear_blue)
                "vmess"  -> ctx.getColor(R.color.bear_mauve)
                "trojan" -> ctx.getColor(R.color.bear_peach)
                "ss"     -> ctx.getColor(R.color.bear_yellow)
                else     -> ctx.resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
            b.tvProtocol.setTextColor(protoColor)
            b.tvProtocol.backgroundTintList = ColorStateList.valueOf(
                protoColor and 0x00FFFFFF or 0x1A000000
            )

            // Security
            if (server.security.isNotEmpty() && server.security != "none") {
                b.tvSecurity.visibility = android.view.View.VISIBLE
                b.tvSecurity.text = server.security.uppercase()
            } else {
                b.tvSecurity.visibility = android.view.View.GONE
            }

            // Network
            if (server.network.isNotEmpty() && server.network != "tcp") {
                b.tvNetwork.visibility = android.view.View.VISIBLE
                b.tvNetwork.text = server.network.uppercase()
            } else {
                b.tvNetwork.visibility = android.view.View.GONE
            }

            // Пинг / индикатор загрузки
            val isPinging = position == pingingIdx
            if (isPinging) {
                b.pingIndicator.visibility = android.view.View.VISIBLE
                b.tvPing.visibility        = android.view.View.GONE
            } else {
                b.pingIndicator.visibility = android.view.View.GONE
                b.tvPing.visibility        = android.view.View.VISIBLE
                b.tvPing.text = server.pingDisplay
                val pingColor = when (server.pingColor) {
                    PingColor.GOOD    -> ctx.getColor(R.color.bear_green)
                    PingColor.OK      -> ctx.getColor(R.color.bear_yellow)
                    PingColor.BAD     -> ctx.getColor(R.color.bear_red)
                    PingColor.ERROR   -> ctx.resolveAttr(com.google.android.material.R.attr.colorError)
                    PingColor.NEUTRAL -> ctx.resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
                }
                b.tvPing.setTextColor(pingColor)
            }

            b.root.setOnClickListener { onServerClick(position) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), position)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ServerConfig>() {
            override fun areItemsTheSame(a: ServerConfig, b: ServerConfig) =
                a.address == b.address && a.port == b.port && a.id == b.id
            override fun areContentsTheSame(a: ServerConfig, b: ServerConfig) =
                a == b
        }
    }
}
