package io.github.timhub135.dsh.feature.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.data.Effort
import io.github.timhub135.dsh.core.provider.DEEPSEEK_MODELS
import io.github.timhub135.dsh.core.provider.ModelChoice
import io.github.timhub135.dsh.core.provider.ModelSearch
import io.github.timhub135.dsh.core.session.TodoDockMode
import io.github.timhub135.dsh.core.session.todoDockMode
import io.github.timhub135.dsh.core.provider.ModelReasoning
import io.github.timhub135.dsh.core.provider.ProviderConfig
import io.github.timhub135.dsh.core.provider.ReasoningLevels
import io.github.timhub135.dsh.core.designsystem.Chevron
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DInput
import io.github.timhub135.dsh.core.designsystem.DMenu
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.Fmt
import io.github.timhub135.dsh.core.designsystem.Hairline
import io.github.timhub135.dsh.core.designsystem.IconBtn
import io.github.timhub135.dsh.core.designsystem.MenuDivider
import io.github.timhub135.dsh.core.designsystem.MenuItem
import io.github.timhub135.dsh.core.designsystem.MenuSectionTitle
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.RiskConfirmDialog
import io.github.timhub135.dsh.core.designsystem.elevated
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.model.PermissionPreset
import io.github.timhub135.dsh.core.model.SessionMode
import io.github.timhub135.dsh.core.session.QueuedMessage
import io.github.timhub135.dsh.core.session.TodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

data class ImageAttachment(val mime: String, val base64: String, val thumb: Bitmap)

/** A document copied into the agent's home (`home/attachments`); referenced by path when sent. */
data class FileAttachment(val name: String, val path: String, val size: Long)

const val MAX_IMAGES = 4

@Stable
class ComposerState {
    var text by mutableStateOf("")
    val images = mutableStateListOf<ImageAttachment>()
    val files = mutableStateListOf<FileAttachment>()
    /** Picked attachments still decoding / copying; Send waits for them. */
    var pending by mutableStateOf(0)
    val isEmpty get() = text.isBlank() && images.isEmpty() && files.isEmpty()
    fun clear() { text = ""; images.clear(); files.clear() }

    /** The text to send: the draft plus a reference line per attached file. */
    fun outgoingText(context: Context): String = buildString {
        append(text.trim())
        files.forEach { f -> if (isNotEmpty()) append('\n'); append(context.getString(R.string.attached_file, f.path)) }
    }
}

/** Drafts per session for the life of the process (session switch, rotation, theme/locale change, panel → app). */
private object Drafts {
    private val map = HashMap<String, ComposerState>()
    fun of(key: String) = map.getOrPut(key) { ComposerState() }
}

/** The composer draft for [key] (a session id); the text also survives process death via saved state. */
@Composable
fun rememberComposerState(key: String): ComposerState {
    val state = remember(key) { Drafts.of(key) }
    var saved by androidx.compose.runtime.saveable.rememberSaveable(key) { mutableStateOf(state.text) }
    androidx.compose.runtime.LaunchedEffect(state) {
        if (state.text.isEmpty() && saved.isNotEmpty()) state.text = saved
        androidx.compose.runtime.snapshotFlow { state.text }.collect { saved = it }
    }
    return state
}

/**
 * True once [ms] have passed since this composable appeared. The composer slot swaps cards for
 * the composer in place, so a tap aimed at a card button (允许一次 …) can land on whatever moved
 * under the finger; controls ignore taps until the slot has settled.
 */
@Composable
fun rememberSettled(ms: Long = 600): () -> Boolean {
    val since = remember { android.os.SystemClock.uptimeMillis() }
    return { android.os.SystemClock.uptimeMillis() - since >= ms }
}

/** DeepSeek models shown by the model chip (dsh ui-model-selection); third-party ones come from Settings.providers. */
val Models = DEEPSEEK_MODELS
fun modelLabel(id: String) = Models.firstOrNull { it.first == id }?.second ?: id

