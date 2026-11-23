package com.inkforge.notesstudio

import android.view.View
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.floor

internal data class LibraryFolderUi(val id: String, val title: String, val parentId: String, val color: Color, val count: Int)
internal data class LibraryDocumentUi(val record: LibraryDocumentRecord, val date: String) {
    val document get() = record.document
    val pages get() = record.pageCount
}
internal data class LibraryUiState(val title: String, val folder: String, val filter: String,
    val query: String, val folders: List<LibraryFolderUi>, val path: List<LibraryFolderUi>,
    val listMode: Boolean, val sortByTitle: Boolean, val selectionMode: Boolean,
    val selected: Set<String>, val narrow: Boolean, val searchOpen: Boolean,
    val ocrMatches: Set<String> = emptySet(), val scrollReset: Int = 0)

private val libraryBackground = Color(0xfff6f7fb)
private val libraryPanel = Color.White
private val libraryText = Color(0xff202739)
private val libraryMuted = Color(0xff636c80)
private val libraryAccent = Color(0xff344ca3)
private val libraryTint = Color(0xffe8edff)
private val libraryBorder = Color(0xffe0e4ec)

@Composable
internal fun NativeLibraryScreen(state: LibraryUiState, documents: List<LibraryDocumentUi>,
    preview: (LibraryDocumentUi, Int) -> View, icon: (String, Int, Int) -> View,
    translate: (String) -> String, onQuery: (String) -> Unit, onSearch: () -> Unit,
    onFilter: (String) -> Unit,
    onFolder: (String) -> Unit, onFolderMenu: (String) -> Unit, onNewFolder: () -> Unit,
    onNewNote: (String) -> Unit, onImport: () -> Unit, onDocument: (String) -> Unit, onDocumentMenu: (String) -> Unit,
    onFavorite: (String) -> Unit, onSelect: (String) -> Unit, onSelectionMode: () -> Unit,
    onSelectedTrash: () -> Unit, onSort: () -> Unit, onListMode: () -> Unit,
    onSettings: () -> Unit) {
    val visible = remember(documents, state.query, state.folder, state.filter, state.sortByTitle, state.ocrMatches) {
        documents.filter { item ->
            val doc = item.document
            val matches = doc.title.contains(state.query, true) || doc.data.array("tags").toString().contains(state.query, true) ||
                (state.query.isNotBlank() && doc.id in state.ocrMatches)
            matches && when (state.filter) {
                "trash" -> doc.trashed
                "favorite" -> !doc.trashed && doc.favorite
                "shared" -> !doc.trashed && doc.data.optBoolean("shared")
                else -> !doc.trashed && (state.query.isNotBlank() || doc.folder == state.folder)
            }
        }.let { if (state.sortByTitle) it.sortedBy { item -> item.document.title } else it }
    }
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val pad = if (state.narrow) 14 else (screenWidth * .03f).toInt().coerceIn(18, 38)
    val gap = if (state.narrow) 14 else (screenWidth * .032f).toInt().coerceIn(18, 44)
    val available = (screenWidth - if (state.narrow) 0 else 78) - pad * 2
    val columns = if (state.listMode) 1 else if (state.narrow) 2 else
        floor((available + gap).toFloat() / (164 + gap)).toInt().coerceAtLeast(1)
    Column(Modifier.fillMaxSize().background(libraryBackground)) {
        Row(Modifier.weight(1f)) {
            if (!state.narrow) {
                Column(Modifier.width(78.dp).fillMaxHeight().background(Color(0xffeef0f6))
                    .padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconAction("notebook", translate("문서"), icon, Modifier.size(44.dp)) { onFolder("root") }
                    Spacer(Modifier.height(18.dp))
                    listOf(Triple("all", "folder", "모든 문서"), Triple("favorite", "star", "즐겨찾기"),
                        Triple("shared", "users", "공유됨"), Triple("templates", "store", "템플릿"),
                        Triple("trash", "trash", "휴지통")).forEach { (key, glyph, label) ->
                        if (key == "trash") Spacer(Modifier.fillMaxWidth().height(16.dp)
                            .border(0.5.dp, libraryBorder))
                        IconAction(glyph, translate(label), icon, Modifier.size(44.dp), state.filter == key) { onFilter(key) }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
            val groups = remember(visible, columns) { visible.chunked(columns) }
            val listState = key(state.scrollReset) { rememberLazyListState() }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = pad.dp), state = listState,
                contentPadding = PaddingValues(top = if (state.narrow) 10.dp else 14.dp, bottom = if (state.narrow) 92.dp else 72.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.title, color = libraryText, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier)
                        Text("${if (state.filter == "templates") 6 else visible.size}${translate("개")}", color = libraryMuted,
                            fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                        IconAction("search", translate("노트 검색"), icon, Modifier.size(44.dp)) { onSearch() }
                        Spacer(Modifier.width(6.dp))
                        IconAction("settings", translate("설정"), icon, Modifier.size(44.dp)) { onSettings() }
                    }
                }
                if (state.searchOpen || state.query.isNotBlank()) item {
                    BasicTextField(state.query, onQuery, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = libraryText, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().background(libraryPanel, RoundedCornerShape(13.dp))
                            .padding(14.dp).semantics { contentDescription = translate("노트 검색") },
                        decorationBox = { inner -> Box {
                            if (state.query.isEmpty()) Text(translate("노트 검색"), color = libraryMuted, fontSize = 15.sp)
                            inner()
                        } })
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(translate("문서"), color = libraryText, fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onFolder("root") })
                            state.path.forEach { folder ->
                                Text(" / ", color = libraryMuted, fontSize = 15.sp)
                                Text(folder.title, color = libraryText, fontSize = 15.sp,
                                    modifier = Modifier.clickable { onFolder(folder.id) })
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LibraryAction(translate("신규"), true) { onNewNote("grid") }
                            LibraryAction(translate("노트 가져오기"), false, onClick = onImport)
                            IconAction("folderPlus", translate("폴더 생성"), icon, Modifier.size(44.dp)) { onNewFolder() }
                            if (state.folder != "root") IconAction("trash", translate("폴더 메뉴"), icon,
                                Modifier.size(44.dp)) { onFolderMenu(state.folder) }
                            if (!state.narrow) LibraryAction(translate(if (state.sortByTitle) "이름" else "날짜"), false) { onSort() }
                            IconAction(if (state.listMode) "list" else "grid", translate("보기 방식 전환"), icon,
                                Modifier.size(44.dp)) { onListMode() }
                            IconAction("check-circle", translate("문서 선택"), icon, Modifier.size(44.dp),
                                state.selectionMode) { onSelectionMode() }
                        }
                    }
                }
                if (state.filter == "all" && state.query.isBlank()) {
                    item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            state.folders.filter { it.parentId == state.folder }.forEach { folder ->
                                FolderChip(folder, icon, translate, state.narrow,
                                    { onFolder(folder.id) }, { onFolderMenu(folder.id) })
                            }
                        }
                    }
                }
                if (state.filter == "templates") {
                    items(listOf("blank" to "무지", "lined" to "줄 노트", "grid" to "격자",
                        "dotted" to "도트", "cornell" to "코넬", "planner" to "플래너")) { (id, label) ->
                        LibraryAction("${translate(label)} · ${translate("새 노트")}", false, Modifier.fillMaxWidth()) { onNewNote(id) }
                    }
                } else if (visible.isEmpty()) {
                    item { Column(Modifier.fillMaxWidth().padding(vertical = 42.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        NativeGlyph("notebook-plus", 0xff666666.toInt(), 56, icon)
                        Text(translate("노트를 만들어 보세요"), color = libraryText, fontSize = 20.sp,
                            modifier = Modifier.padding(top = 17.dp))
                        Text(translate("+ 신규 버튼으로 템플릿을 고르고 바로 필기를 시작할 수 있습니다."),
                            color = libraryMuted, fontSize = 13.sp)
                        LibraryAction(translate("새 노트"), true, Modifier.padding(top = 18.dp)) { onNewNote("grid") }
                    } }
                } else {
                    items(groups) { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                            group.forEach { item ->
                                LibraryCard(item, state.listMode, item.document.id in state.selected,
                                    preview, icon, translate, { onDocument(item.document.id) }, { onDocumentMenu(item.document.id) },
                                    { onFavorite(item.document.id) }, { onSelect(item.document.id) },
                                    state.selectionMode, Modifier.weight(1f))
                            }
                            repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (state.selectionMode && state.selected.isNotEmpty()) {
                    item { LibraryAction(translate("선택한 문서를 휴지통으로 이동"), false, Modifier.fillMaxWidth(), onSelectedTrash) }
                }
            }
        }
        if (state.narrow) {
            Row(Modifier.fillMaxWidth().background(Color(0xffeef0f6)).padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("all" to "문서", "favorite" to "즐겨찾기", "new" to "신규",
                    "templates" to "템플릿", "settings" to "설정").forEach { (key, label) ->
                    Column(Modifier.weight(1f).clickable {
                        when (key) { "new" -> onNewNote("grid"); "settings" -> onSettings(); else -> onFilter(key) }
                    }.semantics { contentDescription = translate(label) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        NativeGlyph(when(key){"all"->"folder";"favorite"->"star";"new"->"plus-circle";
                            "templates"->"store";else->"settings"},
                            if(state.filter==key)libraryAccent.toArgb() else libraryMuted.toArgb(),23,icon)
                        Text(translate(label), color = if(state.filter==key)libraryAccent else libraryMuted,
                            fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(item: LibraryDocumentUi, listMode: Boolean, selected: Boolean,
    preview: (LibraryDocumentUi, Int) -> View, icon: (String, Int, Int) -> View, translate: (String) -> String,
    onOpen: () -> Unit, onMenu: () -> Unit,
    onFavorite: () -> Unit, onSelect: () -> Unit, selectionMode: Boolean, modifier: Modifier) {
    val doc = item.document
    val coverColor = runCatching { AndroidColor.parseColor(doc.data.optString("coverColor", "#2f7fb7")) }
        .getOrDefault(0xff2f7fb7.toInt())
    @Composable fun Cover(modifier: Modifier) {
        Box(modifier.background(Color.White, RoundedCornerShape(12.dp))) {
            key(doc.id, coverColor, item.record.coverPageId, item.record.coverGeneration) {
                AndroidView(factory = { preview(item, coverColor) }, modifier = Modifier.fillMaxSize())
            }
            IconAction(if (selectionMode) "check-circle" else "star",
                translate(if (selectionMode) "문서 선택" else "즐겨찾기"), icon,
                Modifier.align(Alignment.TopEnd).padding(7.dp).size(32.dp), selected || doc.favorite,
                0xffacacaf.toInt()) { if (selectionMode) onSelect() else onFavorite() }
        }
    }
    @Composable fun Details(modifier: Modifier) {
        Column(modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(doc.title, color = libraryText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconAction("chevron-down", translate("문서 메뉴"), icon, Modifier.size(24.dp)) { onMenu() }
            }
            Text("${item.pages}${translate("페이지")} · ${item.date}", color = libraryMuted, fontSize = 11.sp,
                maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        }
    }
    val root = modifier.background(libraryPanel, RoundedCornerShape(12.dp))
        .border(1.dp, if (selected) libraryAccent else libraryBorder, RoundedCornerShape(12.dp))
        .clickable { if (selectionMode) onSelect() else onOpen() }
        .semantics { contentDescription = "${doc.title} ${translate("열기")}" }
    if (listMode) {
        Row(root, verticalAlignment = Alignment.CenterVertically) {
            Cover(Modifier.size(width = 72.dp, height = 96.dp))
            Details(Modifier.weight(1f).padding(start = 14.dp))
        }
    } else {
        Column(root.padding(8.dp)) {
            Cover(Modifier.fillMaxWidth().aspectRatio(.72f))
            Details(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NativeGlyph(name: String, tint: Int, size: Int, icon: (String, Int, Int) -> View) {
    key(name, tint, size) {
        AndroidView(factory = { icon(name, tint, size) }, modifier = Modifier.size(size.dp))
    }
}

@Composable
private fun IconAction(name: String, label: String, icon: (String, Int, Int) -> View,
    modifier: Modifier, selected: Boolean = false, tint: Int = libraryMuted.toArgb(),
    onClick: () -> Unit) {
    Box(modifier.background(if (selected) libraryTint else Color.Transparent, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        NativeGlyph(name, if (selected) libraryAccent.toArgb() else tint, 23, icon)
    }
}

@Composable
private fun FolderChip(folder: LibraryFolderUi, icon: (String, Int, Int) -> View,
    translate: (String) -> String, narrow: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    Row(Modifier.width(if (narrow) 174.dp else 190.dp).height(if (narrow) 61.dp else 68.dp)
        .background(libraryPanel, RoundedCornerShape(13.dp)).clickable(onClick = onOpen).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NativeGlyph("folder", folder.color.toArgb(), 24, icon)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(folder.title, color = libraryText,
                fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${folder.count}${translate("개 노트")}", color = libraryMuted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction("chevron-down", translate("폴더 메뉴"), icon, Modifier.size(28.dp),
            onClick = onMenu)
    }
}

@Composable
private fun LibraryAction(label: String, selected: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    Box(modifier.background(if (selected) libraryTint else libraryPanel, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).semantics { contentDescription = label }
        .heightIn(min = 42.dp).padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) libraryAccent else libraryText,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
