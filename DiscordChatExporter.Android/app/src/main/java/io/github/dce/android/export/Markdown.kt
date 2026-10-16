package io.github.dce.android.export

import io.github.dce.android.discord.ImageCdn
import java.time.Instant

// Port of DiscordChatExporter.Core/Markdown.
// Discord doesn't use a recursive-descent parser, so (like the original) this runs a set of
// regular expressions in a first-matched-first-served manner: the earliest match wins, and
// ties go to the matcher listed first.

sealed interface MdNode

data class TextNode(val text: String) : MdNode

enum class FormattingKind { Bold, Italic, Underline, Strikethrough, Spoiler, Quote }

data class FormattingNode(val kind: FormattingKind, val children: List<MdNode>) : MdNode

data class HeadingNode(val level: Int, val children: List<MdNode>) : MdNode

data class ListNode(val items: List<List<MdNode>>) : MdNode

data class InlineCodeNode(val code: String) : MdNode

data class MultiLineCodeNode(val language: String, val code: String) : MdNode

enum class MentionKind { Everyone, Here, User, Channel, Role }

data class MentionNode(val targetId: String?, val kind: MentionKind) : MdNode

/** Custom emoji only; standard emoji are left as text and rendered by the viewer's font. */
data class EmojiNode(val id: String, val name: String, val isAnimated: Boolean) : MdNode

data class LinkNode(val url: String, val children: List<MdNode>) : MdNode

/** [instant] is null for invalid timestamps; [format] is null for the default format. */
data class TimestampNode(val instant: Instant?, val format: String?) : MdNode

object MarkdownParser {
    private const val MAX_DEPTH = 32

    private class Match(val start: Int, val end: Int, val node: MdNode)

    private fun interface Matcher {
        fun match(text: String, depth: Int): Match?
    }

    private val defaultOptions = setOf(RegexOption.MULTILINE, RegexOption.UNIX_LINES)
    private val singleLine = defaultOptions + RegexOption.DOT_MATCHES_ALL

    private fun regex(pattern: String, options: Set<RegexOption> = defaultOptions, transform: (MatchResult, Int) -> MdNode?) =
        Regex(pattern, options).let { r ->
            Matcher { text, depth ->
                r.find(text)?.let { m -> transform(m, depth)?.let { Match(m.range.first, m.range.last + 1, it) } }
            }
        }

    private fun string(value: String, transform: () -> MdNode) = Matcher { text, _ ->
        val index = text.indexOf(value)
        if (index >= 0) Match(index, index + value.length, transform()) else null
    }

    private fun MatchResult.g(i: Int) = groups[i]?.value ?: ""

    /* Formatting */

    private val bold: Matcher = regex("""\*\*(.+?)\*\*(?!\*)""", singleLine) { m, d ->
        FormattingNode(FormattingKind.Bold, parse(m.g(1), d))
    }

    private val italicBold = regex("""\*(\*\*.+?\*\*)\*(?!\*)""", singleLine) { m, d ->
        FormattingNode(FormattingKind.Italic, parse(m.g(1), d, listOf(bold)))
    }

    private val italic = regex("""\*(?!\s)(.+?)(?<!\s|\*)\*(?!\*)""", singleLine) { m, d ->
        FormattingNode(FormattingKind.Italic, parse(m.g(1), d))
    }

    private val underline: Matcher = regex("""__(.+?)__(?!_)""", singleLine) { m, d ->
        FormattingNode(FormattingKind.Underline, parse(m.g(1), d))
    }

    private val italicUnderline = regex("""_(__.+?__)_(?!_)""", singleLine) { m, d ->
        FormattingNode(FormattingKind.Italic, parse(m.g(1), d, listOf(underline)))
    }

    private val italicAlt = regex("""_(.+?)_(?!\w)""", singleLine) { m, d ->
        FormattingNode(FormattingKind.Italic, parse(m.g(1), d))
    }

    private val strikethrough = regex("""~~(.+?)~~""", singleLine) { m, d ->
        FormattingNode(FormattingKind.Strikethrough, parse(m.g(1), d))
    }

    private val spoiler = regex("""\|\|(.+?)\|\|""", singleLine) { m, d ->
        FormattingNode(FormattingKind.Spoiler, parse(m.g(1), d))
    }

