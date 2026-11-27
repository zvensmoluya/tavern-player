package io.github.zvensmoluya.tavernplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Velocity

// Stop leftover list motion at its edge. The Material drag handle is outside this subtree
// and retains the normal swipe-to-dismiss gesture.
private object SheetContentScroll : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
        Offset(0f, available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity) =
        Velocity(0f, available.y)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScrollableBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(Modifier.testTag("sheetDragHandle")) },
        containerColor = containerColor,
        contentColor = contentColor,
    ) {
        Column(Modifier.nestedScroll(SheetContentScroll), content = content)
    }
}
