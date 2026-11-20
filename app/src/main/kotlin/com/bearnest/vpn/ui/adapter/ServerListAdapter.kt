package com.bearnest.vpn.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bearnest.vpn.R
import com.bearnest.vpn.data.SubscriptionEntity
import com.bearnest.vpn.databinding.ItemServerBinding
import com.bearnest.vpn.databinding.ItemSubscriptionHeaderBinding
import com.bearnest.vpn.model.PingColor
import com.bearnest.vpn.model.ServerConfig
import com.bearnest.vpn.ui.fragments.resolveAttr

/**
 * Элемент списка серверов: заголовок группы-подписки или сервер.
 * Порт структуры списка из Tauri-версии (sub-head + servers).
 */
sealed interface ServerListItem {
    val stableId: String

    data class Header(
        val sub: SubscriptionEntity,
        val serverCount: Int,
        /** "↑↓ 1.2 GB / 100 GB" или "" если данных о трафике нет */
        val usageMeta: String
    ) : ServerListItem {
        override val stableId: String get() = "header_${sub.id}"
    }

    data class Entry(val server: ServerConfig) : ServerListItem {
        override val stableId: String
            get() = "srv_${server.subscriptionId}_${server.dbId}_${server.name}"
    }
}

/**
 * Адаптер списка серверов с группами-подписками (мультиподписка).
 * Выделение и пинг — по selectionKey ("subId::name"), не по индексу.
 */
