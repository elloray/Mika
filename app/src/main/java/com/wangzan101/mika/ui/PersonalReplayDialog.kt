package com.wangzan101.mika.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.personal.PersonalReplayFrame
import com.wangzan101.mika.personal.PersonalRuntime
import com.wangzan101.mika.ui.components.ActivitySizedDialog
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun PersonalReplayDialog(
    runtime: PersonalRuntime,
    conversationId: String,
    isLive: Boolean,
    initialFrame: Bitmap?,
    onDismiss: () -> Unit,
) {
    val liveFrames = remember(runtime, conversationId) { runtime.phonePreview(listOf(conversationId)) }
    val liveFrame by produceState(initialFrame, liveFrames, isLive) {
        if (isLive) liveFrames.collect { preview ->
            preview?.frame?.takeUnless { it.isRecycled }?.let { value = it }
        }
    }
    val frames by produceState<List<PersonalReplayFrame>?>(null, runtime, conversationId, isLive) {
        do {
            value = runtime.replayFrames(conversationId)
            if (isLive) delay(1_000)
        } while (isLive)
    }
    PersonalPreviewPlayer(frames, liveFrame, isLive, onDismiss)
}

@Composable
internal fun PersonalPreviewPlayer(
    frames: List<PersonalReplayFrame>?,
    liveFrame: Bitmap?,
    isLive: Boolean,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    val recorded = frames.orEmpty()
    val first = recorded.firstOrNull()?.atMs ?: 0L
    val duration = recorded.lastOrNull()?.let { it.atMs - first } ?: 0L
    // A null position follows the latest frame as the recording grows.
    var seekPosition by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    val position = seekPosition?.coerceIn(0, duration) ?: duration
    LaunchedEffect(playing, duration) {
        while (playing) {
            delay(100)
            val next = (seekPosition ?: duration) + 100
            if (next >= duration) {
                seekPosition = null
                playing = false
            } else {
                seekPosition = next
            }
        }
    }
    val latestFrame = liveFrame?.takeUnless { it.isRecycled }
    val showLive = isLive && seekPosition == null && latestFrame != null
    val file = if (showLive) null else {
        val index = recorded.binarySearchBy(first + position) { it.atMs }
        recorded.getOrNull(if (index >= 0) index else -index - 2)?.file
    }
    val recordedFrame by produceState<Bitmap?>(null, file) {
        if (file != null) value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path) }
    }
    val image = if (showLive) latestFrame else recordedFrame ?: latestFrame
    ActivitySizedDialog(onDismiss) { safeArea ->
        Column(Modifier.fillMaxSize().background(c.bg).then(safeArea).padding(12.dp)
            .testTag("personal-replay-dialog")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("手机画面", style = MikaType.Title, color = c.ink)
                BarIconButton(MikaIcons.Close, "关闭", onDismiss)
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("personal-preview-image"), contentAlignment = Alignment.Center) {
                if (image != null) {
                    ZoomableFrame(image, if (showLive) "实时手机画面" else "录制的任务画面", Modifier.fillMaxSize())
                } else {
                    Text(if (frames == null || isLive || file != null) "正在加载画面…" else "还没有可播放的画面。",
                        style = MikaType.Body, color = c.ink2)
                }
            }
            Slider(
                value = if (duration > 0) position.toFloat() else 1f,
                onValueChange = { target ->
                    playing = false
                    seekPosition = if (target >= duration.toFloat()) null else target.toLong()
                },
                modifier = Modifier.testTag("personal-preview-timeline"),
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = duration > 0,
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (isLive && seekPosition == null) {
                    Text("实时", Modifier.padding(horizontal = 12.dp), style = MikaType.Hint, color = c.accent)
                } else {
                    TextButton(enabled = duration > 0, onClick = {
                        if (position >= duration) seekPosition = 0
                        playing = !playing
                    }) { Text(if (playing) "暂停" else "播放") }
                }
                Text("${replayTime(position)} / ${replayTime(duration)}", style = MikaType.Hint, color = c.ink2)
                if (isLive && seekPosition != null) {
                    TextButton(onClick = { playing = false; seekPosition = null }) { Text("回到实时") }
                }
            }
        }
    }
}

private fun replayTime(ms: Long): String {
    val seconds = ms / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
