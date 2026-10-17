package io.github.dce.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.github.dce.android.archive.Archive
import io.github.dce.android.discord.Channel
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.discord.DiscordException
import io.github.dce.android.discord.Snowflake
import io.github.dce.android.discord.User
import io.github.dce.android.export.ChannelExporter
import io.github.dce.android.export.ExportFormat
import io.github.dce.android.export.ExportOptions
import io.github.dce.android.export.ExportTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_LOGIN = 1
        private const val REQUEST_NOTIFICATIONS = 3
    }

    private class PendingExport(val channel: Channel, val options: ExportOptions) {
        val baseName = ChannelExporter.defaultBaseName(channel)
    }

    private lateinit var account: TextView
    private lateinit var logout: Button
    private lateinit var loginPanel: View
    private lateinit var channelsPanel: View
    private lateinit var listStatus: TextView
    private lateinit var progressPanel: View
    private lateinit var progressTitle: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressCancel: Button
    private lateinit var progressDismiss: Button
    private lateinit var progressShare: Button
    private lateinit var progressQuickShare: Button
    private lateinit var progressOpen: Button

    private val adapter = ChannelAdapter()
    private var me: User? = null
    private var pendingExport: PendingExport? = null
    private var progressDismissed = false
    private val stateListener: (ExportState.Snapshot) -> Unit = ::renderExportState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets(findViewById(R.id.root))

        account = findViewById(R.id.account)
        logout = findViewById(R.id.logout)
        loginPanel = findViewById(R.id.login_panel)
        channelsPanel = findViewById(R.id.channels_panel)
        listStatus = findViewById(R.id.list_status)
        progressPanel = findViewById(R.id.progress_panel)
        progressTitle = findViewById(R.id.progress_title)
        progressText = findViewById(R.id.progress_text)
        progressBar = findViewById(R.id.progress_bar)
        progressCancel = findViewById(R.id.progress_cancel)
        progressDismiss = findViewById(R.id.progress_dismiss)
        progressShare = findViewById(R.id.progress_share)
        progressQuickShare = findViewById(R.id.progress_quick_share)
        progressOpen = findViewById(R.id.progress_open)

        findViewById<Button>(R.id.archive).setOnClickListener { startActivity(Intent(this, ArchiveActivity::class.java)) }

        findViewById<Button>(R.id.login_web).setOnClickListener {
            startActivityForResult(Intent(this, LoginActivity::class.java), REQUEST_LOGIN)
        }
        findViewById<Button>(R.id.token_submit).setOnClickListener {
            val input = findViewById<EditText>(R.id.token_input)
            val token = input.text.toString().trim()
            if (token.isNotEmpty()) {
                input.setText("")
                signIn(token)
            }
        }
        logout.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Remove the saved token from this device?")
                .setPositiveButton("Log out") { _, _ ->
                    TokenStore.set(this, null)
                    LoginActivity.clearWebSession()
                    refreshAuthState()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val list = findViewById<ListView>(R.id.channels)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showExportDialog(adapter.getItem(position)) }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
        })
        findViewById<Button>(R.id.refresh).setOnClickListener { loadChannels() }
        findViewById<Button>(R.id.by_id).setOnClickListener { promptChannelId() }

        progressCancel.setOnClickListener { ExportService.cancel(this) }
        progressDismiss.setOnClickListener {
            progressDismissed = true
            renderExportState(ExportState.current)
        }
        progressShare.setOnClickListener { completedEntry()?.let { ArchiveUi.share(this, listOf(it)) } }
        progressQuickShare.setOnClickListener { completedEntry()?.let { ArchiveUi.quickShare(this, it) } }
        progressOpen.setOnClickListener { completedEntry()?.let { ArchiveUi.open(this, it) } }

        refreshAuthState()
    }

    override fun onStart() {
        super.onStart()
        ExportState.observe(stateListener)
    }

    override fun onResume() {
        super.onResume()
        // The quick-share app may have changed in the share sheet
        renderExportState(ExportState.current)
    }

    override fun onStop() {
        ExportState.unobserve(stateListener)
        super.onStop()
    }

    private fun applySystemBarInsets(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
    }

    /* Authentication */

    private fun refreshAuthState() {
        val token = TokenStore.get(this)
        loginPanel.visibility = if (token == null) View.VISIBLE else View.GONE
        channelsPanel.visibility = if (token == null) View.GONE else View.VISIBLE
        logout.visibility = if (token == null) View.GONE else View.VISIBLE
        if (token == null) {
            me = null
            account.text = "Export Discord DMs"
            adapter.setChannels(emptyList())
        } else {
            loadChannels()
        }
    }

    private fun signIn(token: String) {
        account.text = "Checking token…"
        thread {
            val result = runCatching { DiscordClient(token).getCurrentUser() }
            runOnUiThread {
                result.onSuccess {
                    TokenStore.set(this, token)
                    refreshAuthState()
                }.onFailure {
                    account.text = "Export Discord DMs"
                    toast(it.message ?: "Couldn't sign in.")
                }
            }
        }
    }

    /* Channel list */

    private fun loadChannels() {
        val token = TokenStore.get(this) ?: return
        listStatus.visibility = View.VISIBLE
        listStatus.text = "Loading direct messages…"
        thread {
            val result = runCatching {
                val client = DiscordClient(token)
                client.getCurrentUser() to client.getDirectMessageChannels()
            }
            runOnUiThread {
                result.onSuccess { (user, channels) ->
                    me = user
                    account.text = "Signed in as ${user.displayName} (@${user.fullName})"
                    adapter.setChannels(channels)
                    listStatus.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
                    listStatus.text = "No open DMs. Use \"By ID\" to export a closed one."
                }.onFailure { error ->
                    if (error is DiscordException && error.message?.contains("token is invalid") == true) {
                        TokenStore.set(this, null)
                        toast("Your token is no longer valid. Please sign in again.")
                        refreshAuthState()
                    } else {
                        listStatus.text = "Couldn't load DMs: ${error.message}"
                    }
                }
            }
        }
    }

    private fun promptChannelId() {
        val input = EditText(this).apply {
            hint = "Channel ID or discord.com/channels/@me/… link"
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            val padding = dp(20)
            setPadding(padding, dp(8), padding, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Export by channel ID")
            .setMessage("In Discord, enable Developer Mode, then long-press a DM and copy its channel ID (or its link).")
            .setView(container)
            .setPositiveButton("Next") { _, _ ->
                val id = Regex("""(\d{15,25})/?$""").find(input.text.toString().trim())?.groupValues?.get(1)
                if (id == null) toast("That doesn't look like a channel ID.") else fetchChannelAndExport(id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchChannelAndExport(channelId: String) {
        val token = TokenStore.get(this) ?: return
        thread {
            val result = runCatching { DiscordClient(token).getChannel(channelId) }
            runOnUiThread {
                result.onSuccess(::showExportDialog).onFailure { toast(it.message ?: "Couldn't load the channel.") }
            }
        }
    }

    /* Export */

    private fun showExportDialog(channel: Channel) {
        if (ExportState.current.phase == ExportState.Phase.Running) {
            toast("An export is already running.")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_export, null)
        val formatGroup = view.findViewById<RadioGroup>(R.id.format)
        val download = view.findViewById<CheckBox>(R.id.download)
        val downloadHint = view.findViewById<TextView>(R.id.download_hint)
        val packagingSection = view.findViewById<View>(R.id.packaging_section)
        val themeSection = view.findViewById<View>(R.id.theme_section)
        val pace = view.findViewById<RadioGroup>(R.id.export_pace)
        pace.check(
            when (Settings.exportPageDelay(this)) {
                0L -> R.id.export_pace_fast
                1000L -> R.id.export_pace_normal
                else -> R.id.export_pace_gentle
            },
        )

        fun selectedFormat() = when (formatGroup.checkedRadioButtonId) {
            R.id.format_json -> ExportFormat.Json
            R.id.format_xml -> ExportFormat.Xml
            else -> ExportFormat.Html
        }

        fun updateVisibility() {
            val isHtml = selectedFormat() == ExportFormat.Html
            themeSection.visibility = if (isHtml) View.VISIBLE else View.GONE
            packagingSection.visibility = if (isHtml && download.isChecked) View.VISIBLE else View.GONE
            downloadHint.text = when {
                !download.isChecked -> "Text only. Attachments stay as links to Discord, which expire after a while."
                isHtml -> "Every attachment, avatar and emoji is saved with the export."
                else -> "Saved as a ZIP: the ${selectedFormat().extension.uppercase()} file plus a folder of attachments it links to."
            }
        }
        formatGroup.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        download.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        updateVisibility()

        AlertDialog.Builder(this)
            .setTitle("Export \"${channel.name}\"")
            .setView(view)
            .setPositiveButton("Export") { _, _ ->
                val options = ExportOptions(
                    format = selectedFormat(),
                    theme = if (view.findViewById<RadioGroup>(R.id.theme).checkedRadioButtonId == R.id.theme_light) {
                        ExportTheme.Light
                    } else {
                        ExportTheme.Dark
                    },
                    downloadAssets = download.isChecked,
                    singleFile = view.findViewById<RadioGroup>(R.id.packaging).checkedRadioButtonId != R.id.packaging_zip,
                    pageDelayMillis = when (pace.checkedRadioButtonId) {
                        R.id.export_pace_normal -> 1000L
                        R.id.export_pace_gentle -> 3000L
                        else -> 0L
                    },
                )
                Settings.setExportPageDelay(this, options.pageDelayMillis)
                pendingExport = PendingExport(channel, options)
                requestNotificationPermissionThenExport()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestNotificationPermissionThenExport() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            startPendingExport()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Progress notifications are nice to have; export either way
        if (requestCode == REQUEST_NOTIFICATIONS) startPendingExport()
    }

    private fun startPendingExport() {
        val export = pendingExport ?: return
        pendingExport = null
        progressDismissed = false
        ExportService.start(this, export.channel.id, export.channel.name, export.baseName, export.options)
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && requestCode == REQUEST_LOGIN) {
            data?.getStringExtra(LoginActivity.EXTRA_TOKEN)?.let(::signIn)
        }
    }

    private fun completedEntry() = ExportState.current.entryId?.let { Archive.of(this).get(it) }

    private fun renderExportState(state: ExportState.Snapshot) {
        val dismissed = state.phase != ExportState.Phase.Running && progressDismissed
        progressPanel.visibility = if (state.phase == ExportState.Phase.Idle || dismissed) View.GONE else View.VISIBLE

        val counts = "${state.messages} messages · ${state.assets} files"
        when (state.phase) {
            ExportState.Phase.Idle -> Unit
            ExportState.Phase.Running -> {
                progressTitle.text = "Exporting ${state.channelName}"
                progressText.text = "$counts · ${(state.fraction * 100).toInt()}%"
                progressBar.isIndeterminate = state.messages == 0L
                progressBar.progress = (state.fraction * 1000).toInt()
            }
            ExportState.Phase.Completed -> {
                progressTitle.text = "Exported ${state.channelName}"
                progressText.text = "$counts · saved to the archive"
                progressBar.isIndeterminate = false
                progressBar.progress = 1000
            }
            ExportState.Phase.Cancelled -> {
                progressTitle.text = "Export cancelled"
                progressText.text = state.channelName
                progressBar.isIndeterminate = false
                progressBar.progress = 0
            }
            ExportState.Phase.Failed -> {
                progressTitle.text = "Export failed"
                progressText.text = state.error
                progressBar.isIndeterminate = false
                progressBar.progress = 0
            }
        }
        val isRunning = state.phase == ExportState.Phase.Running
        val isDone = state.phase == ExportState.Phase.Completed
        progressBar.visibility = if (isRunning || isDone) View.VISIBLE else View.GONE
        progressCancel.visibility = if (isRunning) View.VISIBLE else View.GONE
        progressDismiss.visibility = if (isRunning) View.GONE else View.VISIBLE
        progressShare.visibility = if (isDone) View.VISIBLE else View.GONE
        progressOpen.visibility = if (isDone) View.VISIBLE else View.GONE
        val quickShare = if (isDone) ArchiveUi.quickShareLabel(this) else null
        progressQuickShare.text = quickShare
        progressQuickShare.visibility = if (quickShare != null) View.VISIBLE else View.GONE
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private inner class ChannelAdapter : BaseAdapter() {
        private var all = emptyList<Channel>()
        private var visible = emptyList<Channel>()
        private var query = ""
        private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

        fun setChannels(channels: List<Channel>) {
            all = channels
            filter(query)
        }

        fun filter(text: String) {
            query = text
            visible = if (text.isBlank()) all else all.filter { channel ->
                channel.name.contains(text, ignoreCase = true) ||
                    channel.recipients.any { it.name.contains(text, ignoreCase = true) }
            }
            notifyDataSetChanged()
        }

        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                addView(TextView(this@MainActivity).apply {
                    textSize = 16f
                    setTextColor(getColor(R.color.text_primary))
                    setSingleLine()
                })
                addView(TextView(this@MainActivity).apply {
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    setSingleLine()
                })
            }
            val channel = visible[position]
            (row.getChildAt(0) as TextView).text = channel.name
            val kind = if (channel.isGroup) "Group DM · ${channel.recipients.size + 1} members" else
                channel.recipients.firstOrNull()?.let { "@${it.fullName}" } ?: "Direct message"
            val lastActive = channel.lastMessageId?.let {
                " · last message " + dateFormat.format(Snowflake.toInstant(it).atZone(ZoneId.systemDefault()))
            }.orEmpty()
            (row.getChildAt(1) as TextView).text = kind + lastActive
            return row
        }
    }
}