object PresetUi {
    @DrawableRes fun icon(p: PermissionPreset) = when (p) {
        PermissionPreset.ReadOnly -> R.drawable.dsw_perm_read_only
        PermissionPreset.Ask -> R.drawable.dsw_perm_workspace_write
        PermissionPreset.Full -> R.drawable.dsw_perm_full_access
    }
    fun label(p: PermissionPreset) = when (p) {
        PermissionPreset.ReadOnly -> R.string.preset_read_only
        PermissionPreset.Ask -> R.string.preset_ask
        PermissionPreset.Full -> R.string.preset_full
    }
    fun desc(p: PermissionPreset) = when (p) {
        PermissionPreset.ReadOnly -> R.string.preset_read_only_desc
        PermissionPreset.Ask -> R.string.preset_ask_desc
        PermissionPreset.Full -> R.string.preset_full_desc
    }
}

/** Actions reachable from the "+" button (the same menu as "/"). */
data class ComposerActions(
    val onNewSession: (() -> Unit)? = null,
    val onTrajectory: (() -> Unit)? = null,
    val onAutomation: (() -> Unit)? = null,
)

/**
 * dsh InputBar: R28 card on the input-major fill with the soft elevation; editor on top,
 * toolbar below: [+] [permission chip] [mode chip] … [context ring] [model chip] [send/stop 34].
 */
