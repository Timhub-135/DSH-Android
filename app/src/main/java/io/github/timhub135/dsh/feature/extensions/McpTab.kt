package io.github.timhub135.dsh.feature.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DInput
import io.github.timhub135.dsh.core.designsystem.DSwitch
import io.github.timhub135.dsh.core.designsystem.DotKind
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.EmptyNote
import io.github.timhub135.dsh.core.designsystem.IconBtn
import io.github.timhub135.dsh.core.designsystem.Segmented
import io.github.timhub135.dsh.core.designsystem.SettingsCard
import io.github.timhub135.dsh.core.designsystem.SettingsGroupTitle
import io.github.timhub135.dsh.core.designsystem.SettingsRow
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.StateDot
import io.github.timhub135.dsh.core.designsystem.Tag
import io.github.timhub135.dsh.core.designsystem.Tone
import io.github.timhub135.dsh.core.extensions.ExtException
import io.github.timhub135.dsh.core.extensions.ExtText
import io.github.timhub135.dsh.core.extensions.InstallSource
import io.github.timhub135.dsh.core.extensions.McpCandidate
import io.github.timhub135.dsh.core.extensions.McpImport
import io.github.timhub135.dsh.core.extensions.McpServer
import io.github.timhub135.dsh.core.extensions.McpServerStatus
import io.github.timhub135.dsh.core.extensions.McpTestResult
import io.github.timhub135.dsh.core.extensions.UserError
import kotlinx.coroutines.launch

/** MCP 服务 (§11): the user's servers with live status, and the three ways to add one. */
@Composable
fun McpTab(vm: ExtensionsViewModel, open: (io.github.timhub135.dsh.nav.Route) -> Unit) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.mcpStatus.collectAsStateWithLifecycle()
    var paste by remember { mutableStateOf(false) }
    LaunchedEffect(s.mcpServers.size) { vm.loadMcpStatus() }

    Text(stringResource(R.string.ext_mcp_desc), Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        SettingsRow(stringResource(R.string.ext_mcp_add_form), null, divider = false, onClick = { open(io.github.timhub135.dsh.nav.McpRoute()) },
            leading = { DIcon(R.drawable.dsw_link_outline, size = 16.dp, tint = c.labelSecondary) })
        SettingsRow(stringResource(R.string.ext_mcp_add_json), null, onClick = { paste = true },
            leading = { DIcon(R.drawable.dsw_code_outline, size = 16.dp, tint = c.labelSecondary) })
        SettingsRow(stringResource(R.string.ext_mcp_add_npm), null, onClick = { open(io.github.timhub135.dsh.nav.McpRoute(npm = true)) },
            leading = { DIcon(R.drawable.dsw_download_outline, size = 16.dp, tint = c.labelSecondary) })
    }
    SettingsGroupTitle(stringResource(R.string.ext_tab_mcp))
    SettingsCard {
        if (s.mcpServers.isEmpty()) EmptyNote(stringResource(R.string.ext_mcp_empty))
        s.mcpServers.forEachIndexed { i, m ->
            val st = status?.servers?.firstOrNull { it.server == m.id }
            SettingsRow(m.name, listOf(stringResource(if (m.local) R.string.ext_mcp_kind_npm else R.string.ext_mcp_kind_remote), mcpStateText(m, st)).joinToString(" · "),
                divider = i > 0, onClick = { open(io.github.timhub135.dsh.nav.McpRoute(m.id)) },
                leading = { StateDot(mcpDot(m, st)) },
                trailing = { McpSwitch(vm, m) })
        }
    }
    if (s.mcpServers.any { it.local }) Text(stringResource(R.string.ext_mcp_local_note, McpServer.MAX_LOCAL_ENABLED), Modifier.padding(start = 4.dp, top = 6.dp),
        style = DshType.xxs12, color = c.labelTertiary)
    if (paste) PasteJsonDialog(vm) { paste = false }
}

@Composable
private fun McpSwitch(vm: ExtensionsViewModel, m: McpServer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val limit = stringResource(R.string.ext_mcp_local_limit, McpServer.MAX_LOCAL_ENABLED)
    DSwitch(m.enabled, { on -> scope.launch { if (!vm.setMcpEnabled(m.id, on)) android.widget.Toast.makeText(context, limit, android.widget.Toast.LENGTH_LONG).show() } })
}

