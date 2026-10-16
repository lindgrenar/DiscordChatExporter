package io.github.dce.android.discord

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

// Ported from DiscordChatExporter.Core/Discord/Data.
// Only the parts needed to export direct message channels to HTML are included.

object ImageCdn {
    // Overridable so that tests can serve images from a local server
    var baseUrl = "https://cdn.discordapp.com"

    private fun ext(hash: String) = if (hash.startsWith("a_")) "gif" else "png"

    // Standard emoji are rendered through Twemoji. Variant selectors are dropped unless the emoji has a ZWJ.
    fun standardEmojiUrl(name: String): String {
        val codePoints = name.codePoints().toArray().toList()
        val filtered = if (0x200d in codePoints) codePoints else codePoints.filter { it != 0xfe0f }
        return "https://cdn.jsdelivr.net/gh/twitter/twemoji@latest/assets/svg/${filtered.joinToString("-") { Integer.toHexString(it) }}.svg"
    }

    fun customEmojiUrl(id: String, isAnimated: Boolean) =
        "$baseUrl/emojis/$id.${if (isAnimated) "gif" else "png"}"

    fun channelIconUrl(channelId: String, hash: String, size: Int = 512) =
        "$baseUrl/channel-icons/$channelId/$hash.${ext(hash)}?size=$size"

    fun userAvatarUrl(userId: String, hash: String, size: Int = 512) =
        "$baseUrl/avatars/$userId/$hash.${ext(hash)}?size=$size"

    fun fallbackUserAvatarUrl(index: Int = 0) = "$baseUrl/embed/avatars/$index.png"

    fun stickerUrl(id: String, format: String) = "$baseUrl/stickers/$id.$format"
}

object Snowflake {
    private const val DISCORD_EPOCH = 1420070400000L

    fun toInstant(id: String): Instant =
        Instant.ofEpochMilli((id.toULong() shr 22).toLong() + DISCORD_EPOCH)

    fun compare(a: String, b: String): Int = a.toULong().compareTo(b.toULong())
}

// JSON helpers mirroring JsonExtensions' "OrNull" semantics
internal fun JSONObject.str(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

internal fun JSONObject.nonBlank(name: String): String? = str(name)?.takeIf { it.isNotBlank() }

internal fun JSONObject.intOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) (opt(name) as? Number)?.toInt() ?: str(name)?.toIntOrNull()
    else null

internal fun JSONObject.longOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) (opt(name) as? Number)?.toLong() ?: str(name)?.toLongOrNull()
    else null

internal fun JSONObject.boolOrFalse(name: String): Boolean =
    if (has(name) && !isNull(name)) optBoolean(name, false) else false

internal fun JSONObject.obj(name: String): JSONObject? =
    if (has(name) && !isNull(name)) optJSONObject(name) else null

internal fun JSONObject.instant(name: String): Instant? =
    nonBlank(name)?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

internal inline fun <T> JSONObject.list(name: String, parse: (JSONObject) -> T): List<T> {
    val array = if (has(name) && !isNull(name)) optJSONArray(name) else null
    return array?.objects()?.map(parse) ?: emptyList()
}

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

data class User(
    val id: String,
    val isBot: Boolean,
    val discriminator: Int?,
    val name: String,
    val displayName: String,
    val avatarUrl: String,
) {
    val fullName: String
        get() = if (discriminator != null) "$name#${"%04d".format(discriminator)}" else name

    companion object {
        fun parse(json: JSONObject): User {
            val id = json.getString("id")
            val discriminator = json.nonBlank("discriminator")?.toIntOrNull()?.takeIf { it != 0 }
            val name = json.str("username") ?: "Unknown"
            val displayName = json.nonBlank("global_name") ?: name
            val avatarIndex = discriminator?.rem(5) ?: ((id.toULong() shr 22) % 6u).toInt()
            val avatarUrl = json.nonBlank("avatar")?.let { ImageCdn.userAvatarUrl(id, it) }
                ?: ImageCdn.fallbackUserAvatarUrl(avatarIndex)

            return User(id, json.boolOrFalse("bot"), discriminator, name, displayName, avatarUrl)
        }
    }
}

