package io.github.dce.android.export

import io.github.dce.android.discord.Attachment
import io.github.dce.android.discord.CancelledException
import io.github.dce.android.discord.Channel
import io.github.dce.android.discord.DiscordClient
import io.github.dce.android.discord.Embed
import io.github.dce.android.discord.Emoji
import io.github.dce.android.discord.Message
import io.github.dce.android.discord.Sticker
import io.github.dce.android.discord.User
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Common shape of the HTML, JSON and XML writers (port of MessageWriter). */
interface MessageWriter {
    val messagesWritten: Long
    fun writePreamble(channel: Channel, me: User?)
    fun writeMessage(message: Message)
    fun writePostamble()
}

/** Port of ExportContext: caches users/channels referenced by messages and formats dates. */
class ExportContext(
    private val client: DiscordClient?,
    private val assets: AssetSink,
    private val zone: ZoneId = ZoneId.systemDefault(),
    val locale: Locale = Locale.getDefault(),
) : MarkdownContext {
    private val usersById = HashMap<String, User?>()
    private val channelsById = HashMap<String, Channel?>()
    private val formatters = HashMap<String, DateTimeFormatter>()
    private val isoFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx")

    // Lookups are best-effort, but cancellation must still get through
    private fun <T> tryFetch(block: () -> T?): T? =
        try {
            block()
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            null
        }

    fun register(message: Message) {
        message.referencedUsers().forEach { usersById[it.id] = it }
    }

    override fun resolveAsset(url: String) = assets.resolve(url)

    // Mentioned users aren't always included in the message, so fetch them on demand
    // https://github.com/Tyrrrz/DiscordChatExporter/issues/304
    override fun userName(id: String): Pair<String, String>? {
        if (!usersById.containsKey(id)) usersById[id] = tryFetch { client?.tryGetUser(id) }
        return usersById[id]?.let { it.displayName to it.fullName }
    }

    override fun channelName(id: String): String? {
        if (!channelsById.containsKey(id)) channelsById[id] = tryFetch { client?.tryGetChannel(id) }
        return channelsById[id]?.name
    }

    // Approximates .NET's standard date format strings with the device locale
    override fun formatDate(instant: Instant, format: String): String {
        val formatter = formatters.getOrPut(format) {
            when (format) {
                "t" -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                "T" -> DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
                "d" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
                "D" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                "f" -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
                "F" -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.MEDIUM)
                else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)
            }.withLocale(locale)
        }
        return formatter.format(instant.atZone(zone))
    }

    /** ISO 8601 with the local offset, like the desktop app's JSON dates. */
    fun isoDate(instant: Instant): String = isoFormatter.format(instant.atZone(zone))

    fun reactionUsers(channelId: String, messageId: String, emoji: Emoji): List<User> =
        tryFetch { client?.getReactionUsers(channelId, messageId, emoji) }.orEmpty()

    fun formatNumber(value: Number): String = NumberFormat.getIntegerInstance(locale).format(value)

    fun formatPercent(value: Double): String = NumberFormat.getPercentInstance(locale).format(value)

    fun timezoneLabel(): String {
        val seconds = zone.rules.getOffset(Instant.now()).totalSeconds
        if (seconds == 0) return "UTC+0"
        val hours = abs(seconds) / 3600.0
        val text = if (hours % 1.0 == 0.0) hours.toInt().toString() else "%.1f".format(Locale.ROOT, hours)
        return "UTC" + (if (seconds > 0) "+" else "-") + text
    }
}

/**
 * Port of HtmlMessageWriter and its Razor templates (Preamble, MessageGroup, Postamble).
 * HTML is streamed to [stream]; messages are buffered only until their group is complete.
 */