@Composable
fun Composer(
    state: ComposerState,
    running: Boolean,
    preset: PermissionPreset,
    onPreset: (PermissionPreset) -> Unit,
    model: ModelChoice,
    /** Third-party providers; their enabled models are listed under the provider's name. */
    providers: List<ProviderConfig>,
    effort: Effort,
    /** (provider route id or "" for DeepSeek, model id). */
    onModel: (String, String) -> Unit,
    onEffort: (Effort) -> Unit,
    contextTokens: Long,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    actions: ComposerActions = ComposerActions(),
    placeholder: String = stringResource(if (hero) R.string.composer_hint_hero else R.string.composer_hint),
    showChips: Boolean = true,
    /** Send is allowed with an empty draft (e.g. the quick panel carries shared content). */
    canSendEmpty: Boolean = false,
    /** The conversation runs: a model/effort change applies from its next step (ConversationController.modelChangePending). */
    modelChangePending: Boolean = running,
    /** The conversation's session mode (null hides the mode chip) and the modes it can switch to. */
    mode: SessionMode? = null,
    modes: List<SessionMode> = emptyList(),
    /** The conversation has started: the mode is shown but cannot change. */
    modeLocked: Boolean = false,
    onMode: (String) -> Unit = {},
    onManageModes: (() -> Unit)? = null,
    /** Speech input (mic button + status line); null hides it. */
    voice: io.github.timhub135.dsh.feature.voice.VoiceInput? = null,
    /** The selected third-party model's levels as the engine reported them (null / empty: no 推理强度 section). */
    reasoning: ModelReasoning? = null,
    /** Its saved level (null = the model's default); a level it no longer offers shows as 默认. */
    reasoningChoice: String? = null,
    onReasoning: (String?) -> Unit = {},
    /** A line about the conversation's model shown above the editor (its own model is gone, #53); null = none. */
    modelNotice: String? = null,
) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plusOpen by remember { mutableStateOf(false) }
    /** The "/" menu: non-focusable so the keyboard stays up while it is open. */
    var slashOpen by remember { mutableStateOf(false) }
    var presetOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(modelOpen) { if (!modelOpen) modelQuery = "" }
    var modeOpen by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    val settled = rememberSettled()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val toKeyboard = { runCatching { focus.requestFocus() }; keyboard?.show(); Unit }
    // Speech found no recognizer: fall back to typing.
    val noRecognizer = (voice?.state as? io.github.timhub135.dsh.feature.voice.VoiceState.Failed)?.error == io.github.timhub135.dsh.feature.voice.VoiceError.NoRecognizer
    androidx.compose.runtime.LaunchedEffect(noRecognizer) { if (noRecognizer) toKeyboard() }
    androidx.compose.runtime.DisposableEffect(voice) { onDispose { voice?.cancel() } }
    // A preset change from elsewhere (session switch, approval) drops any half-finished choice.
    androidx.compose.runtime.LaunchedEffect(preset) { presetOpen = false; confirmFull = false }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        scope.launch { addImages(context, state, uris) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { addFile(context, state, uri) }
    }

    Column(
        modifier.fillMaxWidth().elevated(Radius.panel, c.borderL2, 8.dp).clip(Radius.panel).background(c.inputMajor).padding(top = 8.dp),
    ) {
        if (state.images.isNotEmpty() || state.files.isNotEmpty() || state.pending > 0) Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val removeLabel = stringResource(R.string.remove_attachment)
            state.images.forEach { img ->
                Box(Modifier.size(64.dp)) {
                    androidx.compose.foundation.Image(img.thumb.asImageBitmap(), null, Modifier.size(64.dp).clip(Radius.xl), contentScale = ContentScale.Crop)
                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(Color(0x99000000)).pressable({ state.images.remove(img) }, CircleShape),
                        contentAlignment = Alignment.Center) { DIcon(R.drawable.dsw_close_outline, size = 10.dp, tint = Color.White, contentDescription = removeLabel) }
                }
            }
            repeat(state.pending) {
                Box(Modifier.size(64.dp).clip(Radius.xl).background(c.selector), contentAlignment = Alignment.Center) { io.github.timhub135.dsh.core.designsystem.Spinner(16.dp) }
            }
            // dsh file chip: name + size, removable.
            state.files.forEach { f ->
                Row(Modifier.height(40.dp).widthIn(max = 220.dp).clip(Radius.md).background(c.selector).padding(start = 10.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DIcon(R.drawable.dsw_paperclip_outline, size = 14.dp, tint = c.labelSecondary)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(f.name, style = DshType.xxs12m, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(android.text.format.Formatter.formatShortFileSize(context, f.size), style = DshType.tiny10, color = c.labelTertiary, maxLines = 1)
                    }
                    IconBtn(R.drawable.dsw_close_outline, { state.files.remove(f) }, size = 32.dp, iconSize = 12.dp, contentDescription = removeLabel)
                }
            }
        }
        if (modelNotice != null) Text(modelNotice, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = DshType.xxs12, color = c.amber)
        // #39: a text-only model gets "[image omitted …]" instead of the picture; say so before it is sent.
        if ((state.images.isNotEmpty() || state.pending > 0) && !model.acceptsImages) Text(
            stringResource(if (model.isDeepSeek) R.string.images_text_only_deepseek else R.string.images_text_only, modelLabel(model.model)),
            Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = DshType.xxs12, color = c.amber)
        BasicTextField(
            state.text,
            { v ->
                if (voice?.active == true) voice.onUserEdit()
                state.text = v
                if (v == "/") slashOpen = true else if (slashOpen && !v.startsWith("/")) slashOpen = false
            },
            Modifier.fillMaxWidth().heightIn(min = if (hero) 52.dp else 36.dp, max = 216.dp).padding(start = 14.dp, end = 8.dp, top = 4.dp).focusRequester(focus),
            textStyle = DshType.base16.copy(color = c.labelPrimary),
            cursorBrush = SolidColor(c.business),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box {
                    if (state.text.isEmpty()) Text(placeholder, style = DshType.base16, color = c.labelCaption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (voice != null) io.github.timhub135.dsh.feature.voice.VoiceStrip(voice, toKeyboard)
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        // dsh control-row layout: below ~460 px the permission chip drops its label.
        val narrow = maxWidth < 400.dp
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.selector).pressable({ if (settled()) plusOpen = true }, CircleShape), contentAlignment = Alignment.Center) {
                    DIcon(R.drawable.dsw_plus_outline, size = 14.dp, tint = c.labelPrimary, contentDescription = stringResource(R.string.plus_hint))
                }
                DMenu(plusOpen || slashOpen, { plusOpen = false; slashOpen = false; if (state.text == "/") state.text = "" }, focusable = !slashOpen) {
                    val close = { plusOpen = false; slashOpen = false; if (state.text == "/") state.text = "" }
                    MenuSectionTitle(stringResource(R.string.menu_add))
                    MenuItem(stringResource(R.string.menu_image), { close(); pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, R.drawable.dsw_fullscreen_outline)
                    MenuItem(stringResource(R.string.menu_file), { close(); pickFile.launch(arrayOf("*/*")) }, R.drawable.dsw_paperclip_outline)
                    MenuDivider()
                    MenuSectionTitle(stringResource(R.string.menu_commands))
                    if (showChips) {
                        MenuItem(stringResource(R.string.cmd_permission), { close(); presetOpen = true }, R.drawable.dsw_shield_outline, stringResource(R.string.cmd_permission_desc))
                        if (mode != null) MenuItem(stringResource(R.string.mode_menu_title), { close(); modeOpen = true }, ModeUi.icon(mode),
                            if (modeLocked) stringResource(R.string.mode_locked) else stringResource(R.string.mode_menu_hint))
                        MenuItem(stringResource(R.string.cmd_model), { close(); modelOpen = true }, R.drawable.dsw_data_outline, stringResource(R.string.cmd_model_desc))
                    }
                    actions.onNewSession?.let { MenuItem(stringResource(R.string.new_session), { close(); it() }, R.drawable.dsw_new_chat_outline) }
                    actions.onTrajectory?.let { MenuItem(stringResource(R.string.view_trajectory), { close(); it() }, R.drawable.dsw_inspect_outline, stringResource(R.string.cmd_trajectory_desc)) }
                    actions.onAutomation?.let { MenuItem(stringResource(R.string.automation), { close(); it() }, R.drawable.dsw_clock_outline) }
                }
            }
            if (showChips) Box {
                // Full access is marked by the warn tone; on narrow screens every mode is icon-only.
                val full = preset == PermissionPreset.Full
                Chip(PresetUi.icon(preset), if (narrow) null else stringResource(PresetUi.label(preset)), { if (settled()) presetOpen = true },
                    description = stringResource(R.string.permission_chip_desc, stringResource(PresetUi.label(preset))),
                    tint = if (full) c.warnLabel else c.labelSecondary, background = if (full) c.warnTertiary else Color.Transparent)
                DMenu(presetOpen, { presetOpen = false }) {
                    PermissionPreset.entries.forEach { p ->
                        MenuItem(stringResource(PresetUi.label(p)), {
                            presetOpen = false
                            if (p == PermissionPreset.Full && preset != PermissionPreset.Full) confirmFull = true else onPreset(p)
                        }, PresetUi.icon(p), stringResource(PresetUi.desc(p)), selected = p == preset)
                    }
                }
            }
            if (showChips && mode != null) ModeChip(mode, modes, modeLocked, narrow, onMode, onManageModes, modeOpen, { modeOpen = it }, settled)
            // The context ring and model chip take only the space left, so Send always stays on screen.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            if (showChips && contextTokens > 0) ContextRing(contextTokens, model.contextWindow, model.contextSource)
            if (showChips) Box(Modifier.weight(1f, fill = false)) {
                // Third-party models: the levels the engine reports for them (#46); the chip names the level in effect.
                val defaultLabel = stringResource(R.string.effort_default)
                val reasoningMenu = if (model.isDeepSeek) null else ReasoningLevels.menu(reasoning, reasoningChoice, defaultLabel)
                val effortLabel = if (model.isDeepSeek) effort.label
                    else reasoning?.let { r -> r.level(ReasoningLevels.effective(r, reasoningChoice) ?: r.default)?.name }.orEmpty()
                val modelDesc = stringResource(R.string.model_chip_desc, modelLabel(model.model), effortLabel)
                Row(Modifier.heightIn(min = 28.dp).widthIn(max = 200.dp).clip(Radius.sm).pressable({ if (settled()) modelOpen = true }, Radius.sm)
                    .semantics(mergeDescendants = true) { contentDescription = modelDesc }.padding(start = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(modelLabel(model.model).removePrefix("DeepSeek-"), Modifier.weight(1f, fill = false), style = DshType.xs13m, color = c.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (effortLabel.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(effortLabel, style = DshType.xs13m, color = c.labelCaption, maxLines = 1)
                    }
                    Chevron(Modifier.padding(start = 2.dp), size = 12.dp)
                }
                DMenu(modelOpen, { modelOpen = false }) {
                    val listed = providers.filter { it.enabled && it.models.isNotEmpty() }
                    // A long list (many third-party models) gets a search field: model id, display name or provider.
                    val searchable = ModelSearch.shown(Models.size + listed.sumOf { it.models.size })
                    if (searchable) DInput(modelQuery, { modelQuery = it }, Modifier.padding(horizontal = 8.dp, vertical = 4.dp).width(232.dp),
                        placeholder = stringResource(R.string.search_models), minHeight = 34.dp)
                    val q = if (searchable) modelQuery else ""
                    val deepseek = Models.filter { (id, label) -> ModelSearch.matches(q, id, label, "DeepSeek") }
                    val shown = listed.map { p -> p to p.models.filter { m -> ModelSearch.matches(q, m, p.name) } }.filter { it.second.isNotEmpty() }
                    if (deepseek.isNotEmpty()) MenuSectionTitle("DeepSeek")
                    deepseek.forEach { (id, label) -> MenuItem(label, { modelOpen = false; onModel("", id) }, R.drawable.dsw_data_outline, selected = model.isDeepSeek && id == model.model) }
                    shown.forEachIndexed { i, (p, models) ->
                        if (deepseek.isNotEmpty() || i > 0) MenuDivider()
                        MenuSectionTitle(p.name)
                        models.forEach { m -> MenuItem(m, { modelOpen = false; onModel(p.id, m) }, R.drawable.dsw_data_outline, selected = model.provider?.id == p.id && model.model == m) }
                    }
                    if (deepseek.isEmpty() && shown.isEmpty()) MenuItem(stringResource(R.string.search_models_none), {}, enabled = false)
                    if (model.isDeepSeek) {
                        MenuDivider()
                        MenuSectionTitle(stringResource(R.string.effort))
                        Effort.entries.forEach { e -> MenuItem(e.label, { modelOpen = false; onEffort(e) }, null, stringResource(effortDesc(e)), selected = e == effort) }
                    }
                    if (reasoningMenu != null) {
                        MenuDivider()
                        MenuSectionTitle(stringResource(R.string.effort))
                        reasoningMenu.forEach { e ->
                            val desc = when {
                                e.id == null -> stringResource(R.string.effort_default_desc)
                                e.isDefault -> stringResource(R.string.effort_is_default)
                                else -> null
                            }
                            MenuItem(e.name, { modelOpen = false; onReasoning(e.id) }, null, desc, selected = e.selected)
                        }
                    }
                    // #53: the chip is this conversation's; the default lives in 设置 → 模型服务.
                    Text(stringResource(if (modelChangePending) R.string.model_applies_next else R.string.model_this_session), Modifier.padding(8.dp).widthIn(max = 240.dp),
                        style = DshType.xxs12, color = c.labelCaption)
                }
            }
            }
            if (voice != null) io.github.timhub135.dsh.feature.voice.MicButton(voice)
            SendButton(running, state.isEmpty && !canSendEmpty, {
                if (state.pending > 0) android.widget.Toast.makeText(context, R.string.attachments_loading, android.widget.Toast.LENGTH_SHORT).show()
                else onSend()
            }, onStop)
        }
        }
    }
    if (confirmFull) RiskConfirmDialog(
        stringResource(R.string.full_confirm_title), stringResource(R.string.full_confirm_body), stringResource(R.string.full_confirm_check),
        stringResource(R.string.full_confirm_ok), stringResource(R.string.cancel),
        onConfirm = { confirmFull = false; onPreset(PermissionPreset.Full) }, onDismiss = { confirmFull = false },
    )
}

