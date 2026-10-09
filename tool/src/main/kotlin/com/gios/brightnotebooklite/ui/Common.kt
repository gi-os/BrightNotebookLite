package com.gios.brightnotebooklite.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharedFlow

/** The tool's theme, following LightOS's light/dark setting. */
@Composable
fun Themed(content: @Composable () -> Unit) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

/** A back arrow for the top bar's left slot. */
fun backButton(onBack: () -> Unit): LightBarButton =
    LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack, contentDescription = "Back")

/**
 * The standard page: a top bar, a body that scrolls (with the wheel too, when [wheel] is given),
 * and an optional bottom bar.
 */
@Composable
fun Page(
    center: LightTopBarCenter?,
    left: LightBarButton? = null,
    right: LightBarButton? = null,
    bottom: List<LightBarButton?>? = null,
    wheel: SharedFlow<Int>? = null,
    scrollState: ScrollState = rememberScrollState(),
    body: @Composable ColumnScope.() -> Unit,
) {
    Themed {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = left,
                center = center,
                rightButton = right,
                modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
            )
            if (wheel != null) WheelScroll(wheel, scrollState)
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
                scrollState = scrollState,
            ) {
                body()
                Box(modifier = Modifier.height(1f.gridUnitsAsDp()))
            }
            if (bottom != null) LightBottomBar(bottom)
        }
    }
}

/** A tappable row of copy with an optional detail line underneath. */
@Composable
fun ListRow(
    title: String,
    detail: String? = null,
    lighten: Boolean = false,
    strike: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier
        .fillMaxWidth()
        .padding(vertical = 0.5f.gridUnitsAsDp())
    Column(modifier = if (onClick != null) base.lightClickable(onClick = onClick) else base) {
        LightText(text = title, variant = LightTextVariant.Copy, lighten = lighten, strikethrough = strike)
        if (detail != null) {
            LightText(text = detail, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}

/** A small heading between groups of rows. */
@Composable
fun SectionLabel(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), bottom = 0.25f.gridUnitsAsDp()),
    )
}

/** A centred line of copy for an empty page. */
@Composable
fun EmptyLine(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        lighten = true,
        modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
    )
}

/** A hairline rule. */
@Composable
fun Rule(color: Color = LightThemeTokens.colors.contentSecondary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.25f.gridUnitsAsDp())
            .height(1f.gridUnitsAsDp() / 12)
            .background(color),
    )
}

/**
 * A Light typography style scaled for this screen, the same way `LightText` scales its own, for
 * the few places that need an `AnnotatedString` (bold runs inside a note line).
 */
@Composable
fun scaled(style: TextStyle): TextStyle {
    return style.copy(
        fontSize = style.fontSize.scale(),
        lineHeight = style.lineHeight.scale(),
        letterSpacing = style.letterSpacing.scale(),
    )
}

@Composable
private fun TextUnit.scale(): TextUnit = if (isSpecified) value.designVerticalPxToSp() else this