data class Channel(
    val id: String,
    val kind: Int,
    val name: String,
    val iconUrl: String?,
    val lastMessageId: String?,
    val recipients: List<User>,
) {
    val isDirect: Boolean get() = kind == KIND_DM || kind == KIND_GROUP_DM
    val isGroup: Boolean get() = kind == KIND_GROUP_DM

    companion object {
        const val KIND_DM = 1
        const val KIND_GROUP_DM = 3

        fun parse(json: JSONObject): Channel {
            val id = json.getString("id")
            val recipients = json.list("recipients", User::parse)
            val name = json.nonBlank("name")
                ?: recipients.sortedWith { a, b -> Snowflake.compare(a.id, b.id) }
                    .joinToString(", ") { it.displayName }.ifBlank { null }
                ?: id

            return Channel(
                id = id,
                kind = json.optInt("type", 0),
                name = name,
                iconUrl = json.nonBlank("icon")?.let { ImageCdn.channelIconUrl(id, it) },
                lastMessageId = json.nonBlank("last_message_id"),
                recipients = recipients,
            )
        }
    }
}

data class Attachment(
    val id: String,
    val url: String,
    val fileName: String,
    val description: String?,
    val fileSize: Long,
) {
    private val extension = fileName.substringAfterLast('.', "").lowercase()

    val isImage get() = extension in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    val isVideo get() = extension in setOf("gifv", "mp4", "webm", "mov")
    val isAudio get() = extension in setOf("mp3", "wav", "ogg", "flac", "m4a")
    val isSpoiler get() = fileName.startsWith("SPOILER_")

    companion object {
        fun parse(json: JSONObject) = Attachment(
            id = json.getString("id"),
            url = json.getString("url"),
            fileName = json.str("filename") ?: "file",
            description = json.nonBlank("description"),
            fileSize = json.longOrNull("size") ?: 0,
        )
    }
}

data class Emoji(val id: String?, val name: String, val isAnimated: Boolean) {
    val isCustom get() = id != null

    // Standard emoji are rendered as text, so they don't need an image
    val imageUrl: String? get() = id?.let { ImageCdn.customEmojiUrl(it, isAnimated) }

    companion object {
        fun parse(json: JSONObject) = Emoji(
            id = json.nonBlank("id"),
            name = json.nonBlank("name") ?: "Unknown Emoji",
            isAnimated = json.boolOrFalse("animated"),
        )
    }
}

data class Reaction(val emoji: Emoji, val count: Int) {
    companion object {
        fun parse(json: JSONObject) = Reaction(
            emoji = Emoji.parse(json.optJSONObject("emoji") ?: JSONObject()),
            count = json.optInt("count", 0),
        )
    }
}

data class Sticker(val id: String, val name: String, val format: Int, val sourceUrl: String) {
    // Lottie stickers (format 3) require a JS renderer, which this port doesn't ship
    val isImage get() = format != FORMAT_LOTTIE

    companion object {
        const val FORMAT_LOTTIE = 3

        fun parse(json: JSONObject): Sticker {
            val id = json.getString("id")
            val format = json.optInt("format_type", 1)
            val ext = when (format) {
                FORMAT_LOTTIE -> "json"
                4 -> "gif"
                else -> "png"
            }
            return Sticker(id, json.str("name") ?: "Sticker", format, ImageCdn.stickerUrl(id, ext))
        }
    }
}

data class EmbedAuthor(val name: String?, val url: String?, val iconUrl: String?, val iconProxyUrl: String?)
data class EmbedField(val name: String, val value: String, val isInline: Boolean)
data class EmbedMedia(val url: String?, val proxyUrl: String?, val width: Int?, val height: Int?)
data class EmbedFooter(val text: String, val iconUrl: String?, val iconProxyUrl: String?)