private fun effortDesc(e: Effort) = when (e) {
    Effort.Off -> R.string.effort_off_desc; Effort.Low -> R.string.effort_low_desc
    Effort.High -> R.string.effort_high_desc; Effort.Max -> R.string.effort_max_desc
}

/** Composer chip: height 28, R8, 13/20 weight 500 secondary, 14 dp icon + chevron. */
@Composable
private fun Chip(@DrawableRes icon: Int, label: String?, onClick: () -> Unit, description: String, tint: Color, background: Color) {
    Row(Modifier.heightIn(min = 28.dp).clip(Radius.sm).background(background).pressable(onClick, Radius.sm)
        .semantics(mergeDescendants = true) { contentDescription = description }.padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        DIcon(icon, size = 14.dp, tint = tint)
        if (label != null) {
            Spacer(Modifier.width(4.dp))
            Text(label, style = DshType.xs13m, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chevron(Modifier.padding(start = 2.dp), size = 12.dp)
    }
}

/** 34 dp blue circle: up arrow to send, rounded square to stop (dsh Send/Stop). */
@Composable
fun SendButton(running: Boolean, empty: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val c = Dsh.colors
    val stop = running && empty
    val enabled = stop || !empty
    val label = stringResource(if (stop) R.string.stop_generating else R.string.send_message)
    Box(Modifier.size(34.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(c.info)
        .pressable({ if (stop) onStop() else if (!empty) onSend() }, CircleShape, enabled)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            if (stop) {
                drawRoundRect(Color.White, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), size = Size(10.dp.toPx(), 10.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()))
            } else {
                val w = 1.6.dp.toPx(); val cx = size.width / 2; val top = 1.5.dp.toPx(); val bottom = size.height - 1.5.dp.toPx()
                drawLine(Color.White, Offset(cx, top + w / 2), Offset(cx, bottom), w, StrokeCap.Round)
                drawLine(Color.White, Offset(cx, top), Offset(cx - 5.dp.toPx(), top + 5.dp.toPx()), w, StrokeCap.Round)
                drawLine(Color.White, Offset(cx, top), Offset(cx + 5.dp.toPx(), top + 5.dp.toPx()), w, StrokeCap.Round)
            }
        }
    }
}

/** dsh ContextMeter: 14 dp ring + percentage; tap for the occupancy panel. */
@Composable
fun ContextRing(tokens: Long, window: Long, source: io.github.timhub135.dsh.core.provider.LimitSource? = null) {
    val c = Dsh.colors
    var open by remember { mutableStateOf(false) }
    val frac = (tokens.toFloat() / window).coerceIn(0f, 1f)
    val pct = (frac * 100).toInt().coerceAtLeast(if (tokens > 0) 1 else 0)
    Box {
        Row(Modifier.height(28.dp).clip(Radius.sm).pressable({ open = true }, Radius.sm).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(14.dp)) {
                val sw = 2.dp.toPx(); val r = 5.5.dp.toPx()
                val tl = Offset(center.x - r, center.y - r); val sz = Size(r * 2, r * 2)
                drawArc(c.borderL3, 0f, 360f, false, tl, sz, style = Stroke(sw))
                drawArc(c.labelTertiary, -90f, 360f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Spacer(Modifier.width(4.dp))
            Text("$pct%", style = DshType.xs13, color = c.labelTertiary)
        }
        DMenu(open, { open = false }) {
            Column(Modifier.width(240.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.context_used), style = DshType.xs13, color = c.labelSecondary)
                    Text(" $pct%", style = DshType.xs13.copy(fontWeight = FontWeight(600)), color = c.labelPrimary)
                    Spacer(Modifier.weight(1f))
                    Text("~${Fmt.tokens(tokens)} / ${Fmt.tokens(window)}", style = DshType.xxs12, color = c.labelTertiary)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.borderL2)) {
                    Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(c.kindMessages))
                }
                // Third-party windows come from /models, the user, or the default: say which.
                if (source != null) Text(stringResource(R.string.context_window_src, Fmt.tokens(window),
                    stringResource(io.github.timhub135.dsh.feature.settings.limitSourceLabel(source))), style = DshType.xxs12, color = c.labelTertiary)
            }
        }
    }
}

