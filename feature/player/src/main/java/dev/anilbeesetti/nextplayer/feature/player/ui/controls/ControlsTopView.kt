package dev.anilbeesetti.nextplayer.feature.player.ui.controls

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlayerButton

const val PLAYLIST_TEST_TAG = "openPlaylist"

@Composable
fun ControlsTopView(
    modifier: Modifier = Modifier,
    title: String,
    queuePosition: String? = null,
    onBackClick: () -> Unit = {},
    onPlaylistClick: () -> Unit = {},
) {
    val firstControlFocusRequester = remember { FocusRequester() }
    val systemBarsPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    // Add top spacing only when the system bars don't already provide it (e.g. on TV / landscape).
    val extraTopPadding = if (systemBarsPadding.calculateTopPadding() == 0.dp) 16.dp else 0.dp
    Row(
        modifier = modifier
            .padding(systemBarsPadding.copy(bottom = 0.dp))
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .padding(top = extraTopPadding)
            .focusProperties {
                onEnter = { firstControlFocusRequester.requestFocus() }
            }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlayerButton(onClick = onBackClick) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = null,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Heading),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The queue is already numbered in the playlist panel, but that panel has to be opened to see it;
            // this line is the answer to "which one of the list am I on" while the controls are just up.
            if (queuePosition != null) {
                Text(
                    text = queuePosition,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        PlayerButton(
            modifier = Modifier
                .testTag(PLAYLIST_TEST_TAG)
                .focusRequester(firstControlFocusRequester),
            onClick = onPlaylistClick,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_playlist),
                contentDescription = null,
            )
        }
    }
}

@Preview
@Composable
private fun ControlsTopViewPreview() {
    NextPlayerTheme(darkTheme = true) {
        Surface {
            ControlsTopView(
                title = "Title",
                queuePosition = "Item 2 of 30",
                onBackClick = {},
            )
        }
    }
}
