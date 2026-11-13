package io.github.timhub135.dsh.feature.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.account.AccountNotice
import io.github.timhub135.dsh.core.account.AccountRepository
import io.github.timhub135.dsh.core.account.AccountUi
import io.github.timhub135.dsh.core.account.Loaded
import io.github.timhub135.dsh.core.account.SignInPhase
import io.github.timhub135.dsh.core.account.formatBalance
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.DDialog
import io.github.timhub135.dsh.core.designsystem.DIcon
import io.github.timhub135.dsh.core.designsystem.DMenu
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType
import io.github.timhub135.dsh.core.designsystem.HDivider
import io.github.timhub135.dsh.core.designsystem.Hairline
import io.github.timhub135.dsh.core.designsystem.MenuItem
import io.github.timhub135.dsh.core.designsystem.Radius
import io.github.timhub135.dsh.core.designsystem.Spinner
import io.github.timhub135.dsh.core.designsystem.copyText
import io.github.timhub135.dsh.core.designsystem.pressable
import io.github.timhub135.dsh.core.platform.Browser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The account service for composables (null outside MainActivity, e.g. the quick panel). */
val LocalAccount = staticCompositionLocalOf<AccountRepository?> { null }

/**
 * Opens a Platform page (充值 / 用量 / account): in MainActivity's in-app WebView screen, so Back
 * returns to DSH; outside it (the quick panel has no account service) in the user's browser.
 */
@Composable
fun rememberPlatformOpener(): (String) -> Unit {
    val context = LocalContext.current
    val account = LocalAccount.current
    return remember(account) { { url: String -> if (account != null) account.openPage(url) else Browser.openExternal(context, url) } }
}