class ServerListAdapter(
    private val onServerClick: (ServerConfig) -> Unit,
    private val onFavoriteClick: (ServerConfig) -> Unit,
    private val onHeaderClick: (SubscriptionEntity) -> Unit,
    private val onRefreshSub: (SubscriptionEntity) -> Unit,
    private val onCloneSub: (SubscriptionEntity) -> Unit,
    private val onDeleteSub: (SubscriptionEntity) -> Unit
) : ListAdapter<ServerListItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SERVER = 1

        private val DIFF = object : DiffUtil.ItemCallback<ServerListItem>() {
            override fun areItemsTheSame(a: ServerListItem, b: ServerListItem) =
                a.stableId == b.stableId
            override fun areContentsTheSame(a: ServerListItem, b: ServerListItem) =
                a == b
        }
    }

    /** Ключ выбранного сервера "subId::name". */
    var selectedKey: String = ""
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }

    /** Ключ сервера, который сейчас пингуется ("" — никого). */
    var pingingKey: String = ""
        set(value) {
            if (field != value) { field = value; notifyDataSetChanged() }
        }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ServerListItem.Header -> TYPE_HEADER
        is ServerListItem.Entry  -> TYPE_SERVER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                ItemSubscriptionHeaderBinding.inflate(inflater, parent, false)
            )
            else -> ServerHolder(
                ItemServerBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ServerListItem.Header -> (holder as HeaderHolder).bind(item)
            is ServerListItem.Entry  -> (holder as ServerHolder).bind(item.server)
        }
    }

    // ── Заголовок подписки ──────────────────────────────────────────────────

    inner class HeaderHolder(private val b: ItemSubscriptionHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(h: ServerListItem.Header) {
            val ctx = b.root.context
            val sub = h.sub
            b.tvSubName.text = sub.name.ifEmpty { sub.url }
            val meta = StringBuilder(ctx.getString(R.string.sub_servers_count, h.serverCount))
            if (h.usageMeta.isNotEmpty()) meta.append(" • ").append(h.usageMeta)
            b.tvSubMeta.text = meta

            // Шеврон: свёрнуто → вправо (0°), развёрнуто → вниз (90°)
            b.ivChevron.animate()
                .rotation(if (sub.collapsed) 0f else 90f)
                .setDuration(150)
                .start()

            b.root.setOnClickListener { onHeaderClick(sub) }
            b.btnRefresh.setOnClickListener { onRefreshSub(sub) }
            b.btnClone.setOnClickListener { onCloneSub(sub) }
            b.btnDelete.setOnClickListener { onDeleteSub(sub) }
        }
    }

    // ── Сервер ──────────────────────────────────────────────────────────────

    inner class ServerHolder(private val b: ItemServerBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(server: ServerConfig) {
            val ctx = b.root.context

            // ── Выделение (по ключу, не по позиции) ──────────────────────
            val isSelected = server.selectionKey == selectedKey
            val dotColor = if (isSelected)
                ctx.resolveAttr(com.google.android.material.R.attr.colorPrimary)
            else
                ctx.resolveAttr(com.google.android.material.R.attr.colorSurfaceVariant)
            b.dotSelected.backgroundTintList = ColorStateList.valueOf(dotColor)

            // ── Имя ──────────────────────────────────────────────────
            b.tvServerName.text = server.toString()
            b.tvServerName.setTypeface(
                null,
                if (isSelected) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.NORMAL
            )

            // ── Протокол ─────────────────────────────────────────────
            val proto = server.protocol.lowercase()
            b.tvProtocol.text = proto.uppercase()
            val protoColor = when (proto) {
                "vless"  -> ctx.getColor(R.color.bear_blue)
                "vmess"  -> ctx.getColor(R.color.bear_mauve)
                "trojan" -> ctx.getColor(R.color.bear_peach)
                "ss"     -> ctx.getColor(R.color.bear_yellow)
                else     -> ctx.resolveAttr(
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            }
            b.tvProtocol.setTextColor(protoColor)
            b.tvProtocol.backgroundTintList = ColorStateList.valueOf(
                protoColor and 0x00FFFFFF or 0x1A000000
            )

            // ── Security ─────────────────────────────────────────────
            if (server.security.isNotEmpty() && server.security != "none") {
                b.tvSecurity.visibility = View.VISIBLE
                b.tvSecurity.text = server.security.uppercase()
            } else {
                b.tvSecurity.visibility = View.GONE
            }

            // ── Network ──────────────────────────────────────────────
            if (server.network.isNotEmpty() && server.network != "tcp") {
                b.tvNetwork.visibility = View.VISIBLE
                b.tvNetwork.text = server.network.uppercase()
            } else {
                b.tvNetwork.visibility = View.GONE
            }

            // ── Звезда избранного ────────────────────────────────────
            b.btnFavorite.setImageResource(
                if (server.isFavorite) R.drawable.ic_star_24
                else R.drawable.ic_star_outline_24
            )
            b.btnFavorite.imageTintList = ColorStateList.valueOf(
                if (server.isFavorite) ctx.getColor(R.color.bear_yellow)
                else ctx.resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            b.btnFavorite.setOnClickListener { onFavoriteClick(server) }

            // ── Пинг / индикатор загрузки ────────────────────────────
            val isPinging = server.selectionKey == pingingKey
            if (isPinging) {
                b.pingIndicator.visibility = View.VISIBLE
                b.tvPing.visibility        = View.GONE
            } else {
                b.pingIndicator.visibility = View.GONE
                b.tvPing.visibility        = View.VISIBLE

                b.tvPing.text = server.pingDisplay

                // Цвет пинга — тёмный для Snow, пастельный для тёмных тем
                val isSnow = ctx.getSharedPreferences(
                    "bearnest_lang", android.content.Context.MODE_PRIVATE
                ).getString("theme", "") == "snow"

                val pingColor = when (server.pingColor) {
                    PingColor.GOOD    -> ctx.getColor(
                        if (isSnow) R.color.snow_ping_good else R.color.bear_green
                    )
                    PingColor.OK      -> ctx.getColor(
                        if (isSnow) R.color.snow_ping_mid  else R.color.bear_yellow
                    )
                    PingColor.BAD     -> ctx.getColor(
                        if (isSnow) R.color.snow_ping_bad  else R.color.bear_red
                    )
                    PingColor.ERROR   -> ctx.resolveAttr(
                        com.google.android.material.R.attr.colorError
                    )
                    PingColor.NEUTRAL -> ctx.resolveAttr(
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                }
                b.tvPing.setTextColor(pingColor)
            }

            b.root.setOnClickListener { onServerClick(server) }
        }
    }
}
