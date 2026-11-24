/*
 * PARA VPN - tela estilo "X-VPN" para o fork do ics-openvpn.
 * Substitui o MainActivity original (mesmo pacote e mesmo nome).
 * Licenca: GPLv2 (como o projeto original).
 */
package de.blinkt.openvpn.activities

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.LogItem
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity(), VpnStatus.StateListener, VpnStatus.ByteCountListener {

    private enum class UiState { IDLE, CONNECTING, CONNECTED }

    // Cores
    private val bg = Color.parseColor("#0E1224")
    private val card = Color.parseColor("#1A2038")
    private val textMain = Color.parseColor("#FFFFFF")
    private val textSub = Color.parseColor("#8E97B8")
    private val green = Color.parseColor("#19D38B")
    private val blue = Color.parseColor("#4C8DFF")
    private val amber = Color.parseColor("#FFB020")
    private val idleColor = Color.parseColor("#3D4A7A")
    private val red = Color.parseColor("#FF5A5F")

    // Views
    private lateinit var connectBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var timerTv: TextView
    private lateinit var downTv: TextView
    private lateinit var upTv: TextView
    private lateinit var downTotalTv: TextView
    private lateinit var upTotalTv: TextView
    private lateinit var graph: SpeedGraph
    private lateinit var serverNameTv: TextView
    private lateinit var serverSubTv: TextView
    private lateinit var hintTv: TextView
    private lateinit var speedLine: TextView
    private lateinit var statsRow: LinearLayout
    private lateinit var optionsChip: TextView
    private lateinit var modeChip: TextView
    private lateinit var lockOverlay: View

    private var pulse: ObjectAnimator? = null
    private var uiState = UiState.IDLE
    private var connectedSince = 0L
    private var lastTs = 0L
    private var lastIn = 0L
    private var lastOut = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (uiState == UiState.CONNECTED) {
                val s = (SystemClock.elapsedRealtime() - connectedSince) / 1000
                timerTv.text = String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importFromUri(uri)
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) unlock() else toast("App continua bloqueado")
        }

    private var advanced = false
    private var locked = false
    private var unlocked = false
    private var leftAt = 0L
    private var hintAt = 0L
    private val pingCache = ConcurrentHashMap<String, Long>()

    private val logListener = object : VpnStatus.LogListener {
        override fun newLog(logItem: LogItem?) {
            if (logItem == null) return
            val msg = try { logItem.getString(this@MainActivity) } catch (e: Exception) { return }
            val h = friendly(msg ?: return) ?: return
            runOnUiThread { showHint(h) }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun chip(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(textMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(card, 20)
            setOnClickListener { onClick() }
        }

    private fun field(hint: String, lines: Int = 1, input: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply {
            this.hint = hint
            if (lines > 1) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = lines
                maxLines = 8
                gravity = Gravity.TOP or Gravity.START
            } else {
                inputType = input
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

    private fun section(t: String): TextView =
        label(t, 12f, Color.GRAY, true).apply { setPadding(0, dp(14), 0, dp(2)) }

    private fun showError(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = bg
            window.navigationBarColor = bg
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        advanced = prefs().getBoolean("advanced", false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(dp(20), b.top + dp(12), dp(20), b.bottom + dp(16))
            insets
        }

        // Barra superior: emblema + nome + Logs
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_para_fg)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        top.addView(logo, LinearLayout.LayoutParams(dp(64), dp(64)).apply { leftMargin = -dp(10) })

        val title = label("PARA VPN", 22f, textMain, true).apply { letterSpacing = 0.08f }
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = -dp(6) })

        modeChip = chip(if (advanced) "Simples" else "Avançado") { toggleMode() }
        top.addView(modeChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
            .apply { rightMargin = dp(8) })

        top.addView(chip("Logs") {
            startActivity(Intent(this@MainActivity, LogWindow::class.java))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        root.addView(top)

        // Area central
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        connectBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textMain)
            setOnClickListener { onConnectClicked() }
        }
        center.addView(connectBtn, LinearLayout.LayoutParams(dp(190), dp(190)))

        statusTv = label("Desconectado", 18f, textMain, true).apply { gravity = Gravity.CENTER }
        center.addView(statusTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        hintTv = label("", 13f, amber).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), 0)
        }
        center.addView(hintTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        timerTv = label("00:00:00", 26f, textSub).apply { gravity = Gravity.CENTER }
        center.addView(timerTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        speedLine = label("↓ 0 B/s     ↑ 0 B/s", 14f, textSub).apply { gravity = Gravity.CENTER }
        center.addView(speedLine, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        // Velocidade de download / upload
        downTv = label("0 B/s", 18f, textMain, true)
        upTv = label("0 B/s", 18f, textMain, true)
        downTotalTv = label("Total: 0 B", 11f, textSub)
        upTotalTv = label("Total: 0 B", 11f, textSub)
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statsRow = stats
        stats.addView(statBox("↓ Download", downTv, downTotalTv, green),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(8) })
        stats.addView(statBox("↑ Upload", upTv, upTotalTv, blue),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) })
        center.addView(stats, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        graph = SpeedGraph(this, green, blue).apply { background = rounded(card, 14) }
        center.addView(graph, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(64)
        ).apply { topMargin = dp(10) })

        root.addView(center, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Cartao do servidor
        val serverCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 16)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setOnClickListener { showServerDialog() }
        }
        serverCard.addView(label("Servidor", 12f, textSub))
        serverNameTv = label("Nenhum perfil", 17f, textMain, true)
        serverCard.addView(serverNameTv)
        serverSubTv = label("Toque para adicionar um servidor", 12f, textSub)
        serverCard.addView(serverSubTv)
        root.addView(serverCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        // Linha de botoes
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chip("Usuário e senha") { showCredentialsDialog() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(5) })
        optionsChip = chip("Opções") { showOptionsDialog() }
        row.addView(optionsChip,
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(5) })
        root.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val frame = FrameLayout(this)
        frame.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        lockOverlay = buildLockOverlay()
        lockOverlay.visibility = if (lockEnabled()) View.VISIBLE else View.GONE
        frame.addView(lockOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        if (lockEnabled()) setSecure(true)

        applyMode()
        applyState(UiState.IDLE, "Desconectado")
        refreshServerCard()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Primeiro uso: oferece adicionar um servidor
        if (savedInstanceState == null && allProfiles().isEmpty() && !lockEnabled()) {
            handler.post { showAddDialog() }
        }
    }

    private fun statBox(name: String, value: TextView, total: TextView, accent: Int): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(card, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(label(name, 12f, accent, true))
            addView(value)
            addView(total)
        }

    override fun onResume() {
        super.onResume()
        refreshServerCard()
        lastTs = 0L
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
        VpnStatus.addLogListener(logListener)

        if (lockEnabled()) {
            setSecure(true)
            val now = SystemClock.elapsedRealtime()
            if (!locked && (!unlocked || now - leftAt > 30000)) {
                locked = true
                lockOverlay.visibility = View.VISIBLE
                launchUnlock()
            }
        } else {
            locked = false
            lockOverlay.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        leftAt = SystemClock.elapsedRealtime()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
        VpnStatus.removeLogListener(logListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        pulse?.cancel()
        handler.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------- Estado

    private fun resetSpeed() {
        lastTs = 0L
        lastIn = 0L
        lastOut = 0L
        downTv.text = "0 B/s"
        upTv.text = "0 B/s"
        downTotalTv.text = "Total: 0 B"
        upTotalTv.text = "Total: 0 B"
        speedLine.text = "↓ 0 B/s     ↑ 0 B/s"
        graph.clear()
    }

    private fun applyState(state: UiState, message: String, errorColor: Boolean = false) {
        val previous = uiState
        uiState = state
        if (state == UiState.CONNECTED) clearHint()
        if (state == UiState.IDLE && message == "Desconectado" &&
            SystemClock.elapsedRealtime() - hintAt > 6000) clearHint()
        val color = when (state) {
            UiState.CONNECTED -> green
            UiState.CONNECTING -> amber
            UiState.IDLE -> idleColor
        }
        connectBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#141A33"))
            setStroke(dp(8), color)
        }
        connectBtn.text = when (state) {
            UiState.CONNECTED -> "DESCONECTAR"
            UiState.CONNECTING -> "CANCELAR"
            UiState.IDLE -> "CONECTAR"
        }
        connectBtn.setTextColor(if (state != UiState.IDLE) color else textMain)
        statusTv.text = message
        statusTv.setTextColor(if (errorColor) red else textMain)

        pulse?.cancel()
        connectBtn.alpha = 1f
        if (state == UiState.CONNECTING) {
            pulse = ObjectAnimator.ofFloat(connectBtn, "alpha", 1f, 0.45f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }

        if (state == UiState.CONNECTED && previous != UiState.CONNECTED) {
            connectedSince = SystemClock.elapsedRealtime()
            lastTs = 0L
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } else if (state != UiState.CONNECTED) {
            handler.removeCallbacks(ticker)
            timerTv.text = "00:00:00"
            resetSpeed()
        }
    }

    override fun updateState(
        state: String?, logmessage: String?, localizedResId: Int,
        level: ConnectionStatus?, intent: Intent?
    ) {
        runOnUiThread {
            when (level) {
                ConnectionStatus.LEVEL_CONNECTED ->
                    applyState(UiState.CONNECTED, "Conectado")
                ConnectionStatus.LEVEL_NOTCONNECTED, null, ConnectionStatus.UNKNOWN_LEVEL ->
                    applyState(UiState.IDLE, "Desconectado")
                ConnectionStatus.LEVEL_AUTH_FAILED ->
                {
                    applyState(UiState.IDLE, "Falha de autenticação", true)
                    showHint("Usuário ou senha incorretos. Toque em “Usuário e senha” para corrigir.")
                }
                ConnectionStatus.LEVEL_NONETWORK ->
                {
                    applyState(UiState.CONNECTING, "Sem rede, aguardando…")
                    showHint("Sem internet no momento. Verifique seu Wi-Fi ou dados móveis.")
                }
                ConnectionStatus.LEVEL_VPNPAUSED ->
                    applyState(UiState.CONNECTING, "Pausado")
                else ->
                    applyState(UiState.CONNECTING, "Conectando…")
            }
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        val now = SystemClock.elapsedRealtime()
        runOnUiThread {
            var dl = 0.0
            var ul = 0.0
            if (lastTs != 0L && now > lastTs) {
                val dt = (now - lastTs) / 1000.0
                if (inBytes >= lastIn) dl = (inBytes - lastIn) / dt
                if (outBytes >= lastOut) ul = (outBytes - lastOut) / dt
            }
            lastTs = now
            lastIn = inBytes
            lastOut = outBytes
            downTv.text = humanSpeed(dl)
            upTv.text = humanSpeed(ul)
            downTotalTv.text = "Total: " + humanBytes(inBytes)
            upTotalTv.text = "Total: " + humanBytes(outBytes)
            speedLine.text = "↓ ${humanSpeed(dl)}     ↑ ${humanSpeed(ul)}"
            graph.push(dl.toFloat(), ul.toFloat())
        }
    }

    private fun humanBytes(b: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$b B" else String.format("%.1f %s", v, units[i])
    }

    private fun humanSpeed(bps: Double): String {
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = bps
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) String.format("%.0f %s", v, units[i]) else String.format("%.1f %s", v, units[i])
    }

    // ------------------------------------------------------- Perfis

    private fun prefs() = getSharedPreferences("meuvpn", Context.MODE_PRIVATE)

    private fun allProfiles(): List<VpnProfile> =
        ProfileManager.getInstance(this).profiles.toList().sortedBy { it.mName.lowercase() }

    private fun selectedProfile(): VpnProfile? {
        val list = allProfiles()
        if (list.isEmpty()) return null
        val id = prefs().getString("selected", null)
        return list.firstOrNull { it.uuidString == id } ?: list.first()
    }

    private fun refreshServerCard() {
        val p = selectedProfile()
        if (p == null) {
            serverNameTv.text = "Nenhum perfil"
            serverSubTv.text = "Toque para adicionar um servidor"
        } else {
            serverNameTv.text = p.mName
            serverSubTv.text = if (p.mUsername.isNullOrEmpty()) "Sem usuário salvo" else "Usuário: ${p.mUsername}"
        }
    }

    private fun showServerDialog() {
        val list = allProfiles()
        val names = ArrayList<String>()
        list.forEach {
            val ms = pingCache[it.uuidString]
            val suffix = when {
                ms == null -> ""
                ms < 0 -> "   •   sem resposta"
                else -> "   •   $ms ms"
            }
            names.add(it.mName + suffix)
        }
        names.add("⚡ Testar ping / melhor servidor")
        names.add("➕ Adicionar servidor")
        val sel = selectedProfile()
        if (sel != null) names.add("🗑 Remover \"${sel.mName}\"")

        AlertDialog.Builder(this)
            .setTitle("Servidor")
            .setItems(names.toTypedArray()) { _, which ->
                when {
                    which < list.size -> {
                        prefs().edit().putString("selected", list[which].uuidString).apply()
                        refreshServerCard()
                    }
                    which == list.size -> testPings()
                    which == list.size + 1 -> showAddDialog()
                    else -> sel?.let { removeProfile(it) }
                }
            }
            .show()
    }

    private fun showAddDialog() {
        val items = arrayOf(
            "📁 Importar arquivo .ovpn",
            "📋 Colar configuração",
            "⚙ Configuração manual"
        )
        AlertDialog.Builder(this)
            .setTitle("Adicionar servidor")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> importLauncher.launch(arrayOf("*/*"))
                    1 -> showPasteDialog()
                    else -> showManualDialog()
                }
            }
            .show()
    }

    private fun removeProfile(p: VpnProfile) {
        AlertDialog.Builder(this)
            .setMessage("Remover o perfil \"${p.mName}\"?")
            .setPositiveButton("Remover") { _, _ ->
                ProfileManager.getInstance(this).removeProfile(this, p)
                refreshServerCard()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i)
            }
        } catch (e: Exception) { }
        var n = name ?: "VPN"
        if (n.endsWith(".ovpn", true)) n = n.dropLast(5)
        return n
    }

    private fun importFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
            importConfig(text, displayName(uri))
        } catch (e: Exception) {
            showError("Erro ao ler o arquivo", e.localizedMessage ?: e.toString())
        }
    }

    /** Converte o texto de uma configuracao OpenVPN em perfil e salva. */
    private fun importConfig(text: String, baseName: String): Boolean {
        return try {
            val cp = ConfigParser()
            cp.parseConfig(StringReader(text))
            val profile = cp.convertProfile()

            val vpl = ProfileManager.getInstance(this)
            val base = if (baseName.isBlank()) "VPN" else baseName.trim()
            var name = base
            var n = 2
            while (vpl.getProfileByName(name) != null) { name = "$base ($n)"; n++ }
            profile.mName = name

            vpl.addProfile(profile)
            ProfileManager.saveProfile(this, profile)
            vpl.saveProfileList(this)

            prefs().edit().putString("selected", profile.uuidString).apply()
            refreshServerCard()
            toast("Perfil adicionado: $name")
            true
        } catch (e: Exception) {
            showError("Erro na configuração", e.localizedMessage ?: e.toString())
            false
        }
    }

    // --------------------------------------------- Colar configuracao

    private fun showPasteDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = field("Nome do servidor")
        val cfg = field("Cole aqui o conteúdo do .ovpn", 8)
        box.addView(name)
        box.addView(cfg)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Colar configuração")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Adicionar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = cfg.text.toString()
            if (text.isBlank()) {
                toast("Cole a configuração")
            } else if (importConfig(text, name.text.toString().ifBlank { "VPN" })) {
                dlg.dismiss()
            }
        }
    }

    // --------------------------------------------- Configuracao manual

    private fun pem(tag: String, body: String): String =
        "<$tag>\n${body.trim()}\n</$tag>\n"

    private fun showManualDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }

        val name = field("Nome (ex: Servidor Brasil)")
        val host = field("Servidor (IP ou domínio)", input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field("Porta", input = InputType.TYPE_CLASS_NUMBER).apply { setText("1194") }

        val proto = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rUdp = RadioButton(this).apply { text = "UDP"; id = View.generateViewId() }
        val rTcp = RadioButton(this).apply { text = "TCP"; id = View.generateViewId() }
        proto.addView(rUdp)
        proto.addView(rTcp)
        proto.check(rUdp.id)
        proto.setOnCheckedChangeListener { _, checked ->
            val cur = port.text.toString()
            if (checked == rTcp.id && cur == "1194") port.setText("443")
            if (checked == rUdp.id && cur == "443") port.setText("1194")
        }

        val user = field("Usuário (opcional)")
        val pass = field("Senha (opcional)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val ca = field("Certificado CA (-----BEGIN CERTIFICATE-----…)", 4)
        val cert = field("Certificado do cliente (opcional)", 3)
        val key = field("Chave privada do cliente (opcional)", 3)

        val tls = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val tNone = RadioButton(this).apply { text = "Nenhuma"; id = View.generateViewId() }
        val tCrypt = RadioButton(this).apply { text = "tls-crypt (criptografa o handshake TLS)"; id = View.generateViewId() }
        val tAuth = RadioButton(this).apply { text = "tls-auth (autentica o handshake TLS)"; id = View.generateViewId() }
        tls.addView(tNone)
        tls.addView(tCrypt)
        tls.addView(tAuth)
        tls.check(tNone.id)
        val tlsKey = field("Chave estática (-----BEGIN OpenVPN Static key V1-----…)", 4)

        val ciphers = arrayOf(
            "Automático", "AES-256-GCM", "AES-128-GCM", "CHACHA20-POLY1305", "AES-256-CBC", "AES-128-CBC"
        )
        val cipher = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ciphers)
        }

        val proxy = field("Proxy HTTP (opcional): host porta  ex: 10.0.0.1 8080")
        val extra = field("Opções extras do OpenVPN (uma por linha)", 3)

        box.addView(section("SERVIDOR"))
        box.addView(name); box.addView(host); box.addView(port); box.addView(proto)
        box.addView(section("AUTENTICAÇÃO"))
        box.addView(user); box.addView(pass)
        box.addView(section("CERTIFICADOS"))
        box.addView(ca); box.addView(cert); box.addView(key)
        box.addView(section("PROTEÇÃO TLS"))
        box.addView(tls); box.addView(tlsKey)
        box.addView(section("CRIPTOGRAFIA"))
        box.addView(cipher)
        box.addView(section("AVANÇADO"))
        box.addView(proxy); box.addView(extra)

        val dlg = AlertDialog.Builder(this)
            .setTitle("Configuração manual")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val h = host.text.toString().trim()
            val pt = port.text.toString().trim().toIntOrNull()
            if (h.isEmpty()) { toast("Informe o servidor"); return@setOnClickListener }
            if (pt == null || pt < 1 || pt > 65535) { toast("Porta inválida"); return@setOnClickListener }
            if (ca.text.isBlank()) { toast("Cole o certificado CA"); return@setOnClickListener }
            val tlsChoice = tls.checkedRadioButtonId
            if (tlsChoice != tNone.id && tlsKey.text.isBlank()) {
                toast("Cole a chave do tls-crypt/tls-auth"); return@setOnClickListener
            }
            if (cert.text.isNotBlank() != key.text.isNotBlank()) {
                toast("Informe certificado E chave do cliente"); return@setOnClickListener
            }

            val sb = StringBuilder()
            sb.append("client\ndev tun\n")
            sb.append("proto ").append(if (proto.checkedRadioButtonId == rTcp.id) "tcp" else "udp").append('\n')
            sb.append("remote $h $pt\n")
            val px = proxy.text.toString().trim()
            if (px.isNotEmpty()) sb.append("http-proxy $px\n")
            sb.append("resolv-retry infinite\nnobind\npersist-key\npersist-tun\n")
            sb.append("remote-cert-tls server\nverb 3\n")
            val c = cipher.selectedItem.toString()
            if (c != "Automático") {
                sb.append("cipher $c\n")
                if (c.contains("GCM") || c.contains("POLY1305")) sb.append("data-ciphers $c\n")
            }
            sb.append(pem("ca", ca.text.toString()))
            if (cert.text.isNotBlank()) {
                sb.append(pem("cert", cert.text.toString()))
                sb.append(pem("key", key.text.toString()))
            }
            when (tlsChoice) {
                tCrypt.id -> sb.append(pem("tls-crypt", tlsKey.text.toString()))
                tAuth.id -> {
                    sb.append("key-direction 1\n")
                    sb.append(pem("tls-auth", tlsKey.text.toString()))
                }
            }
            val u = user.text.toString()
            if (u.isNotEmpty()) {
                sb.append("<auth-user-pass>\n$u\n${pass.text}\n</auth-user-pass>\n")
            }
            val ex = extra.text.toString().trim()
            if (ex.isNotEmpty()) sb.append(ex).append('\n')

            val nm = name.text.toString().ifBlank { h }
            if (importConfig(sb.toString(), nm)) dlg.dismiss()
        }
    }

    // --------------------------------------------------- Credenciais

    private fun showCredentialsDialog() {
        val p = selectedProfile()
        if (p == null) { toast("Adicione um servidor primeiro"); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val user = EditText(this).apply {
            hint = "Usuário"
            setText(p.mUsername ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pass = EditText(this).apply {
            hint = "Senha"
            setText(p.mPassword ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(user)
        box.addView(pass)
        AlertDialog.Builder(this)
            .setTitle("Credenciais")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                p.mUsername = user.text.toString()
                p.mPassword = pass.text.toString()
                if (p.mUsername.isNotEmpty()) {
                    when (p.mAuthenticationType) {
                        VpnProfile.TYPE_CERTIFICATES -> p.mAuthenticationType = VpnProfile.TYPE_USERPASS_CERTIFICATES
                        VpnProfile.TYPE_KEYSTORE -> p.mAuthenticationType = VpnProfile.TYPE_USERPASS_KEYSTORE
                        VpnProfile.TYPE_PKCS12 -> p.mAuthenticationType = VpnProfile.TYPE_USERPASS_PKCS12
                    }
                }
                ProfileManager.saveProfile(this, p)
                refreshServerCard()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------- Opcoes

    private fun showOptionsDialog() {
        val sel = selectedProfile()
        val tuned = sel != null && isTuned(sel)
        val items = arrayOf(
            "DNS personalizado",
            "Apps que usam a VPN",
            if (tuned) "⚡ Otimizar velocidade: LIGADO" else "⚡ Otimizar velocidade: DESLIGADO",
            "Dicas para mais velocidade",
            if (lockEnabled()) "🔒 Bloqueio do app (PIN/digital): LIGADO" else "🔓 Bloqueio do app (PIN/digital): DESLIGADO",
            "Bloquear internet sem VPN",
            "Ver meu IP público"
        )
        AlertDialog.Builder(this)
            .setTitle("Opções")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDnsDialog()
                    1 -> showAppsDialog()
                    2 -> toggleTuning()
                    3 -> showSpeedTips()
                    4 -> toggleLock()
                    5 -> showKillSwitchDialog()
                    else -> showIp()
                }
            }
            .show()
    }

    private fun showDnsDialog() {
        val p = selectedProfile()
        if (p == null) { toast("Adicione um servidor primeiro"); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val d1 = field("DNS 1 (ex: 1.1.1.1)", input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val d2 = field("DNS 2 (ex: 8.8.8.8)", input = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        if (p.mOverrideDNS) {
            d1.setText(p.mDNS1 ?: "")
            d2.setText(p.mDNS2 ?: "")
        }
        box.addView(d1)
        box.addView(d2)
        AlertDialog.Builder(this)
            .setTitle("DNS personalizado")
            .setMessage("Deixe vazio para usar o DNS do servidor.")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val a = d1.text.toString().trim()
                if (a.isEmpty()) {
                    p.mOverrideDNS = false
                } else {
                    p.mOverrideDNS = true
                    p.mDNS1 = a
                    p.mDNS2 = d2.text.toString().trim()
                }
                ProfileManager.saveProfile(this, p)
                toast("DNS salvo. Reconecte para aplicar.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAppsDialog() {
        val p = selectedProfile()
        if (p == null) { toast("Adicione um servidor primeiro"); return }
        val modes = arrayOf(
            "Todos os apps usam a VPN (padrão)",
            "Somente os apps escolhidos usam a VPN",
            "Todos, exceto os apps escolhidos"
        )
        val cur = when {
            p.mAllowedAppsVpn.isEmpty() -> 0
            p.mAllowedAppsVpnAreDisallowed -> 2
            else -> 1
        }
        AlertDialog.Builder(this)
            .setTitle("Apps que usam a VPN")
            .setSingleChoiceItems(modes, cur) { d, which ->
                d.dismiss()
                if (which == 0) {
                    p.mAllowedAppsVpn.clear()
                    p.mAllowedAppsVpnAreDisallowed = true
                    ProfileManager.saveProfile(this, p)
                    toast("Todos os apps usarão a VPN. Reconecte para aplicar.")
                } else {
                    pickApps(p, which == 2)
                }
            }
            .show()
    }

    private fun pickApps(p: VpnProfile, disallowed: Boolean) {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
            .map { Pair(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) { toast("Nenhum app encontrado"); return }
        val labels = apps.map { it.second }.toTypedArray()
        val checked = BooleanArray(apps.size) { p.mAllowedAppsVpn.contains(apps[it].first) }
        AlertDialog.Builder(this)
            .setTitle(if (disallowed) "Apps que NÃO usarão a VPN" else "Apps que usarão a VPN")
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Salvar") { _, _ ->
                p.mAllowedAppsVpn.clear()
                for (i in apps.indices) if (checked[i]) p.mAllowedAppsVpn.add(apps[i].first)
                p.mAllowedAppsVpnAreDisallowed = disallowed
                ProfileManager.saveProfile(this, p)
                toast("Salvo. Reconecte para aplicar.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showKillSwitchDialog() {
        AlertDialog.Builder(this)
            .setTitle("Bloquear internet sem VPN")
            .setMessage(
                "Para impedir qualquer conexão fora da VPN, abra os ajustes de VPN do Android, " +
                "toque na engrenagem ao lado de PARA VPN e ative \"VPN sempre ativa\" e " +
                "\"Bloquear conexões sem VPN\"."
            )
            .setPositiveButton("Abrir ajustes") { _, _ ->
                try {
                    startActivity(Intent("android.settings.VPN_SETTINGS"))
                } catch (e: Exception) {
                    toast("Não foi possível abrir os ajustes")
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showIp() {
        toast("Consultando…")
        Thread {
            val r = try {
                URL("https://api.ipify.org").openStream().bufferedReader().use { it.readText().trim() }
            } catch (e: Exception) {
                "Erro: " + (e.localizedMessage ?: e.toString())
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("IP público")
                    .setMessage(r)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    // ------------------------------------------------------- Conexao

    private fun onConnectClicked() {
        if (uiState != UiState.IDLE) {
            // Abre o dialogo oficial de desconexao do projeto
            startActivity(Intent(this, DisconnectVPN::class.java))
            return
        }
        val p = selectedProfile()
        if (p == null) {
            toast("Adicione um servidor primeiro")
            showAddDialog()
            return
        }
        clearHint()
        ProfileManager.saveProfile(this, p)
        val intent = Intent(this, LaunchVPN::class.java)
        intent.putExtra(LaunchVPN.EXTRA_KEY, p.uuid.toString())
        intent.putExtra(OpenVPNService.EXTRA_START_REASON, "meu vpn")
        intent.action = Intent.ACTION_MAIN
        startActivity(intent)
    }

    // ------------------------------------------- Modo simples / avancado

    private fun toggleMode() {
        advanced = !advanced
        prefs().edit().putBoolean("advanced", advanced).apply()
        applyMode()
    }

    private fun applyMode() {
        val adv = if (advanced) View.VISIBLE else View.GONE
        statsRow.visibility = adv
        graph.visibility = adv
        optionsChip.visibility = adv
        speedLine.visibility = if (advanced) View.GONE else View.VISIBLE
        modeChip.text = if (advanced) "Simples" else "Avançado"
    }

    // ------------------------------------------------- Mensagens de erro

    private fun showHint(msg: String) {
        hintAt = SystemClock.elapsedRealtime()
        hintTv.text = msg
        hintTv.visibility = View.VISIBLE
    }

    private fun clearHint() {
        hintTv.text = ""
        hintTv.visibility = View.GONE
    }

    private fun friendly(m: String): String? {
        val s = m.lowercase()
        return when {
            "auth_failed" in s || "auth failed" in s || "authentication failed" in s ->
                "Usuário ou senha incorretos. Toque em “Usuário e senha” para corrigir."
            "cannot resolve host" in s || "resolve: cannot" in s || "unknownhost" in s ->
                "Não achei o servidor. Confira o endereço e sua internet."
            "connection refused" in s || "econnrefused" in s ->
                "O servidor recusou a conexão. Confira a porta ou se ele está ligado."
            "verify error" in s || "certificate verify failed" in s || "unable to get local issuer" in s ->
                "O certificado do servidor não confere. Confira o certificado CA."
            "tls key negotiation failed" in s || "tls handshake failed" in s ->
                "O servidor não respondeu. Confira endereço e porta, ou tente TCP na porta 443."
            "network is unreachable" in s || "no network" in s ->
                "Sem internet no momento."
            "cipher" in s && ("mismatch" in s || "not supported" in s || "no common" in s) ->
                "A criptografia não é compatível com o servidor. Tente a cifra “Automático”."
            else -> null
        }
    }

    // ------------------------------------------------------------- Ping

    private fun profileEndpoint(p: VpnProfile): Pair<String, Int>? {
        val c = p.mConnections.firstOrNull() ?: return null
        return Pair(c.mServerName, c.mServerPort.toIntOrNull() ?: 1194)
    }

    private fun measure(host: String, port: Int): Long {
        return try {
            val addr = InetSocketAddress(host, port)
            if (addr.isUnresolved) return -1L
            val s = Socket()
            val t0 = SystemClock.elapsedRealtime()
            try {
                s.connect(addr, 3000)
            } catch (e: ConnectException) {
                val m = e.message ?: ""
                if (!m.contains("refused", true) && !m.contains("ECONNREFUSED")) throw e
            } finally {
                try { s.close() } catch (e: Exception) { }
            }
            SystemClock.elapsedRealtime() - t0
        } catch (e: Exception) {
            -1L
        }
    }

    private fun testPings() {
        if (uiState != UiState.IDLE) {
            toast("Desconecte a VPN para testar o ping")
            return
        }
        val list = allProfiles()
        if (list.isEmpty()) { toast("Adicione um servidor primeiro"); return }
        toast("Testando ping…")
        Thread {
            val workers = list.map { p ->
                Thread {
                    val ep = profileEndpoint(p)
                    pingCache[p.uuidString] = if (ep == null) -1L else measure(ep.first, ep.second)
                }.also { it.start() }
            }
            workers.forEach { try { it.join(5000) } catch (e: Exception) { } }
            runOnUiThread { showPingResults(list) }
        }.start()
    }

    private fun showPingResults(list: List<VpnProfile>) {
        val sorted = list.sortedBy {
            val ms = pingCache[it.uuidString] ?: -1L
            if (ms < 0) Long.MAX_VALUE else ms
        }
        val best = sorted.firstOrNull { (pingCache[it.uuidString] ?: -1L) >= 0 }
        val labels = sorted.map {
            val ms = pingCache[it.uuidString] ?: -1L
            val star = if (it === best) "⚡ " else ""
            if (ms < 0) "${it.mName}  —  sem resposta" else "$star${it.mName}  —  $ms ms"
        }.toTypedArray()
        val b = AlertDialog.Builder(this)
            .setTitle("Ping (menor é melhor)")
            .setItems(labels) { _, which ->
                prefs().edit().putString("selected", sorted[which].uuidString).apply()
                refreshServerCard()
            }
            .setNegativeButton("Fechar", null)
        if (best != null) {
            b.setPositiveButton("Usar o melhor") { _, _ ->
                prefs().edit().putString("selected", best.uuidString).apply()
                refreshServerCard()
                toast("Servidor escolhido: ${best.mName}")
            }
        }
        b.show()
    }

    // ---------------------------------------------------- Velocidade

    private val tuning = "sndbuf 524288\nrcvbuf 524288"

    private fun isTuned(p: VpnProfile): Boolean =
        p.mUseCustomConfig && p.mCustomConfigOptions.contains("sndbuf 524288")

    private fun toggleTuning() {
        val p = selectedProfile()
        if (p == null) { toast("Adicione um servidor primeiro"); return }
        if (isTuned(p)) {
            p.mCustomConfigOptions = p.mCustomConfigOptions
                .replace("sndbuf 524288", "").replace("rcvbuf 524288", "").trim()
            if (p.mCustomConfigOptions.isEmpty()) p.mUseCustomConfig = false
            toast("Otimização desligada. Reconecte para aplicar.")
        } else {
            p.mCustomConfigOptions = (p.mCustomConfigOptions + "\n" + tuning).trim()
            p.mUseCustomConfig = true
            toast("Otimização ligada. Reconecte para aplicar.")
        }
        ProfileManager.saveProfile(this, p)
    }

    private fun showSpeedTips() {
        AlertDialog.Builder(this)
            .setTitle("Dicas para mais velocidade")
            .setMessage(
                "• Use o servidor mais perto de você (veja o ping em Servidor).\n\n" +
                "• Prefira UDP; use TCP só se o UDP não conectar.\n\n" +
                "• Na configuração manual, a cifra AES-GCM costuma ser a mais rápida.\n\n" +
                "• \"Otimizar velocidade\" aumenta os buffers de rede. O ganho varia.\n\n" +
                "• A velocidade final depende do servidor e da sua internet; o app não " +
                "consegue ultrapassar esse limite."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------ Bloqueio do app

    private fun lockEnabled(): Boolean = prefs().getBoolean("lock", false)

    private fun keyguard(): KeyguardManager =
        getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private fun setSecure(on: Boolean) {
        if (on) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun toggleLock() {
        if (lockEnabled()) {
            prefs().edit().putBoolean("lock", false).apply()
            setSecure(false)
            toast("Bloqueio desligado")
            return
        }
        if (!keyguard().isDeviceSecure) {
            showError(
                "Sem bloqueio de tela",
                "Configure um PIN, padrão, senha ou digital nos ajustes do Android para usar esta opção."
            )
            return
        }
        prefs().edit().putBoolean("lock", true).apply()
        setSecure(true)
        unlocked = true
        toast("Bloqueio ligado")
    }

    private fun launchUnlock() {
        val i = keyguard().createConfirmDeviceCredentialIntent(
            "PARA VPN", "Confirme que é você para abrir o app")
        if (i == null) {
            unlock()
            return
        }
        try {
            unlockLauncher.launch(i)
        } catch (e: Exception) {
            unlock()
        }
    }

    private fun unlock() {
        locked = false
        unlocked = true
        lockOverlay.visibility = View.GONE
    }

    private fun buildLockOverlay(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            isClickable = true
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_para_fg)
            }, LinearLayout.LayoutParams(dp(140), dp(140)))
            addView(label("PARA VPN bloqueado", 18f, textMain, true).apply {
                gravity = Gravity.CENTER
            })
            addView(chip("Desbloquear") { launchUnlock() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
                    .apply { topMargin = dp(20) })
        }

    // ----------------------------------------------- Grafico de velocidade

    private class SpeedGraph(ctx: Context, private val downColor: Int, private val upColor: Int) : View(ctx) {
        private val n = 40
        private val d = FloatArray(n)
        private val u = FloatArray(n)
        private val density = ctx.resources.displayMetrics.density
        private val pd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; color = downColor
            strokeJoin = Paint.Join.ROUND
        }
        private val pu = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density; color = upColor
            strokeJoin = Paint.Join.ROUND
        }
        private val base = Paint().apply { color = Color.parseColor("#2A3256"); strokeWidth = density }

        fun push(down: Float, up: Float) {
            System.arraycopy(d, 1, d, 0, n - 1)
            System.arraycopy(u, 1, u, 0, n - 1)
            d[n - 1] = down
            u[n - 1] = up
            invalidate()
        }

        fun clear() {
            d.fill(0f)
            u.fill(0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val pad = 8f * density
            var max = 10240f
            for (i in 0 until n) { if (d[i] > max) max = d[i]; if (u[i] > max) max = u[i] }
            canvas.drawLine(pad, h - pad, w - pad, h - pad, base)
            drawLine(canvas, d, pd, w, h, pad, max)
            drawLine(canvas, u, pu, w, h, pad, max)
        }

        private fun drawLine(c: Canvas, v: FloatArray, paint: Paint, w: Float, h: Float, pad: Float, max: Float) {
            val path = Path()
            val step = (w - 2 * pad) / (n - 1)
            for (i in 0 until n) {
                val x = pad + i * step
                val y = (h - pad) - (v[i] / max) * (h - 2 * pad)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, paint)
        }
    }
}
