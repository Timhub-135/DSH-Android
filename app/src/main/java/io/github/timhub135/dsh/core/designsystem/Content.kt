package io.github.timhub135.dsh.core.designsystem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import io.github.timhub135.dsh.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dsh", text))
    // Android 13+ shows its own clipboard confirmation; a Toast on top would double it.
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

/**
 * dsh MarkdownText: 14/24 body, h1 21/30 … h4 14/24, R16 code blocks on the code-block fill,
 * inline code on the inline-code fill. [compact] is the thinking style (13/20 tertiary).
 */
@Composable
fun DshMarkdown(text: String, modifier: Modifier = Modifier, compact: Boolean = false, color: Color = if (compact) Dsh.colors.labelTertiary else Dsh.colors.labelPrimary) {
    val c = Dsh.colors
    val body = (if (compact) DshType.xs13 else DshType.body).copy(color = color)
    fun h(size: Int, line: Int, w: Int) = body.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(w))
    val code = DshType.mono12.copy(fontSize = 11.sp, lineHeight = 19.sp, color = c.labelPrimary)
    Markdown(
        text,
        colors = markdownColor(text = color, codeBackground = c.codeBlock, inlineCodeBackground = c.inlineCode, dividerColor = c.borderL2, tableBackground = c.bgModule),
        typography = markdownTypography(
            h1 = if (compact) body.copy(fontWeight = FontWeight(600)) else h(21, 30, 700),
            h2 = if (compact) body.copy(fontWeight = FontWeight(600)) else h(19, 28, 700),
            h3 = if (compact) body.copy(fontWeight = FontWeight(600)) else h(18, 26, 700),
            h4 = body.copy(fontWeight = FontWeight(600)), h5 = body.copy(fontWeight = FontWeight(600)), h6 = body.copy(fontWeight = FontWeight(600)),
            text = body, code = code, inlineCode = body.copy(fontFamily = FontFamily.Monospace, fontSize = body.fontSize * 0.875f),
            quote = body.copy(color = c.labelSecondary), paragraph = body, ordered = body, bullet = body, list = body,
            textLink = androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = c.link, fontWeight = FontWeight(500))),
            table = body.copy(fontSize = 13.sp, lineHeight = 22.sp),
        ),
        padding = markdownPadding(block = if (compact) 2.dp else 6.dp, codeBlock = androidx.compose.foundation.layout.PaddingValues(14.dp, 10.dp)),
        modifier = modifier,
    )
}

/** dsh CodeBlock / TerminalBlock: R16 card on the code-block fill with a banner and copy button. */
@Composable
fun CodeCard(text: String, modifier: Modifier = Modifier, label: String? = null, maxHeight: Dp = 260.dp, header: String? = null) {
    val c = Dsh.colors
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().clip(Radius.lg).background(c.codeBlock).border(Hairline, c.borderL1, Radius.lg)) {
        Row(Modifier.fillMaxWidth().background(c.codeBanner).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label ?: "", Modifier.weight(1f), style = DshType.mono11, color = c.labelTertiary, maxLines = 1)
            IconBtn(R.drawable.dsw_copy_outline, { copyText(context, text) }, size = 32.dp, iconSize = 14.dp, tint = c.labelTertiary, contentDescription = stringResource(R.string.copy))
        }
        if (header != null) Text(header, Modifier.padding(horizontal = 14.dp, vertical = 2.dp), style = DshType.mono11, color = c.labelPrimary)
        SelectionContainer {
            Text(text, Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp), style = DshType.mono11, color = c.labelSecondary)
        }
    }
}

/** dsh IN/OUT fallback card: label column + monospace text, hairline divider between sections. */
@Composable
fun InOutCard(sections: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val c = Dsh.colors
    Column(modifier.fillMaxWidth().clip(Radius.lg).background(c.codeBlock).border(Hairline, c.borderL1, Radius.lg)) {
        sections.forEachIndexed { i, (label, text) ->
            if (i > 0) HDivider()
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(label, Modifier.width(36.dp), style = DshType.mono11, color = c.labelCaption)
                Spacer(Modifier.width(8.dp))
                SelectionContainer {
                    Text(text.ifBlank { "—" }, Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState()), style = DshType.mono11, color = c.labelSecondary)
                }
            }
        }
    }
}

/** dsh JsonTree: collapsible read-only JSON inspection. */
@Composable
fun JsonTree(element: JsonElement, modifier: Modifier = Modifier) {
    Column(modifier) { JsonNode(null, element, 0, true) }
}

@Composable
private fun JsonNode(key: String?, el: JsonElement, depth: Int, startOpen: Boolean) {
    val c = Dsh.colors
    val pad = Modifier.padding(start = (depth * 14).dp)
    val keyText = key?.let { "$it: " } ?: ""
    when (el) {
        is JsonObject, is JsonArray -> {
            var open by remember { mutableStateOf(startOpen && depth < 2) }
            val size = if (el is JsonObject) el.size else (el as JsonArray).size
            val brackets = if (el is JsonObject) "{…} $size" else "[…] $size"
            Row(pad.clickable { open = !open }.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Chevron(Modifier.size(12.dp), open = open, size = 12.dp)
                Text(keyText, style = DshType.mono12, color = c.labelSecondary)
                if (!open) Text(brackets, style = DshType.mono12, color = c.labelCaption)
            }
            if (open) {
                if (el is JsonObject) el.forEach { (k, v) -> JsonNode(k, v, depth + 1, false) }
                else (el as JsonArray).forEachIndexed { i, v -> JsonNode(i.toString(), v, depth + 1, false) }
            }
        }
        is JsonNull -> Text(keyText + "null", pad.padding(start = 12.dp), style = DshType.mono12, color = c.labelCaption)
        is JsonPrimitive -> SelectionContainer {
            Row(pad.padding(start = 12.dp)) {
                Text(keyText, style = DshType.mono12, color = c.labelSecondary)
                Text(if (el.isString) "\"${el.content.take(4000)}\"" else el.content, style = DshType.mono12,
                    color = if (el.isString) c.success else c.business)
            }
        }
    }
}

/** Inline screenshot thumbnail; tap opens the lightbox. */
@Composable
fun Screenshot(file: File, modifier: Modifier = Modifier, maxHeight: Dp = 240.dp) {
    var open by remember { mutableStateOf(false) }
    val c = Dsh.colors
    AsyncImage(file, stringResource(R.string.screenshot), modifier.heightIn(max = maxHeight).clip(Radius.lg).border(Hairline, c.borderL2, Radius.lg)
        .clickable { open = true }, contentScale = ContentScale.Fit)
    if (open) Lightbox(file) { open = false }
}

/** dsh ImageLightbox: black 88 % mask, pinch to zoom, tap to close. */
@Composable
fun Lightbox(file: File, onDismiss: () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { z, p, _ -> scale = (scale * z).coerceIn(1f, 6f); offset += p }
        Box(Modifier.fillMaxSize().background(Color(0xE0000000)).clickable { onDismiss() }, contentAlignment = Alignment.Center) {
            AsyncImage(file, null, Modifier.fillMaxSize().transformable(state)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
            IconBtn(R.drawable.dsw_close_outline, onDismiss, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp), size = 40.dp, iconSize = 20.dp, tint = Color.White, contentDescription = stringResource(R.string.close))
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, Modifier.width(96.dp), style = DshType.xs13, color = Dsh.colors.labelTertiary)
        SelectionContainer(Modifier.weight(1f)) { Text(value, style = if (mono) DshType.mono12 else DshType.xs13, color = Dsh.colors.labelPrimary) }
    }
}
