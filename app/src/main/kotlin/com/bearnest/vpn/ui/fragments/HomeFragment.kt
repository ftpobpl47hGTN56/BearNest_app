package com.bearnest.vpn.ui.fragments

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bearnest.vpn.R
import com.bearnest.vpn.databinding.FragmentHomeBinding
import com.bearnest.vpn.model.PingColor
import com.bearnest.vpn.model.ServerConfig
import com.bearnest.vpn.model.SubscriptionInfo
import com.bearnest.vpn.ui.MainActivity
import com.bearnest.vpn.ui.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()

    private var pulseAnimator: ObjectAnimator? = null
    private var timerJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvXrayVersion.text = "xray ${vm.xrayVersion}"

        binding.cardServer.setOnClickListener {
            (activity as? MainActivity)?.showTab(MainActivity.TAB_SERVERS)
        }

        binding.btnConnect.setOnClickListener {
            if (vm.connected.value) vm.disconnect()
            else vm.connect((activity as? MainActivity)?.vpnPermLauncher)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Объединяем connected + loading в один коллектор чтобы обновления были синхронны
                launch { vm.connected.collect { updateConnectState() } }
                launch { vm.loading.collect   { updateConnectState() } }
                launch { vm.currentServerFlow.collect { updateServerCard(it) } }
                launch { vm.subInfo.collect   { updateSubCard(it) } }
                launch { vm.sessionStartMs.collect { startTimerIfNeeded(it) } }
            }
        }
    }

    // ── Кнопка подключения ────────────────────────────────────────────────────

    private fun updateConnectState() {
        val connected  = vm.connected.value
        val connecting = vm.loading.value

        val colorAttr = when {
            connecting -> com.google.android.material.R.attr.colorSurfaceVariant
            connected  -> com.google.android.material.R.attr.colorError
            else       -> com.google.android.material.R.attr.colorPrimary
        }
        val color = requireContext().resolveAttr(colorAttr)
        binding.btnConnect.backgroundTintList = ColorStateList.valueOf(color)

        binding.btnConnect.text = when {
            connecting -> getString(R.string.connecting)
            connected  -> getString(R.string.disconnect)
            else       -> getString(R.string.connect)
        }
        binding.btnConnect.setIconResource(
            if (connected) R.drawable.ic_power_settings_new_24 else R.drawable.ic_power_24
        )
        binding.btnConnect.isEnabled = !connecting

        if (connecting) startPulse() else stopPulse()

        val showStatus = connected && vm.vpnMode.value == "tun"
        binding.cardStatus.visibility      = if (showStatus) View.VISIBLE else View.GONE
        binding.tvSessionTimer.visibility  = if (showStatus) View.VISIBLE else View.GONE
        binding.icChevron.visibility       = if (connected) View.INVISIBLE else View.VISIBLE
    }

    // ── Карточка сервера ──────────────────────────────────────────────────────

    private fun updateServerCard(server: ServerConfig?) {
        if (server != null) {
            binding.tvServerName.text   = server.toString()
            binding.tvServerDetail.text = "${server.protocol.uppercase()} · ${server.pingDisplay}"
            binding.tvServerDetail.visibility = View.VISIBLE
            val pingColor = when (server.pingColor) {
                PingColor.GOOD    -> requireContext().getColor(R.color.bear_green)
                PingColor.OK      -> requireContext().getColor(R.color.bear_yellow)
                PingColor.BAD     -> requireContext().getColor(R.color.bear_red)
                PingColor.ERROR   -> requireContext().resolveAttr(com.google.android.material.R.attr.colorError)
                PingColor.NEUTRAL -> requireContext().resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            }
            binding.tvServerDetail.setTextColor(pingColor)
        } else {
            binding.tvServerName.text         = getString(R.string.server_not_selected)
            binding.tvServerDetail.visibility = View.GONE
        }
    }

    // ── Карточка подписки ─────────────────────────────────────────────────────

    private fun updateSubCard(info: SubscriptionInfo) {
        if (info.title.isEmpty()) {
            binding.cardSubscription.visibility = View.GONE
            return
        }
        binding.cardSubscription.visibility = View.VISIBLE
        binding.tvSubTitle.text = info.title

        if (info.total > 0) {
            val usageInt = (info.usagePercent * 100).toInt()
            binding.pbSubUsage.visibility = View.VISIBLE
            binding.pbSubUsage.progress   = usageInt
            // setIndicatorColor принимает @ColorInt Int, не ColorStateList
            binding.pbSubUsage.setIndicatorColor(
                if (info.usagePercent > 0.9f) requireContext().getColor(R.color.bear_red)
                else requireContext().getColor(R.color.bear_blue)
            )
            binding.tvSubUsage.visibility = View.VISIBLE
            binding.tvSubUsage.text       = "${info.usedDisplay} / ${info.totalDisplay}"
        } else {
            binding.pbSubUsage.visibility = View.GONE
            binding.tvSubUsage.visibility = View.GONE
        }

        if (info.expireUnix > 0) {
            binding.tvSubExpire.visibility = View.VISIBLE
            binding.tvSubExpire.text       = getString(R.string.subscription_expires_prefix) + info.expireDisplay
            binding.tvSubExpire.setTextColor(
                if (info.isExpired) requireContext().getColor(R.color.bear_red)
                else requireContext().resolveAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
        } else {
            binding.tvSubExpire.visibility = View.GONE
        }
    }

    // ── Таймер сессии ─────────────────────────────────────────────────────────

    private fun startTimerIfNeeded(startMs: Long) {
        timerJob?.cancel()
        if (startMs <= 0L) { binding.tvSessionTimer.text = ""; return }
        timerJob = viewLifecycleOwner.lifecycleScope.launch {
            val sdf = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
            while (true) {
                val elapsed = System.currentTimeMillis() - startMs
                val h = elapsed / 3_600_000
                val m = (elapsed % 3_600_000) / 60_000
                val s = (elapsed % 60_000) / 1_000
                binding.tvSessionTimer.text =
                    "%02d:%02d:%02d  (с %s)".format(h, m, s, sdf.format(java.util.Date(startMs)))
                delay(1_000)
            }
        }
    }

    // ── Пульсация кнопки ─────────────────────────────────────────────────────

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.btnConnect,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.05f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.05f)
        ).apply {
            duration    = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode  = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        _binding?.btnConnect?.scaleX = 1f
        _binding?.btnConnect?.scaleY = 1f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPulse()
        timerJob?.cancel()
        _binding = null
    }
}

// ── Вспомогательная extension для чтения цвета из темы ───────────────────────
fun android.content.Context.resolveAttr(attr: Int): Int {
    val ta = obtainStyledAttributes(intArrayOf(attr))
    val color = ta.getColor(0, 0)
    ta.recycle()
    return color
}