/** dsh AccountAvatar: the profile picture once loaded, else the user glyph on a skeleton circle. */
@Composable
fun AccountAvatar(account: AccountRepository, size: Dp, signedIn: Boolean = true) {
    val c = Dsh.colors
    val bmp by account.avatar.collectAsStateWithLifecycle()
    Box(Modifier.size(size).clip(CircleShape).background(c.bgSkeleton), contentAlignment = Alignment.Center) {
        val b = bmp
        if (signedIn && b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else DIcon(R.drawable.dsw_user_outline, size = 16.dp, tint = c.labelTertiary)
    }
}

/** dsh SignInDialog: start / waiting (copy link, reopen) / failure / timeout, with the API key fallback. */
@Composable
fun SignInDialog(account: AccountRepository, onUseApiKey: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by account.state.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    LaunchedEffect(s.signedIn) { if (s.signedIn) account.showLogin(false) }
    val a = s.attempt
    val phase = a?.phase
    val active = phase?.active == true
    val expired = phase == SignInPhase.Expired
    val error = s.loginFailed || phase == SignInPhase.Failed
    val waiting = active && !error
    val committing = phase == SignInPhase.Committing
    val dismiss: () -> Unit = {
        if (!committing) {
            if (active) scope.launch { account.cancel() }
            account.showLogin(false)
        }
    }
    val title = stringResource(when {
        error -> R.string.acct_failure_title
        active -> R.string.acct_browser_title
        expired -> R.string.acct_timeout_title
        else -> R.string.acct_login_title
    })
    DDialog(dismiss, title, buttons = {
        DButton(stringResource(if (active) R.string.cancel else R.string.acct_add_api_key),
            { if (active) dismiss() else { account.showLogin(false); onUseApiKey() } }, variant = BtnVariant.Outline, enabled = !committing)
        when {
            waiting && phase == SignInPhase.WaitingBrowser && a?.authorizeUrl != null ->
                DButton(stringResource(R.string.acct_open), { account.openSignInPage() })
            waiting -> Box(Modifier.heightIn(min = 36.dp).clip(Radius.md).background(c.brandPrimary).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                Spinner(14.dp, c.labelOnPrimary)
            }
            else -> DButton(stringResource(if (expired || error) R.string.acct_retry else R.string.acct_sign_in), { account.startSignIn() })
        }
    }) {
        if (waiting) {
            val url = account.authorizeUrl()
            val linkText = stringResource(if (copied) R.string.acct_copied_link else R.string.acct_copy_link)
            val text = buildAnnotatedString {
                append(stringResource(R.string.acct_browser_prompt))
                withStyle(SpanStyle(color = if (url != null) c.link else c.labelCaption)) { append(linkText) }
                append(stringResource(R.string.acct_browser_desc))
            }
            Text(text, Modifier.clip(Radius.sm).clickable(enabled = url != null) { url?.let { copyText(context, it); copied = true } },
                style = DshType.s14, color = c.labelSecondary)
            Text(stringResource(when (phase) {
                SignInPhase.Initializing -> R.string.acct_initializing
                SignInPhase.WaitingBrowser -> R.string.acct_waiting
                else -> R.string.acct_completing
            }), style = DshType.xs13, color = c.labelTertiary)
        } else {
            Text(stringResource(when {
                error && !s.loginFailed && a?.errorCode == "no-response" -> R.string.acct_no_response
                error -> R.string.acct_failed
                expired -> R.string.acct_timeout_desc
                else -> R.string.acct_login_desc
            }), style = DshType.s14, color = c.labelSecondary)
        }
    }
}

/** dsh SignOutDialog: the impact of signing out on running work, checked when it opens. */
@Composable
fun SignOutDialog(account: AccountRepository, running: Boolean?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    DDialog({ if (!busy) onDismiss() }, stringResource(R.string.acct_sign_out), buttons = {
        DButton(stringResource(R.string.cancel), { if (!busy) onDismiss() }, variant = BtnVariant.Outline, enabled = !busy)
        DButton(stringResource(R.string.acct_sign_out), {
            busy = true; failed = false
            scope.launch {
                runCatching { account.signOut() }.onSuccess { onDismiss() }.onFailure { failed = true }
                busy = false
            }
        }, enabled = !busy)
    }) {
        Text(stringResource(when (running) { null -> R.string.acct_sign_out_unknown; true -> R.string.acct_sign_out_running; false -> R.string.acct_sign_out_desc }),
            style = DshType.s14, color = Dsh.colors.labelSecondary)
        if (failed) Text(stringResource(R.string.acct_failed), style = DshType.xs13, color = Dsh.colors.error)
    }
}

/** Shared sign-out entry: asks the engine for running account tasks, then confirms. */
@Composable
fun rememberSignOut(account: AccountRepository): Pair<() -> Unit, @Composable () -> Unit> {
    val scope = rememberCoroutineScope()
    var impact by remember { mutableStateOf<Boolean?>(null) }
    var open by remember { mutableStateOf(false) }
    val request = { scope.launch { impact = account.hasRunningTasks(); open = true }; Unit }
    val dialog: @Composable () -> Unit = { if (open) SignOutDialog(account, impact) { open = false } }
    return request to dialog
}

/**
 * Settings › 账号与余额 (dsh AccountSection): signed-out card with 登录, or the identity card
 * (32 dp avatar, name, contact) and the balance card (充值余额 / 赠金余额 / 查询用量 / 充值), plus
 * 退出登录. Profile and balance are re-read on every entry.
 */
@Composable
fun AccountSettingsSection(account: AccountRepository, usingApiKey: Boolean) {
    val c = Dsh.colors
    val s by account.state.collectAsStateWithLifecycle()
    val open = rememberPlatformOpener()
    val (requestSignOut, signOutDialog) = rememberSignOut(account)
    LaunchedEffect(Unit) { account.refresh() }
    val attempt = s.attempt
    val active = s.active
    if (!s.signedIn && !active) {
        Card {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.acct_settings_signed_out_title), style = DshType.s14m, color = c.labelPrimary)
                    Text(stringResource(if (s.loginFailed) R.string.acct_failed else R.string.acct_settings_signed_out_desc), style = DshType.xs13, color = c.labelTertiary)
                }
                DButton(stringResource(R.string.acct_sign_in), { account.startSignIn() }, size = BtnSize.Sm)
            }
        }
        return
    }
    val profile = (s.profile as? Loaded.Ready)?.value ?: s.cached
    val status = when {
        s.loginFailed || attempt?.phase == SignInPhase.Failed -> stringResource(R.string.acct_failed)
        attempt?.phase == SignInPhase.Expired -> stringResource(R.string.acct_expired)
        active -> stringResource(when (attempt?.phase) {
            SignInPhase.Initializing -> R.string.acct_initializing; SignInPhase.WaitingBrowser -> R.string.acct_waiting; else -> R.string.acct_completing
        })
        s.signedIn -> profile?.contact ?: stringResource(if (s.profile == null) R.string.acct_loading else R.string.acct_profile_unavailable)
        else -> stringResource(R.string.acct_sign_in_desc)
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccountAvatar(account, 32.dp, s.signedIn)
                    Column(Modifier.weight(1f)) {
                        Text(if (s.signedIn) profile?.name ?: stringResource(R.string.acct_signed_in) else stringResource(R.string.acct_signed_out),
                            style = DshType.s14m, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status, style = DshType.xs13, color = c.labelTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (s.signedIn) Row(Modifier.clip(Radius.sm).clickable { open(s.platformRoot + "/") }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.acct_info), style = DshType.xs13, color = c.labelSecondary)
                    DIcon(R.drawable.dsw_right_up_outline, size = 12.dp, tint = c.labelSecondary)
                }
            }
            if (active) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (attempt?.authorizeUrl != null) DButton(stringResource(R.string.acct_open), { account.openSignInPage() }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                val scope = rememberCoroutineScope()
                DButton(stringResource(R.string.cancel), { scope.launch { account.cancel() } }, variant = BtnVariant.Outline, size = BtnSize.Sm,
                    enabled = attempt?.phase != SignInPhase.Committing)
            }
        }
        if (s.signedIn) {
            BalanceCard(s, open)
            if (usingApiKey) Text(stringResource(R.string.acct_using_key), Modifier.padding(horizontal = 4.dp), style = DshType.xxs12, color = c.labelCaption)
            Card {
                Row(Modifier.fillMaxWidth().pressable(requestSignOut, Radius.xl).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DIcon(R.drawable.dsw_logout_outline, size = 16.dp, tint = c.error)
                    Text(stringResource(R.string.acct_sign_out), style = DshType.s14, color = c.error)
                }
            }
        }
    }
    signOutDialog()
}

