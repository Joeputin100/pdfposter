package com.posterpdf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * RC44 — BBCode renderer for community posts.
 *
 * Supported tags (top-level only — v1 does not nest tags inside each other):
 *   [b]bold[/b]
 *   [i]italic[/i]
 *   [url]https://example.com[/url]
 *   [url=https://example.com]link text[/url]
 *   [img]https://example.com/foo.png[/img]   (https only — http rejected)
 *   [code]preformatted[/code]
 *
 * Images are split out as standalone Compose blocks because
 * Compose's inline-image content path (InlineTextContent) is awkward
 * for variable-aspect images. Splitting also lets us put fillMaxWidth
 * with aspectRatio scaling on each one without disturbing flowing text.
 */
@Composable
fun BBCodeText(
    body: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val sections = remember(body) { parseBBCode(body) }
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sections.forEach { section ->
            when (section) {
                is BBSection.Text -> {
                    if (section.raw.isBlank()) return@forEach
                    val annotated = remember(section.raw) {
                        renderInlineBBCode(section.raw)
                    }
                    ClickableText(
                        text = annotated,
                        style = style.copy(color = MaterialTheme.colorScheme.onSurface),
                        onClick = { offset ->
                            annotated.getStringAnnotations(
                                tag = "URL", start = offset, end = offset,
                            ).firstOrNull()?.let { uriHandler.openUri(it.item) }
                        },
                    )
                }
                is BBSection.Image -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AsyncImage(
                            model = section.url,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

private sealed class BBSection {
    data class Text(val raw: String) : BBSection()
    data class Image(val url: String) : BBSection()
}

private val IMG_RE = Regex(
    """\[img\](https://[^\s\[\]]+)\[/img\]""",
    RegexOption.IGNORE_CASE,
)

/**
 * Splits raw body into alternating Text/Image sections at every [img]
 * tag. Anything that isn't an [img] (including malformed [img http://…])
 * stays in the Text section as-is so the inline parser can keep the
 * tag visible to the user — we don't silently strip http:// images;
 * the user can fix the URL and re-post.
 */
private fun parseBBCode(body: String): List<BBSection> {
    val out = mutableListOf<BBSection>()
    var cursor = 0
    IMG_RE.findAll(body).forEach { m ->
        if (m.range.first > cursor) {
            out += BBSection.Text(body.substring(cursor, m.range.first))
        }
        out += BBSection.Image(m.groupValues[1])
        cursor = m.range.last + 1
    }
    if (cursor < body.length) {
        out += BBSection.Text(body.substring(cursor))
    }
    if (out.isEmpty()) out += BBSection.Text(body)
    return out
}

/**
 * Inline BBCode → AnnotatedString. Supports b/i/url/code tags. Tags
 * are matched non-greedy and processed left-to-right; nesting is not
 * supported in v1 (a [b][i]…[/i][/b] would render the outer span only).
 */
private val INLINE_RE = Regex(
    """\[(b|i|code)\](.*?)\[/\1\]""" +
        """|\[url=([^\]]+)\](.*?)\[/url\]""" +
        """|\[url\]([^\[]+)\[/url\]""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private fun renderInlineBBCode(raw: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    INLINE_RE.findAll(raw).forEach { m ->
        if (m.range.first > cursor) append(raw.substring(cursor, m.range.first))
        val simple = m.groupValues[1]
        when {
            simple.equals("b", ignoreCase = true) -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(m.groupValues[2])
                pop()
            }
            simple.equals("i", ignoreCase = true) -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(m.groupValues[2])
                pop()
            }
            simple.equals("code", ignoreCase = true) -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                append(m.groupValues[2])
                pop()
            }
            m.groupValues[3].isNotEmpty() -> {
                val href = m.groupValues[3]
                val text = m.groupValues[4]
                pushStringAnnotation(tag = "URL", annotation = href)
                pushStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.Underline,
                        // colored via outer Text; spans inherit theme.
                    ),
                )
                append(text)
                pop()
                pop()
            }
            m.groupValues[5].isNotEmpty() -> {
                val href = m.groupValues[5]
                pushStringAnnotation(tag = "URL", annotation = href)
                pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                append(href)
                pop()
                pop()
            }
        }
        cursor = m.range.last + 1
    }
    if (cursor < raw.length) append(raw.substring(cursor))
}
