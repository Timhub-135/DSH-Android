package io.github.jackwener.dsh.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DCheckbox
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.DInput
import io.github.jackwener.dsh.core.designsystem.DSwitch
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.FilterPill
import io.github.jackwener.dsh.core.designsystem.Fmt
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsGroupTitle
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.Spinner
import io.github.jackwener.dsh.core.provider.CUSTOM
import io.github.jackwener.dsh.core.provider.DEEPSEEK_MODELS
import io.github.jackwener.dsh.core.provider.EffectiveLimits
import io.github.jackwener.dsh.core.provider.FetchedModels
import io.github.jackwener.dsh.core.provider.HeaderRow
import io.github.jackwener.dsh.core.provider.ModelVision
import io.github.jackwener.dsh.core.provider.headerRowsOf
import io.github.jackwener.dsh.core.provider.isUsableHeaderName
import io.github.jackwener.dsh.core.provider.isUsableHeaderValue
import io.github.jackwener.dsh.core.provider.VerifyErrors
import io.github.jackwener.dsh.core.provider.VerifyFailure
import io.github.jackwener.dsh.core.provider.VerifyOutcome
import io.github.jackwener.dsh.core.provider.API_CHAT
import io.github.jackwener.dsh.core.provider.API_RESPONSES
import io.github.jackwener.dsh.core.provider.endpointKey
import io.github.jackwener.dsh.core.provider.normalizeBaseUrl
import io.github.jackwener.dsh.core.provider.planTest
import io.github.jackwener.dsh.core.provider.verifiedOnSave
import io.github.jackwener.dsh.core.provider.rankCandidates
import io.github.jackwener.dsh.core.provider.LimitSource
import io.github.jackwener.dsh.core.provider.ModelLimits
import io.github.jackwener.dsh.core.provider.ModelReasoning
import io.github.jackwener.dsh.core.provider.ReasoningControl
import io.github.jackwener.dsh.core.provider.ReasoningDecl
import io.github.jackwener.dsh.core.provider.ReasoningLevels
import io.github.jackwener.dsh.core.provider.NetDiagnose
import io.github.jackwener.dsh.core.provider.NetDiagnosis
import io.github.jackwener.dsh.core.provider.PRESETS
import io.github.jackwener.dsh.core.provider.ProviderConfig
import io.github.jackwener.dsh.core.provider.ProviderPreset
import io.github.jackwener.dsh.core.provider.RemoteModel
import io.github.jackwener.dsh.core.provider.ThirdParty
import io.github.jackwener.dsh.core.provider.parseTokenInput
import io.github.jackwener.dsh.core.provider.refreshOffered
import io.github.jackwener.dsh.core.provider.newRouteId
import io.github.jackwener.dsh.core.provider.presetOf
import kotlinx.coroutines.launch

/** Sub-page target of the 模型服务 page: [NEW_PROVIDER] or a provider's route id. */
const val NEW_PROVIDER = "\u0000new"

/** fetchState marker: the connection test failed with a network cause (shown from fetchNet). */
private const val NET_FAILURE = "\u0000net"

@Composable
private fun presetName(p: ProviderPreset) = if (p.id == CUSTOM) stringResource(R.string.prov_custom) else p.name

/** Label for where a limit came from (also used by the composer's context ring). */
fun limitSourceLabel(s: LimitSource) = when (s) {
    LimitSource.DETECTED -> R.string.limit_src_detected
    LimitSource.MANUAL -> R.string.limit_src_manual
    LimitSource.DEFAULT -> R.string.limit_src_default
}

/** Blank or invalid → null (invalid text is reported on save). */
private fun tokensOrNull(text: String): Long? = parseTokenInput(text).getOrNull()
private fun Long.toIntSat(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

@Composable
private fun chevron() = DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = Dsh.colors.labelTertiary)

