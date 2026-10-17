package io.github.dce.android.delete

import io.github.dce.android.discord.CancellationSignal
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.discord.Message
import io.github.dce.android.discord.Snowflake
import kotlin.random.Random

data class DeletionProgress(
    /** Messages looked at so far, by anyone. */
    val scanned: Long,
    /** Own messages removed in this run (including ones that turned out to be gone already). */
    val deleted: Long,
    /** Own messages Discord refused to delete, e.g. call logs. */
    val skipped: Long,
    /** How far back through the channel the scan has got, by time. */
    val fraction: Double,
)

/**
 * Deletes the user's own messages in a channel, one at a time, newest first, waiting [intervalMillis]
 * between deletions. Only messages up to and including [upToMessageId] are touched, so messages sent after
 * the export was made are left alone.
 *
 * Restarting is safe: deleted messages no longer show up, so a new run picks up where the last one stopped.
 */
class MessageDeleter(
    private val client: DiscordClient,
    private val cancellation: CancellationSignal,
    private val sleep: (Long) -> Unit = cancellation::sleep,
    private val random: Random = Random.Default,
) {
    companion object {
        /** System messages that Discord never lets anyone delete. */
        private val UNDELETABLE_KINDS = setOf(
            Message.KIND_RECIPIENT_ADD,
            Message.KIND_RECIPIENT_REMOVE,
            Message.KIND_CALL,
            Message.KIND_CHANNEL_NAME_CHANGE,
            Message.KIND_CHANNEL_ICON_CHANGE,
            21, // Thread starter message
        )

        const val MIN_INTERVAL_MILLIS = 500L

        fun isDeletable(message: Message, userId: String) =
            message.author.id == userId && message.kind !in UNDELETABLE_KINDS
    }

    fun run(
        channelId: String,
        userId: String,
        upToMessageId: String?,
        intervalMillis: Long,
        onProgress: (DeletionProgress) -> Unit,
    ): DeletionProgress {
        val interval = intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)
        var progress = DeletionProgress(0, 0, 0, 0.0)
        val oldest = client.tryGetFirstMessage(channelId) ?: return progress.copy(fraction = 1.0)

        // "before" is exclusive, so start just past the last exported message
        var cursor = upToMessageId?.let { (it.toULong() + 1u).toString() }
        var newestMillis = upToMessageId?.let { Snowflake.toInstant(it).toEpochMilli() }

        while (true) {
            cancellation.throwIfCancelled()
            val page = client.getMessagesBefore(channelId, cursor)
            if (page.isEmpty()) break

            for (message in page) {
                val newest = newestMillis ?: message.timestamp.toEpochMilli().also { newestMillis = it }
                val span = newest - oldest.timestamp.toEpochMilli()
                val fraction = if (span > 0) (newest - message.timestamp.toEpochMilli()).toDouble() / span else 1.0

                progress = progress.copy(scanned = progress.scanned + 1, fraction = fraction.coerceIn(0.0, 1.0))
                if (isDeletable(message, userId)) {
                    cancellation.throwIfCancelled()
                    progress = when (client.deleteMessage(channelId, message.id)) {
                        DiscordClient.DeleteResult.NotAllowed -> progress.copy(skipped = progress.skipped + 1)
                        else -> progress.copy(deleted = progress.deleted + 1)
                    }
                    onProgress(progress)
                    // A little jitter, so the deletions don't tick like a metronome
                    sleep(interval + random.nextLong(interval / 5 + 1))
                } else if (progress.scanned % 100 == 0L) {
                    onProgress(progress)
                }
            }

            if (Snowflake.compare(page.last().id, oldest.id) <= 0) break
            cursor = page.last().id
        }

        progress = progress.copy(fraction = 1.0)
        onProgress(progress)
        return progress
    }
}
