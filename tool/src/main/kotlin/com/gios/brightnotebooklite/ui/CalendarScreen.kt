package com.gios.brightnotebooklite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.gios.brightnotebooklite.data.Notebook
import com.gios.brightnotebooklite.data.NotebookDao
import com.gios.brightnotebooklite.data.Planner
import com.gios.brightnotebooklite.data.Settings
import com.gios.brightnotebooklite.util.DayPlan
import com.gios.brightnotebooklite.util.Holidays
import com.gios.brightnotebooklite.util.NoteDates
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import kotlin.math.abs

class CalendarViewModel(
    dao: NotebookDao,
    showHolidays: kotlinx.coroutines.flow.Flow<Boolean>,
) : WheelViewModel<Unit>() {

    val month = MutableStateFlow(NoteDates.monthOf(NoteDates.today()))

    /** Which days of the month have something on them, and which are holidays. */
    data class Marks(val busy: Set<Long> = emptySet(), val holidays: Set<Long> = emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    val marks: StateFlow<Marks> = month.flatMapLatest { m ->
        val from = m.atDay(1).toEpochDay()
        val to = m.atEndOfMonth().toEpochDay()
        combine(Planner.entries(dao, from, to), showHolidays) { entries, holidays ->
            Marks(
                busy = DayPlan.busyDays(entries, from, to),
                holidays = if (holidays) Holidays.inRange(from, to).map { it.epochDay }.toSet() else emptySet(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Marks())

    fun step(months: Long) {
        month.value = month.value.plusMonths(months)
    }

    fun toToday() {
        month.value = NoteDates.monthOf(NoteDates.today())
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // Coming back from a day keeps the month you were looking at; nothing to do.
    }
}

/**
 * A month on one screen. Tap a day to open it; the wheel and a sideways swipe step months.
 */
class CalendarScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, CalendarViewModel>(sealedActivity) {

    private val dao = Notebook.dao(lightContext)

    override val viewModelClass = CalendarViewModel::class.java
    override fun createViewModel() = CalendarViewModel(dao, Settings.showHolidays(lightContext.dataStore))

    @Composable
    override fun Content() {
        val month by viewModel.month.collectAsState()
        val marks by viewModel.marks.collectAsState()
        LaunchedEffect(Unit) {
            viewModel.wheel.collect { viewModel.step(it.toLong()) }
        }
        Themed {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = backButton { goBack(null) },
                    center = LightTopBarCenter.Text(NoteDates.monthTitle(month)),
                    rightButton = LightBarButton.Text(text = "TODAY", onClick = viewModel::toToday),
                )
                MonthGrid(
                    month = month,
                    marks = marks,
                    today = NoteDates.today(),
                    onDay = { day -> navigateTo({ DayScreen(it, day) }) },
                    onSwipe = viewModel::step,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                )
                LightBottomBar(
                    listOf(
                        LightBarButton.LightIcon(icon = LightIcons.UP, onClick = { viewModel.step(-1) }, contentDescription = "Previous month"),
                        LightBarButton.Text(text = "OPEN TODAY", onClick = { navigateTo({ DayScreen(it, NoteDates.today()) }) }),
                        LightBarButton.LightIcon(icon = LightIcons.DOWN, onClick = { viewModel.step(1) }, contentDescription = "Next month"),
                    ),
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    marks: CalendarViewModel.Marks,
    today: Long,
    onDay: (Long) -> Unit,
    onSwipe: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val unit = 1f.gridUnitsAsDp()
    Column(
        modifier = modifier.pointerInput(month) {
            var dragged = 0f
            detectHorizontalDragGestures(
                onDragStart = { dragged = 0f },
                onHorizontalDrag = { _, dx -> dragged += dx },
                onDragEnd = {
                    if (abs(dragged) > size.width / 5f) onSwipe(if (dragged < 0) 1 else -1)
                },
            )
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = unit / 2)) {
            NoteDates.weekdayInitials.forEach { letter ->
                LightText(
                    text = letter,
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        NoteDates.weeks(month).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                week.forEach { day ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .padding(unit / 12),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day != null) {
                            DayCell(
                                day = day,
                                isToday = day == today,
                                isPast = day < today,
                                busy = day in marks.busy,
                                holiday = day in marks.holidays,
                                onClick = { onDay(day) },
                            )
                        }
                    }
                }
            }
        }
        Box(modifier = Modifier.height(unit / 2))
    }
}

@Composable
private fun DayCell(
    day: Long,
    isToday: Boolean,
    isPast: Boolean,
    busy: Boolean,
    holiday: Boolean,
    onClick: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val unit = 1f.gridUnitsAsDp()
    // Today is the one inverted cell, so it reads at a glance on a greyscale panel.
    val ink = if (isToday) colors.background else colors.content
    Box(
        modifier = Modifier
            .fillMaxSize()
            .let { if (isToday) it.background(colors.content) else it }
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = NoteDates.of(day).dayOfMonth.toString(),
                variant = LightTextVariant.Detail,
                color = if (isPast && !isToday) colors.contentSecondary else ink,
            )
            Row(modifier = Modifier.height(unit * 0.5f), verticalAlignment = Alignment.CenterVertically) {
                // A filled dot for something written there, a ring for a holiday.
                if (busy) {
                    Box(modifier = Modifier.size(unit * 0.3f).background(ink, CircleShape))
                }
                if (busy && holiday) Box(modifier = Modifier.size(unit * 0.15f))
                if (holiday) {
                    Box(modifier = Modifier.size(unit * 0.3f).border(unit / 16, ink, CircleShape))
                }
            }
        }
    }
}