/** 第三方服务商: one row per provider (switch = shown in the picker), then 添加服务商. */
@Composable
fun ProvidersCard(vm: SettingsViewModel, onOpen: (String) -> Unit) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    SettingsGroupTitle(stringResource(R.string.prov_group))
    Text(stringResource(R.string.prov_group_desc), Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    val check by vm.modelCheck.collectAsStateWithLifecycle()
    SettingsCard {
        s.providers.forEachIndexed { i, p ->
            SettingsRow(p.name, stringResource(R.string.prov_models_count, p.models.size) + " · " + (Uri.parse(p.baseUrl).host ?: p.baseUrl),
                onClick = { onOpen(p.id) }, divider = i > 0,
                trailing = { DSwitch(p.enabled, { vm.setProviderEnabled(p, it) }) })
            // The main model of this provider has not passed the connection test (never tested, or failed): offer a retest.
            val main = s.choice.takeIf { it.provider?.id == p.id }?.model
            val mine = check?.takeIf { it.provider == p.id }
            if (main != null && (main !in p.verified || mine?.model == main)) {
                if (main !in p.verified) SettingsRow(stringResource(R.string.prov_main_untested, main), divider = false, titleColor = c.amber,
                    trailing = {
                        if (mine?.busy == true) Spinner(14.dp)
                        else DButton(stringResource(R.string.prov_retest), { vm.checkModel(p, main, select = false) }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    })
                mine?.message?.let { CheckMessage(it, mine.ok) }
            }
        }
        SettingsRow(stringResource(R.string.prov_add), onClick = { onOpen(NEW_PROVIDER) }, divider = s.providers.isNotEmpty(), titleColor = c.link,
            leading = { DIcon(R.drawable.dsw_plus_outline, size = 16.dp, tint = c.link) })
    }
}

@Composable
private fun CheckMessage(text: String, ok: Boolean) {
    val c = Dsh.colors
    Text(text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = DshType.xxs12, color = if (ok) c.labelTertiary else c.error)
}

/**
 * 默认模型 (new conversations start from it, #53; each conversation then keeps its own, changed in the
 * composer): DeepSeek models, then each enabled provider's models; the effort of the selected model.
 */
@Composable
fun DefaultModelCard(vm: SettingsViewModel) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val choice = s.choice
    SettingsGroupTitle(stringResource(R.string.set_default_model))
    SettingsCard {
        DEEPSEEK_MODELS.forEachIndexed { i, (id, label) ->
            SettingsRow(label, stringResource(if (id == "deepseek-flash") R.string.model_flash_desc else R.string.model_pro_desc),
                onClick = { vm.setModel("", id) }, divider = i > 0,
                trailing = { if (choice.isDeepSeek && choice.model == id) DIcon(R.drawable.dsw_check_outline, size = 16.dp, tint = c.labelPrimary) })
        }
        val check by vm.modelCheck.collectAsStateWithLifecycle()
        s.providers.filter { it.enabled }.forEach { p ->
            p.models.forEach { m ->
                val mine = check?.takeIf { it.provider == p.id && it.model == m }
                // Picking an untested model tests it first (ModelVerify.kt); a verified one switches at once.
                SettingsRow(m, p.name, onClick = { vm.checkModel(p, m, select = true) },
                    trailing = {
                        if (mine?.busy == true) Spinner(14.dp)
                        else if (choice.provider?.id == p.id && choice.model == m) DIcon(R.drawable.dsw_check_outline, size = 16.dp, tint = c.labelPrimary)
                    })
                mine?.message?.let { CheckMessage(it, mine.ok) }
            }
        }
        if (choice.isDeepSeek) SettingsRow(stringResource(R.string.effort), stringResource(R.string.effort_desc), stacked = true) {
            Segmented(Effort.entries.map { it.label }, s.effort.ordinal, { vm.setEffort(Effort.entries[it]) })
        } else {
            // The levels the engine reported for the model (#46); 默认 = none sent.
            val levels = s.reasoning?.levels.orEmpty()
            val p = choice.provider
            if (levels.isEmpty() || p == null) SettingsRow(stringResource(R.string.effort), stringResource(R.string.prov_model_desc))
            else SettingsRow(stringResource(R.string.effort), stringResource(R.string.effort_desc), stacked = true) {
                val chosen = s.reasoningEffort
                Segmented(listOf(stringResource(R.string.effort_default)) + levels.map { it.name },
                    if (chosen == null) 0 else levels.indexOfFirst { it.id == chosen } + 1,
                    { i -> vm.setReasoningChoice(p.id, choice.model, if (i == 0) null else levels[i - 1].id) })
            }
        }
    }
}

