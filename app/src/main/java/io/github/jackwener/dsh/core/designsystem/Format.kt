package io.github.jackwener.dsh.core.designsystem

import android.content.Context
import io.github.jackwener.dsh.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    /** Sidebar relative time (dsh ui-workspace Rows): 刚刚 / 9分钟 / 3小时 / 4天 / 2个月 / 1年. */
    fun relative(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        val r = context.resources
        return when {
            s < 60 -> r.getString(R.string.rel_now)
            s < 3600 -> r.getString(R.string.rel_min, s / 60)
            s < 86400 -> r.getString(R.string.rel_hour, s / 3600)
            s < 86400 * 30 -> r.getString(R.string.rel_day, s / 86400)
            s < 86400 * 365 -> r.getString(R.string.rel_month, s / (86400 * 30))
            else -> r.getString(R.string.rel_year, s / (86400 * 365))
        }
    }

    /** dsh turn-header duration: 1小时2分3秒 / 1h 2m 3s. */
    fun duration(context: Context, ms: Long): String {
        val r = context.resources
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
        val parts = buildList {
            if (h > 0) add(r.getString(R.string.dur_h, h))
            if (m > 0) add(r.getString(R.string.dur_m, m))
            if (sec > 0 || isEmpty()) add(r.getString(R.string.dur_s, sec))
        }
        return parts.joinToString(r.getString(R.string.dur_sep))
    }

    /** Compact record timing for the trajectory ledger: 820ms / 3.4s / 2m 5s. */
    fun shortDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }

    fun tokens(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
        else -> n.toString()
    }

    /** 0.853 -> "85%". */
    fun percent(ratio: Double): String = "${kotlin.math.round(ratio * 100).toInt()}%"

    /** Message timestamp: HH:mm today, otherwise M月d日 / MMM d. */
    fun stamp(context: Context, at: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance()
        val same = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        return if (same) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        else context.getString(R.string.date_md, cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    fun clock(at: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(at))
    fun dateTime(at: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))
}
