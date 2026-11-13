package io.github.timhub135.dsh.core.model

import android.content.Context
import androidx.annotation.StringRes
import io.github.timhub135.dsh.R

/** The resource name of a built-in mode, or null for a user mode (named by [SessionMode.name]). */
@StringRes
fun builtInModeName(id: String): Int? = when (id) {
    SessionModes.DEFAULT_ID -> R.string.mode_default
    SessionModes.PHONE_ID -> R.string.mode_phone
    SessionModes.CODE_ID -> R.string.mode_code
    SessionModes.CHAT_ID -> R.string.mode_chat
    else -> null
}

fun modeDisplayName(context: Context, mode: SessionMode): String =
    (if (mode.builtIn) builtInModeName(mode.id)?.let(context::getString) else null) ?: mode.name.ifBlank { mode.id }
