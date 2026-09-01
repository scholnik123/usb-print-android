package ru.usbprint.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

enum class AdaptiveWidthClass { COMPACT, MEDIUM, EXPANDED }

/** One window-width policy shared by responsive screens and deterministic tests. */
object AdaptiveLayoutPolicy {
    const val MEDIUM_MIN_WIDTH_DP = 600
    const val EXPANDED_MIN_WIDTH_DP = 840
    const val MAX_READABLE_CONTENT_WIDTH_DP = 840

    fun widthClass(widthDp: Int): AdaptiveWidthClass = when {
        widthDp < MEDIUM_MIN_WIDTH_DP -> AdaptiveWidthClass.COMPACT
        widthDp < EXPANDED_MIN_WIDTH_DP -> AdaptiveWidthClass.MEDIUM
        else -> AdaptiveWidthClass.EXPANDED
    }
}

/** Wraps choices vertically and constrains every chip to the actual available width. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ResponsiveChoiceFlow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/** Supplies a safe field width: stacked on narrow/large-text windows, two columns otherwise. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AdaptiveTwoColumnFields(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.(Modifier) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stack = maxWidth < 360.dp || LocalDensity.current.fontScale >= 1.5f
        val fieldWidth = if (stack) maxWidth else (maxWidth - 8.dp) / 2
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content(Modifier.width(fieldWidth))
        }
    }
}

/** Scroll-safe content that reacts to the current window constraints, including multi-window resize. */
@Composable
fun AdaptiveContentContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(AdaptiveWidthClass) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthClass = AdaptiveLayoutPolicy.widthClass(maxWidth.value.toInt())
        val horizontalPadding = if (widthClass == AdaptiveWidthClass.COMPACT) 12.dp else 24.dp
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = AdaptiveLayoutPolicy.MAX_READABLE_CONTENT_WIDTH_DP.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = horizontalPadding, top = 2.dp, end = horizontalPadding, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = { content(widthClass) }
        )
    }
}