    private val multiLineQuote = regex("""^>>>\s(.+)""", singleLine) { m, d ->
        FormattingNode(FormattingKind.Quote, parse(m.g(1), d))
    }

    // Java regexes don't keep per-capture history, so re-split the matched lines manually
    private val quoteLine = Regex("""^>\s(.*\n?)""", defaultOptions)
    private val repeatedSingleLineQuote = regex("""(?:^>\s(.*\n?)){2,}""") { m, d ->
        FormattingNode(FormattingKind.Quote, quoteLine.findAll(m.value).flatMap { parse(it.g(1), d) }.toList())
    }

    private val singleLineQuote = regex("""^>\s(.+\n?)""") { m, d ->
        FormattingNode(FormattingKind.Quote, parse(m.g(1), d))
    }

    private val heading = regex("""^(#{1,3})\s(.+)\n""") { m, d ->
        HeadingNode(m.g(1).length, parse(m.g(2), d))
    }

    private val list = regex("""^(\s*)(?:[\-*]\s(.+(?:\n\s\1.*)*)?\n?)+""") { m, d ->
        val indent = m.g(1)
        val item = java.util.regex.Pattern.compile(
            """[\-*]\s(.+(?:\n\s${java.util.regex.Pattern.quote(indent)}.*)*)?\n?""",
            java.util.regex.Pattern.UNIX_LINES,
        ).matcher(m.value)
        val items = mutableListOf<List<MdNode>>()
        var position = indent.length
        while (position < m.value.length) {
            item.region(position, m.value.length)
            if (!item.lookingAt() || item.end() == position) break
            items += parse(item.group(1) ?: "", d)
            position = item.end()
        }
        ListNode(items)
    }

    /* Code blocks */

    private val inlineCode = regex("""(`{1,2})([^`]+)\1""", singleLine) { m, _ -> InlineCodeNode(m.g(2)) }

    private val multiLineCode = regex("""```(?:(\w*)\n)?(.+?)```""", singleLine) { m, _ ->
        MultiLineCodeNode(m.g(1), m.g(2).trim('\r', '\n'))
    }

    /* Mentions */

    private val everyone = string("@everyone") { MentionNode(null, MentionKind.Everyone) }
    private val here = string("@here") { MentionNode(null, MentionKind.Here) }
    private val userMention = regex("""<@!?(\d+)>""") { m, _ -> MentionNode(m.g(1), MentionKind.User) }
    private val channelMention = regex("""<#!?(\d+)>""") { m, _ -> MentionNode(m.g(1), MentionKind.Channel) }
    private val roleMention = regex("""<@&(\d+)>""") { m, _ -> MentionNode(m.g(1), MentionKind.Role) }

    /* Emoji */

    private val customEmoji = regex("""<(a)?:(.+?):(\d+?)>""") { m, _ ->
        EmojiNode(m.g(3), m.g(2), m.g(1).isNotBlank())
    }

    /* Links */

    private const val URL = """https?://\S*[^.,:;"'\s]"""

    private val maskedLink = regex("""\[(.+?)\]\(($URL)\)""") { m, d -> LinkNode(m.g(2), parse(m.g(1), d)) }
    private val autoLink = regex("""($URL)""") { m, _ -> LinkNode(m.g(1), listOf(TextNode(m.g(1)))) }
    private val hiddenLink = regex("""<($URL)>""") { m, _ -> LinkNode(m.g(1), listOf(TextNode(m.g(1)))) }

    /* Text */

    private val shrug = string("""¯\_(ツ)_/¯""") { TextNode("""¯\_(ツ)_/¯""") }
    private val escapedSymbol = regex("""\\(\p{So}|[^\x00-\x{FFFF}])""") { m, _ -> TextNode(m.g(1)) }
    private val escapedCharacter = regex("""\\([^a-zA-Z0-9\s])""") { m, _ -> TextNode(m.g(1)) }

    /* Misc */

    private val timestamp = regex("""<t:(-?\d+)(?::(\w))?>""") { m, _ ->
        // Relative and unspecified formats fall back to the default format;
        // unknown formats render as an invalid date, like the Discord client does.
        val format = when (val f = m.g(2)) {
            "t", "T", "d", "D", "f", "F" -> f
            "r", "R", "" -> null
            else -> return@regex TimestampNode(null, null)
        }
        val instant = m.g(1).toLongOrNull()?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
        TimestampNode(instant, format)
    }

