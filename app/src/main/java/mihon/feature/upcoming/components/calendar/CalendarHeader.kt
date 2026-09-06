package mihon.feature.upcoming.components.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import mihon.core.designsystem.utils.currentLocale
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowLeft
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun CalenderHeader(
    yearMonth: YearMonth,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = yearMonth,
            transitionSpec = { getAnimation() },
            label = "Change Month",
        ) { monthYear ->
            Text(
                text = getTitleText(monthYear),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Row {
            IconButton(onClick = onPreviousClick) {
                Icon(MaterialSymbols.Rounded.KeyboardArrowLeft, stringResource(MR.strings.upcoming_calendar_prev))
            }
            IconButton(onClick = onNextClick) {
                Icon(MaterialSymbols.Rounded.KeyboardArrowRight, stringResource(MR.strings.upcoming_calendar_next))
            }
        }
    }
}

private const val MONTH_YEAR_CHANGE_ANIMATION_DURATION = 200

private fun AnimatedContentTransitionScope<YearMonth>.getAnimation(): ContentTransform {
    val movingForward = targetState > initialState

    val enterTransition = slideInVertically(
        animationSpec = tween(durationMillis = MONTH_YEAR_CHANGE_ANIMATION_DURATION),
    ) { height -> if (movingForward) height else -height } + fadeIn(
        animationSpec = tween(durationMillis = MONTH_YEAR_CHANGE_ANIMATION_DURATION),
    )
    val exitTransition = slideOutVertically(
        animationSpec = tween(durationMillis = MONTH_YEAR_CHANGE_ANIMATION_DURATION),
    ) { height -> if (movingForward) -height else height } + fadeOut(
        animationSpec = tween(durationMillis = MONTH_YEAR_CHANGE_ANIMATION_DURATION),
    )
    return (enterTransition togetherWith exitTransition)
        .using(SizeTransform(clip = false))
}

@Composable
@ReadOnlyComposable
private fun getTitleText(monthYear: YearMonth): String {
    val formatter = DateTimeFormatter.ofPattern("MMMM yyyy", currentLocale())
    return formatter.format(monthYear)
}

@Preview
@Composable
private fun CalenderHeaderPreview() {
    CalenderHeader(
        yearMonth = YearMonth.now(),
        onNextClick = {},
        onPreviousClick = {},
    )
}