/** Queue dock (dsh QueueDock): tucked above the composer; edit pulls a message back into the draft. */
@Composable
fun QueueDock(queue: List<QueuedMessage>, onEdit: (QueuedMessage) -> Unit, onDelete: (QueuedMessage) -> Unit, onSendNow: (QueuedMessage) -> Unit) {
    if (queue.isEmpty()) return
    val c = Dsh.colors
    var open by remember { mutableStateOf(queue.size == 1) }
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Radius.lg).background(c.menuFill)
        .then(Modifier.padding(bottom = 4.dp))) {
        if (queue.size > 1) Row(Modifier.fillMaxWidth().height(36.dp).pressable({ open = !open }, Radius.md).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DIcon(R.drawable.dsw_queue_outline, size = 14.dp, tint = c.labelTertiary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.queued_n, queue.size), Modifier.weight(1f), style = DshType.xs13m, color = c.labelSecondary)
            Chevron(open = open)
        }
        if (open || queue.size == 1) queue.forEach { q ->
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (queue.size == 1) { DIcon(R.drawable.dsw_queue_outline, size = 14.dp, tint = c.labelTertiary); Spacer(Modifier.width(6.dp)) }
                Text(q.text, Modifier.weight(1f), style = DshType.xs13, color = c.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconBtn(R.drawable.dsw_edit_outline, { onEdit(q) }, size = 40.dp, iconSize = 14.dp, contentDescription = stringResource(R.string.queue_edit))
                IconBtn(R.drawable.dsw_trash_outline, { onDelete(q) }, size = 40.dp, iconSize = 14.dp, contentDescription = stringResource(R.string.queue_delete))
                IconBtn(R.drawable.dsw_paper_plane_outline, { onSendNow(q) }, size = 40.dp, iconSize = 14.dp, contentDescription = stringResource(R.string.queue_steer))
            }
        }
    }
}

