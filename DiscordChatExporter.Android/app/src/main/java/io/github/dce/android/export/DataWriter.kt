package io.github.dce.android.export

import io.github.dce.android.discord.Attachment
import io.github.dce.android.discord.Channel
import io.github.dce.android.discord.Embed
import io.github.dce.android.discord.EmbedMedia
import io.github.dce.android.discord.Emoji
import io.github.dce.android.discord.ImageCdn
import io.github.dce.android.discord.Message
import io.github.dce.android.discord.MessageSnapshot
import io.github.dce.android.discord.Sticker
import io.github.dce.android.discord.User
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import kotlin.math.roundToLong

/**
 * Port of JsonMessageWriter. Each message is built as a small document tree and streamed out,
 * either as JSON (same schema as the desktop app, so existing tooling can read it) or as XML
 * (the same tree, with arrays as wrapper elements around singular items).
 */
class DataWriter(
    stream: OutputStream,
    private val isXml: Boolean,
    private val context: ExportContext,
    private val assets: AssetSink,
) : MessageWriter {
    private val out = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 64 * 1024)
    private var channelId = ""

    override var messagesWritten = 0L
        private set

    private fun asset(url: String) = assets.resolve(url)

    private fun plain(markdown: String) = PlainTextMarkdown.format(context, markdown)

    private fun date(instant: Instant?) = instant?.let(context::isoDate)

    /* Document tree */

    private fun user(user: User, includeRoles: Boolean = true) = linkedMapOf<String, Any?>(
        "id" to user.id,
        "name" to user.name,
        "discriminator" to "%04d".format(user.discriminator ?: 0),
        "nickname" to user.displayName,
        "color" to null,
        "isBot" to user.isBot,
    ).apply {
        // DMs have no roles, but keep the field for schema compatibility
        if (includeRoles) put("roles", emptyList<Any>())
        put("avatarUrl", asset(user.avatarUrl))
    }

    // Standard emoji images point to Twemoji and are only written as links, never downloaded
    private fun emoji(emoji: Emoji) = linkedMapOf<String, Any?>(
        "id" to emoji.id.orEmpty(),
        "name" to emoji.name,
        "code" to emoji.name,
        "isAnimated" to emoji.isAnimated,
        "imageUrl" to (emoji.imageUrl?.let(::asset) ?: ImageCdn.standardEmojiUrl(emoji.name)),
    )

    private fun inlineEmojis(markdown: String?) =
        if (markdown.isNullOrBlank()) emptyList()
        else MarkdownParser.extractEmojis(markdown).map { emoji(Emoji(it.id, it.name, it.isAnimated)) }

    private fun attachment(attachment: Attachment) = linkedMapOf<String, Any?>(
        "id" to attachment.id,
        "url" to asset(attachment.url),
        "fileName" to attachment.fileName,
        "fileSizeBytes" to attachment.fileSize,
    )

    private fun media(media: EmbedMedia) = linkedMapOf<String, Any?>().apply {
        if (!media.url.isNullOrBlank()) {
            put("url", asset(media.proxyUrl ?: media.url))
            put("canonicalUrl", media.url)
        }
        put("width", media.width)
        put("height", media.height)
    }

    private fun embed(embed: Embed) = linkedMapOf<String, Any?>(
        "title" to plain(embed.title.orEmpty()),
        "url" to embed.url,
        "timestamp" to date(embed.timestamp),
        "description" to plain(embed.description.orEmpty()),
    ).apply {
        embed.color?.let { put("color", "#%06X".format(it and 0xFFFFFF)) }
        embed.author?.let { author ->
            put("author", linkedMapOf<String, Any?>("name" to author.name, "url" to author.url).apply {
                if (!author.iconUrl.isNullOrBlank()) {
                    put("iconUrl", asset(author.iconProxyUrl ?: author.iconUrl))
                    put("iconCanonicalUrl", author.iconUrl)
                }
            })
        }
        embed.thumbnail?.let { put("thumbnail", media(it)) }
        embed.images.firstOrNull()?.let { put("image", media(it)) }
        embed.video?.let { put("video", media(it)) }
        embed.footer?.let { footer ->
            put("footer", linkedMapOf<String, Any?>("text" to footer.text).apply {
                if (!footer.iconUrl.isNullOrBlank()) {
                    put("iconUrl", asset(footer.iconProxyUrl ?: footer.iconUrl))
                    put("iconCanonicalUrl", footer.iconUrl)
                }
            })
        }
        put("images", embed.images.map(::media))
        put("fields", embed.fields.map {
            linkedMapOf<String, Any?>("name" to plain(it.name), "value" to plain(it.value), "isInline" to it.isInline)
        })
        put("inlineEmojis", inlineEmojis(embed.description))
    }

    private fun sticker(sticker: Sticker) = linkedMapOf<String, Any?>(
        "id" to sticker.id,
        "name" to sticker.name,
        "format" to when (sticker.format) {
            1 -> "Png"
            2 -> "Apng"
            Sticker.FORMAT_LOTTIE -> "Lottie"
            4 -> "Gif"
            else -> sticker.format.toString()
        },
        "sourceUrl" to asset(sticker.sourceUrl),
    )

    private fun forwarded(snapshot: MessageSnapshot) = linkedMapOf<String, Any?>(
        "timestamp" to date(snapshot.timestamp),
        "timestampEdited" to date(snapshot.editedTimestamp),
        "content" to plain(snapshot.content),
        "attachments" to snapshot.attachments.map(::attachment),
        "embeds" to snapshot.embeds.map(::embed),
        "stickers" to snapshot.stickers.map(::sticker),
    )

    private fun kindName(kind: Int) = when (kind) {
        0 -> "Default"
        Message.KIND_RECIPIENT_ADD -> "RecipientAdd"
        Message.KIND_RECIPIENT_REMOVE -> "RecipientRemove"
        Message.KIND_CALL -> "Call"
        Message.KIND_CHANNEL_NAME_CHANGE -> "ChannelNameChange"
        Message.KIND_CHANNEL_ICON_CHANGE -> "ChannelIconChange"
        Message.KIND_CHANNEL_PINNED_MESSAGE -> "ChannelPinnedMessage"
        Message.KIND_GUILD_MEMBER_JOIN -> "GuildMemberJoin"
        Message.KIND_THREAD_CREATED -> "ThreadCreated"
        Message.KIND_REPLY -> "Reply"
        21 -> "ThreadStarterMessage"
        Message.KIND_POLL_RESULT -> "PollResult"
        else -> kind.toString()
    }

    // Port of PlainTextMessageExtensions.GetFallbackContent
    private fun fallbackContent(message: Message): String {
        val mentioned = message.mentionedUsers.firstOrNull()
        return when (message.kind) {
            Message.KIND_RECIPIENT_ADD ->
                if (mentioned != null) "Added ${mentioned.displayName} to the group." else "Added a recipient."
            Message.KIND_RECIPIENT_REMOVE -> when {
                mentioned == null -> "Removed a recipient."
                mentioned.id == message.author.id -> "Left the group."
                else -> "Removed ${mentioned.displayName} from the group."
            }
            Message.KIND_CALL -> {
                val minutes = message.callEndedTimestamp
                    ?.let { ((it.epochSecond - message.timestamp.epochSecond) / 60.0).roundToLong() } ?: 0
                "Started a call that lasted $minutes minutes."
            }
            Message.KIND_CHANNEL_NAME_CHANGE ->
                if (message.content.isNotBlank()) "Changed the channel name: ${message.content}" else "Changed the channel name."
            Message.KIND_CHANNEL_ICON_CHANGE -> "Changed the channel icon."
            Message.KIND_CHANNEL_PINNED_MESSAGE -> "Pinned a message."
            Message.KIND_THREAD_CREATED -> "Started a thread."
            Message.KIND_GUILD_MEMBER_JOIN -> "Joined the server."
            Message.KIND_POLL_RESULT -> {
                val embed = message.embeds.firstOrNull { it.kind == Embed.KIND_POLL_RESULT }
                val question = embed?.field("poll_question_text")
                when {
                    embed == null -> "A poll has closed."
                    question.isNullOrBlank() -> "${message.author.displayName}'s poll has closed."
                    else -> "${message.author.displayName}'s poll $question has closed."
                }
            }
            else -> message.content
        }
    }

    private fun message(message: Message) = linkedMapOf<String, Any?>(
        "id" to message.id,
        "type" to kindName(message.kind),
        "timestamp" to date(message.timestamp),
        "timestampEdited" to date(message.editedTimestamp),
        "callEndedTimestamp" to date(message.callEndedTimestamp),
        "isPinned" to message.isPinned,
        "content" to if (message.isSystemNotification) fallbackContent(message) else plain(message.content),
        "author" to user(message.author),
        "attachments" to message.attachments.map(::attachment),
        "embeds" to message.embeds.map(::embed),
        "stickers" to message.stickers.map(::sticker),
        "reactions" to message.reactions.map { reaction ->
            linkedMapOf<String, Any?>(
                "emoji" to emoji(reaction.emoji),
                "count" to reaction.count,
                "users" to context.reactionUsers(channelId, message.id, reaction.emoji).map { user(it, includeRoles = false) },
            )
        },
        "mentions" to message.mentionedUsers.map { user(it) },
    ).apply {
        if (message.referenceKind != null) {
            put("reference", linkedMapOf<String, Any?>(
                "type" to if (message.referenceKind == 1) "Forward" else "Default",
                "messageId" to message.referenceMessageId,
                "channelId" to message.referenceChannelId,
                "guildId" to message.referenceGuildId,
            ))
        }
        message.forwardedMessage?.let { put("forwardedMessage", forwarded(it)) }
        if (message.interactionUser != null) {
            put("interaction", linkedMapOf<String, Any?>(
                "id" to message.interactionId,
                "name" to message.interactionName,
                "user" to user(message.interactionUser),
            ))
        }
        put("inlineEmojis", inlineEmojis(message.content))
    }

    /* Streaming */

    override fun writePreamble(channel: Channel, me: User?) {
        channelId = channel.id
        val header = linkedMapOf<String, Any?>(
            "guild" to linkedMapOf<String, Any?>(
                "id" to "0",
                "name" to "Direct Messages",
                "iconUrl" to asset(ImageCdn.fallbackUserAvatarUrl()),
            ),
            "channel" to linkedMapOf<String, Any?>(
                "id" to channel.id,
                "type" to if (channel.isGroup) "DirectGroupTextChat" else "DirectTextChat",
                "categoryId" to null,
                "category" to null,
                "name" to channel.name,
                "topic" to null,
            ).apply { channel.iconUrl?.let { put("iconUrl", asset(it)) } },
            "dateRange" to linkedMapOf<String, Any?>("after" to null, "before" to null),
            "exportedAt" to context.isoDate(Instant.now()),
        )

        if (isXml) {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<export>\n")
            header.forEach { (key, value) -> Xml.write(out, key, value, 1) }
            out.write("  <messages>")
        } else {
            out.write("{\n")
            header.forEach { (key, value) ->
                out.write("  ${Json.string(key)}: ")
                Json.write(out, value, 1)
                out.write(",\n")
            }
            out.write("  \"messages\": [")
        }
        flush()
    }

    override fun writeMessage(message: Message) {
        context.register(message)
        val tree = message(message)
        if (isXml) {
            if (messagesWritten == 0L) out.write("\n")
            Xml.write(out, "message", tree, 2)
        } else {
            out.write(if (messagesWritten == 0L) "\n    " else ",\n    ")
            Json.write(out, tree, 2)
        }
        messagesWritten++
        flush()
    }

    override fun writePostamble() {
        if (isXml) {
            out.write(if (messagesWritten > 0) "  </messages>\n" else "</messages>\n")
            out.write("  <messageCount>$messagesWritten</messageCount>\n</export>\n")
        } else {
            out.write(if (messagesWritten > 0) "\n  ],\n" else "],\n")
            out.write("  \"messageCount\": $messagesWritten\n}\n")
        }
        flush()
    }

    private fun flush() {
        out.flush()
    }
}