    private val allMatchers = listOf(
        shrug, escapedSymbol, escapedCharacter,
        italicBold, italicUnderline, bold, italic, underline, italicAlt, strikethrough, spoiler,
        multiLineQuote, repeatedSingleLineQuote, singleLineQuote, heading, list,
        multiLineCode, inlineCode,
        everyone, here, userMention, channelMention, roleMention,
        maskedLink, autoLink, hiddenLink,
        customEmoji,
        timestamp,
    )

    private fun parse(text: String, depth: Int, matchers: List<Matcher> = allMatchers): List<MdNode> {
        // Prevent stack overflow on inadvertently deep nesting (e.g. ****************)
        if (depth >= MAX_DEPTH) return listOf(TextNode(text))

        val nodes = mutableListOf<MdNode>()
        var position = 0
        while (position < text.length) {
            val rest = text.substring(position)
            var earliest: Match? = null
            for (matcher in matchers) {
                val match = matcher.match(rest, depth + 1) ?: continue
                if (earliest == null || match.start < earliest.start) earliest = match
                if (earliest.start == 0) break
            }
            if (earliest == null || earliest.end == 0) break
            if (earliest.start > 0) nodes += TextNode(rest.substring(0, earliest.start))
            nodes += earliest.node
            position += earliest.end
        }
        if (position < text.length) nodes += TextNode(text.substring(position))
        return nodes
    }

    // Minimal set for non-HTML formats: only the tokens that need resolving to be readable
    private val minimalMatchers = listOf(
        everyone, here, userMention, channelMention, roleMention,
        customEmoji,
        timestamp,
    )

    fun parse(markdown: String): List<MdNode> = parse(markdown, 0)

    fun parseMinimal(markdown: String): List<MdNode> = parse(markdown, 0, minimalMatchers)

    /** Custom emoji used anywhere in [markdown], deduplicated by name. */
    fun extractEmojis(markdown: String): List<EmojiNode> {
        val result = mutableListOf<EmojiNode>()
        fun visit(nodes: List<MdNode>) {
            for (node in nodes) {
                when (node) {
                    is EmojiNode -> result += node
                    is FormattingNode -> visit(node.children)
                    is HeadingNode -> visit(node.children)
                    is LinkNode -> visit(node.children)
                    is ListNode -> node.items.forEach(::visit)
                    else -> Unit
                }
            }
        }
        visit(parse(markdown))
        return result.distinctBy { it.name }
    }
}

/** Port of PlainTextMarkdownVisitor: resolves mentions, custom emoji and timestamps, leaving everything else as written. */
object PlainTextMarkdown {
    fun format(context: MarkdownContext, markdown: String): String = buildString {
        for (node in MarkdownParser.parseMinimal(markdown)) {
            when (node) {
                is TextNode -> append(node.text)
                is EmojiNode -> append(":${node.name}:")
                is MentionNode -> when (node.kind) {
                    MentionKind.Everyone -> append("@everyone")
                    MentionKind.Here -> append("@here")
                    MentionKind.User -> append("@" + (node.targetId?.let(context::userName)?.first ?: "Unknown"))
                    MentionKind.Channel -> append("#" + (node.targetId?.let(context::channelName) ?: "deleted-channel"))
                    MentionKind.Role -> append("@deleted-role")
                }
                is TimestampNode -> append(node.instant?.let { context.formatDate(it, node.format ?: "g") } ?: "Invalid date")
                else -> Unit
            }
        }
    }
}

/** Resolves the context-dependent bits of markdown (mentions, assets, dates) while rendering. */
interface MarkdownContext {
    fun resolveAsset(url: String): String
    fun userName(id: String): Pair<String, String>? // display name, full name
    fun channelName(id: String): String?
    fun formatDate(instant: Instant, format: String): String
}

object HtmlMarkdown {
    private val messageLink = Regex("""^https?://(?:discord|discordapp)\.com/channels/.*?/(\d+)/?$""")

    fun format(context: MarkdownContext, markdown: String, isJumboAllowed: Boolean = true): String {
        val nodes = MarkdownParser.parse(markdown)
        val isJumbo = isJumboAllowed && nodes.any { it is EmojiNode } &&
            nodes.all { it is EmojiNode || (it is TextNode && it.text.isBlank()) }
        return buildString { nodes.forEach { visit(context, it, isJumbo, this) } }
    }