@Composable
private fun BalanceCard(s: AccountUi, open: (String) -> Unit) {
    val c = Dsh.colors
    val balance = (s.balance as? Loaded.Ready)?.value
    val bonus = balance?.bonus?.filter { it.positive }.orEmpty()
    val showBonus = balance == null || bonus.isNotEmpty()
    val platformLink: @Composable (String) -> Unit = { label ->
        Text(label, Modifier.clip(Radius.sm).clickable { open(s.usageUrl) }.padding(vertical = 2.dp), style = DshType.xs13, color = c.link)
    }
    Card {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            BalanceRow(stringResource(R.string.acct_balance)) {
                when {
                    balance != null && balance.wallets.isNotEmpty() -> Amounts(balance.wallets.map { formatBalance(it.balance, it.symbol) })
                    s.balance == null -> Text(stringResource(R.string.acct_loading), style = DshType.xs13, color = c.labelTertiary)
                    else -> platformLink(stringResource(R.string.acct_balance_unavailable))
                }
            }
            if (showBonus) {
                HDivider()
                BalanceRow(stringResource(R.string.acct_bonus_balance)) {
                    when {
                        bonus.isNotEmpty() -> Amounts(bonus.map { formatBalance(it.balance, it.symbol) })
                        s.balance == null -> Text(stringResource(R.string.acct_loading), style = DshType.xs13, color = c.labelTertiary)
                        else -> platformLink(stringResource(R.string.acct_balance_unavailable))
                    }
                }
            }
            HDivider()
            BalanceRow(stringResource(R.string.acct_more), secondary = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DButton(stringResource(R.string.acct_usage), { open(s.usageUrl) }, variant = BtnVariant.Outline, size = BtnSize.Sm)
                    DButton(stringResource(R.string.acct_top_up), { open(s.topUpUrl) }, size = BtnSize.Sm)
                }
            }
        }
    }
}

