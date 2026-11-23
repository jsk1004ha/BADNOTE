package com.inkforge.notesstudio

import android.text.format.Formatter
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val updateInk = Color(0xff172b42)
private val updateMuted = Color(0xff607185)
private val updateAccent = Color(0xff106ba8)
private val updateAccentSoft = Color(0xffe4f3fd)
private val updateCard = Color(0xfff4f6f8)
private val updateBorder = Color(0xffdce3ea)
private val updateDanger = Color(0xffa23737)
private val updateEnter = CubicBezierEasing(.2f, 0f, 0f, 1f)

@Composable
internal fun NativeAppUpdateDialog(
    visible: Boolean,
    state: UpdateScreenState,
    installedVersion: String,
    translate: (String) -> String,
    icon: (String, Int, Int) -> View,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { visibility.targetState = visible }
    if (!visibility.currentState && !visibility.targetState) return

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(tween(340, easing = updateEnter)) +
                slideInVertically(tween(340, easing = updateEnter)) { it / 16 },
            exit = fadeOut(tween(230, easing = FastOutLinearInEasing)) +
                slideOutVertically(tween(230, easing = FastOutLinearInEasing)) { it / 24 }
        ) {
            val configuration = LocalConfiguration.current
            val horizontalMargin = if (configuration.screenWidthDp < 480) 18.dp else 28.dp
            Column(
                Modifier.padding(horizontal = horizontalMargin).widthIn(max = 620.dp).fillMaxWidth()
                    .heightIn(max = (configuration.screenHeightDp * .82f).dp).clip(RoundedCornerShape(24.dp))
                    .background(Color.White).padding(horizontal = 22.dp, vertical = 20.dp)
            ) {
                UpdateHeader(state, translate, icon, onDismiss)
                Spacer(Modifier.height(18.dp))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                ) {
                    UpdateStatus(state, installedVersion, translate, icon)
                    UpdateReleaseNotes(state.release, translate)
                }
                Spacer(Modifier.height(22.dp))
                UpdateActions(state.stage, translate, onDismiss, onPrimary, onCancelDownload)
            }
        }
    }
}

@Composable
internal fun NativeAppUpdateNotice(
    release: AppUpdater.Release?,
    translate: (String) -> String,
    icon: (String, Int, Int) -> View,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedContent(
        targetState = release,
        transitionSpec = {
            (fadeIn(tween(320, easing = updateEnter)) + slideInVertically(tween(320, easing = updateEnter)) { it / 6 })
                .togetherWith(fadeOut(tween(200, easing = FastOutLinearInEasing)))
        },
        label = "Update notice"
    ) { offer ->
        if (offer == null) return@AnimatedContent
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(15.dp))
                .background(updateAccentSoft).padding(start = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UpdateGlyph("settings-update", updateAccent.toArgb(), 22, icon)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(translate("새 업데이트"), color = updateInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(releaseLabel(offer, translate), color = updateMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(translate("보기"), color = updateAccent, fontWeight = FontWeight.Bold)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp).semantics { contentDescription = translate("업데이트 알림 닫기") }
            ) {
                UpdateGlyph("close", updateMuted.toArgb(), 19, icon)
            }
        }
    }
}

@Composable
private fun UpdateHeader(
    state: UpdateScreenState,
    translate: (String) -> String,
    icon: (String, Int, Int) -> View,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val description = if (state.stage == UpdateStage.AVAILABLE && (state.release?.size ?: 0) > 0) {
        Formatter.formatShortFileSize(context, checkNotNull(state.release).size)
    } else descriptionFor(state.stage)?.let(translate)

    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(updateAccentSoft),
            contentAlignment = Alignment.Center
        ) {
            UpdateGlyph("settings-update", updateAccent.toArgb(), 25, icon)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 2.dp)) {
            Text(
                translate(titleFor(state.stage)), color = updateInk, fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }
            )
            if (description != null) {
                Text(description, color = if (state.stage in failureStages) updateDanger else updateMuted,
                    fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(48.dp).semantics { contentDescription = translate("업데이트 화면 닫기") }
        ) {
            UpdateGlyph("close", updateMuted.toArgb(), 20, icon)
        }
    }
}

@Composable
private fun UpdateStatus(
    state: UpdateScreenState,
    installedVersion: String,
    translate: (String) -> String,
    icon: (String, Int, Int) -> View
) {
    val release = state.release
    val progress = state.percent?.coerceIn(0, 100)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(updateCard).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(translate("현재 버전"), color = updateMuted, fontSize = 11.sp)
                Text(installedVersion, color = updateInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            if (release != null) {
                UpdateGlyph("arrow", updateMuted.toArgb(), 20, icon)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(translate("새 버전"), color = updateMuted, fontSize = 11.sp)
                    Text(releaseLabel(release, translate), color = updateAccent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (state.stage == UpdateStage.CHECKING) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = updateAccent, trackColor = updateBorder)
        } else if (state.stage == UpdateStage.DOWNLOADING) {
            Spacer(Modifier.height(14.dp))
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = updateAccent, trackColor = updateBorder)
            } else {
                val animated by animateFloatAsState(progress / 100f, tween(250, easing = updateEnter),
                    label = "APK download progress")
                LinearProgressIndicator(
                    progress = { animated }, modifier = Modifier.fillMaxWidth(),
                    color = updateAccent, trackColor = updateBorder
                )
                Text("$progress%", color = updateMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }
    }
}

