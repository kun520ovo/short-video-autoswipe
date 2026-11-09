package com.shortvideo.autoswipe.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shortvideo.autoswipe.R
import com.shortvideo.autoswipe.model.TargetApp
import com.shortvideo.autoswipe.model.RunStatus
import com.shortvideo.autoswipe.platform.PermissionKind

@Composable
fun TaskScreen(
    model: MainViewModel,
    onStart: () -> Unit,
    onPause: (resume: Boolean) -> Unit,
    onAllPermissions: () -> Unit,
    onPermission: (PermissionKind) -> Unit,
) {
    val ui by model.uiState.collectAsStateWithLifecycle()
    val palette = LocalAppPalette.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val inputsLocked = ui.run.locked || !ui.ready
    Column(
        Modifier.fillMaxSize().background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .verticalScroll(rememberScrollState())
            .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }
            .padding(horizontal = 24.dp),
    ) {
        Text("短视频挂机", color = palette.ink, fontSize = 23.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 24.dp, bottom = 26.dp))
        Text("应用名称", color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp))
        val appError = ui.errors["app"]
        PlainInput(
            ui.draft.appName, { model.edit("app", it) }, inputsLocked,
            Modifier.fillMaxWidth().height(52.dp), placeholder = "填写手机里的应用名称",
            isError = appError != null,
            onBlur = { model.touched("app") },
        )
        FieldError(appError)
        if (ui.draft.appName.isNotBlank() && (ui.match.selected != null || ui.appLoading || ui.match.candidates.size > 1)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                val selected = ui.match.selected
                when {
                    selected != null -> {
                        InstalledIcon(selected, model, Modifier.size(22.dp))
                        Text(selected.label, Modifier.weight(1f), color = palette.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("已匹配", color = palette.accent, fontSize = 11.sp)
                        if (ui.match.candidates.size > 1 && !ui.run.locked) TextButton(onClick = model::openPicker) { Text("更换") }
                    }
                    ui.appLoading -> Text("正在查找应用", color = palette.muted, fontSize = 12.sp)
                    ui.match.candidates.size > 1 -> {
                        Text("找到 " + ui.match.candidates.size + " 个应用", Modifier.weight(1f), color = palette.ink, fontSize = 12.sp)
                        TextButton(onClick = model::openPicker) { Text("选择") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingRow("滑屏次数") {
            PlainInput(
                ui.draft.count, { model.edit("count", it) }, inputsLocked, Modifier.width(150.dp).height(44.dp),
                unit = "次", numeric = true, placeholder = "填写次数", onBlur = { model.touched("count") },
            )
        }
        FieldError(ui.errors["count"])
        HorizontalDivider(color = palette.line)
        SettingRow("停留时间") {
            Row(
                Modifier.width(150.dp).height(44.dp).clip(RoundedCornerShape(10.dp)).background(palette.field).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                InputText(ui.draft.minSeconds, { model.edit("min", it) }, inputsLocked, Modifier.weight(1f), "最短", { model.touched("time") })
                Text("–", color = palette.muted, fontSize = 13.sp)
                InputText(ui.draft.maxSeconds, { model.edit("max", it) }, inputsLocked, Modifier.weight(1f), "最长", { model.touched("time") })
                Text("秒", color = palette.muted, fontSize = 12.sp)
            }
        }
        FieldError(ui.errors["time"])
        HorizontalDivider(color = palette.line)
        SettingRow("随机双击点赞") {
            Switch(
                checked = ui.draft.randomLike, onCheckedChange = model::setLike, enabled = !inputsLocked,
                colors = SwitchDefaults.colors(checkedTrackColor = palette.accent),
            )
        }
        if (ui.draft.randomLike) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("点赞概率", color = palette.muted, fontSize = 12.sp)
                PlainInput(
                    ui.draft.likePercent, { model.edit("like", it) }, inputsLocked, Modifier.width(94.dp).height(44.dp),
                    unit = "%", numeric = true, placeholder = "概率", onBlur = { model.touched("like") },
                )
            }
            FieldError(ui.errors["like"])
        }
        HorizontalDivider(color = palette.line)
        Text("运行权限", color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 5.dp))
        PermissionRow("无障碍服务", if (ui.permissions.accessibility) "已开启" else "未开启", ui.permissions.accessibility) { onPermission(PermissionKind.ACCESSIBILITY) }
        PermissionRow("悬浮窗", if (ui.permissions.overlay) "已授权" else "未授权", ui.permissions.overlay) { onPermission(PermissionKind.OVERLAY) }
        ui.message?.let { Text(it, color = palette.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(14.dp))
        if (ui.run.locked) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val paused = ui.run.status == RunStatus.PAUSED
                ControlButton(
                    if (paused) "继续" else "暂停",
                    { focus.clearFocus(); if (paused && !ui.permissions.granted) onAllPermissions() else onPause(paused) },
                    Modifier.weight(1f),
                    icon = if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                    background = palette.accent, foreground = MaterialTheme.colorScheme.onPrimary,
                )
                ControlButton("关闭", model::stop, Modifier.weight(1f), R.drawable.ic_stop, background = palette.field, foreground = palette.ink)
            }
            Text("本次参数已锁定，关闭后可修改", color = palette.muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 13.dp))
        } else {
            val grant = !ui.permissions.granted
            ControlButton(
                ui.primaryLabel,
                onClick = {
                    focus.clearFocus()
                    keyboard?.hide()
                    if (grant) onAllPermissions() else onStart()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = ui.ready && (grant || ui.permissions.connected),
                background = if (grant) palette.field else palette.accent,
                foreground = if (grant) palette.ink else MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
    if (ui.picker) AppPicker(ui.match.candidates, model)
}

@Composable
private fun SettingRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalAppPalette.current.ink, fontSize = 14.sp)
        content()
    }
}