data class Embed(
    val title: String?,
    val kind: String,
    val url: String?,
    val timestamp: Instant?,
    val color: Int?,
    val author: EmbedAuthor?,
    val description: String?,
    val fields: List<EmbedField>,
    val thumbnail: EmbedMedia?,
    val images: List<EmbedMedia>,
    val video: EmbedMedia?,
    val footer: EmbedFooter?,
) {
    fun field(name: String) = fields.firstOrNull { it.name == name }?.value

    companion object {
        const val KIND_RICH = "rich"
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
        const val KIND_GIFV = "gifv"
        const val KIND_POLL_RESULT = "poll_result"

        private fun media(json: JSONObject?) = json?.let {
            EmbedMedia(it.nonBlank("url"), it.nonBlank("proxy_url"), it.intOrNull("width"), it.intOrNull("height"))
        }

        fun parse(json: JSONObject) = Embed(
            title = json.str("title"),
            kind = json.nonBlank("type") ?: KIND_RICH,
            url = json.nonBlank("url"),
            timestamp = json.instant("timestamp"),
            color = json.intOrNull("color"),
            author = json.obj("author")?.let {
                EmbedAuthor(it.nonBlank("name"), it.nonBlank("url"), it.nonBlank("icon_url"), it.nonBlank("proxy_icon_url"))
            },
            description = json.str("description"),
            fields = json.list("fields") {
                EmbedField(it.str("name") ?: "", it.str("value") ?: "", it.boolOrFalse("inline"))
            },
            thumbnail = media(json.obj("thumbnail")),
            images = listOfNotNull(media(json.obj("image"))),
            video = media(json.obj("video")),
            footer = json.obj("footer")?.let {
                EmbedFooter(it.str("text") ?: "", it.nonBlank("icon_url"), it.nonBlank("proxy_icon_url"))
            },
        )

        // The API splits multi-image embeds (e.g. tweets) into consecutive embeds with the same URL.
        // https://github.com/Tyrrrz/DiscordChatExporter/issues/695
        fun normalize(embeds: List<Embed>): List<Embed> {
            if (embeds.size <= 1) return embeds
            val result = mutableListOf<Embed>()
            var i = 0
            while (i < embeds.size) {
                val embed = embeds[i]
                if (embed.url?.contains("://twitter.com/", ignoreCase = true) == true) {
                    val trailing = embeds.drop(i + 1).takeWhile {
                        it.url == embed.url && it.timestamp == null && it.author == null &&
                            it.color == null && it.description.isNullOrBlank() &&
                            it.fields.isEmpty() && it.images.size == 1 && it.footer == null
                    }
                    result += embed.copy(images = embed.images + trailing.flatMap { it.images })
                    i += trailing.size
                } else {
                    result += embed
                }
                i++
            }
            return result
        }
    }
}

data class PollAnswer(val id: Int, val text: String, val emoji: Emoji?)
data class PollAnswerCount(val answerId: Int, val count: Int, val meVoted: Boolean)

data class Poll(
    val question: String,
    val answers: List<PollAnswer>,
    val expiresAt: Instant?,
    val allowsMultiple: Boolean,
    val isFinalized: Boolean?,
    val counts: List<PollAnswerCount>?,
) {
    val totalVotes get() = counts?.sumOf { it.count } ?: 0
    val winningVotes get() = counts?.maxOfOrNull { it.count } ?: 0

    companion object {
        fun parse(json: JSONObject): Poll {
            val results = json.obj("results")
            return Poll(
                question = json.obj("question")?.str("text") ?: "",
                answers = json.list("answers") {
                    val media = it.obj("poll_media")
                    PollAnswer(it.optInt("answer_id"), media?.str("text") ?: "", media?.obj("emoji")?.let(Emoji::parse))
                },
                expiresAt = json.instant("expiry"),
                allowsMultiple = json.boolOrFalse("allow_multiselect"),
                isFinalized = results?.boolOrFalse("is_finalized"),
                counts = results?.list("answer_counts") {
                    PollAnswerCount(it.optInt("id"), it.optInt("count"), it.boolOrFalse("me_voted"))
                },
            )
        }
    }
}

data class MessageSnapshot(
    val timestamp: Instant,
    val editedTimestamp: Instant?,
    val content: String,
    val attachments: List<Attachment>,
    val embeds: List<Embed>,
    val stickers: List<Sticker>,
) {
    companion object {
        fun parse(json: JSONObject) = MessageSnapshot(
            timestamp = json.instant("timestamp") ?: Instant.EPOCH,
            editedTimestamp = json.instant("edited_timestamp"),
            content = json.str("content") ?: "",
            attachments = json.list("attachments", Attachment::parse),
            embeds = Embed.normalize(json.list("embeds", Embed::parse)),
            stickers = json.list("sticker_items", Sticker::parse),
        )
    }
}

