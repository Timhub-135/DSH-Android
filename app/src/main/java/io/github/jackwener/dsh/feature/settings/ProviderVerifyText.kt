package io.github.jackwener.dsh.feature.settings

import android.content.Context
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.provider.VerifyFailure
import io.github.jackwener.dsh.core.provider.VerifyOutcome

/** One failed connection test in plain words; the server's own message is kept where it explains more. */
fun describeFailure(context: Context, f: VerifyFailure): String = when (f) {
    is VerifyFailure.KeyInvalid -> context.getString(R.string.verify_key, f.message)
    is VerifyFailure.ModelNotAvailable -> context.getString(R.string.verify_unavailable, f.message.trimEnd('.', '。', ' '))
    is VerifyFailure.ModelNotFound -> context.getString(R.string.verify_not_found, f.message)
    is VerifyFailure.ToolsUnsupported -> context.getString(R.string.verify_no_tools, f.message)
    is VerifyFailure.NoToolCall -> if (f.reply.isBlank()) context.getString(R.string.verify_no_tool_call) else context.getString(R.string.verify_no_tool_call_reply, f.reply)
    is VerifyFailure.Quota -> context.getString(R.string.verify_quota, f.message)
    // Same wording as a failed chat turn and 获取模型列表: cause, hint, raw detail.
    is VerifyFailure.Network -> context.getString(R.string.verify_network, context.getString(f.diagnosis.cause.message)) +
        (f.diagnosis.cause.hint?.let { " " + context.getString(it) } ?: "") + " (" + f.diagnosis.detail.take(160) + ")"
    is VerifyFailure.Server -> if (f.status in 100..599) context.getString(R.string.verify_server, f.status, f.message) else context.getString(R.string.verify_bad_response, f.message)
}

/** The 测试 result: what happened, which model became the main one, and why earlier candidates failed. */
fun describeOutcome(context: Context, outcome: VerifyOutcome, becameMain: Boolean): String {
    val earlier = outcome.failed.filter { outcome !is VerifyOutcome.Stopped || it.second !== outcome.failure }
    return when (outcome) {
        is VerifyOutcome.Passed -> (listOf(context.getString(if (becameMain) R.string.prov_test_ok_main else R.string.prov_test_ok, outcome.model)) +
            earlier.map { (m, f) -> context.getString(R.string.prov_test_earlier, m, describeFailure(context, f)) }).joinToString("\n")
        is VerifyOutcome.NoModelWorked -> (listOf(context.getString(R.string.prov_test_none)) +
            outcome.failed.map { (m, f) -> "· $m — " + describeFailure(context, f) }).joinToString("\n")
        is VerifyOutcome.Stopped -> (listOf(context.getString(R.string.prov_test_stopped, describeFailure(context, outcome.failure))) +
            earlier.map { (m, f) -> context.getString(R.string.prov_test_earlier, m, describeFailure(context, f)) }).joinToString("\n")
    }
}