@Composable
private fun PlainInput(
    value: String, change: (String) -> Unit, readOnly: Boolean, modifier: Modifier,
    unit: String? = null, numeric: Boolean = false, placeholder: String = "", isError: Boolean = false, onBlur: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.clip(shape).background(palette.field)
            .then(if (isError) Modifier.border(1.dp, palette.error, shape) else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        InputText(
            value, change, readOnly, Modifier.weight(1f), placeholder, onBlur,
            numeric = numeric, centered = false,
            fontSize = if (numeric) 17.sp else 16.sp, placeholderSize = if (numeric) 14.sp else 16.sp,
        )
        if (unit != null) Text(unit, color = palette.muted, fontSize = 12.sp)
    }
}

@Composable
private fun InputText(
    value: String, change: (String) -> Unit, readOnly: Boolean, modifier: Modifier,
    placeholder: String, onBlur: () -> Unit,
    numeric: Boolean = true, centered: Boolean = true,
    fontSize: TextUnit = 16.sp, placeholderSize: TextUnit = 12.sp,
) {
    val palette = LocalAppPalette.current
    val focus = LocalFocusManager.current
    BasicTextField(
        value, change, modifier.onFocusChanged { if (!it.isFocused) onBlur() },
        readOnly = readOnly, singleLine = true,
        textStyle = TextStyle(color = palette.ink, fontSize = fontSize, fontWeight = FontWeight.Medium,
            textAlign = if (centered) TextAlign.Center else TextAlign.Unspecified),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { inner ->
            Box(
                if (centered) Modifier.fillMaxWidth() else Modifier,
                contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = palette.muted, fontSize = placeholderSize,
                    maxLines = 1, overflow = if (centered) TextOverflow.Clip else TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

@Composable
private fun FieldError(value: String?) {
    if (value != null) Text(value, color = LocalAppPalette.current.error, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp, bottom = 9.dp))
}

@Composable
private fun PermissionRow(label: String, status: String, granted: Boolean, open: () -> Unit) {
    val palette = LocalAppPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(status, color = if (granted) palette.accent else palette.error, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = open) { Text(if (granted) "管理" else if (label == "悬浮窗") "去授权" else "去开启", fontSize = 14.sp) }
    }
}

@Composable
private fun InstalledIcon(app: TargetApp, model: MainViewModel, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, app.component) {
        value = model.container.resolver.icon(app)?.asImageBitmap()
    }
    val image = bitmap
    if (image != null) Image(image, null, modifier.clip(RoundedCornerShape(5.dp)))
    else Box(modifier.clip(RoundedCornerShape(5.dp)).background(LocalAppPalette.current.field), contentAlignment = Alignment.Center) {
        Text(app.label.take(1), color = LocalAppPalette.current.ink, fontSize = 12.sp)
    }
}

@Composable
private fun AppPicker(apps: List<TargetApp>, model: MainViewModel) {
    val palette = LocalAppPalette.current
    AlertDialog(
        onDismissRequest = model::closePicker,
        title = { Text("选择目标应用") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(apps, key = { it.component }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { model.selectApp(app) }.padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InstalledIcon(app, model, Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, color = palette.ink)
                            Text(app.packageName, fontSize = 11.sp, color = palette.muted)
                            if (apps.count { it.packageName == app.packageName } > 1) Text(app.component.substringAfterLast('.'), fontSize = 11.sp, color = palette.muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = model::closePicker) { Text("取消") } },
    )
}
