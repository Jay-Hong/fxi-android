package com.jay.fxi.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jay.fxi.ui.theme.CardBackground
import com.jay.fxi.ui.theme.Primary
import com.jay.fxi.ui.theme.PrimaryText
import com.jay.fxi.ui.theme.SecondaryText

internal const val IDENTITY_RECOVERY_BANNER_TAG = "identity_recovery_banner"
internal const val IDENTITY_RECOVERY_CONTENT_TAG = "identity_recovery_content"

/**
 * Places the banner above [content]. With no notice, it reserves no banner space and leaves
 * inherited insets untouched. The current Root destinations fill the slot; the wrapper still adds
 * layout nodes and does not propagate the content Box's minimum constraints to its children.
 *
 * Takes a notice and a callback rather than a view model, so the layout can be composed on its own
 * with fake inputs — nothing here needs the Root graph or the D24 gate.
 *
 * The content is pushed down rather than covered. An overlay would sit on top of whatever the
 * destination draws first, including a full-screen chart that owns its own gestures.
 * Notice presence controls whether space is reserved; text and action changes can also change
 * the banner's height while the same hold remains open.
 *
 * Status-bar insets: the banner pads itself under the bar, and while it is showing the content box
 * marks those insets consumed. Destinations such as the login and free screens pad themselves for
 * the status bar; without the consumption they would add that padding a second time below the
 * banner. With no notice nothing is consumed, so they behave as before.
 */
@Composable
internal fun IdentityRecoveryHost(
    notice: RecoveryNotice?,
    onRecheck: (holdId: Long) -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (notice != null) {
            IdentityRecoveryBanner(notice = notice, onRecheck = onRecheck)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(IDENTITY_RECOVERY_CONTENT_TAG)
                .then(
                    if (notice != null) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier
                )
        ) {
            content()
        }
    }
}

/**
 * Draws a [RecoveryNotice] and forwards a press. Nothing about *what* to say is decided here; that is
 * [recoveryNoticeFor]'s job, where the JVM tests can reach it.
 */
@Composable
internal fun IdentityRecoveryBanner(
    notice: RecoveryNotice,
    onRecheck: (holdId: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Painted before the inset padding, so the bar area behind the status icons is covered too.
            .background(CardBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(IDENTITY_RECOVERY_BANNER_TAG)
            // Its text changes as a hold moves between states; a polite region reads those changes out
            // without interrupting whatever the user is doing.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = notice.title, color = PrimaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(text = notice.body, color = SecondaryText, fontSize = 13.sp)
        notice.action?.let { label ->
            // The id comes from the notice being drawn now, never a remembered one: a stale id is
            // refused by the coordinator rather than applied to whichever hold stands later.
            TextButton(onClick = { onRecheck(notice.holdId) }) {
                Text(text = label, color = Primary)
            }
        }
    }
}
