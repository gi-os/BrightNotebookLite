package com.gios.brightnotebooklite.data

import com.gios.brightnotebooklite.util.CalendarUrl
import com.gios.brightnotebooklite.util.IcsParser
import com.gios.brightnotebooklite.util.ImportedEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/**
 * Subscribed calendars: fetch an .ics feed, read it, and swap that calendar's rows.
 *
 * The same code runs when a feed is first added (so a wrong address says so on the spot) and
 * from the hourly background job.
 */
object FeedSync {

    private val client by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    class FeedException(message: String) : Exception(message)

    /** The body of a feed, or a [FeedException] that says in words what went wrong. */
    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val response = try {
            client.get(url) { header("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5") }
        } catch (e: Exception) {
            throw FeedException("Could not reach ${CalendarUrl.labelFor(url)}")
        }
        if (!response.status.isSuccess()) {
            throw FeedException("The server answered ${response.status.value}")
        }
        val text = response.bodyAsText()
        if (!IcsParser.looksLikeIcs(text)) {
            throw FeedException("That address did not return a calendar")
        }
        text
    }

    /**
     * Adds a feed, or refreshes it if it is already here. Anything that is not a feed address is
     * refused before any network call.
     */
    suspend fun subscribe(dao: NotebookDao, raw: String): Result<CalendarEntity> = runCatching {
        val url = CalendarUrl.feedIn(raw) ?: throw FeedException("That is not a calendar address")
        val text = fetch(url)
        val now = System.currentTimeMillis()
        val existing = dao.calendarByUrl(url)
        val calendar = existing ?: CalendarEntity(
            id = UUID.randomUUID().toString(),
            label = IcsParser.calendarName(text) ?: CalendarUrl.labelFor(url),
            url = url,
            createdAt = now,
        )
        store(dao, calendar, text, now)
    }

    /** Re-reads one feed. On failure the previous copy stays and the error is recorded. */
    suspend fun refresh(dao: NotebookDao, calendar: CalendarEntity): Result<CalendarEntity> {
        val result = runCatching {
            val text = fetch(calendar.url)
            store(dao, calendar, text, System.currentTimeMillis())
        }
        result.exceptionOrNull()?.let { error ->
            val current = dao.calendar(calendar.id) ?: return result
            dao.putCalendar(current.copy(lastError = error.message ?: "Refresh failed"))
        }
        return result
    }

    /** Every feed, one after the other. Returns how many failed. */
    suspend fun refreshAll(dao: NotebookDao): Int =
        dao.calendars().count { refresh(dao, it).isFailure }

    private suspend fun store(dao: NotebookDao, calendar: CalendarEntity, text: String, now: Long): CalendarEntity {
        val events = withContext(Dispatchers.Default) { IcsParser.parse(text, ZoneId.systemDefault()) }
        val rows = events.map { toEntry(calendar.id, it, now) }.distinctBy { it.id }
        val updated = calendar.copy(lastSyncAt = now, lastError = null, eventCount = rows.size)
        dao.replaceEntriesOf(updated, rows)
        return updated
    }

    internal fun toEntry(calendarId: String, event: ImportedEvent, now: Long): EntryEntity = EntryEntity(
        id = "$calendarId/${event.uid}",
        epochDay = event.epochDay,
        text = event.title,
        startMinutes = event.startMinutes,
        endMinutes = event.endMinutes,
        calendarId = calendarId,
        sourceUid = event.uid,
        rrule = event.rrule,
        exDays = event.exDays.sorted().joinToString(",").takeIf { it.isNotBlank() },
        location = event.location,
        createdAt = now,
        updatedAt = now,
    )
}