@Composable
private fun mcpStateText(m: McpServer, st: McpServerStatus?): String = when {
    !m.enabled -> stringResource(R.string.ext_mcp_state_off)
    st == null -> stringResource(R.string.ext_status_pending)
    st.state == "connected" -> stringResource(R.string.ext_mcp_state_connected, st.toolNames.size)
    st.state == "failed" -> stringResource(R.string.ext_mcp_state_failed) + (st.errorText?.let { " · $it" } ?: "")
    st.state == "disabled" -> stringResource(R.string.ext_mcp_state_off)
    else -> stringResource(R.string.ext_mcp_state_connecting)
}

private fun mcpDot(m: McpServer, st: McpServerStatus?) = when {
    !m.enabled -> DotKind.Idle
    st?.state == "connected" -> DotKind.Done
    st?.state == "failed" -> DotKind.Error
    else -> DotKind.Info
}

/** Add ([serverId] null; [npmNew] opens on the npm kind) or edit one server; remote servers are tested before saving. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun McpEditorPage(vm: ExtensionsViewModel, serverId: String?, npmNew: Boolean, onDone: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.mcpStatus.collectAsStateWithLifecycle()
    val existing = serverId?.let { s.mcpServers.firstOrNull { m -> m.id == it } }
    if (serverId != null && existing == null) { LaunchedEffect(Unit) { onDone() }; return }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: if (npmNew) McpServer.KIND_NPM else McpServer.KIND_REMOTE) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(existing?.url.orEmpty()) }
    var pkg by rememberSaveable { mutableStateOf(existing?.`package`.orEmpty()) }
    var args by rememberSaveable { mutableStateOf(existing?.args?.joinToString("\n").orEmpty()) }
    val headers = remember { mutableStateListOf<Pair<String, String>>().apply { existing?.headerNames?.forEach { add(it to "") } } }
    var busy by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<McpTestResult?>(null) }
    var error by remember { mutableStateOf<UserError?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun draft(): McpServer {
        val id = existing?.id ?: McpServer.newId(name.ifBlank { url.substringAfter("://").substringBefore('/').substringBefore('.').ifBlank { pkg.substringAfterLast('/') } },
            s.mcpServers.map { it.id })
        val base = existing ?: McpServer(id)
        return if (kind == McpServer.KIND_NPM) base.copy(name = name.ifBlank { id }, kind = kind, `package` = pkg.trim(), args = args.lines().map { it.trim() }.filter { it.isNotEmpty() })
        else base.copy(name = name.ifBlank { id }, kind = kind, url = url.trim(), headerNames = headers.map { it.first.trim() }.filter { it.isNotEmpty() }.distinct())
    }
    val headerValues = { headers.filter { it.first.isNotBlank() && it.second.isNotEmpty() }.associate { it.first.trim() to it.second } }

    Spacer(Modifier.height(8.dp))
    if (existing == null) Segmented(listOf(stringResource(R.string.ext_mcp_kind_remote), stringResource(R.string.ext_mcp_add_npm)),
        if (kind == McpServer.KIND_NPM) 1 else 0, { kind = if (it == 1) McpServer.KIND_NPM else McpServer.KIND_REMOTE; test = null; error = null })
    SettingsCard(Modifier.padding(top = 12.dp)) {
        SettingsRow(stringResource(R.string.ext_mcp_name), stringResource(R.string.ext_mcp_name_hint), divider = false, stacked = true) {
            DInput(name, { name = it.take(40) }, placeholder = "github")
        }
        if (kind == McpServer.KIND_REMOTE) {
            SettingsRow(stringResource(R.string.ext_mcp_url), null, stacked = true) {
                DInput(url, { url = it.trim(); test = null }, placeholder = "https://example.com/mcp", keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri)
                // SSE-only endpoints are refused before anything is saved.
                if (url.isNotBlank()) (McpImport.classify("x", kotlinx.serialization.json.buildJsonObject { put("url", kotlinx.serialization.json.JsonPrimitive(url)) }) as? McpCandidate.Unsupported)?.let {
                    Text(stringResource(R.string.err_mcp_sse_only), Modifier.padding(top = 4.dp), style = DshType.xxs12, color = c.warnLabel)
                }
            }
            SettingsRow(stringResource(R.string.ext_mcp_headers), stringResource(R.string.ext_mcp_secret_note), stacked = true) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    headers.forEachIndexed { i, (h, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.weight(0.4f)) { DInput(h, { headers[i] = it.trim() to v }, placeholder = "Authorization") }
                            Box(Modifier.weight(0.6f)) {
                                DInput(v, { headers[i] = h to it }, placeholder = if (existing?.headerNames?.contains(h) == true) stringResource(R.string.ext_mcp_header_keep) else "Bearer …", secret = true)
                            }
                            IconBtn(R.drawable.dsw_close_outline, { headers.removeAt(i) }, size = 32.dp)
                        }
                    }
                    DButton(stringResource(R.string.ext_mcp_header_add), { headers.add("Authorization" to "") }, variant = BtnVariant.Ghost, size = BtnSize.Sm, icon = R.drawable.dsw_plus_outline)
                }
            }
        } else {
            SettingsRow(stringResource(R.string.ext_mcp_package), stringResource(R.string.ext_mcp_local_note, McpServer.MAX_LOCAL_ENABLED), stacked = true) {
                if (existing == null) DInput(pkg, { pkg = it.trim() }, placeholder = stringResource(R.string.ext_mcp_package_hint))
                else Text(pkg, style = DshType.mono12, color = c.labelSecondary)
            }
            SettingsRow(stringResource(R.string.ext_mcp_args), null, stacked = true) {
                DInput(args, { args = it }, singleLine = false, minHeight = 64.dp)
            }
        }
    }

    // Existing server: switches, live tools.
    if (existing != null) {
        val st = status?.servers?.firstOrNull { it.server == existing.id }
        SettingsCard(Modifier.padding(top = 12.dp)) {
            SettingsRow(stringResource(R.string.ext_enable), mcpStateText(existing, st), divider = false, leading = { StateDot(mcpDot(existing, st)) },
                trailing = { McpSwitch(vm, existing) })
            SettingsRow(stringResource(R.string.ext_ask_before_call), stringResource(R.string.ext_ask_before_call_desc),
                trailing = { DSwitch(existing.askBeforeCall, { vm.setMcpAsk(existing.id, it) }) })
            if (st != null && st.toolNames.isNotEmpty()) SettingsRow(stringResource(R.string.ext_tools), null, stacked = true) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.toolNames.forEach { Tag("mcp__${existing.id}__$it", Tone.Neutral, mono = true) }
                }
            }
        }
    }

    // Test result / error, in place.
    test?.let { t ->
        if (t.ok) Notice(stringResource(R.string.ext_mcp_test_ok, t.tools.size), Tone.Success)
        else Notice(t.error?.let { ExtText.error(context, it) } ?: stringResource(R.string.ext_mcp_state_failed), Tone.Warn)
    }
    error?.let { Notice(ExtText.error(context, it), Tone.Warn) }

    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (busy) { Spinner(14.dp); Spacer(Modifier.width(4.dp)) }
        if (existing != null) DButton(stringResource(R.string.delete), { confirmDelete = true }, variant = BtnVariant.Danger, size = BtnSize.Sm)
        if (kind == McpServer.KIND_REMOTE) {
            val sse = url.trimEnd('/').endsWith("/sse")
            val valid = url.startsWith("https://") || url.startsWith("http://")
            if (test?.ok == false) DButton(stringResource(R.string.ext_mcp_save_anyway), { vm.saveRemoteMcp(draft(), headerValues()); onDone() }, variant = BtnVariant.Outline, size = BtnSize.Sm)
            DButton(stringResource(R.string.ext_mcp_test_save), {
                busy = true; test = null
                scope.launch {
                    val d = draft()
                    val r = vm.testMcp(d, headerValues())
                    busy = false; test = r
                    if (r.ok) { vm.saveRemoteMcp(d, headerValues()); onDone() }
                }
            }, size = BtnSize.Sm, enabled = valid && !sse && !busy)
        } else if (existing == null) {
            DButton(stringResource(R.string.ext_mcp_install_save), {
                busy = true; error = null
                scope.launch {
                    val r = vm.addNpmMcp(draft())
                    busy = false
                    r.onSuccess { onDone() }.onFailure { e -> error = (e as? ExtException)?.error ?: UserError("pnpm-failed", e.message.orEmpty()) }
                }
            }, size = BtnSize.Sm, enabled = InstallSource.npmSpec(pkg.trim()) != null && !busy)
        } else {
            DButton(stringResource(R.string.save), { vm.saveRemoteMcp(draft(), emptyMap()); onDone() }, size = BtnSize.Sm)
        }
    }
    if (busy && kind == McpServer.KIND_NPM) Text(stringResource(R.string.ext_mcp_installing, pkg), Modifier.padding(top = 6.dp), style = DshType.xxs12, color = c.labelTertiary)

    if (confirmDelete && existing != null) DDialog({ confirmDelete = false }, stringResource(R.string.delete), buttons = {
        DButton(stringResource(R.string.cancel), { confirmDelete = false }, variant = BtnVariant.Outline)
        DButton(stringResource(R.string.delete), { confirmDelete = false; vm.removeMcp(existing.id); onDone() }, variant = BtnVariant.Danger)
    }) { Text(stringResource(R.string.ext_mcp_remove_confirm, existing.name), style = DshType.s14, color = c.labelSecondary) }
}

/** 粘贴 JSON 配置: classify each entry (§11.1) and add the supported ones. */
@Composable
internal fun PasteJsonDialog(vm: ExtensionsViewModel, initial: String = "", onDismiss: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var parsed by remember { mutableStateOf<List<McpCandidate>?>(initial.takeIf { it.isNotBlank() }?.let { runCatching { McpImport.parse(it) }.getOrNull() }) }
    var invalid by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val supported = parsed.orEmpty().filter { it !is McpCandidate.Unsupported }
    DDialog(onDismiss, stringResource(R.string.ext_mcp_add_json), buttons = {
        DButton(stringResource(R.string.cancel), onDismiss, variant = BtnVariant.Outline)
        if (parsed == null) DButton(stringResource(R.string.ext_mcp_json_parse), {
            runCatching { McpImport.parse(text) }.onSuccess { parsed = it; invalid = false }.onFailure { invalid = true }
        }, enabled = text.isNotBlank())
        else DButton(stringResource(R.string.ext_mcp_json_add, supported.size), {
            busy = true
            scope.launch {
                val n = vm.addCandidates(supported)
                busy = false
                android.widget.Toast.makeText(context, context.getString(R.string.ext_mcp_json_added, n), android.widget.Toast.LENGTH_LONG).show()
                onDismiss()
            }
        }, enabled = supported.isNotEmpty() && !busy)
    }) {
        if (parsed == null) {
            Text(stringResource(R.string.ext_mcp_json_hint), style = DshType.xs13, color = c.labelTertiary)
            DInput(text, { text = it; invalid = false }, placeholder = "{\"mcpServers\": {…}}", singleLine = false, minHeight = 140.dp)
            if (invalid) Text(stringResource(R.string.ext_mcp_json_invalid), style = DshType.xxs12, color = c.error)
        } else {
            if (parsed!!.isEmpty()) Text(stringResource(R.string.ext_mcp_json_none), style = DshType.s14, color = c.labelSecondary)
            parsed!!.forEach { cand ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(top = 7.dp)) { StateDot(if (cand is McpCandidate.Unsupported) DotKind.Warn else DotKind.Done) }
                    Column(Modifier.weight(1f)) {
                        Text(cand.name, style = DshType.s14m, color = c.labelPrimary)
                        Text(when (cand) {
                            is McpCandidate.Remote -> stringResource(R.string.ext_mcp_remote_line, cand.url)
                            is McpCandidate.Npm -> stringResource(R.string.ext_mcp_npm_line, cand.pkg)
                            is McpCandidate.Unsupported -> if (cand.code == "mcp-runtime" && cand.runtime != null) stringResource(R.string.err_mcp_runtime, cand.runtime)
                                else ExtText.error(context, UserError(cand.code))
                        }, style = DshType.xxs12, color = if (cand is McpCandidate.Unsupported) c.warnLabel else c.labelTertiary)
                    }
                }
            }
            if (busy) Spinner(14.dp)
        }
    }
}