/**
 * Todo dock (dsh TodoPanel): collapsed header with progress, expands to the checklist. A finished
 * list is one 全部完成 line, and gone once a later turn runs ([todoDockMode]).
 */
@Composable
fun TodoDock(todos: List<TodoItem>?, writtenThisTurn: Boolean) {
    val mode = todoDockMode(todos, writtenThisTurn)
    if (todos == null || mode == TodoDockMode.Hidden) return
    val c = Dsh.colors
    if (mode == TodoDockMode.AllDone) {
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Radius.lg).background(c.menuFill).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DIcon(R.drawable.dsw_checklist_outline, size = 14.dp, tint = c.labelSecondary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.todos), style = DshType.xs13m, color = c.labelPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.todos_all_done) + " · ${todos.size}/${todos.size}", Modifier.weight(1f), style = DshType.xxs12, color = c.labelTertiary, maxLines = 1)
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    val active = todos.count { it.status == "in_progress" }
    val pending = todos.size - done - active
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Radius.lg).background(c.menuFill).border0().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().pressable({ open = !open }, Radius.sm).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            DIcon(R.drawable.dsw_checklist_outline, size = 14.dp, tint = c.labelSecondary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.todos), style = DshType.xs13m.copy(lineHeight = 24.sp), color = c.labelPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.todo_progress, done, active, pending), Modifier.weight(1f), style = DshType.xxs12, color = c.labelTertiary, maxLines = 1)
            Chevron(open = open)
        }
        if (open) Box(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            TodoList(todos.map { it.content to it.status })
        }
    }
}

