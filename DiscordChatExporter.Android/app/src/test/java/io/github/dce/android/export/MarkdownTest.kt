package io.github.dce.android.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MarkdownTest {
    private val context = object : MarkdownContext {
        override fun resolveAsset(url: String) = "asset:$url"
        override fun userName(id: String) = if (id == "42") "Alice" to "alice" else null
        override fun channelName(id: String): String? = null
        override fun formatDate(instant: Instant, format: String) =
            DateTimeFormatter.ISO_INSTANT.format(instant.atOffset(ZoneOffset.UTC)) + "|$format"
    }

    private fun html(markdown: String) = HtmlMarkdown.format(context, markdown)

    @Test
    fun formatting() {
        assertEquals("<strong>bold</strong> <em>it</em> <u>under</u> <s>gone</s>", html("**bold** *it* __under__ ~~gone~~"))
        assertEquals("<em><strong>both</strong></em>", html("***both***"))
        assertEquals("<em>alt</em>", html("_alt_"))
        assertEquals("snake_case_name", html("snake_case_name"))
    }

    @Test
    fun escapingAndHtmlEncoding() {
        assertEquals("*not italic*", html("""\*not italic\*"""))
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;", html("<script>alert(1)</script>"))
        assertEquals("""¯\_(ツ)_/¯""", html("""¯\_(ツ)_/¯"""))
    }

    @Test
    fun codeBlocks() {
        assertEquals(
            """<code class="chatlog__markdown-pre chatlog__markdown-pre--inline">**x**</code>""",
            html("`**x**`"),
        )
        assertEquals(
            """<code class="chatlog__markdown-pre chatlog__markdown-pre--multiline language-kotlin">val x = 1</code>""",
            html("```kotlin\nval x = 1\n```"),
        )
    }

    @Test
    fun quotesHeadingsAndLists() {
        assertTrue(html("> quoted").startsWith("""<div class="chatlog__markdown-quote">"""))
        val repeated = html("> one\n> two\nafter")
        assertTrue(repeated, repeated.contains("one\ntwo\n</div></div>after"))
        assertEquals("<h2>Title</h2>rest", html("## Title\nrest"))
        assertEquals("<ul><li>a</li><li>b</li></ul>", html("- a\n- b"))
    }

    @Test
    fun linksMentionsEmojiAndTimestamps() {
        assertEquals("""<a href="https://example.com">https://example.com</a>.""", html("https://example.com."))
        assertEquals("""<a href="https://example.com">site</a>""", html("[site](https://example.com)"))
        assertEquals("""<a href="https://example.com">https://example.com</a>""", html("<https://example.com>"))
        assertEquals("""<span class="chatlog__markdown-mention" title="alice">@Alice</span>""", html("<@42>"))
        assertEquals("""<span class="chatlog__markdown-mention" title="Unknown">@Unknown</span>""", html("<@!7>"))

        val emoji = html("<a:party:123>")
        assertTrue(emoji, emoji.contains("chatlog__emoji--large") && emoji.contains("asset:https://cdn.discordapp.com/emojis/123.gif"))
        assertTrue(html("hi <:wave:1>").contains("class=\"chatlog__emoji \""))

        assertTrue(html("<t:0:R>").contains(">1970-01-01T00:00:00Z|g<"))
        assertTrue(html("<t:0:D>").contains(">1970-01-01T00:00:00Z|D<"))
        assertTrue(html("<t:0:Q>").contains(">Invalid date<"))
    }

    @Test
    fun deepNestingDoesNotOverflow() {
        html("*".repeat(5000))
        html("||".repeat(2000) + "x")
    }
}
