/*
 * Tela simples estilo "X-VPN" para o fork do ics-openvpn.
 * Substitui o MainActivity original (mesmo pacote e mesmo nome,
 * entao o AndroidManifest e a notificacao continuam funcionando).
 * Licenca: GPLv2 (como o projeto original).
 */
package de.blinkt.openvpn.activities

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VpnStatus
import java.io.InputStreamReader

class MainActivity : AppCompatActivity(), VpnStatus.StateListener, VpnStatus.ByteCountListener {

    private enum class UiState { IDLE, CONNECTING, CONNECTED }

    // Cores
    private val bg = Color.parseColor("#0E1224")
    private val card = Color.parseColor("#1A2038")
    private val textMain = Color.parseColor("#FFFFFF")
    private val textSub = Color.parseColor("#8E97B8")
    private val green = Color.parseColor("#19D38B")
    private val amber = Color.parseColor("#FFB020")
    private val idleColor = Color.parseColor("#3D4A7A")
    private val red = Color.parseColor("#FF5A5F")

    // Views
    private lateinit var connectBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var timerTv: TextView
    private lateinit var downTv: TextView
    private lateinit var upTv: TextView
    private lateinit var serverNameTv: TextView
    private lateinit var serverSubTv: TextView

    private var pulse: ObjectAnimator? = null
    private var uiState = UiState.IDLE
    private var connectedSince = 0L
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
            if (uri != null) importProfile(uri)
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(20), b.top + dp(12), dp(20), b.bottom + dp(16))
            insets
        }

        // Barra superior
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = label("Meu VPN", 22f, textMain, true)
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val importBtn = Button(this).apply {
            text = "+ .ovpn"
            isAllCaps = false
            setTextColor(textMain)
            background = rounded(card, 20)
            setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        }
        top.addView(importBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(top)

        // Area central: botao grande
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
        center.addView(connectBtn, LinearLayout.LayoutParams(dp(200), dp(200)))

        statusTv = label("Desconectado", 18f, textMain, true).apply { gravity = Gravity.CENTER }
        center.addView(statusTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(28) })

        timerTv = label("00:00:00", 28f, textSub).apply { gravity = Gravity.CENTER }
        center.addView(timerTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // Download / Upload
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        downTv = label("0 B", 16f, textMain, true)
        upTv = label("0 B", 16f, textMain, true)
        stats.addView(statBox("↓ Download", downTv), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { rightMargin = dp(8) })
        stats.addView(statBox("↑ Upload", upTv), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(8) })
        center.addView(stats, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(28) })

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
        serverSubTv = label("Toque para escolher ou importar", 12f, textSub)
        serverCard.addView(serverSubTv)
        root.addView(serverCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val credBtn = Button(this).apply {
            text = "Usuário e senha"
            isAllCaps = false
            setTextColor(textMain)
            background = rounded(card, 16)
            setOnClickListener { showCredentialsDialog() }
        }
        root.addView(credBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(10) })

        setContentView(root)
        applyState(UiState.IDLE, "Desconectado")
        refreshServerCard()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun statBox(name: String, value: TextView): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(card, 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(label(name, 12f, textSub))
            addView(value)
        }

    override fun onResume() {
        super.onResume()
        refreshServerCard()
        VpnStatus.addStateListener(this)
        VpnStatus.addByteCountListener(this)
    }

    override fun onPause() {
        super.onPause()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeByteCountListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        pulse?.cancel()
        handler.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------- Estado

    private fun applyState(state: UiState, message: String, errorColor: Boolean = false) {
        val previous = uiState
        uiState = state
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
        connectBtn.setTextColor(color.takeIf { state != UiState.IDLE } ?: textMain)
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
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } else if (state != UiState.CONNECTED) {
            handler.removeCallbacks(ticker)
            timerTv.text = "00:00:00"
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
                    applyState(UiState.IDLE, "Falha de autenticação", true)
                ConnectionStatus.LEVEL_NONETWORK ->
                    applyState(UiState.CONNECTING, "Sem rede, aguardando…")
                ConnectionStatus.LEVEL_VPNPAUSED ->
                    applyState(UiState.CONNECTING, "Pausado")
                else ->
                    applyState(UiState.CONNECTING, "Conectando…")
            }
        }
    }

    override fun setConnectedVPN(uuid: String?) {}

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        runOnUiThread {
            downTv.text = humanBytes(inBytes)
            upTv.text = humanBytes(outBytes)
        }
    }

    private fun humanBytes(b: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${b} B" else String.format("%.1f %s", v, units[i])
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
            serverSubTv.text = "Toque para importar um arquivo .ovpn"
        } else {
            serverNameTv.text = p.mName
            serverSubTv.text = if (p.mUsername.isNullOrEmpty()) "Sem usuário salvo" else "Usuário: ${p.mUsername}"
        }
    }

    private fun showServerDialog() {
        val list = allProfiles()
        val names = ArrayList<String>()
        list.forEach { names.add(it.mName) }
        names.add("➕ Importar novo .ovpn")
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
                    which == list.size -> importLauncher.launch(arrayOf("*/*"))
                    else -> sel?.let { removeProfile(it) }
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

    private fun importProfile(uri: Uri) {
        try {
            val cp = ConfigParser()
            contentResolver.openInputStream(uri)!!.use { cp.parseConfig(InputStreamReader(it)) }
            val profile = cp.convertProfile()

            val vpl = ProfileManager.getInstance(this)
            val base = displayName(uri)
            var name = base
            var n = 2
            while (vpl.getProfileByName(name) != null) { name = "$base ($n)"; n++ }
            profile.mName = name

            vpl.addProfile(profile)
            ProfileManager.saveProfile(this, profile)
            vpl.saveProfileList(this)

            prefs().edit().putString("selected", profile.uuidString).apply()
            refreshServerCard()
            Toast.makeText(this, "Perfil importado: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Erro ao importar")
                .setMessage(e.localizedMessage ?: e.toString())
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showCredentialsDialog() {
        val p = selectedProfile()
        if (p == null) {
            Toast.makeText(this, "Importe um .ovpn primeiro", Toast.LENGTH_SHORT).show()
            return
        }
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

    // ------------------------------------------------------- Conexao

    private fun onConnectClicked() {
        if (uiState != UiState.IDLE) {
            // Abre o dialogo oficial de desconexao do projeto
            startActivity(Intent(this, DisconnectVPN::class.java))
            return
        }
        val p = selectedProfile()
        if (p == null) {
            Toast.makeText(this, "Importe um arquivo .ovpn primeiro", Toast.LENGTH_SHORT).show()
            importLauncher.launch(arrayOf("*/*"))
            return
        }
        ProfileManager.saveProfile(this, p)
        val intent = Intent(this, LaunchVPN::class.java)
        intent.putExtra(LaunchVPN.EXTRA_KEY, p.uuid.toString())
        intent.putExtra(OpenVPNService.EXTRA_START_REASON, "meu vpn")
        intent.action = Intent.ACTION_MAIN
        startActivity(intent)
    }
}
