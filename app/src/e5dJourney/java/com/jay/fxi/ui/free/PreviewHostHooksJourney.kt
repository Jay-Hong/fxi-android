package com.jay.fxi.ui.free

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.jay.fxi.e5djourney.E5dJourneyHooks

/**
 * S7 E5d-J (`e5dj_design.claude.r3.md` §3.1): the journey build type's one accessor, in place of the no-op one every other build
 * type compiles. It hands the preview host a hook set for the lifetime of this call site's composition — the host's own — and
 * records that it was selected; it keeps no command as a composition key or saved state.
 */
@Composable
internal fun currentPreviewHostHooks(): PreviewHostHooks {
    val hooks = remember { E5dJourneyHooks.newSet() }
    DisposableEffect(hooks) {
        hooks.attached()
        onDispose { hooks.detached() }
    }
    return hooks
}
