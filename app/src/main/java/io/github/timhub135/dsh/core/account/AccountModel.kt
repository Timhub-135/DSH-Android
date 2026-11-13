package io.github.timhub135.dsh.core.account

import io.github.timhub135.dsh.core.session.a
import io.github.timhub135.dsh.core.session.l
import io.github.timhub135.dsh.core.session.o
import io.github.timhub135.dsh.core.session.s
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Client-side mirror of dsh's account projection (`@deepseek-ai/dsh-deepseek-account/types`):
 * AccountView, SignInAttemptView, AccountProfile, AccountWallet. Credentials never cross it.
 */
enum class SignInPhase(val id: String) {
    Initializing("initializing"), WaitingBrowser("waiting-browser"), Exchanging("exchanging"), Committing("committing"),
    Succeeded("succeeded"), Cancelled("cancelled"), Expired("expired"), Failed("failed");

    val active get() = this == Initializing || this == WaitingBrowser || this == Exchanging || this == Committing

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

data class SignInAttempt(
    val id: String,
    val phase: SignInPhase,
    val authorizeUrl: String? = null,
    val expiresAt: Long? = null,
    /** dsh SignInErrorCode: no-response | network | protocol | expired | storage. */
    val errorCode: String? = null,
)

/**
 * A Platform page shown in the in-app WebView screen: the attempt's authorization page
 * ([signIn]; leaving it cancels the attempt) or a plain page such as 充值 / 用量.
 */
data class PlatformPage(val url: String, val signIn: Boolean)

data class AccountProfile(val id: String?, val name: String?, val contact: String?, val avatarUrl: String?)

data class Wallet(val currency: String, val balance: String) {
    val symbol get() = if (currency == "USD") "$" else "¥"
    val positive get() = runCatching { BigDecimal(balance).signum() > 0 }.getOrDefault(false)
}

/** One profile / balance query outcome; `null` in [AccountUi] means not loaded yet. */
sealed interface Loaded<out T> {
    data class Ready<T>(val value: T) : Loaded<T>
    data object Failed : Loaded<Nothing>
}

data class Balances(val wallets: List<Wallet>, val bonus: List<Wallet>)

data class AccountUi(
    /** Whether the engine has reported the account at least once in this app process. */
    val known: Boolean = false,
    val signedIn: Boolean = false,
    val attempt: SignInAttempt? = null,
    val usageUrl: String = "$PLATFORM/usage",
    val topUpUrl: String = "$PLATFORM/top_up",
    val profile: Loaded<AccountProfile>? = null,
    val balance: Loaded<Balances>? = null,
    /** Last good profile, kept across restarts for the drawer (name / contact / avatar file). */
    val cached: AccountProfile? = null,
    /** dsh `loginVisible`: the sign-in dialog was explicitly opened. */
    val loginVisible: Boolean = false,
    /** dsh `loginFailed`: the latest start request failed before the engine reported an attempt. */
    val loginFailed: Boolean = false,
) {
    val active get() = attempt?.phase?.active == true
    val platformRoot get() = usageUrl.substringBefore("/usage").ifBlank { PLATFORM }
    /** Name shown in the drawer and settings: profile name → contact (dsh AccountMenu label). */
    val displayProfile get() = (profile as? Loaded.Ready)?.value ?: cached

    companion object { const val PLATFORM = "https://platform.deepseek.com" }
}

sealed interface AccountNotice {
    /** dsh `deepseek-account/session-expired`: Platform rejected the stored login. */
    data object SessionExpired : AccountNotice
    /** dsh `deepseek-account/model-sign-in-required`: an account-model request needs a login. */
    data object ModelSignInRequired : AccountNotice
}

internal object AccountJson {
    fun attempt(o: JsonObject?): SignInAttempt? {
        o ?: return null
        val phase = SignInPhase.of(o.s("phase")) ?: return null
        return SignInAttempt(o.s("id") ?: return null, phase, o.s("authorizeUrl"), o.l("expiresAt"), o.s("errorCode"))
    }

    fun profile(o: JsonObject?): Loaded<AccountProfile>? = when (o?.s("status")) {
        "ready" -> o.o("value")?.let { v -> Loaded.Ready(AccountProfile(v.s("id"), v.s("name"), v.s("contact"), v.s("avatarUrl"))) } ?: Loaded.Failed
        "failed" -> Loaded.Failed
        else -> null
    }

    fun balance(o: JsonObject?): Loaded<Balances>? = when (o?.s("status")) {
        "ready" -> Loaded.Ready(Balances(wallets(o.a("value")), wallets(o.a("bonusWallets"))))
        "failed" -> Loaded.Failed
        else -> null
    }

    private fun wallets(a: kotlinx.serialization.json.JsonArray?) = a.orEmpty().mapNotNull { e ->
        val w = e as? JsonObject ?: return@mapNotNull null
        Wallet(w.s("currency") ?: return@mapNotNull null, w.s("balance") ?: return@mapNotNull null)
    }
}

/**
 * dsh `formatBalance` (ui-settings-account/formatBalance.ts), Platform Web's display rules:
 * two decimals rounded down, grouped digits, `<¥0.01` for positive sub-cent amounts and
 * `-¥0.01` for negative ones. Source strings keep Platform precision (`0E-16`, `5.0000…`).
 */
fun formatBalance(amount: String, symbol: String, locale: Locale = Locale.getDefault()): String {
    val value = runCatching { BigDecimal(amount.trim()) }.getOrElse { return amount }
    if (value.signum() == 0) return "${symbol}0.00"
    if (value.signum() < 0) {
        return "-$symbol" + if (value > BigDecimal("-0.01")) "0.01" else group(value.abs().setScale(2, RoundingMode.HALF_UP), locale)
    }
    if (value < BigDecimal("0.01")) return "<${symbol}0.01"
    return symbol + group(value.setScale(2, RoundingMode.DOWN), locale)
}

private fun group(value: BigDecimal, locale: Locale): String {
    val plain = value.toPlainString()
    val integer = plain.substringBefore('.')
    val fraction = plain.substringAfter('.', "00")
    val grouped = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(locale)).format(BigDecimal(integer))
    return "$grouped.$fraction"
}