@Composable
private fun UpdateReleaseNotes(release: AppUpdater.Release?, translate: (String) -> String) {
    val lines = remember(release?.notes) { release?.notes.orEmpty().toReadableReleaseNotes() }
    if (lines.isEmpty()) return
    Spacer(Modifier.height(18.dp))
    Text(translate("이번 업데이트"), color = updateInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Column(
        Modifier.fillMaxWidth().padding(top = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        lines.forEach { line ->
            Row {
                Text("•", color = updateAccent, fontSize = 14.sp)
                Text(line, color = updateMuted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 9.dp))
            }
        }
    }
}

@Composable
private fun UpdateActions(
    stage: UpdateStage,
    translate: (String) -> String,
    onDismiss: () -> Unit,
    onPrimary: () -> Unit,
    onCancelDownload: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (stage) {
            UpdateStage.CHECKING -> {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(translate("닫기"), color = updateMuted)
                }
            }
            UpdateStage.DOWNLOADING -> {
                OutlinedButton(onClick = onCancelDownload, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(translate("다운로드 취소"), color = updateInk)
                }
            }
            UpdateStage.CURRENT -> UpdatePrimaryButton(translate("확인"), Modifier.weight(1f), onDismiss)
            else -> {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(translate("나중에"), color = updateMuted)
                }
                UpdatePrimaryButton(translate(primaryLabelFor(stage)), Modifier.weight(1f), onPrimary)
            }
        }
    }
}

@Composable
private fun UpdatePrimaryButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = updateAccent, contentColor = Color.White),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UpdateGlyph(name: String, tint: Int, size: Int, icon: (String, Int, Int) -> View) {
    key(name, tint, size) {
        AndroidView(factory = { icon(name, tint, size) }, modifier = Modifier.size(size.dp))
    }
}

private fun titleFor(stage: UpdateStage) = when (stage) {
    UpdateStage.CHECKING -> "업데이트 확인 중"
    UpdateStage.CURRENT -> "최신 버전입니다"
    UpdateStage.CHECK_FAILED -> "업데이트를 확인하지 못했습니다"
    UpdateStage.AVAILABLE -> "새 버전이 있습니다"
    UpdateStage.DOWNLOADING -> "업데이트 다운로드 중"
    UpdateStage.DOWNLOAD_FAILED -> "다운로드하지 못했습니다"
    UpdateStage.READY -> "설치할 준비가 됐습니다"
    UpdateStage.INSTALL_PERMISSION -> "설치 권한이 필요합니다"
    UpdateStage.INSTALL_FAILED -> "설치를 시작하지 못했습니다"
}

private fun descriptionFor(stage: UpdateStage): String? = when (stage) {
    UpdateStage.CHECK_FAILED -> "연결을 확인한 뒤 다시 시도해 주세요."
    UpdateStage.DOWNLOADING -> "화면을 닫으면 다운로드가 취소됩니다."
    UpdateStage.DOWNLOAD_FAILED -> "연결을 확인한 뒤 다시 시도해 주세요."
    UpdateStage.READY -> "Android 설치 화면에서 업데이트를 완료해 주세요."
    UpdateStage.INSTALL_PERMISSION -> "이 출처의 앱 설치를 허용해 주세요."
    else -> null
}

private fun primaryLabelFor(stage: UpdateStage) = when (stage) {
    UpdateStage.CHECK_FAILED -> "다시 확인"
    UpdateStage.AVAILABLE -> "다운로드"
    UpdateStage.DOWNLOAD_FAILED -> "다시 다운로드"
    UpdateStage.READY -> "설치"
    UpdateStage.INSTALL_PERMISSION -> "설정 열기"
    UpdateStage.INSTALL_FAILED -> "다시 시도"
    else -> "확인"
}

private val failureStages = setOf(
    UpdateStage.CHECK_FAILED,
    UpdateStage.DOWNLOAD_FAILED,
    UpdateStage.INSTALL_FAILED
)

private fun releaseLabel(release: AppUpdater.Release, translate: (String) -> String): String {
    val build = release.build?.let { " · ${translate("빌드")} $it" }.orEmpty()
    return "${release.version}$build"
}

private val markdownLink = Regex("""\[([^]]+)]\([^)]+\)""")

private fun String.toReadableReleaseNotes(): List<String> {
    val text = lineSequence().map(String::trim).filterNot { it.isBlank() || it.startsWith('#') }.toList()
    val lines = text.filter { it.startsWith("- ") || it.startsWith("* ") }.ifEmpty { text }
    return lines.take(8).map { line ->
        markdownLink.replace(line.removePrefix("- ").removePrefix("* ")) { it.groupValues[1] }
            .replace("**", "").replace("`", "")
    }
}
