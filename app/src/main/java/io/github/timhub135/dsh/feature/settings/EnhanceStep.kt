package io.github.timhub135.dsh.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.timhub135.dsh.R
import io.github.timhub135.dsh.core.designsystem.BtnSize
import io.github.timhub135.dsh.core.designsystem.BtnVariant
import io.github.timhub135.dsh.core.designsystem.DButton
import io.github.timhub135.dsh.core.designsystem.Dsh
import io.github.timhub135.dsh.core.designsystem.DshType

/**
 * Optional onboarding step after the credential: 增强能力 with the same Shizuku card as Settings.
 * Never blocking — 跳过 goes home, and everything here is also offered just in time later.
 */
@Composable
fun EnhanceStep(onDone: () -> Unit) {
    val c = Dsh.colors
    val (_, s) = rememberShellState()
    Column(Modifier.fillMaxSize().background(c.bgBase).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Text(stringResource(R.string.shz_onb_title), style = DshType.xl24, color = c.labelPrimary)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.shz_onb_desc), style = DshType.s14, color = c.labelTertiary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        ShellAccessCard()
        Spacer(Modifier.height(20.dp))
        DButton(stringResource(if (s.ready) R.string.shz_onb_done else R.string.skip), onDone, Modifier.fillMaxWidth(),
            variant = if (s.ready) BtnVariant.Primary else BtnVariant.Outline, size = BtnSize.Lg)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.shz_onb_later), style = DshType.xxs12, color = c.labelCaption, textAlign = TextAlign.Center)
    }
}
