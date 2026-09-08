package com.jay.fxi.ui.rates.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jay.fxi.ui.free.RateRowEditEntry
import com.jay.fxi.ui.free.RateRowEditor
import com.jay.fxi.ui.theme.SecondaryText
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The list being edited, so a test can find it without guessing at row text. */
const val RATE_ROW_SHEET_TAG = "rate-row-sheet"

/**
 * Where the user says which sources they want and in what order.
 *
 * The skeleton is the paid sheet's (`ui/components/BankCustomizeSheet.kt`) — a modal sheet, a
 * cancel/title/done header, one reorderable row per source with a visibility eye. It is copied
 * rather than called for the same reason the rate row was: that file is reached only through
 * `MainScreen`, which nothing calls, and it speaks a preference model this surface does not share.
 *
 * **Everything happens in a draft.** Nothing is written until 완료, so 취소 and a swipe-down both
 * mean "forget it" without needing an undo path. The draft is seeded once and deliberately not
 * re-seeded when a new snapshot lands: a list rearranging itself under a dragging finger is worse
 * than a list that is one refresh out of date for the seconds the sheet is open.
 *
 * The seed also travels back with the answer. Whether the user *reordered* can only be judged
 * against what they were shown, and it decides whether an order is stored at all — recording the
 * arrival order because somebody hid one bank would freeze the list, and every source the server
 * added afterwards would land at the end for good.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateRowCustomizeSheet(
    editor: RateRowEditor,
    onDismiss: () -> Unit,
    onApply: (seeded: List<String>, order: List<String>, hidden: Set<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Seeded once, by the list this sheet was opened for.
    val seeded = remember(editor.list) { editor.entries }
    var draft by remember(editor.list) { mutableStateOf(seeded) }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        draft = draft.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag(RATE_ROW_SHEET_TAG)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("취소") }
                Text(
                    text = editor.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                TextButton(
                    onClick = {
                        onApply(
                            seeded.map { it.code },
                            draft.map { it.code },
                            draft.filterNot { it.visible }.map { it.code }.toSet()
                        )
                    }
                ) { Text("완료", fontWeight = FontWeight.SemiBold) }
            }

            Text(
                text = "맨 위의 소스가 기준이 됩니다.",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = SecondaryText
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                items(draft, key = { it.code }) { entry ->
                    ReorderableItem(reorderState, key = entry.code) { _ ->
                        SheetRow(
                            entry = entry,
                            onToggle = {
                                draft = draft.map {
                                    if (it.code == entry.code) it.copy(visible = !it.visible) else it
                                }
                            },
                            dragHandle = {
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = "${entry.label} 순서 바꾸기",
                                    tint = SecondaryText,
                                    modifier = Modifier.size(24.dp).draggableHandle()
                                )
                            }
                        )
                    }
                }
            }

            TextButton(
                onClick = { draft = seeded },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("되돌리기", color = SecondaryText) }
        }
    }
}

/**
 * One source in the sheet.
 *
 * A hidden row stays legible rather than disappearing — it is the row somebody came here to turn
 * back on. Half opacity would make the very rows this screen exists for the hardest to read, so the
 * state is carried by the eye icon and by the accessibility label instead.
 */
@Composable
private fun SheetRow(
    entry: RateRowEditEntry,
    onToggle: () -> Unit,
    dragHandle: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.semantics {
                contentDescription = if (entry.visible) "${entry.label} 숨기기" else "${entry.label} 표시하기"
            }
        ) {
            Icon(
                imageVector = if (entry.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = null,
                tint = if (entry.visible) MaterialTheme.colorScheme.onSurface else SecondaryText
            )
        }
        RateRowGlyph(entry.code, entry.label, Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.label, style = MaterialTheme.typography.bodyLarge)
            if (entry.projected) {
                // D18: the screen is showing this row even though the user hid it, because hiding
                // everything would have left the heading empty. Saying so here is the other half of
                // "설정 화면도 같은 effective 결과를 보여준다".
                Text("임시 표시 중", style = MaterialTheme.typography.bodySmall, color = SecondaryText)
            }
        }
        dragHandle()
    }
}
