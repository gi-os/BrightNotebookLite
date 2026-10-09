package com.gios.brightnotebooklite.work

import com.gios.brightnotebooklite.data.FeedSync
import com.gios.brightnotebooklite.data.Notebook
import com.thelightphone.sdk.LightJob
import com.thelightphone.sdk.LightJobHandler
import com.thelightphone.sdk.LightJobResult
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightContext
import kotlin.time.Duration.Companion.hours

const val REFRESH_JOB = "refresh-calendars"

/**
 * Re-reads every subscribed calendar. A feed that fails keeps its previous copy and records why;
 * the job itself only asks to be retried when every feed failed, which is what no signal looks like.
 */
@LightJob("refresh-calendars")
val refreshCalendars: LightJobHandler = { ctx, _ ->
    val dao = Notebook.dao(ctx)
    val total = dao.calendars().size
    val failed = FeedSync.refreshAll(dao)
    if (total > 0 && failed == total) {
        LightJobResult.Retry
    } else {
        LightJobResult.Success(outputFilePath = null, message = "Refreshed ${total - failed} of $total")
    }
}

/** Hourly, replacing any earlier schedule so there is only ever one. */
fun scheduleRefresh(context: SealedLightContext) {
    LightWork.enqueuePeriodic(context, REFRESH_JOB, repeatInterval = 1.hours)
}
