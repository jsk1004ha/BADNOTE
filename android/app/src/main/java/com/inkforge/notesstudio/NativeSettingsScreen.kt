package com.inkforge.notesstudio

import android.content.res.ColorStateList
import android.view.View
import android.widget.CheckBox
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class SettingsPage { LANGUAGE, OCR, BACKUP, SHORTCUTS, LICENSE }

internal data class BackupSettingsUi(
    val enabled: Boolean,
    val folderName: String?,
    val status: String,
    val running: Boolean
)

private val settingCard = Color(0xfff4f6f8)
private val settingInk = Color(0xff172b42)
private val settingMuted = Color(0xff607185)
private val settingAccent = Color(0xff1397ed)

@Composable
internal fun NativeSettingsScreen(
    preferences: JSONObject,
    page: SettingsPage?,
    backup: BackupSettingsUi,
    translate: (String) -> String,
    icon: (String, Int, Int) -> View,
    onNavigate: (SettingsPage?) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpacity: (Float) -> Unit,
    onOpacityFinished: () -> Unit,
    onLanguage: (String) -> Unit,
    onOcrPolicy: (String) -> Unit,
    onPickBackupFolder: () -> Unit,
    onBackupEnabled: (Boolean) -> Unit,
    onBackupNow: () -> Unit,
    onCancelBackup: () -> Unit,
    onUpdate: () -> Unit,
    onClose: () -> Unit
) {
    var displayedPage by remember { mutableStateOf(page) }
    val overviewScroll = rememberLazyListState()
    val languageScroll = rememberLazyListState()
    val ocrScroll = rememberLazyListState()
    val backupScroll = rememberLazyListState()
    val shortcutsScroll = rememberLazyListState()
    val licenseScroll = rememberLazyListState()
    val alpha = remember { Animatable(1f) }
    val offset = remember { Animatable(0f) }
    LaunchedEffect(page) {
        if (page != displayedPage) {
            coroutineScope {
                launch { alpha.animateTo(.35f, tween(120, easing = FastOutSlowInEasing)) }
                launch { offset.animateTo(-3f, tween(120, easing = FastOutSlowInEasing)) }
            }
            displayedPage = page
            alpha.snapTo(.35f)
            offset.snapTo(10f)
        }
        if (alpha.value == 1f && offset.value == 0f) return@LaunchedEffect
        coroutineScope {
            launch { alpha.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
            launch { offset.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        this.alpha = alpha.value
        translationY = offset.value.dp.toPx()
    }) {
        when (displayedPage) {
            SettingsPage.LANGUAGE -> LanguageDetail(preferences, translate, icon, onNavigate,
                onLanguage, languageScroll)
            SettingsPage.OCR -> OcrDetail(preferences, translate, icon, onNavigate,
                onOcrPolicy, ocrScroll)
            SettingsPage.BACKUP -> BackupDetail(backup, translate, icon, onNavigate, onPickBackupFolder,
                onBackupEnabled, onBackupNow, onCancelBackup, backupScroll)
            SettingsPage.SHORTCUTS -> ShortcutsDetail(translate, icon, onNavigate, shortcutsScroll)
            SettingsPage.LICENSE -> LicenseDetail(translate, icon, onNavigate, licenseScroll)
            else -> SettingsOverview(preferences, backup, translate, onNavigate, onToggle,
                onOpacity, onOpacityFinished, onUpdate, onClose, icon, overviewScroll)
        }
    }
}

@Composable
private fun SettingsOverview(preferences: JSONObject, backup: BackupSettingsUi,
    translate: (String) -> String, onNavigate: (SettingsPage?) -> Unit,
    onToggle: (String, Boolean) -> Unit, onOpacity: (Float) -> Unit,
    onOpacityFinished: () -> Unit, onUpdate: () -> Unit, onClose: () -> Unit,
    icon: (String, Int, Int) -> View, scrollState: LazyListState) {
    val savedOpacity = preferences.optDouble("hudTextOpacity", 1.0).toFloat().coerceIn(.35f, 1f)
    var opacity by remember(savedOpacity) { mutableFloatStateOf(savedOpacity) }
    LazyColumn(Modifier.fillMaxSize(), state = scrollState, contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SettingChoice(translate("언어"),
                translate("앱 표시 언어를 바꿉니다. 노트 내용과 파일명은 변경하지 않습니다."),
                languageName(preferences.optString("language", "ko")), translate("언어")) {
                onNavigate(SettingsPage.LANGUAGE)
            }
        }
        item {
            SettingChoice(translate("손글씨 OCR 언어"),
                translate("한국어와 영어가 섞인 필기를 함께 인식합니다."),
                ocrName(preferences.optString("ocrLanguagePolicy", "ko-primary"), translate),
                translate("손글씨 OCR 언어")) { onNavigate(SettingsPage.OCR) }
        }
        listOf(
            Triple("stylusOnly", "스타일러스 전용 필기", "손바닥과 손가락 입력을 필기에서 제외합니다.") to true,
            Triple("scribbleErase", "낙서해서 지우기", "펜으로 글자 위를 지그재그로 문지르면 반복해서 닿은 필기를 지웁니다.") to true,
            Triple("drawHold", "그려서 도형 만들기", "일반 펜으로 그린 뒤 펜촉을 댄 채 멈추면 직선·곡선·원·타원·사각형으로 변환합니다.") to true,
            Triple("telemetry", "스타일러스 정보 표시", "필압, 기울기, 입력 장치를 화면에 표시합니다.") to false,
            Triple("continuous", "연속 페이지 보기", "모든 페이지를 세로로 이어 표시합니다.") to true,
            Triple("autoOcr", "손글씨 OCR 자동 등록", "필기를 인식해 문서 검색에 등록합니다.") to true,
            Triple("autoMath", "손글씨 수식 자동 계산", "등호로 끝나는 수식을 인식해 계산합니다.") to false,
            Triple("pdfDarkMode", "PDF 다크 모드", "화면의 PDF 배경만 반전하며 필기와 내보내기 색상은 유지합니다.") to false
        ).forEach { (setting, fallback) ->
            item(key = setting.first) {
                ToggleRow(preferences, setting.first, fallback, translate(setting.second),
                    translate(setting.third), onToggle)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(settingCard, RoundedCornerShape(13.dp)).padding(12.dp)) {
                Text(translate("HUD 텍스트 투명도"), color = settingInk, fontWeight = FontWeight.Bold)
                Text(translate("S Pen 지우개와 상태 표시 글자의 투명도를 조절합니다."),
                    color = settingMuted, fontSize = 11.sp)
                Text("${(opacity * 100).toInt()}%", color = settingMuted)
                Slider(value = opacity, onValueChange = { opacity = it; onOpacity(it) },
                    onValueChangeFinished = onOpacityFinished, valueRange = .35f..1f)
            }
        }
        item { SettingAction(translate("업데이트 확인"), "bad note ${BuildConfig.VERSION_NAME}", "settings-update", icon, onUpdate) }
        item {
            SettingAction(translate("자동 백업"), backup.status.ifBlank {
                translate("지정한 폴더에 변경된 노트의 복사본을 저장합니다.")
            }, "settings-backup", icon) { onNavigate(SettingsPage.BACKUP) }
        }
        item {
            SettingAction(translate("키보드 단축키"),
                translate("실행 취소와 숫자 도구 전환을 확인합니다."), "settings-keyboard", icon) {
                onNavigate(SettingsPage.SHORTCUTS)
            }
        }
        item {
            SettingAction(translate("PDFium 오픈소스 고지"),
                translate("PDF 원문 검색 엔진의 라이선스와 포함된 고지를 읽습니다."), "settings-license", icon) {
                onNavigate(SettingsPage.LICENSE)
            }
        }
        item { SettingAction(translate("완료"), "", onClick = onClose) }
    }
}

@Composable
private fun LanguageDetail(preferences: JSONObject, translate: (String) -> String,
    icon: (String, Int, Int) -> View, onNavigate: (SettingsPage?) -> Unit,
    onLanguage: (String) -> Unit, scrollState: LazyListState) {
    val language = preferences.optString("language", "ko")
    DetailList(translate, icon, onNavigate, scrollState) {
        item { DetailHeading(translate("앱 표시 언어")) }
        listOf("ko" to "한국어", "en" to "English", "ja" to "日本語", "zh" to "中文", "pt" to "Português")
            .forEach { (code, label) -> item(key = "language-$code") {
                ChoiceRow(if (code == "pt") "Selecionar português" else translate(label), code == language) {
                    onLanguage(code)
                }
            } }
    }
}

@Composable
private fun OcrDetail(preferences: JSONObject, translate: (String) -> String,
    icon: (String, Int, Int) -> View, onNavigate: (SettingsPage?) -> Unit,
    onOcrPolicy: (String) -> Unit, scrollState: LazyListState) {
    val language = preferences.optString("language", "ko")
    val policy = preferences.optString("ocrLanguagePolicy").ifBlank {
        when (language) { "en" -> "en-primary"; "ja" -> "ja"; "zh" -> "zh"; "pt" -> "pt"; else -> "ko-primary" }
    }
    DetailList(translate, icon, onNavigate, scrollState) {
        item { DetailHeading(translate("손글씨 OCR 언어")) }
        listOf("ko-primary" to "한국어 우선", "en-primary" to "English first",
            "mixed-review" to "한·영 검토", "ja" to "日本語", "zh" to "中文", "pt" to "Português")
            .forEach { (code, label) -> item(key = "ocr-$code") {
                ChoiceRow(translate(label), code == policy) { onOcrPolicy(code) }
            } }
    }
}

@Composable
private fun BackupDetail(backup: BackupSettingsUi, translate: (String) -> String,
    icon: (String, Int, Int) -> View, onNavigate: (SettingsPage?) -> Unit, onPickBackupFolder: () -> Unit,
    onBackupEnabled: (Boolean) -> Unit, onBackupNow: () -> Unit, onCancelBackup: () -> Unit,
    scrollState: LazyListState) {
    DetailList(translate, icon, onNavigate, scrollState) {
        item {
            Column(Modifier.fillMaxWidth().background(settingCard, RoundedCornerShape(13.dp)).padding(14.dp)) {
                Text(translate("선택한 폴더에 변경된 문서의 .ifnote 복사본을 저장합니다. 이전 복사본은 보관합니다. 예약 백업은 약 12시간마다 충전·유휴 상태에서 실행되며 Android가 시점을 조절합니다."),
                    color = settingMuted, fontSize = 12.sp)
                if (backup.status.isNotBlank()) Text(backup.status, color = settingInk, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
        item {
            SettingAction(translate("백업 폴더 선택"), backup.folderName
                ?: translate("읽기·쓰기와 파일 이름 변경을 지원하는 폴더를 선택합니다."), "folder", icon, onPickBackupFolder)
        }
        item {
            SettingAction(translate(if (backup.enabled) "자동 백업 끄기" else "자동 백업 켜기"),
                "", "settings-backup", icon) { onBackupEnabled(!backup.enabled) }
        }
        item {
            SettingAction(translate(if (backup.running) "백업 취소" else "지금 백업"),
                translate("휴지통 문서와 PDF 가져오기가 진행 중인 문서는 건너뜁니다."),
                if (backup.running) "close" else "settings-backup", icon,
                if (backup.running) onCancelBackup else onBackupNow)
        }
    }
}

@Composable
private fun ShortcutsDetail(translate: (String) -> String, icon: (String, Int, Int) -> View,
    onNavigate: (SettingsPage?) -> Unit, scrollState: LazyListState) {
    DetailList(translate, icon, onNavigate, scrollState) {
        listOf("Ctrl + Z" to "실행 취소", "Ctrl + Shift + Z" to "다시 실행",
            "1–9" to "도구 전환", "Ctrl + S" to "노트 저장").forEach { (key, action) ->
            item(key = key) {
                Row(Modifier.fillMaxWidth().background(settingCard, RoundedCornerShape(13.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(key, color = settingAccent, fontWeight = FontWeight.Bold, modifier = Modifier.width(150.dp))
                    Text(translate(action), color = settingInk, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun LicenseDetail(translate: (String) -> String, icon: (String, Int, Int) -> View,
    onNavigate: (SettingsPage?) -> Unit, scrollState: LazyListState) {
    val context = LocalContext.current
    val failure = translate("오픈소스 고지를 불러오지 못했습니다.")
    val loading = translate("오픈소스 고지를 불러오고 있습니다.")
    val notices by produceState<String?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("pdfium-notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
                .getOrElse { "$failure\n${it.message.orEmpty()}" }
        }
    }
    DetailList(translate, icon, onNavigate, scrollState) {
        item {
            SelectionContainer {
                Text(notices ?: loading, color = settingInk, fontSize = 11.sp, lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().background(settingCard, RoundedCornerShape(13.dp)).padding(14.dp))
            }
        }
    }
}

@Composable
private fun DetailList(translate: (String) -> String, icon: (String, Int, Int) -> View,
    onNavigate: (SettingsPage?) -> Unit, scrollState: LazyListState,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), state = scrollState, contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onNavigate(null) }
                .semantics { contentDescription = translate("설정으로 돌아가기") }.padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsGlyph("settings-back", settingAccent.toArgb(), 20, icon)
                    Text(translate("설정"), color = settingAccent, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        content()
    }
}

@Composable
private fun DetailHeading(label: String) {
    Text(label, color = settingInk, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(settingCard, RoundedCornerShape(13.dp))
        .clickable(onClick = onClick).semantics { contentDescription = label }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = settingInk, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (selected) Text("✓", color = settingAccent, fontSize = 17.sp)
    }
}

@Composable
private fun ToggleRow(preferences: JSONObject, key: String, fallback: Boolean,
    label: String, description: String, onToggle: (String, Boolean) -> Unit) {
    var checked by remember(preferences.toString(), key) { mutableStateOf(preferences.optBoolean(key, fallback)) }
    Row(Modifier.fillMaxWidth().background(settingCard, RoundedCornerShape(13.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = settingInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(description, color = settingMuted, fontSize = 11.sp)
        }
        AndroidView(factory = { context -> CheckBox(context).apply {
            buttonTintList = ColorStateList.valueOf(settingAccent.toArgb())
            contentDescription = label
        } }, update = { control ->
            control.setOnCheckedChangeListener(null)
            control.isChecked = checked
            control.contentDescription = label
            control.setOnCheckedChangeListener { _, value -> checked = value; onToggle(key, value) }
        }, modifier = Modifier.size(42.dp))
    }
}

@Composable
private fun SettingChoice(label: String, description: String, value: String,
    accessibilityLabel: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(settingCard, RoundedCornerShape(13.dp)).clickable(onClick = onClick)
        .semantics { contentDescription = accessibilityLabel }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = settingInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(description, color = settingMuted, fontSize = 11.sp)
        }
        Box(Modifier.background(Color(0xfff0f2f5), RoundedCornerShape(12.dp)).heightIn(min = 38.dp)
            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(value, color = Color(0xff202228), fontSize = 14.sp)
        }
    }
}

@Composable
private fun SettingAction(label: String, description: String, glyph: String? = null,
    icon: ((String, Int, Int) -> View)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(settingCard, RoundedCornerShape(13.dp)).clickable(onClick = onClick)
        .semantics { contentDescription = label }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null && icon != null) {
            SettingsGlyph(glyph, settingMuted.toArgb(), 22, icon)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = settingInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (description.isNotBlank()) Text(description, color = settingMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsGlyph(name: String, tint: Int, size: Int, icon: (String, Int, Int) -> View) {
    key(name, tint, size) {
        AndroidView(factory = { icon(name, tint, size) }, modifier = Modifier.size(size.dp))
    }
}

private fun languageName(code: String) = when (code) {
    "en" -> "English"; "ja" -> "日本語"; "zh" -> "中文"; "pt" -> "Português"; else -> "한국어"
}

private fun ocrName(code: String, translate: (String) -> String) = when (code) {
    "en-primary" -> "English first"; "mixed-review" -> translate("한·영 검토")
    "ja" -> "日本語"; "zh" -> "中文"; "pt" -> "Português"; else -> translate("한국어 우선")
}
