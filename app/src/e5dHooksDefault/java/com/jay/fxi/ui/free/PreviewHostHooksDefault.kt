package com.jay.fxi.ui.free

import androidx.compose.runtime.Composable

/**
 * S7 E5d-J (`e5dj_design.claude.r3.md` §3.1): the accessor every build type but the local journey fixture compiles. It hands the
 * preview host the no-op hooks: the real hide once, and nothing else. It creates, stores, collects and reaches nothing.
 */
@Composable
internal fun currentPreviewHostHooks(): PreviewHostHooks = NoPreviewHostHooks
