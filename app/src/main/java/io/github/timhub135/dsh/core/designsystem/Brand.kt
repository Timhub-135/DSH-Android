package io.github.timhub135.dsh.core.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.timhub135.dsh.R

/** The DeepSeek whale (dsh FishLogo), drawn in the current text color. */
@Composable
fun Whale(modifier: Modifier = Modifier, width: Dp = 34.dp, tint: Color = Dsh.colors.labelPrimary) =
    Icon(painterResource(R.drawable.dsw_whale), null, modifier.size(width, width * 17.04f / 23.16f), tint = tint)

/**
 * Running indicator whale: dsh animates a 28 px whale sprite; here the vector whale "swims"
 * (gentle bob + tilt, 1.6 s loop). Static when reduced motion is on.
 */
@Composable
fun SwimmingWhale(width: Dp = 14.dp, tint: Color = Dsh.colors.deepDiving) {
    if (LocalReducedMotion.current) { Whale(width = width, tint = tint); return }
    val t = rememberInfiniteTransition(label = "whale")
    val bob by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    Whale(Modifier.wrapContentSize().graphicsLayer { translationY = bob * 1.2f * density; rotationZ = bob * 6f }, width, tint)
}

/** Sidebar brand row: whale + wordmark. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Whale(width = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text("deepseek", style = DshType.l20.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight(600), letterSpacing = (-0.2).sp), color = Dsh.colors.labelPrimary)
        Spacer(Modifier.width(5.dp))
        Text("HARNESS", style = DshType.xxxs11m.copy(letterSpacing = 1.sp), color = Dsh.colors.labelTertiary)
    }
}
