package com.shortvideo.autoswipe.ui

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.model.RunStage
import com.shortvideo.autoswipe.model.RunState
import com.shortvideo.autoswipe.model.RunStatus
import com.shortvideo.autoswipe.model.RunControlAction
import com.shortvideo.autoswipe.model.intervalSeconds
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayContent(
    state: RunState,
    collapsed: Boolean,
    maxHeight: Dp,
    onCollapse: () -> Unit,
    onAction: (RunControlAction) -> Unit,
    onHandle: (String, RectF) -> Unit,
) {
    if (!state.hasOverlayContent) return
    val session = state.session ?: return
    val windowColor = Color.Black.copy(alpha = .58f)
    if (collapsed) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(24.dp))
                .background(windowColor).handle("compact", onHandle)
                .clickable(role = Role.Button, onClick = onCollapse).padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                state.compactLabel, color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis, style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
        return
    }
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(12.dp))
            .background(windowColor).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).handle("header", onHandle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("短视频挂机", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                Text(state.label, color = Color.White, fontSize = 11.sp, maxLines = 1)
            }
            Box(Modifier.size(44.dp).clickable(role = Role.Button, onClick = onCollapse), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_collapse), "收起悬浮窗", Modifier.size(18.dp), tint = Color.White)
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().handle("count", onHandle).padding(bottom = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("${session.completed}/${session.config.swipeCount} 次", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (state.locked && session.stage != RunStage.STARTUP && session.dwellMs > 0) {
                val interval = remember(session.dwellMs) { intervalSeconds(session.dwellMs) }
                Text("停留 " + interval + " 秒", color = Color.White, fontSize = 12.sp, maxLines = 1)
            }
        }
        val log = state.latestLog
        val locale = LocalLocale.current.platformLocale
        val formatter = remember(locale) { SimpleDateFormat("HH:mm", locale) }
        key(session.id, log?.message) {
            val scroll = rememberScrollState()
            val logBounds = remember { RectF() }
            val scrollable = scroll.maxValue > 0
            // A scrollable log consumes vertical swipes; header/count still drag the window.
            SideEffect { onHandle("log", if (scrollable) RectF() else RectF(logBounds)) }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth()
                    .handle("log") { name, bounds ->
                        logBounds.set(bounds)
                        onHandle(name, if (scroll.maxValue > 0) RectF() else bounds)
                    }
                    .verticalScroll(scroll).padding(bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                LogLine(log?.timeMillis, log?.message ?: "等待任务", formatter)
                if (session.config.randomLike) {
                    LogLine(session.likeCountUpdatedAtMillis, "已点赞 ${session.likeCount} 次", formatter, wrap = false)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val paused = state.status == RunStatus.PAUSED
            if (state.status != RunStatus.COMPLETED) {
                ControlButton(
                    if (paused) "继续" else "暂停",
                    { onAction(if (paused) RunControlAction.RESUME else RunControlAction.PAUSE) }, Modifier.weight(1f),
                    icon = if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                    background = Color.White.copy(alpha = .12f),
                    foreground = Color.White, height = 44.dp, compact = true,
                )
            }
            ControlButton(
                "关闭", { onAction(RunControlAction.STOP) }, Modifier.weight(1f),
                icon = R.drawable.ic_stop,
                background = Color.White.copy(alpha = .08f),
                foreground = Color.White, height = 44.dp, compact = true,
            )
        }
    }
}

@Composable
private fun LogLine(timeMillis: Long?, message: String, formatter: SimpleDateFormat, wrap: Boolean = true) {
    val time = remember(timeMillis, formatter) { timeMillis?.let { formatter.format(Date(it)) } ?: "—" }
    Text(
        time + "  " + message,
        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        softWrap = wrap,
        overflow = if (wrap) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

private fun Modifier.handle(key: String, callback: (String, RectF) -> Unit) = onGloballyPositioned { coordinates ->
    val position = coordinates.positionInRoot()
    callback(key, RectF(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height))
}