private fun Modifier.border0() = this

/** Adds picked/shared images (up to [MAX_IMAGES]) with a placeholder while each decodes. */
suspend fun addImages(context: Context, state: ComposerState, uris: List<Uri>) {
    val room = MAX_IMAGES - state.images.size - state.pending
    if (uris.size > room) android.widget.Toast.makeText(context, context.getString(R.string.images_limit, MAX_IMAGES), android.widget.Toast.LENGTH_SHORT).show()
    val take = uris.take(room.coerceAtLeast(0))
    state.pending += take.size
    take.forEach { u ->
        val img = loadImage(context, u)
        state.pending -= 1
        if (img != null) state.images += img
        else android.widget.Toast.makeText(context, R.string.file_import_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** Copies a picked/shared document into the agent's home and adds it as a file chip. */
suspend fun addFile(context: Context, state: ComposerState, uri: Uri) {
    state.pending += 1
    val f = withContext(Dispatchers.IO) { importFile(context, uri) }
    state.pending -= 1
    if (f != null) state.files += f
    else android.widget.Toast.makeText(context, R.string.file_import_failed, android.widget.Toast.LENGTH_SHORT).show()
}

private suspend fun loadImage(context: Context, uri: Uri): ImageAttachment? = withContext(Dispatchers.IO) {
    runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
        encodeImage(bmp)
    }.getOrNull()
}

private fun encodeImage(bmp: Bitmap): ImageAttachment {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    val thumb = Bitmap.createScaledBitmap(bmp, 128, (128f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
    return ImageAttachment("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), thumb)
}

/** Adds an in-memory image (a screenshot), scaled down like picked images. */
suspend fun addBitmap(state: ComposerState, bmp: Bitmap) {
    if (state.images.size + state.pending >= MAX_IMAGES) return
    state.pending += 1
    val img = withContext(Dispatchers.Default) {
        runCatching {
            val scale = 1600f / maxOf(bmp.width, bmp.height)
            encodeImage(if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp)
        }.getOrNull()
    }
    state.pending -= 1
    if (img != null) state.images += img
}

/** Copies a picked document into the agent's home so dsh's file tools can read it. */
private fun importFile(context: Context, uri: Uri): FileAttachment? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: "file_${System.currentTimeMillis()}"
    val dir = File(context.filesDir, "home/attachments").apply { mkdirs() }
    val safe = name.replace('/', '_')
    // Never overwrite an earlier attachment with the same name: report.pdf, report (1).pdf, …
    var dest = File(dir, safe)
    var n = 1
    while (dest.exists()) {
        val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
        dest = File(dir, safe.substring(0, dot) + " ($n)" + safe.substring(dot)); n++
    }
    val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
    input.use { i -> dest.outputStream().use { i.copyTo(it) } }
    FileAttachment(name, dest.path, dest.length())
}.getOrNull()

/** Swallows taps until [settled] (see [rememberSettled]) so a card that just slid into place can't be hit by accident. */
fun Modifier.ignoreTapsUntil(settled: () -> Boolean): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (!settled()) e.changes.forEach { it.consume() }
        }
    }
}

/** The draft of [sessionId] outside composition (e.g. the quick panel filling in shared content). */
fun draftFor(sessionId: String): ComposerState = Drafts.of(sessionId)
