package io.github.timhub135.dsh.core.capability.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.timhub135.dsh.core.capability.ActivityLauncher
import io.github.timhub135.dsh.core.capability.CallContext
import io.github.timhub135.dsh.core.capability.InvalidArgsException
import io.github.timhub135.dsh.core.capability.PermissionMissingException
import io.github.timhub135.dsh.core.capability.Tool
import io.github.timhub135.dsh.core.capability.ToolResult
import io.github.timhub135.dsh.core.capability.ToolSet
import io.github.timhub135.dsh.core.capability.bool
import io.github.timhub135.dsh.core.capability.int
import io.github.timhub135.dsh.core.capability.long
import io.github.timhub135.dsh.core.capability.reqStr
import io.github.timhub135.dsh.core.capability.str
import io.github.timhub135.dsh.core.model.Risk
import io.github.timhub135.dsh.core.model.ToolGroup
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommsTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launcher: ActivityLauncher,
) : ToolSet {
    private val cr get() = context.contentResolver
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private companion object {
        /** The MIME type calendar apps declare for their new-event page. */
        const val EVENT_TYPE = "vnd.android.cursor.dir/event"
    }

    private fun need(permission: String, label: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw PermissionMissingException(label)
    }

    private fun smsError(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off / airplane mode"
        SmsManager.RESULT_ERROR_NULL_PDU -> "invalid message"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure (no SIM, or the carrier refused)"
        else -> "error $code"
    }

    /** ISO-8601 (with or without offset; a bare date = local midnight) or epoch ms. */
    private fun time(args: JsonObject, isoKey: String, msKey: String): Long? {
        args.str(isoKey)?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
            val zone = java.time.ZoneId.systemDefault()
            runCatching { return java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }
            runCatching { return java.time.ZonedDateTime.parse(v).toInstant().toEpochMilli() }
            runCatching { return java.time.LocalDateTime.parse(v.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
            runCatching { return java.time.LocalDateTime.parse(v.replace(' ', 'T') + ":00").atZone(zone).toInstant().toEpochMilli() }
            runCatching { return java.time.LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli() }
            throw InvalidArgsException("'$isoKey' is not ISO-8601 (expected e.g. 2026-10-29T15:00): $v")
        }
        return args.long(msKey)
    }

    override val tools = listOf(
        object : Tool("contacts_search", ToolGroup.Comms, Risk.sensitive, false, "Search contacts by name or number.", {
            string("query", "Name or number fragment", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.READ_CONTACTS, "Contacts")
                val query = args.reqStr("query")
                val rows = mutableListOf<String>()
                // The filter URI matches names and numbers regardless of formatting ("138 0013 8000" vs "13800138000").
                val uri = android.net.Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, android.net.Uri.encode(query))
                cr.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null)?.use { c -> while (c.moveToNext() && rows.size < 50) rows += "${c.getString(0)}: ${c.getString(1)}" }
                return ToolResult.text(rows.distinct().joinToString("\n").ifEmpty { "No contacts found." })
            }
        },
        object : Tool("sms_read", ToolGroup.Comms, Risk.sensitive, false, "Read recent SMS (inbox and sent), optionally filtered by sender/number or text.", {
            string("query", "Filter on number or body"); int("limit", "Default 20")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.READ_SMS, "SMS")
                val q = args.str("query")
                val rows = mutableListOf<String>()
                cr.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                    q?.let { "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?" }, q?.let { arrayOf("%$it%", "%$it%") },
                    "${Telephony.Sms.DATE} DESC")?.use { c ->
                    val limit = args.int("limit") ?: 20
                    while (c.moveToNext() && rows.size < limit) {
                        val dir = when (c.getInt(3)) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> "←"; Telephony.Sms.MESSAGE_TYPE_SENT -> "→"
                            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "(draft) →"; Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "(sending) →"
                            Telephony.Sms.MESSAGE_TYPE_FAILED -> "(failed) →"; else -> "?"
                        }
                        rows += "${fmt.format(Date(c.getLong(2)))} $dir ${c.getString(0)}: ${c.getString(1)}"
                    }
                }
                return ToolResult.text(rows.joinToString("\n").ifEmpty { "No messages." })
            }
        },
        object : Tool("call_log_read", ToolGroup.Comms, Risk.sensitive, false, "Recent calls.", { int("limit", "Default 20") }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.READ_CALL_LOG, "Call log")
                val rows = mutableListOf<String>()
                cr.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                    null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                    val limit = args.int("limit") ?: 20
                    while (c.moveToNext() && rows.size < limit) {
                        val type = when (c.getInt(3)) { CallLog.Calls.INCOMING_TYPE -> "in"; CallLog.Calls.OUTGOING_TYPE -> "out"; CallLog.Calls.MISSED_TYPE -> "missed"; else -> "other" }
                        rows += "${fmt.format(Date(c.getLong(2)))} $type ${c.getString(0) ?: ""} ${c.getString(1)} ${c.getLong(4)}s"
                    }
                }
                return ToolResult.text(rows.joinToString("\n").ifEmpty { "No calls." })
            }
        },
        object : Tool("calendar_read", ToolGroup.Comms, Risk.sensitive, false,
            "Calendar events in a time range (default: the next 7 days). Times are ISO-8601 local time like 2026-10-29T15:00 (or epoch ms).", {
            string("from", "Start, ISO-8601 local time or date"); string("to", "End, ISO-8601 local time or date")
            int("fromMs", "Start (epoch ms), alternative to from"); int("toMs", "End (epoch ms), alternative to to")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.READ_CALENDAR, "Calendar")
                val from = time(args, "from", "fromMs") ?: System.currentTimeMillis()
                val to = time(args, "to", "toMs") ?: (from + 7 * 86_400_000L)
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(from.toString()).appendPath(to.toString()).build()
                val rows = mutableListOf<String>()
                cr.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION),
                    null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                    while (c.moveToNext() && rows.size < 100) rows += "${fmt.format(Date(c.getLong(1)))}–${fmt.format(Date(c.getLong(2)))} ${c.getString(0)} ${c.getString(3) ?: ""}"
                }
                return ToolResult.text(rows.joinToString("\n").ifEmpty { "No events." })
            }
        },
        object : Tool("calendar_add", ToolGroup.Comms, Risk.write, false,
            "Add an event to the user's main writable calendar. Give start/end as ISO-8601 local time (2026-10-29T15:00) — the phone's time zone is applied. " +
                "Needs calendar permission; without it, calendar_new_event opens the calendar app's own new-event page instead.", {
            string("title", "", required = true)
            string("start", "Start, ISO-8601 local time (or a date for all-day)"); string("end", "End, ISO-8601 local time (default start + 1h)")
            int("startMs", "Start epoch ms (alternative)"); int("endMs", "End epoch ms (alternative)")
            string("location", ""); string("description", "")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.WRITE_CALENDAR, "Calendar (write)")
                val allDay = args.str("start")?.trim()?.length == 10
                // All-day events are stored at UTC midnight per the CalendarContract rules.
                fun utcDay(k: String) = args.str(k)?.trim()?.takeIf { it.length == 10 }?.let {
                    runCatching { java.time.LocalDate.parse(it).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() }
                val start = (if (allDay) utcDay("start") else null) ?: time(args, "start", "startMs")
                    ?: throw InvalidArgsException("missing 'start' (ISO-8601 like 2026-10-29T15:00)")
                val end = (if (allDay) utcDay("end")?.plus(if (args.str("end") == args.str("start")) 86_400_000L else 0L) else null)
                    ?: time(args, "end", "endMs") ?: (start + if (allDay) 86_400_000L else 3_600_000L)
                if (end < start) throw InvalidArgsException("end is before start")
                // Writable (contributor+) and visible calendars only; prefer the primary one. The first row
                // used to be Holidays / Birthdays / a subscription where the event fails or is invisible.
                val cal = cr.query(CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
                    "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1",
                    null, "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC")?.use {
                    if (it.moveToFirst()) Triple(it.getLong(0), it.getString(1), it.getString(2)) else null
                } ?: return ToolResult.error("unsupported", "No writable calendar on this phone (add a calendar account, e.g. in the Calendar app).")
                val uri = cr.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, cal.first)
                    put(CalendarContract.Events.TITLE, args.reqStr("title"))
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, end)
                    put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
                    if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
                    args.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                    args.str("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                }) ?: return ToolResult.error("failed", "The calendar provider refused the event.")
                return ToolResult.text("Event \"${args.reqStr("title")}\" created ${fmt.format(Date(start))}–${fmt.format(Date(end))} in calendar \"${cal.second}\" (${cal.third}). id=${uri.lastPathSegment}")
            }
        },
        object : Tool("calendar_new_event", ToolGroup.Comms, Risk.write, true,
            "Open the calendar app's new-event page prefilled with title, time, location and notes (no calendar permission needed). " +
                "Nothing is saved yet: then read the screen and tap its save button (screen_read → screen_tap), and check the result. " +
                "Like app_launch it opens on your background screen when there is one. Times are ISO-8601 local time (2026-10-29T15:00); a bare date is all-day.", {
            string("title", "", required = true)
            string("start", "Start, ISO-8601 local time (or a date for all-day)"); string("end", "End, ISO-8601 local time or date (default: start + 1h; all-day: the start day)")
            int("startMs", "Start epoch ms (alternative)"); int("endMs", "End epoch ms (alternative)")
            bool("allDay", "All-day event (also implied by a bare date)")
            string("location", ""); string("description", "")
            screen()
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val zone = java.time.ZoneId.systemDefault()
                fun day(k: String) = args.str(k)?.trim()?.takeIf { it.length == 10 }?.let {
                    runCatching { java.time.LocalDate.parse(it) }.getOrElse { throw InvalidArgsException("'$k' is not a date like 2026-10-29: $it") } }
                val allDay = args.bool("allDay") == true || day("start") != null
                val begin: Long; val end: Long
                if (allDay) {
                    // All-day: local midnights, the end exclusive (the day after the last day).
                    val first = day("start") ?: time(args, "start", "startMs")?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                        ?: throw InvalidArgsException("missing 'start' (a date like 2026-10-29)")
                    val last = day("end") ?: time(args, "end", "endMs")?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: first
                    if (last < first) throw InvalidArgsException("end is before start")
                    begin = first.atStartOfDay(zone).toInstant().toEpochMilli()
                    end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                } else {
                    begin = time(args, "start", "startMs") ?: throw InvalidArgsException("missing 'start' (ISO-8601 like 2026-10-29T15:00)")
                    end = time(args, "end", "endMs") ?: (begin + 3_600_000L)
                    if (end < begin) throw InvalidArgsException("end is before start")
                }
                val title = args.reqStr("title")
                val intent = android.content.Intent(android.content.Intent.ACTION_INSERT)
                    .setDataAndType(CalendarContract.Events.CONTENT_URI, EVENT_TYPE)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
                args.str("location")?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                args.str("description")?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
                if (!launcher.handles(intent)) return ToolResult.error("not_supported",
                    "No calendar app on this phone opens a new-event page through the standard Android request. Open the calendar app (app_launch) and add it on screen.")
                val r = launcher.open(call, args.str("screen"), "Opened the new-event page of", intent, launcher.handler(intent), create = true)
                if (!r.ok) return r
                val span = if (allDay) "all day ${fmtDay(begin)}" + (if (end - begin > 86_400_000L + 3_600_000L) "–${fmtDay(end - 86_400_000L)}" else "")
                    else "${fmt.format(Date(begin))}–${fmt.format(Date(end))}"
                return r.appendText("Prefilled: \"$title\", $span" + (args.str("location")?.let { ", at $it" } ?: "") +
                    ". Not saved yet: screen_read, check the fields (some calendar apps ignore some of them), then tap the save button (保存/完成/✓).")
            }

            private fun fmtDay(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        },
        object : Tool("sms_send", ToolGroup.Comms, Risk.dangerous, false, "Send an SMS. Always confirm recipient and text with the user first.", {
            string("to", "Phone number", required = true); string("text", "Message", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.SEND_SMS, "Send SMS")
                val to = args.reqStr("to"); val text = args.reqStr("text")
                @Suppress("DEPRECATION")
                val sms = (if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else null) ?: SmsManager.getDefault()
                val parts = sms.divideMessage(text)
                // Report what the radio says instead of assuming success (no SIM / no service / airplane mode).
                val action = "${context.packageName}.SMS_SENT.${java.util.UUID.randomUUID()}"
                val results = java.util.concurrent.ConcurrentHashMap<Int, Int>()
                val all = kotlinx.coroutines.CompletableDeferred<Unit>()
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context, i: android.content.Intent) {
                        results[i.getIntExtra("part", -1)] = resultCode
                        if (results.size >= parts.size) all.complete(Unit)
                    }
                }
                androidx.core.content.ContextCompat.registerReceiver(context, receiver, android.content.IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
                try {
                    val sent = ArrayList(parts.indices.map { idx ->
                        android.app.PendingIntent.getBroadcast(context, idx, android.content.Intent(action).setPackage(context.packageName).putExtra("part", idx),
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT)
                    })
                    sms.sendMultipartTextMessage(to, null, parts, sent, null)
                    kotlinx.coroutines.withTimeoutOrNull(30_000) { all.await() }
                } finally { runCatching { context.unregisterReceiver(receiver) } }
                val failed = results.values.filter { it != android.app.Activity.RESULT_OK }
                return when {
                    results.isEmpty() -> ToolResult.text("SMS to $to was handed to the phone (${parts.size} part(s)) but no delivery report came within 30s; check the Messages app before resending.")
                    failed.isEmpty() && results.size >= parts.size -> ToolResult.text("SMS sent to $to (${parts.size} part(s)).")
                    failed.isEmpty() -> ToolResult.text("SMS to $to: ${results.size}/${parts.size} part(s) confirmed sent, the rest unconfirmed; check the Messages app before resending.")
                    else -> ToolResult.error("sms_failed", "SMS to $to failed (${failed.map(::smsError).distinct().joinToString()}). Do not retry blindly; tell the user.")
                }
            }
        },
    )
}