class HtmlRenderer(
    private val stream: OutputStream,
    private val context: ExportContext,
    private val assets: AssetSink,
    private val css: String,
    private val headHtml: String,
    private val trailer: String = "",
) : MessageWriter {
    private val writer = OutputStreamWriter(stream, Charsets.UTF_8)
    private val group = ArrayList<Message>()

    override var messagesWritten = 0L
        private set

    private fun asset(url: String) = assets.resolve(url).html()

    private fun date(instant: Instant, format: String = "g") = context.formatDate(instant, format).html()

    private fun markdown(text: String, isJumboAllowed: Boolean = true) = HtmlMarkdown.format(context, text, isJumboAllowed)

    private fun write(html: CharSequence) {
        writer.write(html.toString())
        writer.flush()
        assets.flush(stream)
    }

    override fun writePreamble(channel: Channel, me: User?) {
        // For one-on-one chats, use the other person's avatar as the header icon
        val other = channel.recipients.firstOrNull { it.id != me?.id } ?: channel.recipients.firstOrNull()
        val iconUrl = channel.iconUrl ?: other?.avatarUrl ?: io.github.dce.android.discord.ImageCdn.fallbackUserAvatarUrl()

        write(
            buildString {
                append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                append("<title>Direct Messages - ${channel.name.html()}</title>\n")
                append("<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width\">\n")
                append("<style>\n").append(css).append("</style>\n")
                append(headHtml)
                append("</head>\n<body>\n")
                append("<div class=\"preamble\">")
                append("<div class=\"preamble__guild-icon-container\"><img class=\"preamble__guild-icon\" src=\"${asset(iconUrl)}\" alt=\"Guild icon\" loading=\"lazy\"></div>")
                append("<div class=\"preamble__entries-container\">")
                append("<div class=\"preamble__entry\">Direct Messages</div>")
                append("<div class=\"preamble__entry\">${channel.name.html()}</div>")
                append("</div></div>\n")
                append("<div class=\"chatlog\">\n")
            },
        )
    }

    // Note: same rules as HtmlMessageWriter.CanJoinGroup
    private fun canJoinGroup(message: Message): Boolean {
        val last = group.lastOrNull() ?: return true
        if (message.isReplyLike) return false
        if (message.isSystemNotification) return last.isSystemNotification
        if (last.isSystemNotification) return false
        if (abs(message.timestamp.epochSecond - last.timestamp.epochSecond) > 7 * 60) return false
        if (message.author.id != last.author.id) return false
        return message.author.fullName == last.author.fullName
    }

    override fun writeMessage(message: Message) {
        context.register(message)
        messagesWritten++
        if (!canJoinGroup(message)) {
            write(renderGroup(group))
            group.clear()
        }
        group += message
    }

    override fun writePostamble() {
        if (group.isNotEmpty()) write(renderGroup(group))
        group.clear()
        write(
            buildString {
                append("</div>\n")
                append("<div class=\"postamble\">")
                append("<div class=\"postamble__entry\">Exported ${context.formatNumber(messagesWritten)} message(s)</div>")
                append("<div class=\"postamble__entry\">Timezone: ${context.timezoneLabel()}</div>")
                append("</div>\n")
                append(trailer)
                append("</body>\n</html>\n")
            },
        )
    }

    private fun colorStyle(color: Int?) =
        color?.let { "background-color: rgba(${(it shr 16) and 0xFF}, ${(it shr 8) and 0xFF}, ${it and 0xFF}, 1)" }

    private fun renderGroup(messages: List<Message>): String = buildString {
        append("<div class=\"chatlog__message-group\">")
        messages.forEachIndexed { i, message -> renderMessage(message, i == 0) }
        append("</div>\n")
    }

    private fun StringBuilder.renderMessage(message: Message, isFirst: Boolean) {
        val pinned = if (message.isPinned) " chatlog__message-container--pinned" else ""
        append("<div id=\"chatlog__message-container-${message.id}\" class=\"chatlog__message-container$pinned\" data-message-id=\"${message.id}\">")
        append("<div class=\"chatlog__message\">")
        if (message.isSystemNotification) renderSystemNotification(message) else renderRegular(message, isFirst)
        append("</div></div>")
    }

    private fun StringBuilder.renderSystemNotification(message: Message) {
        val icon = when (message.kind) {
            Message.KIND_RECIPIENT_ADD, Message.KIND_GUILD_MEMBER_JOIN -> "join-icon"
            Message.KIND_RECIPIENT_REMOVE -> "leave-icon"
            Message.KIND_CALL -> "call-icon"
            Message.KIND_CHANNEL_PINNED_MESSAGE -> "pin-icon"
            Message.KIND_THREAD_CREATED -> "thread-icon"
            Message.KIND_POLL_RESULT -> "poll-icon"
            else -> "pencil-icon"
        }
        append("<div class=\"chatlog__message-aside\"><svg class=\"chatlog__system-notification-icon\"><use href=\"#$icon\"></use></svg></div>")
        append("<div class=\"chatlog__message-primary\">")
        append("<span class=\"chatlog__system-notification-author\" title=\"${message.author.fullName.html()}\" data-user-id=\"${message.author.id}\">${message.author.displayName.html()}</span>")
        if (message.kind != Message.KIND_POLL_RESULT) append("<span> </span>")

        append("<span class=\"chatlog__system-notification-content\">")
        val mentioned = message.mentionedUsers.firstOrNull()
        val referenceLink = "#chatlog__message-container-${message.referenceMessageId}"
        when {
            message.kind == Message.KIND_RECIPIENT_ADD && mentioned != null ->
                append("<span>added </span><a class=\"chatlog__system-notification-link\" title=\"${mentioned.fullName.html()}\">${mentioned.displayName.html()}</a><span> to the group.</span>")
            message.kind == Message.KIND_RECIPIENT_REMOVE && mentioned != null ->
                if (mentioned.id == message.author.id) append("<span>left the group.</span>")
                else append("<span>removed </span><a class=\"chatlog__system-notification-link\" title=\"${mentioned.fullName.html()}\">${mentioned.displayName.html()}</a><span> from the group.</span>")
            message.kind == Message.KIND_CALL -> {
                val end = message.callEndedTimestamp ?: message.timestamp
                val minutes = ((end.epochSecond - message.timestamp.epochSecond) / 60.0).roundToInt()
                append("<span>started a call that lasted ${context.formatNumber(minutes)} minutes</span>")
            }
            message.kind == Message.KIND_CHANNEL_NAME_CHANGE ->
                append("<span>changed the channel name: </span><span class=\"chatlog__system-notification-link\">${message.content.html()}</span>")
            message.kind == Message.KIND_CHANNEL_ICON_CHANGE -> append("<span>changed the channel icon.</span>")
            message.kind == Message.KIND_CHANNEL_PINNED_MESSAGE && message.referenceMessageId != null ->
                append("<span>pinned </span><a class=\"chatlog__system-notification-link\" href=\"$referenceLink\">a message</a><span> to this channel.</span>")
            message.kind == Message.KIND_THREAD_CREATED -> append("<span>started a thread.</span>")
            message.kind == Message.KIND_GUILD_MEMBER_JOIN -> append("<span>joined the server.</span>")
            message.kind == Message.KIND_POLL_RESULT -> {
                val question = message.embeds.firstOrNull { it.kind == Embed.KIND_POLL_RESULT }?.field("poll_question_text")
                when {
                    question.isNullOrBlank() -> append("<span>'s poll has closed.</span>")
                    message.referenceMessageId != null ->
                        append("<span>'s poll </span><a class=\"chatlog__system-notification-link\" href=\"$referenceLink\">${question.html()}</a><span> has closed.</span>")
                    else -> append("<span>'s poll </span><span class=\"chatlog__system-notification-link\">${question.html()}</span><span> has closed.</span>")
                }
            }
            else -> append("<span>${message.content.lowercase().html()}</span>")
        }
        append("</span>")
        append("<span class=\"chatlog__system-notification-timestamp\" title=\"${date(message.timestamp, "f")}\"><a href=\"#chatlog__message-container-${message.id}\">${date(message.timestamp)}</a></span>")
        append("</div>")
    }

    private fun StringBuilder.renderRegular(message: Message, isFirst: Boolean) {
        append("<div class=\"chatlog__message-aside\">")
        if (isFirst) {
            if (message.isReplyLike) append("<div class=\"chatlog__reply-symbol\"></div>")
            append("<img class=\"chatlog__avatar\" src=\"${asset(message.author.avatarUrl)}\" alt=\"Avatar\" loading=\"lazy\">")
        } else {
            append("<div class=\"chatlog__short-timestamp\" title=\"${date(message.timestamp, "f")}\">${date(message.timestamp, "t")}</div>")
        }
        append("</div>")

        append("<div class=\"chatlog__message-primary\">")
        if (isFirst) {
            if (message.isReplyLike) renderReply(message)
            append("<div class=\"chatlog__header\">")
            append("<span class=\"chatlog__author\" title=\"${message.author.fullName.html()}\" data-user-id=\"${message.author.id}\">${message.author.displayName.html()}</span>")
            if (message.author.isBot) {
                append("<span class=\"chatlog__author-tag\">${if (message.isCrossPost) "SERVER" else "BOT"}</span>")
            }
            append("<span class=\"chatlog__timestamp\" title=\"${date(message.timestamp, "f")}\"><a href=\"#chatlog__message-container-${message.id}\">${date(message.timestamp)}</a></span>")
            append("</div>")
        }

        val hasContent = message.content.isNotBlank() && !message.isContentHidden
        if (hasContent || message.editedTimestamp != null) {
            append("<div class=\"chatlog__content chatlog__markdown\">")
            if (hasContent) append("<span class=\"chatlog__markdown-preserve\">${markdown(message.content)}</span>")
            message.editedTimestamp?.let {
                append("<span class=\"chatlog__edited-timestamp\" title=\"${date(it, "f")}\">(edited)</span>")
            }
            append("</div>")
        }

        val forwarded = message.forwardedMessage
        if (message.isForwarded && forwarded != null) {
            append("<div class=\"chatlog__forwarded\">")
            append("<div class=\"chatlog__forwarded-header\"><svg class=\"chatlog__forwarded-icon\"><use href=\"#forward-icon\"></use></svg><em>Forwarded</em></div>")
            if (forwarded.content.isNotBlank()) {
                append("<div class=\"chatlog__forwarded-content chatlog__markdown\"><span class=\"chatlog__markdown-preserve\">${markdown(forwarded.content)}</span></div>")
            }
            if (forwarded.attachments.isNotEmpty()) {
                append("<div class=\"chatlog__forwarded-attachments\">")
                forwarded.attachments.forEach { renderAttachmentMedia(it, "chatlog__forwarded-attachment") }
                append("</div>")
            }
            forwarded.stickers.forEach { renderSticker(it) }
            append("<div class=\"chatlog__forwarded-timestamp\"><span title=\"${date(forwarded.timestamp, "f")}\">Originally sent: ${date(forwarded.timestamp)}</span>")
            forwarded.editedTimestamp?.let { append("<span title=\"${date(it, "f")}\"> (edited)</span>") }
            append("</div></div>")
        }

        for (attachment in message.attachments) {
            if (attachment.isSpoiler) {
                append("<div class=\"chatlog__attachment chatlog__attachment--hidden\" onclick=\"showSpoiler(event, this)\">")
                append("<div class=\"chatlog__attachment-spoiler-caption\">SPOILER</div>")
            } else {
                append("<div class=\"chatlog__attachment\">")
            }
            renderAttachmentMedia(attachment, "chatlog__attachment-media")
            append("</div>")
        }

        message.poll?.let { renderPoll(it) }
        message.embeds.forEach { renderEmbed(it) }
        message.stickers.forEach { renderSticker(it) }

        if (message.reactions.isNotEmpty()) {
            append("<div class=\"chatlog__reactions\">")
            for (reaction in message.reactions) {
                append("<div class=\"chatlog__reaction\" title=\"${reaction.emoji.name.html()}\">")
                val imageUrl = reaction.emoji.imageUrl
                if (imageUrl != null) {
                    append("<img class=\"chatlog__emoji chatlog__emoji--small\" alt=\"${reaction.emoji.name.html()}\" src=\"${asset(imageUrl)}\" loading=\"lazy\">")
                } else {
                    append("<span>${reaction.emoji.name.html()}</span>")
                }
                append("<span class=\"chatlog__reaction-count\">${reaction.count}</span></div>")
            }
            append("</div>")
        }
        append("</div>")
    }

    private fun StringBuilder.renderReply(message: Message) {
        append("<div class=\"chatlog__reply\">")
        val referenced = message.referencedMessage
        val interactionUser = message.interactionUser
        when {
            referenced != null -> {
                append("<img class=\"chatlog__reply-avatar\" src=\"${asset(referenced.author.avatarUrl)}\" alt=\"Avatar\" loading=\"lazy\">")
                append("<div class=\"chatlog__reply-author\" title=\"${referenced.author.fullName.html()}\">${referenced.author.displayName.html()}</div>")
                append("<div class=\"chatlog__reply-content\"><span class=\"chatlog__reply-link\" onclick=\"scrollToMessage(event, '${referenced.id}')\">")
                when {
                    referenced.content.isNotBlank() && !referenced.isContentHidden -> append(markdown(referenced.content, isJumboAllowed = false))
                    referenced.attachments.isNotEmpty() || referenced.embeds.isNotEmpty() -> append("<em>Click to see attachment</em> <span>🖼️</span>")
                    else -> append("<em>Click to see original message</em>")
                }
                append("</span>")
                referenced.editedTimestamp?.let {
                    append("<span class=\"chatlog__reply-edited-timestamp\" title=\"${date(it, "f")}\">(edited)</span>")
                }
                append("</div>")
            }
            interactionUser != null -> {
                append("<img class=\"chatlog__reply-avatar\" src=\"${asset(interactionUser.avatarUrl)}\" alt=\"Avatar\" loading=\"lazy\">")
                append("<div class=\"chatlog__reply-author\" title=\"${interactionUser.fullName.html()}\">${interactionUser.displayName.html()}</div>")
                append("<div class=\"chatlog__reply-content\">used /${message.interactionName.orEmpty().html()}</div>")
            }
            else -> append("<div class=\"chatlog__reply-unknown\">Original message was deleted or could not be loaded.</div>")
        }
        append("</div>")
    }

    private fun formatFileSize(bytes: Long): String {
        val units = listOf("bytes", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$bytes bytes" else "%.2f %s".format(context.locale, value, units[unit])
    }

    private fun StringBuilder.renderAttachmentMedia(attachment: Attachment, cssClass: String) {
        val src = asset(attachment.url)
        val name = attachment.fileName.html()
        val size = formatFileSize(attachment.fileSize).html()
        when {
            attachment.isImage -> append(
                "<a href=\"$src\"><img class=\"$cssClass\" src=\"$src\" alt=\"${(attachment.description ?: "Image attachment").html()}\" title=\"Image: $name ($size)\" loading=\"lazy\"></a>",
            )
            attachment.isVideo -> append(
                "<video class=\"$cssClass\" controls preload=\"metadata\"><source src=\"$src\" title=\"Video: $name ($size)\"></video>",
            )
            attachment.isAudio -> append(
                "<audio class=\"$cssClass\" controls preload=\"metadata\"><source src=\"$src\" title=\"Audio: $name ($size)\"></audio>",
            )
            else -> append(
                "<div class=\"chatlog__attachment-generic\"><svg class=\"chatlog__attachment-generic-icon\"><use href=\"#attachment-icon\"/></svg>" +
                    "<div class=\"chatlog__attachment-generic-name\"><a href=\"$src\" download=\"$name\">$name</a></div>" +
                    "<div class=\"chatlog__attachment-generic-size\">$size</div></div>",
            )
        }
    }

    private fun StringBuilder.renderSticker(sticker: Sticker) {
        append("<div class=\"chatlog__sticker\" title=\"${sticker.name.html()}\">")
        if (sticker.isImage) {
            append("<img class=\"chatlog__sticker--media\" src=\"${asset(sticker.sourceUrl)}\" alt=\"Sticker\">")
        } else {
            // Lottie stickers need a JS animation runtime that isn't bundled in this port
            append("<strong>[Sticker: ${sticker.name.html()}]</strong>")
        }
        append("</div>")
    }

    private fun StringBuilder.renderPoll(poll: io.github.dce.android.discord.Poll) {
        append("<div class=\"chatlog__poll\"><div class=\"chatlog__poll-question\">${poll.question.html()}</div><div class=\"chatlog__poll-answers\">")
        val total = poll.totalVotes
        for (answer in poll.answers) {
            val result = poll.counts?.firstOrNull { it.answerId == answer.id }
            val isSelected = result?.meVoted == true
            val isWinning = poll.isFinalized == true && poll.winningVotes > 0 && (result?.count ?: 0) >= poll.winningVotes
            val share = if (total > 0) (result?.count ?: 0).toDouble() / total else 0.0
            val stateClass = when {
                isWinning -> " chatlog__poll-answer--winning"
                poll.isFinalized != true && isSelected -> " chatlog__poll-answer--selected"
                else -> ""
            }
            append("<div class=\"chatlog__poll-answer$stateClass\" style=\"--poll-answer-fill: ${"%.2f".format(Locale.ROOT, share * 100)}%\">")
            append("<div class=\"chatlog__poll-answer-content\">")
            answer.emoji?.let { emoji ->
                val url = emoji.imageUrl
                if (url != null) append("<img class=\"chatlog__poll-answer-emoji\" alt=\"${emoji.name.html()}\" src=\"${asset(url)}\" loading=\"lazy\">")
                else append("<span>${emoji.name.html()} </span>")
            }
            append("<span class=\"chatlog__poll-answer-text\">${answer.text.html()}</span></div>")
            append("<div class=\"chatlog__poll-answer-details\">")
            if (poll.counts != null) {
                val votes = result?.count ?: 0
                append("<span class=\"chatlog__poll-answer-count\">${context.formatNumber(votes)} ${if (votes == 1) "vote" else "votes"}</span>")
                append("<span class=\"chatlog__poll-answer-percentage\">${context.formatPercent(share)}</span>")
            }
            if (isSelected) append("<span class=\"chatlog__poll-answer-selected\" title=\"Selected\"><span aria-hidden=\"true\">✓</span><span class=\"chatlog__poll-accessible-label\">Selected</span></span>")
            append("</div></div>")
        }
        append("</div>")
        if (poll.counts != null || poll.allowsMultiple || poll.expiresAt != null) {
            append("<div class=\"chatlog__poll-footer\">")
            if (poll.counts != null) append("<span class=\"chatlog__poll-footer-item\">${context.formatNumber(total)} ${if (total == 1) "vote" else "votes"}</span>")
            if (poll.allowsMultiple) append("<span class=\"chatlog__poll-footer-item\">Multiple answers allowed</span>")
            if (poll.isFinalized == true) append("<span class=\"chatlog__poll-footer-item\">Poll closed</span>")
            if (poll.expiresAt != null && poll.isFinalized != true) {
                append("<span class=\"chatlog__poll-footer-item\">Ends <time title=\"${date(poll.expiresAt, "f")}\">${date(poll.expiresAt)}</time></span>")
            }
            append("</div>")
        }
        append("</div>")
    }

    private fun StringBuilder.renderEmbed(embed: Embed) {
        when {
            embed.kind == Embed.KIND_POLL_RESULT -> Unit

            embed.kind == Embed.KIND_IMAGE && embed.url != null -> {
                val image = embed.images.firstOrNull()
                val url = image?.proxyUrl ?: image?.url ?: embed.thumbnail?.proxyUrl ?: embed.thumbnail?.url ?: embed.url
                val src = asset(url)
                append("<div class=\"chatlog__embed\"><a href=\"$src\"><img class=\"chatlog__embed-generic-image\" src=\"$src\" alt=\"Embedded image\" loading=\"lazy\"></a></div>")
            }

            // Only treat videos that Discord proxies as files; YouTube, Twitch etc. embeds point to web pages
            (embed.kind == Embed.KIND_VIDEO || embed.kind == Embed.KIND_GIFV) && embed.video?.proxyUrl != null -> {
                val src = asset(embed.video.proxyUrl)
                val size = listOfNotNull(embed.video.width?.let { "width=\"$it\"" }, embed.video.height?.let { "height=\"$it\"" }).joinToString(" ")
                if (embed.kind == Embed.KIND_GIFV) {
                    append("<div class=\"chatlog__embed\"><video class=\"chatlog__embed-generic-gifv\" $size autoplay loop muted playsinline><source src=\"$src\"></video></div>")
                } else {
                    append("<div class=\"chatlog__embed\"><video class=\"chatlog__embed-generic-video\" $size controls preload=\"metadata\"><source src=\"$src\"></video></div>")
                }
            }

            else -> renderRichEmbed(embed)
        }
    }

    private fun StringBuilder.renderRichEmbed(embed: Embed) {
        append("<div class=\"chatlog__embed\">")
        val pill = colorStyle(embed.color)
        if (pill != null) append("<div class=\"chatlog__embed-color-pill\" style=\"$pill\"></div>")
        else append("<div class=\"chatlog__embed-color-pill chatlog__embed-color-pill--default\"></div>")

        append("<div class=\"chatlog__embed-content-container\"><div class=\"chatlog__embed-content\"><div class=\"chatlog__embed-text\">")

        embed.author?.let { author ->
            append("<div class=\"chatlog__embed-author-container\">")
            val icon = author.iconProxyUrl ?: author.iconUrl
            if (icon != null) append("<img class=\"chatlog__embed-author-icon\" src=\"${asset(icon)}\" alt=\"Author icon\" loading=\"lazy\">")
            if (!author.name.isNullOrBlank()) {
                if (author.url != null) append("<a class=\"chatlog__embed-author-link\" href=\"${author.url.html()}\"><div class=\"chatlog__embed-author\">${author.name.html()}</div></a>")
                else append("<div class=\"chatlog__embed-author\">${author.name.html()}</div>")
            }
            append("</div>")
        }

        if (!embed.title.isNullOrBlank()) {
            val title = "<div class=\"chatlog__markdown chatlog__markdown-preserve\">${markdown(embed.title, isJumboAllowed = false)}</div>"
            append("<div class=\"chatlog__embed-title\">")
            if (embed.url != null) append("<a class=\"chatlog__embed-title-link\" href=\"${embed.url.html()}\">$title</a>") else append(title)
            append("</div>")
        }

        if (!embed.description.isNullOrBlank()) {
            append("<div class=\"chatlog__embed-description\"><div class=\"chatlog__markdown chatlog__markdown-preserve\">${markdown(embed.description, isJumboAllowed = false)}</div></div>")
        }

        if (embed.fields.isNotEmpty()) {
            append("<div class=\"chatlog__embed-fields\">")
            for (field in embed.fields) {
                append("<div class=\"chatlog__embed-field${if (field.isInline) " chatlog__embed-field--inline" else ""}\">")
                if (field.name.isNotBlank()) append("<div class=\"chatlog__embed-field-name\"><div class=\"chatlog__markdown chatlog__markdown-preserve\">${markdown(field.name, false)}</div></div>")
                if (field.value.isNotBlank()) append("<div class=\"chatlog__embed-field-value\"><div class=\"chatlog__markdown chatlog__markdown-preserve\">${markdown(field.value, false)}</div></div>")
                append("</div>")
            }
            append("</div>")
        }
        append("</div>")

        val thumbnail = embed.thumbnail?.let { it.proxyUrl ?: it.url }
        if (thumbnail != null) {
            val src = asset(thumbnail)
            append("<div class=\"chatlog__embed-thumbnail-container\"><a class=\"chatlog__embed-thumbnail-link\" href=\"$src\"><img class=\"chatlog__embed-thumbnail\" src=\"$src\" alt=\"Thumbnail\" loading=\"lazy\"></a></div>")
        }
        append("</div>")

        val images = embed.images.mapNotNull { it.proxyUrl ?: it.url }
        if (images.isNotEmpty()) {
            append("<div class=\"chatlog__embed-images${if (images.size == 1) " chatlog__embed-images--single" else ""}\">")
            for (image in images) {
                val src = asset(image)
                append("<div class=\"chatlog__embed-image-container\"><a class=\"chatlog__embed-image-link\" href=\"$src\"><img class=\"chatlog__embed-image\" src=\"$src\" alt=\"Image\" loading=\"lazy\"></a></div>")
            }
            append("</div>")
        }

        if (embed.footer != null || embed.timestamp != null) {
            append("<div class=\"chatlog__embed-footer\">")
            val footerIcon = embed.footer?.let { it.iconProxyUrl ?: it.iconUrl }
            if (footerIcon != null) append("<img class=\"chatlog__embed-footer-icon\" src=\"${asset(footerIcon)}\" alt=\"Footer icon\" loading=\"lazy\">")
            append("<span class=\"chatlog__embed-footer-text\">")
            val footerText = embed.footer?.text
            if (!footerText.isNullOrBlank()) append(footerText.html())
            if (!footerText.isNullOrBlank() && embed.timestamp != null) append(" • ")
            embed.timestamp?.let { append(date(it)) }
            append("</span></div>")
        }
        append("</div></div>")
    }
}
