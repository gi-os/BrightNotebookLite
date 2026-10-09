package com.gios.brightnotebooklite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.gios.brightnotebooklite.data.CalendarEntity
import com.gios.brightnotebooklite.data.FeedSync
import com.gios.brightnotebooklite.data.Notebook
import com.gios.brightnotebooklite.data.NotebookDao
import com.gios.brightnotebooklite.data.Settings
import com.gios.brightnotebooklite.util.CalendarUrl
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBarCenter
import androidx.compose.foundation.background
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

class CalendarsViewModel(
    private val dao: NotebookDao,
    private val store: DataStore<Preferences>,
) : WheelViewModel<Unit>() {

    val calendars: StateFlow<List<CalendarEntity>> = dao.observeCalendars()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val showHolidays: StateFlow<Boolean> = Settings.showHolidays(store)
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** What the last action said, shown full screen until dismissed. Null when there is nothing to say. */
    val message = MutableStateFlow<String?>(null)

    /** True while a feed is being fetched, so the page can say so. */
    val busy = MutableStateFlow(false)

    fun toggleHolidays() {
        viewModelScope.launch { Settings.setShowHolidays(store, !showHolidays.value) }
    }

    fun subscribe(raw: String) {
        if (CalendarUrl.feedIn(raw) == null) {
            message.value = "That is not a calendar address. It should start with https:// or webcal://"
            return
        }
        busy.value = true
        viewModelScope.launch {
            val result = FeedSync.subscribe(dao, raw)
            busy.value = false
            message.value = result.fold(
                onSuccess = { "Added ${it.label}: ${it.eventCount} events. It refreshes every hour." },
                onFailure = { it.message ?: "That calendar could not be added." },
            )
        }
    }

    fun refresh(calendar: CalendarEntity) {
        busy.value = true
        viewModelScope.launch {
            val result = FeedSync.refresh(dao, calendar)
            busy.value = false
            message.value = result.fold(
                onSuccess = { "${it.label} is up to date: ${it.eventCount} events." },
                onFailure = { "${calendar.label}: ${it.message ?: "refresh failed"}. The last copy is kept." },
            )
        }
    }

    fun setVisible(calendar: CalendarEntity, visible: Boolean) {
        viewModelScope.launch { dao.putCalendar(calendar.copy(visible = visible)) }
    }

    fun remove(calendar: CalendarEntity) {
        viewModelScope.launch { dao.deleteCalendar(calendar.id) }
    }
}

/**
 * Subscribed calendars and the holiday switch.
 *
 * A feed address is long and random, so it is normally scanned from a QR code rather than typed;
 * typing works too.
 */
class CalendarsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, CalendarsViewModel>(sealedActivity) {

    private val dao = Notebook.dao(lightContext)

    override val viewModelClass = CalendarsViewModel::class.java
    override fun createViewModel() = CalendarsViewModel(dao, lightContext.dataStore)

    private fun add() {
        navigateTo({ ChoiceScreen(it, "Subscribe", listOf("Scan a QR code", "Type the address"), "A published .ics or webcal:// address") }) { pick ->
            if (pick == 0) {
                navigateTo(::FeedScanScreen) { scanned -> viewModel.subscribe(scanned) }
            } else {
                navigateTo({ TextEditScreen(it, title = "Calendar address", initial = "https://", singleLine = true, submitLabel = "ADD") }) { typed ->
                    viewModel.subscribe(typed.trim())
                }
            }
        }
    }

    private fun manage(calendar: CalendarEntity) {
        val options = listOf("Refresh now", if (calendar.visible) "Hide it" else "Show it", "Remove it")
        navigateTo({ ChoiceScreen(it, calendar.label, options, calendar.url) }) { pick ->
            when (pick) {
                0 -> viewModel.refresh(calendar)
                1 -> viewModel.setVisible(calendar, !calendar.visible)
                2 -> navigateTo({ ChoiceScreen(it, "Remove", listOf("Remove ${calendar.label}", "Keep it"), "Its events leave this phone.") }) { sure ->
                    if (sure == 0) viewModel.remove(calendar)
                }
            }
        }
    }

    @Composable
    override fun Content() {
        val calendars by viewModel.calendars.collectAsState()
        val holidays by viewModel.showHolidays.collectAsState()
        val message by viewModel.message.collectAsState()
        val busy by viewModel.busy.collectAsState()

        val shown = message
        if (shown != null) {
            Themed {
                LightFullscreenModal(message = shown, onClose = { viewModel.message.value = null })
            }
            return
        }

        Page(
            center = LightTopBarCenter.Text("Calendars"),
            left = backButton { goBack(null) },
            bottom = listOf(LightBarButton.Text(text = if (busy) "FETCHING…" else "SUBSCRIBE", onClick = { if (!busy) add() })),
            wheel = viewModel.wheel,
        ) {
            SectionLabel("Holidays")
            ListRow(
                title = "US federal holidays",
                detail = if (holidays) "Shown. Worked out on the phone, no network." else "Hidden",
                onClick = viewModel::toggleHolidays,
            )

            SectionLabel("Subscribed")
            if (calendars.isEmpty()) {
                ListRow(
                    title = "None yet",
                    detail = "SUBSCRIBE adds a published calendar feed, such as a work or school calendar.",
                )
            }
            calendars.forEach { cal ->
                ListRow(
                    title = cal.label + if (cal.visible) "" else " (hidden)",
                    detail = status(cal),
                    lighten = !cal.visible,
                    onClick = { manage(cal) },
                )
            }

            SectionLabel("Time zone")
            ListRow(
                title = ZoneId.systemDefault().id,
                detail = "Times from subscribed calendars are shown in this zone.",
            )
        }
    }

    private fun status(cal: CalendarEntity): String {
        val synced = cal.lastSyncAt?.let {
            "refreshed " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
        } ?: "not refreshed yet"
        return listOfNotNull("${cal.eventCount} events", synced, cal.lastError).joinToString(" · ")
    }
}

/** The camera, for one QR code holding a feed address. Returns the address, or nothing. */
class FeedScanScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        var rejected by remember { mutableStateOf<String?>(null) }
        // The scanner can report the same code several frames running; leave exactly once.
        val done = remember { booleanArrayOf(false) }
        Themed {
            val shown = rejected
            if (shown != null) {
                LightFullscreenModal(
                    message = "That code is not a calendar address.",
                    onClose = { rejected = null },
                )
            } else {
                LightQrCodeScanner(
                    title = "Scan calendar address",
                    onScanned = { value ->
                        val url = CalendarUrl.feedIn(value)
                        if (done[0]) {
                            Unit
                        } else if (url != null) {
                            done[0] = true
                            goBack(url)
                        } else {
                            rejected = value
                        }
                    },
                    onBack = { goBack(null) },
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                )
            }
        }
    }
}