    private fun visitAll(context: MarkdownContext, nodes: List<MdNode>, isJumbo: Boolean, out: StringBuilder) =
        nodes.forEach { visit(context, it, isJumbo, out) }

    private fun visit(context: MarkdownContext, node: MdNode, isJumbo: Boolean, out: StringBuilder) {
        when (node) {
            is TextNode -> out.append(node.text.html())

            is FormattingNode -> {
                val (open, close) = when (node.kind) {
                    FormattingKind.Bold -> "<strong>" to "</strong>"
                    FormattingKind.Italic -> "<em>" to "</em>"
                    FormattingKind.Underline -> "<u>" to "</u>"
                    FormattingKind.Strikethrough -> "<s>" to "</s>"
                    FormattingKind.Spoiler ->
                        """<span class="chatlog__markdown-spoiler chatlog__markdown-spoiler--hidden" onclick="showSpoiler(event, this)">""" to "</span>"
                    FormattingKind.Quote ->
                        """<div class="chatlog__markdown-quote"><div class="chatlog__markdown-quote-border"></div><div class="chatlog__markdown-quote-content">""" to "</div></div>"
                }
                out.append(open)
                visitAll(context, node.children, isJumbo, out)
                out.append(close)
            }

            is HeadingNode -> {
                out.append("<h${node.level}>")
                visitAll(context, node.children, isJumbo, out)
                out.append("</h${node.level}>")
            }

            is ListNode -> {
                out.append("<ul>")
                node.items.forEach {
                    out.append("<li>")
                    visitAll(context, it, isJumbo, out)
                    out.append("</li>")
                }
                out.append("</ul>")
            }

            is InlineCodeNode ->
                out.append("""<code class="chatlog__markdown-pre chatlog__markdown-pre--inline">${node.code.html()}</code>""")

            is MultiLineCodeNode -> {
                val highlightClass = if (node.language.isNotBlank()) "language-${node.language.html()}" else "nohighlight"
                out.append("""<code class="chatlog__markdown-pre chatlog__markdown-pre--multiline $highlightClass">${node.code.html()}</code>""")
            }

            is LinkNode -> {
                val linkedMessageId = messageLink.find(node.url)?.groupValues?.get(1)
                if (linkedMessageId != null) {
                    out.append("""<a href="${node.url.html()}" onclick="scrollToMessage(event, '$linkedMessageId')">""")
                } else {
                    out.append("""<a href="${node.url.html()}">""")
                }
                visitAll(context, node.children, isJumbo, out)
                out.append("</a>")
            }

            is EmojiNode -> {
                val src = context.resolveAsset(ImageCdn.customEmojiUrl(node.id, node.isAnimated))
                val jumboClass = if (isJumbo) "chatlog__emoji--large" else ""
                out.append(
                    """<img loading="lazy" class="chatlog__emoji $jumboClass" alt="${node.name.html()}" title="${node.name.html()}" src="${src.html()}">"""
                )
            }

            is MentionNode -> when (node.kind) {
                MentionKind.Everyone -> out.append("""<span class="chatlog__markdown-mention">@everyone</span>""")
                MentionKind.Here -> out.append("""<span class="chatlog__markdown-mention">@here</span>""")
                MentionKind.User -> {
                    val (displayName, fullName) = node.targetId?.let(context::userName) ?: ("Unknown" to "Unknown")
                    out.append("""<span class="chatlog__markdown-mention" title="${fullName.html()}">@${displayName.html()}</span>""")
                }
                MentionKind.Channel -> {
                    val name = node.targetId?.let(context::channelName) ?: "deleted-channel"
                    out.append("""<span class="chatlog__markdown-mention">#${name.html()}</span>""")
                }
                // Roles only exist in servers, so they can't be resolved from a DM
                MentionKind.Role -> out.append("""<span class="chatlog__markdown-mention">@deleted-role</span>""")
            }

            is TimestampNode -> {
                val formatted = node.instant?.let { context.formatDate(it, node.format ?: "g") } ?: "Invalid date"
                val formattedLong = node.instant?.let { context.formatDate(it, "f") } ?: ""
                out.append("""<span class="chatlog__markdown-timestamp" title="${formattedLong.html()}">${formatted.html()}</span>""")
            }
        }
    }
}

fun String.html(): String {
    val sb = StringBuilder(length + 16)
    for (c in this) {
        when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
