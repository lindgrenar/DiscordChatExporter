package io.github.dce.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.github.dce.android.discord.Channel
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.discord.DiscordException
import io.github.dce.android.discord.Snowflake
import io.github.dce.android.discord.User
import io.github.dce.android.export.ChannelExporter
import io.github.dce.android.export.ExportFormat
import io.github.dce.android.export.ExportTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_LOGIN = 1
        private const val REQUEST_CREATE_DOCUMENT = 2
        private const val REQUEST_NOTIFICATIONS = 3
    }

    private class PendingExport(val channel: Channel, val format: ExportFormat, val theme: ExportTheme) {
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
    private lateinit var progressOpen: Button

    private val adapter = ChannelAdapter()
    private var me: User? = null
    private var pendingExport: PendingExport? = null
    private var dismissedOutput: Uri? = null
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
        progressOpen = findViewById(R.id.progress_open)

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
            dismissedOutput = ExportState.current.outputUri ?: Uri.EMPTY
            renderExportState(ExportState.current)
        }
        progressShare.setOnClickListener { shareOutput() }
        progressOpen.setOnClickListener { openOutput() }

        refreshAuthState()
    }

    override fun onStart() {
        super.onStart()
        ExportState.observe(stateListener)
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
            account.text = "Export Discord DMs to HTML"
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
                    account.text = "Export Discord DMs to HTML"
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
        AlertDialog.Builder(this)
            .setTitle("Export \"${channel.name}\"")
            .setView(view)
            .setPositiveButton("Choose location") { _, _ ->
                val format = if (view.findViewById<RadioGroup>(R.id.format).checkedRadioButtonId == R.id.format_zip) {
                    ExportFormat.ZipArchive
                } else {
                    ExportFormat.SingleHtml
                }
                val theme = if (view.findViewById<RadioGroup>(R.id.theme).checkedRadioButtonId == R.id.theme_light) {
                    ExportTheme.Light
                } else {
                    ExportTheme.Dark
                }
                pendingExport = PendingExport(channel, format, theme)
                requestNotificationPermissionThenPickFile()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestNotificationPermissionThenPickFile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            pickOutputFile()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Progress notifications are nice to have; export either way
        if (requestCode == REQUEST_NOTIFICATIONS) pickOutputFile()
    }

    private fun pickOutputFile() {
        val export = pendingExport ?: return
        val (mime, extension) = when (export.format) {
            ExportFormat.SingleHtml -> "text/html" to ".html"
            ExportFormat.ZipArchive -> "application/zip" to ".zip"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, export.baseName + extension)
        startActivityForResult(intent, REQUEST_CREATE_DOCUMENT)
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_LOGIN -> data?.getStringExtra(LoginActivity.EXTRA_TOKEN)?.let(::signIn)
            REQUEST_CREATE_DOCUMENT -> {
                val uri = data?.data ?: return
                val export = pendingExport ?: return
                pendingExport = null
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                dismissedOutput = null
                ExportService.start(this, export.channel.id, export.channel.name, export.baseName, uri, export.format, export.theme)
            }
        }
    }

    private fun renderExportState(state: ExportState.Snapshot) {
        val dismissed = state.phase != ExportState.Phase.Running && dismissedOutput != null
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
                progressText.text = counts
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
    }

    private fun openOutput() {
        val state = ExportState.current
        val uri = state.outputUri ?: return
        if (state.outputMimeType == "text/html") {
            startActivity(Intent(this, ViewerActivity::class.java).setData(uri).putExtra(ViewerActivity.EXTRA_TITLE, state.channelName))
        } else {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, state.outputMimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(Intent.createChooser(intent, "Open with")) }
                .onFailure { toast("No app can open this file.") }
        }
    }

    private fun shareOutput() {
        val state = ExportState.current
        val uri = state.outputUri ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(state.outputMimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "Share export"))
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
