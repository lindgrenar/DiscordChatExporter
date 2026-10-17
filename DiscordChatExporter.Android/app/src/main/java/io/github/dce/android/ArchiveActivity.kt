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
import io.github.dce.android.archive.Archive
import io.github.dce.android.archive.ArchiveEntry
import io.github.dce.android.archive.Sharing
import io.github.dce.android.delete.MessageDeleter
import io.github.dce.android.discord.Snowflake
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.concurrent.thread

/** Exports saved on the device: open, share, back up, remove, or delete the messages from Discord. */
class ArchiveActivity : Activity() {
    companion object {
        private const val REQUEST_SAVE_COPY = 1
        private const val REQUEST_NOTIFICATIONS = 2
    }

    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var deletionPanel: View
    private lateinit var deletionTitle: TextView
    private lateinit var deletionText: TextView
    private lateinit var deletionBar: ProgressBar
    private lateinit var deletionStop: Button
    private lateinit var deletionDismiss: Button

    private val adapter = EntryAdapter()
    private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private var pendingCopy: ArchiveEntry? = null
    private var pendingDeletion: Pair<ArchiveEntry, Long>? = null
    private var deletionDismissed = false
    private var lastPhase: DeletionState.Phase? = null
    private val deletionListener: (DeletionState.Snapshot) -> Unit = ::renderDeletionState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive)
        val root = findViewById<View>(R.id.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        summary = findViewById(R.id.summary)
        empty = findViewById(R.id.empty)
        deletionPanel = findViewById(R.id.deletion_panel)
        deletionTitle = findViewById(R.id.deletion_title)
        deletionText = findViewById(R.id.deletion_text)
        deletionBar = findViewById(R.id.deletion_bar)
        deletionStop = findViewById(R.id.deletion_stop)
        deletionDismiss = findViewById(R.id.deletion_dismiss)

        val list = findViewById<ListView>(R.id.entries)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showActions(adapter.getItem(position)) }
        findViewById<Button>(R.id.share_all).setOnClickListener { ArchiveUi.share(this, adapter.entries) }
        deletionStop.setOnClickListener { DeletionService.stop(this) }
        deletionDismiss.setOnClickListener {
            deletionDismissed = true
            renderDeletionState(DeletionState.current)
        }
    }

    override fun onStart() {
        super.onStart()
        reload()
        DeletionState.observe(deletionListener)
    }

    override fun onStop() {
        DeletionState.unobserve(deletionListener)
        super.onStop()
    }

    private fun reload() {
        val archive = Archive.of(this)
        val entries = archive.list()
        adapter.setEntries(entries)
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.share_all).visibility = if (entries.size > 1) View.VISIBLE else View.GONE
        val total = entries.sumOf { archive.size(it) }
        summary.text = "${entries.size} export${if (entries.size == 1) "" else "s"} · ${ArchiveUi.formatSize(total)} on this device"
    }

    /* Actions */

    private fun isDeleting(entry: ArchiveEntry) =
        DeletionState.current.phase == DeletionState.Phase.Running && DeletionState.current.entryId == entry.id

    private fun showActions(entry: ArchiveEntry) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Open" to { ArchiveUi.open(this, entry) }
        ArchiveUi.quickShareLabel(this)?.let { label -> actions += label to { ArchiveUi.quickShare(this, entry) } }
        actions += "Share… (Drive, Proton Drive, …)" to { ArchiveUi.share(this, listOf(entry)) }
        actions += "Save a copy to…" to { saveCopy(entry) }
        when {
            isDeleting(entry) -> actions += "Stop deleting messages" to { DeletionService.stop(this) }
            entry.deletionCompletedAt != null -> actions += "Delete my messages on Discord again…" to { showDeleteDialog(entry) }
            entry.deletedMessages > 0 -> actions += "Resume deleting my messages on Discord…" to { showDeleteDialog(entry) }
            else -> actions += "Delete my messages on Discord…" to { showDeleteDialog(entry) }
        }
        if (Sharing.targetLabel(this) != null) actions += "Forget quick-share app" to {
            Sharing.forgetTarget(this)
            reload()
        }
        actions += "Remove from this device" to { confirmRemove(entry) }

        AlertDialog.Builder(this)
            .setTitle(entry.channelName)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun confirmRemove(entry: ArchiveEntry) {
        if (isDeleting(entry)) {
            ArchiveUi.toast(this, "Stop deleting messages first.")
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Remove this export from the device? Copies you've shared or saved elsewhere are kept.")
            .setPositiveButton("Remove") { _, _ ->
                Archive.of(this).delete(entry.id)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveCopy(entry: ArchiveEntry) {
        pendingCopy = entry
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(entry.mimeType)
            .putExtra(Intent.EXTRA_TITLE, entry.fileName)
        startActivityForResult(intent, REQUEST_SAVE_COPY)
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE_COPY || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val entry = pendingCopy ?: return
        pendingCopy = null
        val source = Archive.of(this).file(entry)
        thread {
            val result = runCatching {
                val output = runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                    ?: contentResolver.openOutputStream(uri, "w")
                    ?: error("Couldn't open the file.")
                output.use { out -> source.inputStream().use { it.copyTo(out, 64 * 1024) } }
            }
            runOnUiThread {
                ArchiveUi.toast(this, result.fold({ "Saved a copy." }, { "Couldn't save: ${it.message}" }))
            }
        }
    }

    /* Deleting messages */

    private fun showDeleteDialog(entry: ArchiveEntry) {
        if (TokenStore.get(this) == null) {
            ArchiveUi.toast(this, "Sign in first.")
            return
        }
        if (DeletionState.current.phase == DeletionState.Phase.Running) {
            ArchiveUi.toast(this, "Messages are already being deleted in ${DeletionState.current.channelName}.")
            return
        }
        if (entry.lastMessageId == null) {
            ArchiveUi.toast(this, "This export has no messages.")
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_delete, null)
        val pace = view.findViewById<RadioGroup>(R.id.pace)
        val customSection = view.findViewById<View>(R.id.custom_section)
        val customSeconds = view.findViewById<EditText>(R.id.custom_seconds)
        val estimate = view.findViewById<TextView>(R.id.estimate)
        val confirm = view.findViewById<CheckBox>(R.id.confirm)

        val until = dateFormat.format(Snowflake.toInstant(entry.lastMessageId).atZone(ZoneId.systemDefault()))
        val remaining = (entry.ownMessages - entry.deletedMessages).coerceAtLeast(0)
        view.findViewById<TextView>(R.id.delete_scope).text = buildString {
            append("Deletes the messages you sent in this chat, up to the newest one in this export ($until). ")
            append("Messages from others, and anything sent after the export, are left alone.\n\n")
            if (entry.deletedMessages > 0) {
                append("${entry.deletedMessages} have been deleted already; about $remaining of your ${entry.ownMessages} are left. ")
            } else {
                append("The export has ${entry.ownMessages} messages from you. ")
            }
            append("It runs in the background and can be stopped and resumed any time. The export stays in the archive.")
        }

        fun intervalMillis(): Long? = when (pace.checkedRadioButtonId) {
            R.id.pace_gentle -> 5000L
            R.id.pace_fast -> 1000L
            R.id.pace_custom -> customSeconds.text.toString().replace(',', '.').toDoubleOrNull()
                ?.let { (it * 1000).toLong() }
                ?.takeIf { it >= MessageDeleter.MIN_INTERVAL_MILLIS }
            else -> 2000L
        }

        lateinit var dialog: AlertDialog
        fun update() {
            customSection.visibility = if (pace.checkedRadioButtonId == R.id.pace_custom) View.VISIBLE else View.GONE
            val interval = intervalMillis()
            estimate.text = if (interval == null) {
                "Enter at least ${MessageDeleter.MIN_INTERVAL_MILLIS / 1000.0} seconds."
            } else {
                "About ${DeletionService.describeDuration(remaining * interval)} for $remaining messages. " +
                    "Slower is gentler on Discord's rate limits and less likely to get noticed."
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = interval != null && confirm.isChecked
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Delete your messages in \"${entry.channelName}\"?")
            .setView(view)
            .setPositiveButton("Start deleting") { _, _ ->
                pendingDeletion = entry to (intervalMillis() ?: return@setPositiveButton)
                requestNotificationPermissionThenDelete()
            }
            .setNegativeButton("Cancel", null)
            .create()

        pace.setOnCheckedChangeListener { _, _ -> update() }
        confirm.setOnCheckedChangeListener { _, _ -> update() }
        customSeconds.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = update()
        })
        dialog.setOnShowListener { update() }
        dialog.show()
    }

    private fun requestNotificationPermissionThenDelete() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            startPendingDeletion()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The notification is how you stop it from outside the app, but it works either way
        if (requestCode == REQUEST_NOTIFICATIONS) startPendingDeletion()
    }

    private fun startPendingDeletion() {
        val (entry, interval) = pendingDeletion ?: return
        pendingDeletion = null
        deletionDismissed = false
        DeletionService.start(this, entry.id, interval)
    }

    private fun renderDeletionState(state: DeletionState.Snapshot) {
        // Refresh the status lines in the list when a run starts or ends
        if (lastPhase != null && lastPhase != state.phase) reload()
        lastPhase = state.phase

        val isRunning = state.phase == DeletionState.Phase.Running
        deletionPanel.visibility = when {
            state.phase == DeletionState.Phase.Idle -> View.GONE
            !isRunning && deletionDismissed -> View.GONE
            else -> View.VISIBLE
        }
        val skipped = if (state.skipped > 0) " · ${state.skipped} couldn't be deleted" else ""
        when (state.phase) {
            DeletionState.Phase.Idle -> Unit
            DeletionState.Phase.Running -> {
                deletionTitle.text = "Deleting your messages in ${state.channelName}"
                val eta = state.remainingEstimate.takeIf { it > 0 }
                    ?.let { " · ~${DeletionService.describeDuration(it * state.intervalMillis)} left" }.orEmpty()
                deletionText.text = "${state.deleted} deleted$skipped · ${DeletionService.describeInterval(state.intervalMillis)}$eta"
            }
            DeletionState.Phase.Completed -> {
                deletionTitle.text = "Deleted your messages in ${state.channelName}"
                deletionText.text = "${state.deleted} deleted$skipped"
            }
            DeletionState.Phase.Stopped -> {
                deletionTitle.text = "Stopped deleting in ${state.channelName}"
                deletionText.text = "${state.deleted} deleted. Tap the export to resume."
            }
            DeletionState.Phase.Failed -> {
                deletionTitle.text = "Deleting stopped"
                deletionText.text = state.error
            }
        }
        deletionBar.visibility = if (isRunning) View.VISIBLE else View.GONE
        deletionBar.isIndeterminate = state.scanned == 0L
        deletionBar.progress = (state.fraction * 1000).toInt()
        deletionStop.visibility = if (isRunning) View.VISIBLE else View.GONE
        deletionDismiss.visibility = if (isRunning) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private inner class EntryAdapter : BaseAdapter() {
        var entries = emptyList<ArchiveEntry>()
            private set

        fun setEntries(value: List<ArchiveEntry>) {
            entries = value
            notifyDataSetChanged()
        }

        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@ArchiveActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                addView(TextView(this@ArchiveActivity).apply {
                    textSize = 16f
                    setTextColor(getColor(R.color.text_primary))
                    setSingleLine()
                })
                repeat(2) {
                    addView(TextView(this@ArchiveActivity).apply {
                        textSize = 13f
                        setTextColor(getColor(R.color.text_secondary))
                        setSingleLine()
                    })
                }
            }
            val entry = entries[position]
            val format = entry.fileName.substringAfterLast('.').uppercase()
            (row.getChildAt(0) as TextView).text = entry.channelName
            (row.getChildAt(1) as TextView).text = listOf(
                dateFormat.format(entry.createdAt.atZone(ZoneId.systemDefault())),
                format,
                ArchiveUi.formatSize(Archive.of(this@ArchiveActivity).size(entry)),
                "${entry.messages} messages",
            ).joinToString(" · ")
            val status = (row.getChildAt(2) as TextView)
            status.text = when {
                isDeleting(entry) -> "Deleting your messages on Discord…"
                entry.deletionCompletedAt != null -> "Your messages were deleted from Discord (${entry.deletedMessages})"
                entry.deletedMessages > 0 -> "${entry.deletedMessages} of your ${entry.ownMessages} messages deleted from Discord · paused"
                else -> ""
            }
            status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
            return row
        }
    }
}
