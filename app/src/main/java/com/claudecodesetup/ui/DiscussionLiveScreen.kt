package com.claudecodesetup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecodesetup.discussion.DiscussionMode
import com.claudecodesetup.discussion.DiscussionState
import com.claudecodesetup.discussion.Turn
import com.claudecodesetup.discussion.TurnStatus

@Composable
fun DiscussionLiveScreen(
    state: DiscussionState,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onNewDiscussion: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Auto-scroll to bottom as new turns arrive
    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.text?.length) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(state.turns.size - 1)
        }
    }

    AppBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("←", fontSize = 20.sp, color = NexusBlue,
                        modifier = Modifier.clickable(onClick = onBack).padding(end = 10.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            modeBadge(state.mode), fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                            letterSpacing = 3.sp, color = NexusBlue.copy(alpha = 0.7f),
                        )
                        Text(
                            state.topic.take(60) + if (state.topic.length > 60) "…" else "",
                            fontFamily = DmSansFamily, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2,
                        )
                    }
                }
                if (state.isRunning) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444).copy(alpha = 0.15f),
                            contentColor = Color(0xFFEF4444),
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text("Stop", fontFamily = DmSansFamily, fontSize = 12.sp) }
                }
            }

            // Status strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val doneCount = state.turns.count { it.status == TurnStatus.DONE }
                Text(
                    "Turn $doneCount / ${state.maxTurns}",
                    fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText2,
                )
                Text(
                    "↑${state.totalPromptTokens}  ↓${state.totalCompletionTokens}",
                    fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText3,
                )
                if (state.converged) {
                    Text("● converged", fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusGreen)
                }
                if (!state.isRunning && state.stoppedReason != null) {
                    Text("● ${state.stoppedReason}", fontFamily = SpaceMonoFamily, fontSize = 10.sp, color = NexusText3)
                }
            }

            // Transcript
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.turns) { turn ->
                    TurnBubble(turn, onCopy = { clipboard.setText(AnnotatedString(turn.text)) })
                }
            }

            // Footer actions when done
            if (!state.isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onNewDiscussion,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NexusText),
                    ) { Text("New", fontFamily = DmSansFamily) }
                    Button(
                        onClick = onContinue,
                        enabled = state.turns.any { it.status == TurnStatus.DONE } && !state.converged,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NexusAccent, contentColor = Color.White,
                            disabledContainerColor = NexusBorder2,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text("Continue", fontFamily = DmSansFamily) }
                }
            }
        }
    }
}

@Composable
private fun TurnBubble(turn: Turn, onCopy: () -> Unit) {
    val statusColor = when (turn.status) {
        TurnStatus.DONE     -> NexusGreen
        TurnStatus.STREAMING-> NexusAccent
        TurnStatus.PENDING  -> NexusText3
        TurnStatus.SKIPPED  -> NexusAmber
        TurnStatus.FAILED   -> Color(0xFFEF4444)
        TurnStatus.STOPPED  -> NexusText3
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(NexusSurface, RoundedCornerShape(12.dp))
            .border(1.dp, NexusBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, CircleShape),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                turn.speakerLabel, fontFamily = DmSansFamily,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.weight(1f),
            )
            if (turn.completionTokens > 0) {
                Text(
                    "${turn.completionTokens}t",
                    fontFamily = SpaceMonoFamily, fontSize = 9.sp, color = NexusText3,
                )
            }
            if (turn.status == TurnStatus.DONE && turn.text.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    "copy", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                    color = NexusBlue,
                    modifier = Modifier
                        .clickable(onClick = onCopy)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        if (turn.text.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Text(
                turn.text, fontFamily = DmSansFamily, fontSize = 14.sp,
                color = NexusText, lineHeight = 20.sp,
            )
        }
        if (turn.errorMessage != null) {
            Spacer(Modifier.size(6.dp))
            Text(
                turn.errorMessage.take(240),
                fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = statusColor,
            )
        }
        if (turn.status == TurnStatus.STREAMING && turn.text.isEmpty()) {
            Spacer(Modifier.size(6.dp))
            Text("typing…", fontFamily = SpaceMonoFamily, fontSize = 11.sp, color = NexusText3)
        }
    }
}

private fun modeBadge(m: DiscussionMode): String = when (m) {
    DiscussionMode.ROUNDTABLE  -> "ROUNDTABLE"
    DiscussionMode.DEBATE      -> "DEBATE"
    DiscussionMode.CRITIQUE    -> "CRITIQUE"
    DiscussionMode.CODE_REVIEW -> "CODE REVIEW"
}