/** Add (preset list → [onPreset] opens the editor for it) or edit one provider ([presetId]: the new provider's preset). */
@Composable
fun ProviderPage(vm: SettingsViewModel, target: String, presetId: String?, onPreset: (String) -> Unit, onDone: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val existing = s.providers.firstOrNull { it.id == target }
    val preset = presetOf(presetId ?: existing?.preset)
    if (existing == null && preset == null) {
        // Step 1: pick a preset.
        Spacer(Modifier.height(8.dp))
        SettingsGroupTitle(stringResource(R.string.prov_choose))
        SettingsCard {
            PRESETS.forEachIndexed { i, p ->
                SettingsRow(presetName(p), p.baseUrl.ifEmpty { null }, onClick = { onPreset(p.id) }, divider = i > 0, trailing = { chevron() })
            }
        }
        return
    }
    val p = preset ?: presetOf(CUSTOM)!!
    val customName = stringResource(R.string.prov_custom)
    androidx.compose.runtime.key(target, p.id) { ProviderEditor(vm, existing, p, if (p.id == CUSTOM) customName else p.name, onDone) }
}

@Composable
private fun ProviderEditor(vm: SettingsViewModel, existing: ProviderConfig?, preset: ProviderPreset, defaultName: String, onDone: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(existing?.name ?: defaultName) }
    var baseUrl by rememberSaveable { mutableStateOf(existing?.baseUrl ?: preset.baseUrl) }
    var key by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(existing?.models ?: preset.models) } }
    val offered = remember { mutableStateListOf<String>().apply { addAll(existing?.models ?: preset.models) } }
    var typed by rememberSaveable { mutableStateOf("") }
    // Names typed in by hand this visit: kept across a refresh even when unchecked.
    val manual = remember { mutableStateListOf<String>() }
    // Kept models the last successful fetch did not return (shown on top, flagged).
    val missing = remember { mutableStateListOf<String>() }
    // Limits: provider-level text, per-model override text (window, max output), and /models values.
    var ctxText by rememberSaveable { mutableStateOf(existing?.contextWindow?.toString().orEmpty()) }
    var maxText by rememberSaveable { mutableStateOf(existing?.maxTokens?.toString().orEmpty()) }
    val overrides = remember {
        mutableStateMapOf<String, Pair<String, String>>().apply {
            existing?.limits?.forEach { (m, l) -> if (l.contextWindow != null || l.maxTokens != null) put(m, (l.contextWindow?.toString().orEmpty()) to (l.maxTokens?.toString().orEmpty())) }
        }
    }
    val detected = remember {
        mutableStateMapOf<String, RemoteModel>().apply {
            existing?.limits?.forEach { (m, l) -> put(m, RemoteModel(m, l.detectedContextWindow, l.detectedMaxTokens, images = l.detectedImages)) }
        }
    }
    // 图片输入 per model: the user's choice (absent = automatic: listing, then the name; ModelVision).
    val imageOverrides = remember { mutableStateMapOf<String, Boolean>().apply { existing?.limits?.forEach { (m, l) -> l.images?.let { put(m, it) } } } }
    // 推理控制 per model (#46): the user's declaration (absent = automatic: the engine's family table; ModelReasoning.kt).
    val reasoningDecls = remember { mutableStateMapOf<String, ReasoningDecl>().apply { existing?.limits?.forEach { (m, l) -> l.reasoning?.let { put(m, it) } } } }
    var editing by remember { mutableStateOf<String?>(null) }
    fun stored(m: String, withOverride: Boolean = true): ModelLimits {
        val o = overrides[m].takeIf { withOverride }
        val d = detected[m]
        return ModelLimits(o?.first?.let(::tokensOrNull), o?.second?.let(::tokensOrNull)?.toIntSat(), d?.contextWindow, d?.maxTokens,
            images = imageOverrides[m].takeIf { withOverride }, detectedImages = d?.images, reasoning = reasoningDecls[m].takeIf { withOverride })
    }
    fun live(m: String, withOverride: Boolean = true): EffectiveLimits =
        ThirdParty.resolve(stored(m, withOverride), tokensOrNull(ctxText), tokensOrNull(maxText)?.toIntSat())
    var fetchState by remember { mutableStateOf<String?>(null) } // null | "busy" | message
    var fetchOk by remember { mutableStateOf(false) }
    var fetchNet by remember { mutableStateOf<NetDiagnosis?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    // 测试: progress line while testing, then the result (ok = a model passed).
    var testing by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    // Wire protocol (#59): Chat Completions or OpenAI Responses.
    var api by rememberSaveable { mutableStateOf(existing?.api ?: API_CHAT) }
    // Models that passed the connection test (as saved, then per 测试) and the endpoint that holds for (endpointKey).
    val verified = remember { mutableStateListOf<String>().apply { addAll(existing?.verified.orEmpty()) } }
    var verifiedFor by remember { mutableStateOf(existing?.let { endpointKey(it.api, it.baseUrl, "") }) }
    // A model that passed 测试 and becomes the main model on 保存 (a new provider, or the current main route).
    var pendingMain by remember { mutableStateOf<String?>(null) }
    val current = existing
    val needsKey = !preset.keyOptional && current?.hasKey != true
    // 高级设置: extra request headers and the per-conversation session header (js patch 18).
    val headerRows = remember { mutableStateListOf<HeaderRow>().apply { addAll(headerRowsOf(existing)) } }
    var sessionHeader by rememberSaveable { mutableStateOf(if (existing != null) existing.sessionHeader.orEmpty() else preset.sessionHeader.orEmpty()) }
    var advanced by rememberSaveable { mutableStateOf(existing?.headers?.isNotEmpty() == true || existing?.sessionHeader != null) }
    fun sessionHeaderOrNull() = sessionHeader.trim().ifEmpty { null }
    fun headersValid() = headerRows.all { r -> r.name.isBlank() || (isUsableHeaderName(r.name.trim()) && isUsableHeaderValue(r.value)) } &&
        (sessionHeader.isBlank() || isUsableHeaderName(sessionHeader.trim()))

    fun applyListing(fetched: FetchedModels<RemoteModel>) {
        val list = fetched.models
        fetchOk = true
        // A bare origin that 404'd but answers under /v1 (#28): adopt the working address.
        val fixed = fetched.baseUrl != baseUrl.trim().trimEnd('/')
        if (fixed) baseUrl = fetched.baseUrl
        fetchState = if (fixed) context.getString(R.string.prov_fetch_ok_v1, list.size, fetched.baseUrl) else context.getString(R.string.prov_fetch_ok, list.size)
        // A fresh listing replaces what it reports on (fields it dropped are dropped here too).
        list.forEach { detected[it.id] = it }
        val r = refreshOffered(offered.toList(), list.map { it.id }, selected.toList() + manual)
        offered.clear(); offered.addAll(r.offered)
        missing.clear(); missing.addAll(r.missing)
    }

    /**
     * 测试: list the models, then one real chat request with a tool per candidate (ModelVerify.kt).
     * Nothing is stored (#59: 保存 is its own action): a passing model is ticked and counts as
     * verified when the provider is saved with the same protocol, address and key.
     */
    suspend fun runTest() {
        result = null
        baseUrl = normalizeBaseUrl(baseUrl)
        testing = context.getString(R.string.prov_testing_list)
        val listing = vm.fetchModels(baseUrl, key, current, headerRows.toList(), sessionHeaderOrNull())
        listing.onSuccess(::applyListing)
        listing.exceptionOrNull()?.let { t ->
            val f = VerifyErrors.ofThrowable(t)
            // Anything but a bad key or no connection: the provider may just not offer /models; the real call decides.
            if (f is VerifyFailure.KeyInvalid || f is VerifyFailure.Network) {
                testing = null; result = false to context.getString(R.string.prov_test_stopped, describeFailure(context, f)); return
            }
        }
        val listed = listing.getOrNull()?.models
        val ticked = offered.filter { it in selected }
        // A new provider's preset suggestion is pre-ticked, not chosen: with a listing, rank everything (the suggestion still leads).
        val checked = if (existing == null && listed != null && ticked.toSet() == preset.models.toSet()) emptyList() else ticked
        val main = s.choice.takeIf { current != null && it.provider?.id == current.id }?.model?.takeIf { checked.isEmpty() || it in checked }
        val candidates = rankCandidates(listed.orEmpty(), presetOf(preset.id), main, checked)
        if (candidates.isEmpty()) {
            testing = null; result = false to context.getString(if (listed == null) R.string.prov_test_need_model else R.string.prov_test_no_chat_model); return
        }
        val base = baseUrl
        val endpoint = endpointKey(api, base, key)
        val outcome = vm.verifyModels(base, key, current, candidates, { m -> testing = context.getString(R.string.prov_testing_model, m) }, headerRows.toList(), sessionHeaderOrNull(), api)
        testing = null
        val plan = planTest(outcome, checked, verified.toList().takeIf { verifiedFor == endpoint }.orEmpty(),
            makeMain = existing == null || s.choice.provider?.id == current?.id)
        if (plan.apply) {
            plan.models.forEach { if (it !in offered) offered.add(it); if (it !in selected) selected.add(it) }
            verified.clear(); verified.addAll(plan.verified); verifiedFor = endpoint
            pendingMain = plan.main
        }
        result = (outcome is VerifyOutcome.Passed) to describeOutcome(context, outcome, becameMain = plan.main != null)
    }

    /** 保存 without testing (#59): the editor as it stands; models keep their test state only for an unchanged endpoint. */
    fun save() {
        baseUrl = normalizeBaseUrl(baseUrl)
        val id = current?.id ?: newRouteId(preset.id, s.providers.map { it.id })
        // Keep the user's order of discovery, checked ones only.
        val models = offered.filter { it in selected }
        val ok = verifiedOnSave(models, verified.toList(), verifiedFor.orEmpty(), endpointKey(api, baseUrl, key))
        val limits = models.associateWith { stored(it) }.filterValues { !it.isEmpty }
        scope.launch {
            vm.saveEdited(ProviderConfig(id, preset.id, name.trim().ifEmpty { defaultName }, baseUrl, models,
                enabled = current?.enabled ?: true, hasKey = current?.hasKey ?: false,
                contextWindow = tokensOrNull(ctxText), maxTokens = tokensOrNull(maxText)?.toIntSat(), limits = limits, verified = ok,
                sessionHeader = sessionHeaderOrNull(), api = api), key, pendingMain?.takeIf { it in ok }, headerRows.toList())
            onDone()
        }
    }

    /** The editor's input problem, if any (shared by 测试 and 保存). */
    fun inputError(): Int? = when {
        !isHttpUrl(baseUrl) -> R.string.prov_err_url
        !headersValid() -> R.string.prov_err_header
        needsKey && key.isBlank() -> R.string.prov_err_key
        (listOf(ctxText, maxText) + overrides.values.flatMap { listOf(it.first, it.second) }).any { parseTokenInput(it).isFailure } -> R.string.prov_err_limits
        else -> null
    }

    Spacer(Modifier.height(8.dp))
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.prov_name), style = DshType.xs13m, color = c.labelSecondary)
            DInput(name, { name = it }, placeholder = defaultName)
            Text(stringResource(R.string.prov_base_url), style = DshType.xs13m, color = c.labelSecondary)
            DInput(baseUrl, { baseUrl = it.trim() }, placeholder = "https://…/v1", keyboardType = KeyboardType.Uri)
            Text(stringResource(R.string.prov_base_url_hint), style = DshType.xxs12, color = c.labelTertiary)
            if (preset.responses || api == API_RESPONSES) {
                Text(stringResource(R.string.prov_api), style = DshType.xs13m, color = c.labelSecondary)
                Segmented(listOf("Chat Completions", "OpenAI Responses"), if (api == API_RESPONSES) 1 else 0,
                    { api = if (it == 1) API_RESPONSES else API_CHAT }, compact = true)
                Text(stringResource(R.string.prov_api_hint), style = DshType.xxs12, color = c.labelTertiary)
            }
            if (baseUrl.startsWith("http://", ignoreCase = true)) Text(stringResource(R.string.prov_http_warning), style = DshType.xxs12, color = c.error)
            // Warn, but allow saving: something may really listen on the phone (e.g. Termux).
            if (NetDiagnose.isOnDeviceAddress(baseUrl)) Text(stringResource(R.string.prov_local_warning), style = DshType.xxs12, color = c.amber)
            preset.localHint?.let { Text(stringResource(it), style = DshType.xxs12, color = c.labelTertiary) }
            Text(stringResource(if (preset.keyOptional) R.string.prov_key_optional else R.string.api_key_label), style = DshType.xs13m, color = c.labelSecondary)
            DInput(key, { key = it }, placeholder = if (existing?.hasKey == true) stringResource(R.string.prov_key_saved) else "sk-…", secret = true, keyboardType = KeyboardType.Password)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val keysUrl = preset.keysUrl
                if (keysUrl != null) Text(stringResource(R.string.prov_get_key), Modifier.weight(1f).clip(Radius.sm).clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keysUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }.padding(vertical = 4.dp), style = DshType.xs13, color = c.link) else Spacer(Modifier.weight(1f))
                DButton(stringResource(R.string.prov_fetch), {
                    if (!isHttpUrl(baseUrl)) { error = R.string.prov_err_url; return@DButton }
                    baseUrl = normalizeBaseUrl(baseUrl)
                    fetchState = "busy"
                    scope.launch {
                        vm.fetchModels(baseUrl, key, current, headerRows.toList(), sessionHeaderOrNull()).onSuccess(::applyListing).onFailure { t ->
                            fetchOk = false
                            // Same wording as a failed chat turn (Stream.kt ErrorRow): cause, hint, raw detail.
                            fetchNet = NetDiagnose.ofException(t)
                            fetchState = if (fetchNet != null) NET_FAILURE else context.getString(R.string.prov_fetch_failed, t.message ?: t.javaClass.simpleName)
                        }
                    }
                }, variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = fetchState != "busy" && baseUrl.isNotBlank())
            }
            when (val f = fetchState) {
                null -> {}
                "busy" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spinner(12.dp); Text(stringResource(R.string.prov_fetching), style = DshType.xxs12, color = c.labelTertiary)
                }
                NET_FAILURE -> fetchNet?.let { net ->
                    Text(stringResource(R.string.prov_fetch_failed, stringResource(net.cause.message)) + (net.cause.hint?.let { " " + stringResource(it) } ?: ""),
                        style = DshType.xxs12, color = c.error)
                    Text(net.detail.take(300), style = DshType.mono11, color = c.labelTertiary)
                }
                else -> Text(f, style = DshType.xxs12, color = if (fetchOk) c.labelTertiary else c.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.prov_context_window), style = DshType.xs13m, color = c.labelSecondary)
                    DInput(ctxText, { ctxText = it }, placeholder = stringResource(R.string.prov_limit_auto), keyboardType = KeyboardType.Number)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.prov_max_output), style = DshType.xs13m, color = c.labelSecondary)
                    DInput(maxText, { maxText = it }, placeholder = stringResource(R.string.prov_limit_auto), keyboardType = KeyboardType.Number)
                }
            }
            Text(stringResource(R.string.prov_limits_hint, ThirdParty.DEFAULT_CONTEXT_WINDOW, ThirdParty.DEFAULT_MAX_TOKENS), style = DshType.xxs12, color = c.labelTertiary)
            Row(Modifier.fillMaxWidth().clip(Radius.sm).clickable { advanced = !advanced }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prov_advanced), Modifier.weight(1f), style = DshType.xs13m, color = c.labelSecondary)
                DIcon(if (advanced) R.drawable.dsw_chevron_up_outline else R.drawable.dsw_chevron_down_outline, size = 14.dp, tint = c.labelTertiary)
            }
            if (advanced) {
                Text(stringResource(R.string.prov_session_header), style = DshType.xs13m, color = c.labelSecondary)
                DInput(sessionHeader, { sessionHeader = it.trim() }, placeholder = "x-opencode-session")
                Text(stringResource(R.string.prov_session_header_hint), style = DshType.xxs12, color = c.labelTertiary)
                Text(stringResource(R.string.prov_headers), style = DshType.xs13m, color = c.labelSecondary)
                headerRows.forEachIndexed { i, r ->
                    val savedSecret = existing?.headers?.any { it.secret && it.name.equals(r.name.trim(), true) } == true
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DInput(r.name, { headerRows[i] = r.copy(name = it.trim()) }, Modifier.weight(1f), placeholder = stringResource(R.string.prov_header_name))
                        DInput(r.value, { headerRows[i] = r.copy(value = it) }, Modifier.weight(1.4f),
                            placeholder = stringResource(if (savedSecret) R.string.prov_header_saved else R.string.prov_header_value), secret = savedSecret)
                        IconBtn(R.drawable.dsw_close_outline, { headerRows.removeAt(i) }, size = 32.dp, iconSize = 14.dp, tint = c.labelTertiary,
                            contentDescription = stringResource(R.string.prov_header_remove))
                    }
                }
                DButton(stringResource(R.string.prov_header_add), { headerRows.add(HeaderRow("", "")) }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                Text(stringResource(R.string.prov_headers_hint), style = DshType.xxs12, color = c.labelTertiary)
            }
        }
    }

    SettingsGroupTitle(stringResource(R.string.model))
    Text(stringResource(R.string.prov_models_desc), Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        offered.forEachIndexed { i, m ->
            val on = m in selected
            val l = live(m)
            val (images, imagesSrc) = ModelVision.resolve(m, stored(m))
            val line = stringResource(R.string.prov_limits_line, Fmt.tokens(l.contextWindow), stringResource(limitSourceLabel(l.contextSource)),
                Fmt.tokens(l.maxTokens.toLong()), stringResource(limitSourceLabel(l.maxTokensSource))) + " · " +
                stringResource(R.string.prov_images_line, stringResource(if (images) R.string.prov_images_on else R.string.prov_images_off), stringResource(if (imagesSrc == LimitSource.DEFAULT) R.string.prov_images_by_name else limitSourceLabel(imagesSrc)))
            SettingsRow(m, if (m in missing) stringResource(R.string.prov_model_missing) + " · " + line else line,
                onClick = { if (on) selected.remove(m) else selected.add(m) }, divider = i > 0,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(R.drawable.dsw_edit_outline, { editing = if (editing == m) null else m }, size = 32.dp, iconSize = 14.dp,
                            tint = if (m in overrides || m in imageOverrides || m in reasoningDecls) c.link else c.labelTertiary, contentDescription = stringResource(R.string.prov_model_limits))
                        DCheckbox(on, { if (it) selected.add(m) else selected.remove(m) })
                    }
                })
            if (editing == m) {
                val base = live(m, withOverride = false)
                val o = overrides[m] ?: ("" to "")
                fun set(v: Pair<String, String>) { if (v.first.isBlank() && v.second.isBlank()) overrides.remove(m) else overrides[m] = v }
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.prov_context_window), style = DshType.xxs12, color = c.labelSecondary)
                            DInput(o.first, { set(it to o.second) }, placeholder = base.contextWindow.toString(), keyboardType = KeyboardType.Number)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.prov_max_output), style = DshType.xxs12, color = c.labelSecondary)
                            DInput(o.second, { set(o.first to it) }, placeholder = base.maxTokens.toString(), keyboardType = KeyboardType.Number)
                        }
                    }
                    Text(stringResource(R.string.prov_model_limits_hint), style = DshType.xxs12, color = c.labelTertiary)
                    // 图片输入: 自动 (listing / name) · 支持 · 不支持. A text-only model gets a placeholder instead of the image.
                    val auto = ModelVision.resolve(m, stored(m, withOverride = false)).first
                    Text(stringResource(R.string.prov_images), style = DshType.xxs12, color = c.labelSecondary)
                    Segmented(listOf(stringResource(R.string.prov_images_auto, stringResource(if (auto) R.string.prov_images_on else R.string.prov_images_off)),
                        stringResource(R.string.prov_images_on), stringResource(R.string.prov_images_off)),
                        when (imageOverrides[m]) { null -> 0; true -> 1; false -> 2 },
                        { i -> if (i == 0) imageOverrides.remove(m) else imageOverrides[m] = i == 1 }, compact = true)
                    Text(stringResource(R.string.prov_images_hint), style = DshType.xxs12, color = c.labelTertiary)
                    ReasoningControlEditor(reasoningDecls[m], { if (it == null) reasoningDecls.remove(m) else reasoningDecls[m] = it }, responses = api == API_RESPONSES,
                        auto = existing?.let { p -> s.reasoningCatalog[ReasoningLevels.key(p.id, m)]?.takeIf { it.decl == null } })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DInput(typed, { typed = it.trim() }, Modifier.weight(1f), placeholder = stringResource(R.string.prov_add_model))
            DButton(stringResource(R.string.prov_add_model_btn), {
                if (typed.isNotEmpty()) { if (typed !in offered) offered.add(typed); if (typed !in manual) manual.add(typed); if (typed !in selected) selected.add(typed); typed = "" }
            }, variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = typed.isNotEmpty())
        }
    }

    error?.let { Text(stringResource(it), Modifier.padding(start = 4.dp, top = 12.dp), style = DshType.xs13, color = c.error) }
    testing?.let { t ->
        Row(Modifier.padding(start = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spinner(12.dp); Text(t, style = DshType.xs13, color = c.labelTertiary)
        }
    }
    result?.let { (ok, text) -> Text(text, Modifier.padding(start = 4.dp, top = 12.dp), style = DshType.xs13, color = if (ok) c.labelSecondary else c.error) }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (current != null) DButton(stringResource(R.string.prov_delete), { vm.deleteProvider(current); onDone() }, variant = BtnVariant.Danger, size = BtnSize.Sm)
        Spacer(Modifier.weight(1f))
        DButton(stringResource(R.string.cancel), onDone, variant = BtnVariant.Outline, size = BtnSize.Sm)
        DButton(stringResource(R.string.prov_test), {
            error = inputError()
            if (error == null) scope.launch { runTest() }
        }, variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = testing == null)
        DButton(stringResource(R.string.prov_save), {
            error = inputError() ?: R.string.prov_err_no_model.takeIf { offered.none { it in selected } }
            if (error == null) save()
        }, size = BtnSize.Sm, enabled = testing == null)
    }
}