/** Minimal indented JSON serializer for maps, lists, strings, numbers, booleans and nulls. */
internal object Json {
    fun string(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                ' ' -> append("\\u2028")
                ' ' -> append("\\u2029")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun write(out: Appendable, value: Any?, indent: Int) {
        val pad = "  ".repeat(indent + 1)
        val closePad = "  ".repeat(indent)
        when (value) {
            null -> out.append("null")
            is String -> out.append(string(value))
            is Boolean, is Int, is Long -> out.append(value.toString())
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                value.entries.forEachIndexed { i, (k, v) ->
                    out.append(pad).append(string(k.toString())).append(": ")
                    write(out, v, indent + 1)
                    out.append(if (i < value.size - 1) ",\n" else "\n")
                }
                out.append(closePad).append("}")
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                value.forEachIndexed { i, v ->
                    out.append(pad)
                    write(out, v, indent + 1)
                    out.append(if (i < value.size - 1) ",\n" else "\n")
                }
                out.append(closePad).append("]")
            }
            else -> out.append(string(value.toString()))
        }
    }
}

/** Serializes the same tree as XML: keys become elements, arrays wrap singular-named items, nulls are omitted. */
internal object Xml {
    private val singulars = mapOf("inlineEmojis" to "emoji", "mentions" to "user", "users" to "user")

    private fun singular(name: String) = singulars[name] ?: if (name.endsWith("s")) name.dropLast(1) else "item"

    // Escapes markup and drops characters that XML 1.0 can't represent at all
    fun text(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '&'.code -> append("&amp;")
                cp == '<'.code -> append("&lt;")
                cp == '>'.code -> append("&gt;")
                cp == '"'.code -> append("&quot;")
                cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF ->
                    appendCodePoint(cp)
            }
        }
    }

    fun write(out: Appendable, name: String, value: Any?, indent: Int) {
        val pad = "  ".repeat(indent)
        when (value) {
            null -> Unit
            is Map<*, *> -> {
                val children = value.entries.filter { it.value != null }
                if (children.isEmpty()) {
                    out.append("$pad<$name/>\n")
                    return
                }
                out.append("$pad<$name>\n")
                children.forEach { (k, v) -> write(out, k.toString(), v, indent + 1) }
                out.append("$pad</$name>\n")
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    out.append("$pad<$name/>\n")
                    return
                }
                out.append("$pad<$name>\n")
                value.forEach { write(out, singular(name), it, indent + 1) }
                out.append("$pad</$name>\n")
            }
            else -> out.append("$pad<$name>${text(value.toString())}</$name>\n")
        }
    }
}