data class Message(
    val id: String,
    val kind: Int,
    val flags: Int,
    val author: User,
    val timestamp: Instant,
    val editedTimestamp: Instant?,
    val callEndedTimestamp: Instant?,
    val isPinned: Boolean,
    val content: String,
    val attachments: List<Attachment>,
    val embeds: List<Embed>,
    val stickers: List<Sticker>,
    val reactions: List<Reaction>,
    val mentionedUsers: List<User>,
    val referenceMessageId: String?,
    val referenceChannelId: String?,
    val referenceGuildId: String?,
    val referenceKind: Int?,
    val referencedMessage: Message?,
    val forwardedMessage: MessageSnapshot?,
    val interactionId: String?,
    val interactionName: String?,
    val interactionUser: User?,
    val poll: Poll?,
) {
    val isSystemNotification get() = kind in KIND_RECIPIENT_ADD..KIND_THREAD_CREATED || kind == KIND_POLL_RESULT
    val isReply get() = kind == KIND_REPLY
    val isReplyLike get() = isReply || interactionUser != null
    val isForwarded get() = referenceKind == 1
    val isCrossPost get() = flags and 1 != 0

    // Content is hidden when it's just a link to the media that is embedded right below it
    // https://github.com/Tyrrrz/DiscordChatExporter/issues/682
    val isContentHidden: Boolean
        get() {
            val embed = embeds.singleOrNull() ?: return false
            return content.trim().equals(embed.url, ignoreCase = true) &&
                (embed.kind == Embed.KIND_IMAGE || embed.kind == Embed.KIND_GIFV)
        }

    fun referencedUsers(): List<User> =
        listOfNotNull(author, referencedMessage?.author, interactionUser) + mentionedUsers

    companion object {
        const val KIND_RECIPIENT_ADD = 1
        const val KIND_RECIPIENT_REMOVE = 2
        const val KIND_CALL = 3
        const val KIND_CHANNEL_NAME_CHANGE = 4
        const val KIND_CHANNEL_ICON_CHANGE = 5
        const val KIND_CHANNEL_PINNED_MESSAGE = 6
        const val KIND_GUILD_MEMBER_JOIN = 7
        const val KIND_THREAD_CREATED = 18
        const val KIND_REPLY = 19
        const val KIND_POLL_RESULT = 46

        fun parse(json: JSONObject): Message {
            val reference = json.obj("message_reference")
            val interaction = json.obj("interaction")
            val snapshot = json.optJSONArray("message_snapshots")?.objects()
                ?.firstNotNullOfOrNull { it.obj("message") }

            return Message(
                id = json.getString("id"),
                kind = json.optInt("type", 0),
                flags = json.intOrNull("flags") ?: 0,
                author = User.parse(json.getJSONObject("author")),
                timestamp = json.instant("timestamp") ?: Instant.EPOCH,
                editedTimestamp = json.instant("edited_timestamp"),
                callEndedTimestamp = json.obj("call")?.instant("ended_timestamp"),
                isPinned = json.boolOrFalse("pinned"),
                content = json.str("content") ?: "",
                attachments = json.list("attachments", Attachment::parse),
                embeds = Embed.normalize(json.list("embeds", Embed::parse)),
                stickers = json.list("sticker_items", Sticker::parse),
                reactions = json.list("reactions", Reaction::parse),
                mentionedUsers = json.list("mentions", User::parse),
                referenceMessageId = reference?.nonBlank("message_id"),
                referenceChannelId = reference?.nonBlank("channel_id"),
                referenceGuildId = reference?.nonBlank("guild_id"),
                referenceKind = reference?.optInt("type", 0),
                referencedMessage = json.obj("referenced_message")?.let(::parse),
                forwardedMessage = snapshot?.let(MessageSnapshot::parse),
                interactionId = interaction?.nonBlank("id"),
                interactionName = interaction?.str("name"),
                interactionUser = interaction?.obj("user")?.let(User::parse),
                poll = json.obj("poll")?.let(Poll::parse),
            )
        }
    }
}