/**
 * 推理控制 of one model (#46): 自动 (the engine's family table; [auto] is what the engine last
 * reported for it, null = not known yet) · 无 · 推理档位 (`reasoning_effort`, the ticked levels) ·
 * 思考开关 (`enable_thinking` or `thinking.type`). Written into the engine profile (Providers.kt).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReasoningControlEditor(decl: ReasoningDecl?, onChange: (ReasoningDecl?) -> Unit, responses: Boolean, auto: ModelReasoning?) {
    val c = Dsh.colors
    Text(stringResource(R.string.prov_reasoning), style = DshType.xxs12, color = c.labelSecondary)
    // The Responses API spells reasoning as an effort only (`reasoning.effort`): no thinking switch to declare there.
    Segmented(listOf(stringResource(R.string.prov_reasoning_auto_unknown), stringResource(R.string.prov_reasoning_none),
        stringResource(R.string.prov_reasoning_effort)) + (if (responses) emptyList() else listOf(stringResource(R.string.prov_reasoning_switch))),
        when (decl?.control) { null -> 0; ReasoningControl.None -> 1; ReasoningControl.Effort -> 2; else -> 3 },
        { i ->
            onChange(when (i) {
                0 -> null
                1 -> ReasoningDecl(ReasoningControl.None)
                2 -> decl?.takeIf { it.control == ReasoningControl.Effort } ?: ReasoningDecl(ReasoningControl.Effort, ReasoningLevels.EFFORT_START)
                else -> decl?.takeIf { it.control == ReasoningControl.EnableThinking || it.control == ReasoningControl.ThinkingType } ?: ReasoningDecl(ReasoningControl.EnableThinking)
            })
        }, compact = true)
    when (decl?.control) {
        null -> {
            val levels = when {
                auto == null -> stringResource(R.string.prov_reasoning_levels_unknown)
                auto.levels.isEmpty() -> stringResource(R.string.prov_reasoning_levels_none)
                else -> auto.levels.joinToString(" / ") { it.name } + (auto.level(auto.default)?.let { " · " + stringResource(R.string.effort_default) + " " + it.name } ?: "")
            }
            Text(stringResource(R.string.prov_reasoning_auto, levels), style = DshType.xxs12, color = c.labelTertiary)
        }
        ReasoningControl.Effort -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReasoningLevels.EFFORT.forEach { l ->
                val on = l in decl.levels
                // At least one level stays ticked (none would mean 无).
                FilterPill(l, on) { if (!on) onChange(decl.copy(levels = decl.levels + l)) else if (decl.levels.size > 1) onChange(decl.copy(levels = decl.levels - l)) }
            }
        }
        ReasoningControl.EnableThinking, ReasoningControl.ThinkingType ->
            Segmented(listOf("enable_thinking", "thinking.type"), if (decl.control == ReasoningControl.EnableThinking) 0 else 1,
                { onChange(ReasoningDecl(if (it == 0) ReasoningControl.EnableThinking else ReasoningControl.ThinkingType)) }, compact = true)
        ReasoningControl.None -> {}
    }
    Text(stringResource(R.string.prov_reasoning_hint), style = DshType.xxs12, color = c.labelTertiary)
}

/** https:// for cloud services, or plain http:// for a gateway on the user's own LAN (#5). */
private fun isHttpUrl(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