@Composable
private fun BalanceRow(label: String, secondary: Boolean = false, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = DshType.xs13, color = if (secondary) Dsh.colors.labelTertiary else Dsh.colors.labelPrimary)
        value()
    }
}

@Composable
private fun Amounts(values: List<String>) = Column(horizontalAlignment = Alignment.End) {
    values.forEach { Text(it, style = DshType.s14m, color = Dsh.colors.labelPrimary) }
}

/** dsh settings card: R20, hairline, card fill. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = Dsh.colors
    Column(Modifier.fillMaxWidth().clip(Radius.xl).background(c.bgLayer2).border(Hairline, c.borderL4, Radius.xl)) { content() }
}

/**
 * dsh sidebar account launcher: signed in → 24 dp avatar + name (menu: 设置, 退出登录); signed out →
 * a plain 设置 row with a 登录 button beside it (spec §3.7: no account → just 设置).
 */
@Composable
fun DrawerAccountRow(account: AccountRepository, onSettings: () -> Unit) {
    val c = Dsh.colors
    val s by account.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val (requestSignOut, signOutDialog) = rememberSignOut(account)
    val profile = s.displayProfile
    if (!s.signedIn) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 44.dp).pressable(onSettings, Radius.md).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DIcon(R.drawable.dsw_settings_outline, size = 16.dp, tint = c.labelSecondary)
                Text(stringResource(R.string.settings), style = DshType.s14, color = c.labelPrimary)
            }
            DButton(stringResource(R.string.acct_sign_in), { account.startSignIn() }, variant = BtnVariant.Outline, size = BtnSize.Sm, icon = R.drawable.dsw_user_outline)
        }
        return
    }
    val label = profile?.name ?: profile?.contact ?: stringResource(R.string.acct_signed_in)
    Box {
        Row(Modifier.fillMaxWidth().height(44.dp).pressable({ menu = true }, Radius.md).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountAvatar(account, 24.dp)
            Text(label, Modifier.weight(1f), style = DshType.s14, color = c.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DMenu(menu, { menu = false }) {
            MenuItem(stringResource(R.string.settings), { menu = false; onSettings() }, R.drawable.dsw_settings_outline)
            MenuItem(stringResource(R.string.acct_sign_out), { menu = false; requestSignOut() }, R.drawable.dsw_logout_outline)
        }
    }
    signOutDialog()
}

/** dsh Toast (toast-bg, white text) for the account's live notices. */
@Composable
fun AccountToastHost(account: AccountRepository) {
    val c = Dsh.colors
    var text by remember { mutableStateOf(R.string.acct_session_expired) }
    var visible by remember { mutableStateOf(false) }
    var serial by remember { mutableStateOf(0) }
    LaunchedEffect(account) {
        account.notices.collect { n ->
            text = when (n) { AccountNotice.SessionExpired -> R.string.acct_session_expired; AccountNotice.ModelSignInRequired -> R.string.acct_model_sign_in_required }
            visible = true
            serial++
        }
    }
    LaunchedEffect(serial) { if (visible) { delay(3000); visible = false } }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Text(stringResource(text),
                Modifier.padding(horizontal = 24.dp).widthIn(max = 420.dp).clip(Radius.md).background(c.toastBg).padding(horizontal = 16.dp, vertical = 10.dp),
                style = DshType.s14, color = Color.White)
        }
    }
}
