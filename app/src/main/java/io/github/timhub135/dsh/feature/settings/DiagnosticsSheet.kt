package io.github.timhub135.dsh.feature.settings

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.timhub135.dsh.core.diagnostics.DiagnosticsExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.copyText

/**
 * 复制诊断信息: the whole report is shown first; only 复制 / 分享 / 导出到文件 take it anywhere (share
 * goes through the system chooser; the export adds the redacted engine log tail, [DiagnosticsExport]).
 * Nothing is sent automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(text: String, onDismiss: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var exported by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismiss, sheetState = sheet, containerColor = c.bgLayer2,
        shape = Radius.panel.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)),
        scrimColor = c.scrim, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.diag_title), style = DshType.s14m, color = c.labelPrimary)
            Text(stringResource(R.string.diag_note), style = DshType.xxs12, color = c.labelTertiary)
            SelectionContainer(Modifier.weight(1f, fill = false).fillMaxWidth().clip(Radius.lg).background(c.codeBlock)) {
                Text(text, Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
                    style = DshType.mono11, color = c.labelSecondary)
            }
            exported?.let { Text(it, style = DshType.xxs12, color = c.labelSecondary) }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DButton(stringResource(R.string.diag_export), {
                    scope.launch {
                        exported = withContext(Dispatchers.IO) {
                            runCatching { context.getString(R.string.diag_exported, DiagnosticsExport.export(context, text)) }
                                .getOrElse { context.getString(R.string.diag_export_failed, it.message ?: it.javaClass.simpleName) }
                        }
                    }
                }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                DButton(stringResource(R.string.share), {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                DButton(stringResource(R.string.copy), { copyText(context, text) }, size = BtnSize.Sm)
            }
        }
    }
}
