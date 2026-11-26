package org.necmobile

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.function.IntConsumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
internal class LibraryUi(private val activity: MainActivity) {
    private var revision by mutableIntStateOf(0)
    private var launchGame by mutableStateOf<GameLibrary.Game?>(null)
    private var settingsGame by mutableStateOf<GameLibrary.Game?>(null)
    private var defaultsOnly by mutableStateOf(false)
    private var launching by mutableStateOf(false)
    private var launchError by mutableStateOf("")
    private var fontCandidates by mutableStateOf<List<FontResources.Candidate>>(emptyList())
    private var importedFont by mutableStateOf("")
    private var dosDraft by mutableStateOf<DosLibrary.Draft?>(null)
    private var dosTemplates by mutableStateOf<List<DosLibrary.Template>>(emptyList())
    private var dosError by mutableStateOf("")
    private var page by mutableStateOf("library")
    private var managementGame by mutableStateOf<GameLibrary.Game?>(null)
    private data class Choice(val title: String, val items: List<String>, val selected: Int, val action: IntConsumer)
    private var choice by mutableStateOf<Choice?>(null)
    private var errorMessage by mutableStateOf("")
    private var document by mutableStateOf<Pair<String, String>?>(null)
    private var importOpen by mutableStateOf(false)
    private var imageNames by mutableStateOf<List<String>>(emptyList())
    private var importName by mutableStateOf("")
    private var storageUsage by mutableStateOf<AppStorage.Usage?>(null)
    private var storageMessage by mutableStateOf("")
    private var importRevision by mutableIntStateOf(0)

    fun changed() { revision++ }
    fun observe() { revision }
    fun openManagement(game: GameLibrary.Game) { managementGame = game }
    fun entriesChanged(games: List<GameLibrary.Game>) {
        managementGame?.let { previous -> managementGame = games.firstOrNull { it.id == previous.id } }
        settingsGame?.let { previous -> settingsGame = games.firstOrNull { it.id == previous.id } }
    }
    fun showChoices(title: String, items: Array<String>, selected: Int, action: IntConsumer) { choice = Choice(title, items.toList(), selected, action) }
    fun showError(message: String?) { errorMessage = message ?: "操作未完成" }
    fun showDocument(title: String, text: String) { document = title to text }
    fun openPlatformSettings() { page = "settings" }
    fun openImport() { imageNames = emptyList(); importName = ""; importOpen = true }
    fun imagesSelected(names: List<String>) {
        imageNames = names; importOpen = true
        if (importName.isBlank()) importName = names.firstOrNull()?.substringBeforeLast('.') ?: ""
    }
    fun importFinished() { importOpen = false; imageNames = emptyList(); importRevision++ }
    fun showStorage(usage: AppStorage.Usage, message: String) { storageUsage = usage; storageMessage = message }
    fun openLaunch(game: GameLibrary.Game, defaults: Boolean) {
        launchError = ""; defaultsOnly = defaults; launching = false; launchGame = game
    }
    fun openSettings(game: GameLibrary.Game) {
        settingsGame = game; fontCandidates = emptyList(); importedFont = ""
        if (activity.currentPlatform() == 98) activity.loadFonts(game)
    }
    fun fontsLoaded(id: String, fonts: List<FontResources.Candidate>) { if (settingsGame?.id == id) fontCandidates = fonts }
    fun fontImported(id: String, name: String) {
        settingsGame?.takeIf { it.id == id }?.let { importedFont = name; activity.loadFonts(it) }
    }
    fun closeSettings() { settingsGame = null }
    fun launchSucceeded() { launching = false; launchGame = null }
    fun launchFailed(message: String?) {
        launching = false; launchError = message ?: "启动未完成"
        if (launchGame == null) showError(launchError)
    }
    fun playerReturned() { launching = false; changed() }
    fun openDos(draft: DosLibrary.Draft, templates: List<DosLibrary.Template>) { dosDraft = draft; dosTemplates = templates; dosError = "" }
    fun dosTemplatesLoaded(templates: List<DosLibrary.Template>) { dosTemplates = templates }
    fun dosFinished() { dosDraft = null; dosError = ""; importFinished() }
    fun dosFailed(error: String?) { dosError = error ?: "DOS 目录导入未完成" }
    fun createView(): View = ComposeView(activity).apply {
        setContent {
            LibraryTheme { this@LibraryUi.Content() }
        }
    }
    @Composable private fun Content() {
        val platform = activity.currentPlatform()
        var search88 by rememberSaveable { mutableStateOf("") }
        var search98 by rememberSaveable { mutableStateOf("") }
        var filter88 by rememberSaveable { mutableIntStateOf(0) }
        var filter98 by rememberSaveable { mutableIntStateOf(0) }
        val list88 = rememberLazyListState()
        val list98 = rememberLazyListState()
        LaunchedEffect(importRevision) {
            if (importRevision > 0) {
                if (platform == 88) { search88 = ""; filter88 = 0; list88.scrollToItem(0) }
                else { search98 = ""; filter98 = 0; list98.scrollToItem(0) }
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val progress = activity.progressText()
        val busy = activity.isWorking()
        LaunchedEffect(progress, busy) {
            if (!busy && progress.isNotEmpty()) snackbar.showSnackbar(progress)
        }
        BackHandler(enabled = settingsGame != null || managementGame != null || launchGame != null || dosDraft != null || importOpen || page != "library") {
            when {
                launching || activity.isWorking() -> Unit
                dosDraft != null -> { activity.discardDos(dosDraft!!); dosDraft = null }
                importOpen -> { importOpen = false; activity.cancelImageImport() }
                launchGame != null -> launchGame = null
                settingsGame != null -> settingsGame = null
                managementGame != null -> managementGame = null
                else -> page = "library"
            }
        }
        val settings = settingsGame
        val managed = managementGame
        Box(Modifier.fillMaxSize().background(Color(0xff0c100f)), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxHeight().widthIn(max = 600.dp).fillMaxWidth().background(LibraryBackground)) {
                when {
                    settings != null -> Settings(settings, platform)
                    managed != null -> Management(managed, platform)
                    else -> Home(platform, if (platform == 88) search88 else search98,
                        { if (platform == 88) search88 = it else search98 = it },
                        if (platform == 88) filter88 else filter98,
                        { if (platform == 88) filter88 = it else filter98 = it },
                        if (platform == 88) list88 else list98)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp).padding(bottom = if (managed == null && settings == null) 76.dp else 12.dp))
            }
        }
        launchGame?.let { LaunchSheet(it, platform) }
        dosDraft?.let { DosWizard(it) }
        if (importOpen) ImportSheet(platform)
        choice?.let { current ->
            Sheet(current.title, { choice = null }) {
                current.items.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy) {
                        choice = null; current.action.accept(index)
                    }.background(if (index == current.selected) Color(0xff364437) else Color.Transparent).padding(12.dp).heightIn(min = 32.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(item, Modifier.weight(1f))
                        if (index == current.selected) LibraryIcon(LibrarySymbol.Check, tint = LibraryAccent)
                    }
                }
            }
        }
        if (errorMessage.isNotEmpty()) AlertDialog(onDismissRequest = { errorMessage = "" }, title = { Text("操作未完成") },
            text = { Text(errorMessage) }, confirmButton = { TextButton(onClick = { errorMessage = "" }) { Text("知道了") } })
        document?.let { (title, text) ->
            Sheet(title, { document = null }) { SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) } }
        }
        storageUsage?.let { usage ->
            Sheet("数据占用 · PC-88 与 PC-98", { storageUsage = null }) {
                if (storageMessage.isNotEmpty()) Text(storageMessage, color = LibraryAccent)
                Text(usage.describe(activity))
                if (usage.uncompressedStates > 0) {
                    Text("未压缩的即时档 ${android.text.format.Formatter.formatFileSize(activity, usage.uncompressedStates)}", color = LibraryMuted, style = MaterialTheme.typography.bodySmall)
                    Button(shape = RoundedCornerShape(12.dp), onClick = { storageUsage = null; activity.compressStates() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("压缩已有即时档") }
                }
                Text("导入游戏后，应用保留独立可写磁盘。清理可移除已转入 HDI 的源目录、中断导入和失去槽位引用的存档文件。", color = LibraryMuted, style = MaterialTheme.typography.bodySmall)
                if (usage.temporary > 0) Button(shape = RoundedCornerShape(12.dp), onClick = { storageUsage = null; activity.cleanStorage() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("清理残留") }
            }
        }
    }
    @Composable private fun Header(title: String = "NEC Mobile", back: (() -> Unit)? = null, action: @Composable RowScope.() -> Unit = {}) {
        Row(Modifier.fillMaxWidth().background(LibraryBackground).statusBarsPadding().heightIn(min = 70.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (back != null) ActionIcon(LibrarySymbol.Back, "返回", back)
            else Box(Modifier.size(36.dp).background(LibraryAccent, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                LibraryIcon(LibrarySymbol.Monitor, Modifier.size(23.dp), Color(0xff20331a))
            }
            Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            action()
        }
    }
    @Composable private fun ActionIcon(symbol: LibrarySymbol, label: String, click: () -> Unit, tint: Color = LibraryMuted) {
        IconButton(onClick = click, enabled = !activity.isWorking(), modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
            LibraryIcon(symbol, tint = tint)
        }
    }
    @Composable private fun PlatformSwitch(platform: Int) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xff1d2320)).border(1.dp, LibraryLine, RoundedCornerShape(14.dp)).padding(4.dp)) {
            for (value in listOf(98, 88)) {
                val selected = platform == value
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Color(0xff344134) else Color.Transparent)
                    .selectable(selected, enabled = !activity.isWorking(), role = Role.Tab, onClick = { activity.selectPlatform(value) }),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (selected) Box(Modifier.padding(end = 8.dp).size(7.dp).background(LibraryAccent, RoundedCornerShape(2.dp)))
                    Text("PC-$value", fontFamily = FontFamily.Monospace, color = if (selected) Color(0xffe2f4d9) else LibraryMuted, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    @Composable private fun Home(platform: Int, query: String, queryChanged: (String) -> Unit, filter: Int, filterChanged: (Int) -> Unit, list: LazyListState) {
        Scaffold(modifier = Modifier.imePadding(), containerColor = LibraryBackground, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            Header(action = {
                if (page == "library") FilledTonalButton(shape = RoundedCornerShape(12.dp), onClick = { activity.addGame() }, enabled = !activity.isWorking(), contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                    LibraryIcon(LibrarySymbol.Plus, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("添加游戏")
                }
            })
        }, bottomBar = {
            Column(Modifier.background(Color(0xff1c231f)).navigationBarsPadding()) {
                HorizontalDivider(color = LibraryLine)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    for ((tab, title, symbol) in listOf(Triple("library", "游戏库", LibrarySymbol.Library), Triple("updates", "最近更新", LibrarySymbol.Book), Triple("settings", "设置", LibrarySymbol.Settings))) {
                        val selected = page == tab
                        Column(Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(10.dp))
                            .selectable(selected, enabled = !activity.isWorking(), role = Role.Tab, onClick = { page = tab }),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(59.dp, 27.dp).background(if (selected) Color(0xff344635) else Color.Transparent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                LibraryIcon(symbol, tint = if (selected) LibraryAccent else LibraryMuted)
                            }
                            Text(title, fontSize = 13.sp, color = if (selected) LibraryText else LibraryMuted)
                        }
                    }
                }
            }
        }) { padding ->
            when (page) {
                "settings" -> PlatformSettings(platform, Modifier.padding(padding))
                "updates" -> Updates(Modifier.padding(padding))
                else -> Games(platform, query, queryChanged, filter, filterChanged, list, Modifier.padding(padding))
            }
        }
    }
    private fun mediaType(game: GameLibrary.Game): Int {
        val hard = game.media.any { GameLibrary.hardDisk(it) }
        val floppy = game.media.any { !GameLibrary.hardDisk(it) }
        return if (hard && floppy) 3 else if (hard) 2 else 1
    }
    private fun mediaInfo(game: GameLibrary.Game): String {
        val types = listOf("", "软盘", "硬盘", "混合介质")
        val formats = game.media.map { it.file.extension.uppercase() }.distinct().joinToString(" / ")
        val disks = game.media.sumOf { it.titles.size }
        return listOf(types[mediaType(game)], formats, if (disks > 1) "$disks 张盘" else "").filter { it.isNotEmpty() }.joinToString(" · ")
    }
    @Composable private fun Games(platform: Int, query: String, queryChanged: (String) -> Unit, filter: Int, filterChanged: (Int) -> Unit, list: LazyListState, modifier: Modifier) {
        val all = activity.gameEntries()
        val visible = all.filter { game ->
            (filter == 0 || mediaType(game) == filter) && (game.name.contains(query.trim(), true) || game.media.any { it.name.contains(query.trim(), true) })
        }
        val recent = all.firstOrNull { it.played > 0 }
        val types = if (platform == 88) listOf("全部类型", "软盘") else listOf("全部类型", "软盘", "硬盘", "混合介质")
        LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(20.dp, 3.dp, 20.dp, 20.dp)) {
            item("platform") { PlatformSwitch(platform); Spacer(Modifier.height(24.dp)) }
            if (activity.isWorking()) item("progress") { Working(); Spacer(Modifier.height(16.dp)) }
            if (recent != null) item("recent") {
                Text("最近启动", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp)); Recent(recent); Spacer(Modifier.height(23.dp))
            }
            item("search") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("全部游戏", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(if (visible.size == all.size) "${all.size} 款" else "${visible.size} / ${all.size} 款", color = LibraryMuted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, queryChanged, singleLine = true, placeholder = { Text("搜索游戏") }, leadingIcon = { LibraryIcon(LibrarySymbol.Search, tint = LibraryMuted) },
                        trailingIcon = { if (query.isNotEmpty()) ActionIcon(LibrarySymbol.Close, "清除搜索", { queryChanged("") }) },
                        enabled = !activity.isWorking(), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = LibrarySurface, focusedContainerColor = LibrarySurface))
                    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (filter == 0) LibraryLine else Color(0xff50674b)), color = if (filter == 0) LibrarySurface else Color(0xff2b392d)) {
                        ActionIcon(LibrarySymbol.Filter, "按镜像类型筛选", { showChoices("镜像类型", types.toTypedArray(), filter, IntConsumer { filterChanged(it) }) }, if (filter == 0) LibraryMuted else LibraryAccent)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            items(visible, key = { it.id }) { GameRow(it) }
            if (visible.isEmpty()) item("empty") {
                Text(if (all.isEmpty()) "添加游戏文件夹、ZIP 或磁盘镜像以建立游戏库" else "没有符合条件的游戏", Modifier.fillMaxWidth().padding(vertical = 26.dp), color = LibraryMuted)
            }
        }
    }
    @Composable private fun Recent(game: GameLibrary.Game) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Brush.horizontalGradient(listOf(Color(0xff252e26), Color(0xff2d3d2d))))
            .border(1.dp, Color(0xff3d4e3d), RoundedCornerShape(17.dp))) {
            RecentDiskArt(mediaType(game) == 1, Modifier.align(Alignment.CenterEnd).width(150.dp).height(157.dp))
            Column(Modifier.fillMaxWidth().heightIn(min = 157.dp).padding(17.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(game.name, Modifier.fillMaxWidth(.68f), fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(mediaInfo(game), Modifier.fillMaxWidth(.66f), style = MaterialTheme.typography.bodySmall, color = Color(0xffb4c3b1), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Button(shape = RoundedCornerShape(12.dp), onClick = { if (activity.hasSession(game.id)) activity.continueSession(game) else activity.start(game) }, enabled = !activity.isWorking(), modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                    LibraryIcon(LibrarySymbol.Play, Modifier.size(16.dp)); Spacer(Modifier.width(7.dp))
                    Text(if (activity.hasSession(game.id)) "继续游戏" else "启动游戏")
                }
            }
        }
    }
    @Composable private fun MediaBadge(game: GameLibrary.Game, large: Boolean = false) {
        val image = activity.artwork(game.id)
        val floppy = mediaType(game) == 1
        Box(Modifier.size(if (large) 54.dp else 43.dp).clip(RoundedCornerShape(11.dp)).background(if (floppy) Color(0xff383930) else Color(0xff303b35)), contentAlignment = Alignment.Center) {
            if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else LibraryIcon(if (floppy) LibrarySymbol.Floppy else LibrarySymbol.Hard, Modifier.size(if (large) 28.dp else 22.dp), if (floppy) Color(0xffd6cca5) else Color(0xffbad2c0))
        }
    }
    @Composable private fun GameRow(game: GameLibrary.Game) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 70.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = !activity.isWorking()) { activity.manage(game) }.padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    MediaBadge(game)
                    Column(Modifier.weight(1f)) {
                        Text(game.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(mediaInfo(game), color = LibraryMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (game.playTimeMillis > 0) Text("游玩 ${PlayTime.format(game.playTimeMillis)}", color = LibraryMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp).background(Color(0xff2e3b30), RoundedCornerShape(18.dp)))
                    ActionIcon(LibrarySymbol.Play, "启动 ${game.name}", { activity.start(game) }, LibraryAccent)
                }
                ActionIcon(LibrarySymbol.More, "${game.name} 的游戏管理", { activity.manage(game) })
            }
            HorizontalDivider(color = LibraryLine)
        }
    }
    @Composable private fun Working() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(activity.progressText(), color = LibraryAccent, style = MaterialTheme.typography.bodySmall)
        }
    }
    @Composable private fun Group(content: @Composable ColumnScope.() -> Unit) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), color = LibrarySurface, border = BorderStroke(1.dp, LibraryLine)) { Column(content = content) }
    }
    @Composable private fun SettingRow(symbol: LibrarySymbol, title: String, subtitle: String = "", value: String = "", click: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !activity.isWorking(), onClick = click).heightIn(min = 60.dp).padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryIcon(symbol, tint = LibraryAccent)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LibraryMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodySmall, color = LibraryMuted)
            LibraryIcon(LibrarySymbol.Next, Modifier.size(16.dp), LibraryMuted)
        }
    }
    @Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontSize = 14.sp, color = LibraryAccent)
            Group(content)
        }
    }
    @Composable private fun PlatformSettings(platform: Int, modifier: Modifier) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 3.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
            PlatformSwitch(platform)
            Column {
                Text("平台设置", style = MaterialTheme.typography.headlineLarge)
                Text("当前游戏库 · PC-$platform", color = LibraryMuted, fontSize = 14.sp)
            }
            if (activity.isWorking()) Working()
            Section("系统资源") {
                SettingRow(LibrarySymbol.Chip, "系统 ROM / 字库", "导入 PC-$platform 平台资源") { activity.openPlatformSetting("rom") }
                if (platform == 98) {
                    HorizontalDivider(color = LibraryLine)
                    SettingRow(LibrarySymbol.Hard, "DOS 系统模板", "用于创建 DOS 游戏硬盘") { activity.openPlatformSetting("dos") }
                }
            }
            Section("音频 · 所有游戏") {
                SettingRow(LibrarySymbol.Audio, "MIDI 音色库", "导入或选择 SF2 SoundFont") { activity.openPlatformSetting("midi") }
                HorizontalDivider(color = LibraryLine)
                SettingRow(LibrarySymbol.Audio, "手机音频输出", "采样率与缓冲", "跟随系统") { activity.openPlatformSetting("audio") }
            }
            Section("数据管理") {
                SettingRow(LibrarySymbol.Folder, "数据占用与清理", "磁盘、存档、资源和历史残留") { activity.openPlatformSetting("storage") }
            }
            Section("帮助") {
                SettingRow(LibrarySymbol.Book, "使用说明") { activity.openPlatformSetting("guide") }
                HorizontalDivider(color = LibraryLine)
                SettingRow(LibrarySymbol.Book, "来源与许可") { activity.openPlatformSetting("license") }
            }
            Text("NEC Mobile · ${BuildConfig.VERSION_NAME}", color = LibraryMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
    @Composable private fun Updates(modifier: Modifier) {
        var notes by remember { mutableStateOf<List<Pair<String, List<String>>>?>(null) }
        var loadError by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = activity.assets.open("updates/changelog.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    text.split(Regex("(?m)^## +")).drop(1).map { section ->
                        val lines = section.lines()
                        lines.first().trim().substringBefore(" ·") to lines.drop(1).filter { it.isNotBlank() }.map { it.trim().removePrefix("- ").replace("**", "") }
                    }
                }
            }
            notes = result.getOrDefault(emptyList())
            loadError = result.exceptionOrNull()?.localizedMessage ?: ""
        }
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(19.dp)) {
            item {
                Text("最近更新", style = MaterialTheme.typography.headlineLarge)
                Text("当前版本 ${BuildConfig.VERSION_NAME}", color = LibraryMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (notes == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (loadError.isNotEmpty()) item { Text("更新日志读取失败 · $loadError", color = MaterialTheme.colorScheme.error) }
            items(notes.orEmpty(), key = { it.first }) { (version, paragraphs) ->
                Section(version) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
    @Composable private fun Management(game: GameLibrary.Game, platform: Int) {
        val focus = LocalFocusManager.current
        var name by rememberSaveable(game.id, game.name) { mutableStateOf(game.name) }
        var mode by rememberSaveable(game.id, game.mode) { mutableIntStateOf(game.mode) }
        val models = MachineOptions.all(platform).firstOrNull { it.key == "np2kai_model" }
        var model by rememberSaveable(game.id, game.machineOptions["np2kai_model"]) { mutableStateOf(models?.value(game.machineOptions, game.mode) ?: "") }
        var muted by rememberSaveable(game.id, game.machineOptions["output_volume"]) { mutableStateOf(game.machineOptions["output_volume"] == "0") }
        val valid = name.trim().isNotEmpty() && name.length <= 80 && !activity.isWorking()
        Scaffold(modifier = Modifier.imePadding(), containerColor = LibraryBackground, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Header("游戏管理", { managementGame = null }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(21.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    MediaBadge(game, true)
                    Column(Modifier.weight(1f)) {
                        Text(game.name, fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                        Text("PC-$platform · ${mediaInfo(game)}", color = LibraryMuted, style = MaterialTheme.typography.bodySmall)
                        Text("累计游玩 ${PlayTime.format(game.playTimeMillis)}", color = LibraryMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (activity.isWorking()) Working()
                Section("游戏配置") {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true, isError = name.isBlank() || name.length > 80,
                            modifier = Modifier.fillMaxWidth(), enabled = !activity.isWorking())
                        if (platform == 88) SettingChoice("机器模式", MachineOptions.PC88_BOOT_MODES[mode], MachineOptions.PC88_BOOT_MODES.toList()) { mode = it }
                        else if (models != null) SettingChoice("机器模式", model, models.names.toList()) { model = models.values[it] }
                        SettingChoice("游戏音频", if (muted) "静音" else "正常输出", listOf("正常输出", "静音")) { muted = it == 1 }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(shape = RoundedCornerShape(12.dp), onClick = { focus.clearFocus(); activity.saveProfile(game, name, mode, model, muted, false) }, enabled = valid, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("保存配置") }
                    Button(shape = RoundedCornerShape(12.dp), onClick = { focus.clearFocus(); activity.saveProfile(game, name, mode, model, muted, true) }, enabled = valid, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        LibraryIcon(LibrarySymbol.Play, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("启动")
                    }
                }
                Section("启动与机器") {
                    SettingRow(LibrarySymbol.Hard, if (platform == 98) "启动环境与盘位" else "默认盘位", mediaSummary(game)) { openLaunch(game, true) }
                    HorizontalDivider(color = LibraryLine)
                    SettingRow(LibrarySymbol.Chip, "完整机器设置", "CPU、内存、音源、混音器与画面") { openSettings(game) }
                    if (platform == 98) {
                        HorizontalDivider(color = LibraryLine)
                        SettingRow(LibrarySymbol.Book, "字库设置 · FREECG98.BMP") { activity.gameAction(game, "font") }
                    }
                }
                Section("关联镜像") {
                    game.media.forEachIndexed { index, disk ->
                        if (index > 0) HorizontalDivider(color = LibraryLine)
                        Row(Modifier.fillMaxWidth().padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            LibraryIcon(if (GameLibrary.hardDisk(disk)) LibrarySymbol.Hard else LibrarySymbol.Floppy, tint = LibraryMuted)
                            Column(Modifier.weight(1f).padding(vertical = 11.dp)) {
                                Text(disk.name, style = MaterialTheme.typography.bodyMedium)
                                if (disk.titles.size > 1) Text("含 ${disk.titles.size} 张内嵌盘", style = MaterialTheme.typography.bodySmall, color = LibraryMuted)
                            }
                            ActionIcon(LibrarySymbol.More, "管理磁盘 ${disk.name}", { activity.diskActions(game, disk) })
                        }
                    }
                }
                Section("游戏资源与维护") {
                    SettingRow(LibrarySymbol.Folder, "管理磁盘文件", "导出磁盘副本或添加存档盘") { activity.gameAction(game, "files") }
                    HorizontalDivider(color = LibraryLine)
                    SettingRow(LibrarySymbol.Book, "修复磁盘名称编码", "GBK → Shift_JIS") { activity.gameAction(game, "encoding") }
                    HorizontalDivider(color = LibraryLine)
                    SettingRow(LibrarySymbol.Folder, "导入本游戏固件 / 字库") { activity.chooseGameResources(game) }
                }
                OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { activity.gameAction(game, "remove") }, enabled = !activity.isWorking(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("移除游戏", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    @Composable private fun ImportSheet(platform: Int) {
        val focus = LocalFocusManager.current
        Sheet("添加游戏", { if (!activity.isWorking()) { importOpen = false; activity.cancelImageImport() } }) {
            Text("导入至 PC-$platform，多盘游戏可一次选择多张镜像。", color = LibraryMuted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { activity.chooseImages() }, enabled = !activity.isWorking(), modifier = Modifier.heightIn(min = 48.dp)) {
                LibraryIcon(LibrarySymbol.Folder); Spacer(Modifier.width(7.dp)); Text("选择镜像")
            }
            Text(if (imageNames.isEmpty()) "未选择文件" else imageNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = LibraryMuted)
            OutlinedTextField(importName, { importName = it }, label = { Text("游戏名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !activity.isWorking())
            if (activity.isWorking()) Working()
            Button(shape = RoundedCornerShape(12.dp), onClick = { focus.clearFocus(); activity.importImages(importName) }, enabled = imageNames.isNotEmpty() && importName.isNotBlank() && importName.length <= 80 && !activity.isWorking(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("添加到游戏库") }
            HorizontalDivider(color = LibraryLine)
            val choices = if (platform == 98) listOf("磁盘游戏文件夹", "磁盘游戏 ZIP", "DOS 游戏目录", "DOS 游戏 ZIP") else listOf("游戏文件夹", "游戏 ZIP")
            choices.forEachIndexed { index, title ->
                SettingRow(LibrarySymbol.Folder, title) { importOpen = false; activity.chooseImport(index) }
            }
        }
    }
    @Composable private fun Sheet(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !activity.isWorking() }), dragHandle = null, sheetMaxWidth = 600.dp,
            containerColor = Color(0xff252d27), scrimColor = Color(0xcc090d0b)) {
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .88f).dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 19.dp).padding(top = 14.dp, bottom = 20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionIcon(LibrarySymbol.Close, "关闭", dismiss)
                }
                content()
            }
        }
    }
    private fun mediaSummary(game: GameLibrary.Game): String {
        val media = LaunchMedia.defaults(game)
        return media.hardDisk?.let { "硬盘 · ${label(game, it)}" } ?: "A · ${label(game, media.floppyA)}    B · ${label(game, media.floppyB)}"
    }
    private fun label(game: GameLibrary.Game, ref: LaunchMedia.Ref?): String {
        if (ref == null) return "空"
        return game.disk(ref.file)?.label(ref.index) ?: "磁盘已移除"
    }
    private fun same(first: LaunchMedia.Ref?, second: LaunchMedia.Ref?): Boolean =
        first != null && second != null && first.file == second.file && first.index == second.index

    @Composable private fun LaunchSheet(game: GameLibrary.Game, platform: Int) {
        val systems = remember(platform) { BootEnvironment.SYSTEMS.filter { BootEnvironment.hasSystem(activity, it) } }
        val memoryDrivers = remember(platform) { platform == 98 && BootEnvironment.hasMemoryDrivers(activity) }
        var media by remember(game.id, defaultsOnly, game.startupInstance, game.startupSystem) { mutableStateOf(LaunchMedia.defaults(game)) }
        var rememberMedia by remember(game.id) { mutableStateOf(true) }
        var system by remember(game.id, game.startupInstance, game.startupSystem) { mutableStateOf(BootEnvironment.selected(game)) }
        var memory by remember(game.id, game.startupMemory) { mutableStateOf(if (memoryDrivers) game.startupMemory else "none") }
        var source by remember(game.id, game.startupSource) { mutableStateOf(game.startupSource) }
        var command by remember(game.id, game.startupEntry) { mutableStateOf(game.startupEntry) }
        var showBootSettings by remember(game.id) { mutableStateOf(!game.startupReady) }
        val floppies = remember(game.id, game.startupInstance, game.media.size) { GameLibrary.media(game).filter { !GameLibrary.hardDisk(it.disk) }.map { LaunchMedia.Ref(it.disk.file.name, it.index) to it.label() } }
        val disks = remember(game.id, game.media.size) { game.media.filter { GameLibrary.hardDisk(it) }.map { LaunchMedia.Ref(it.file.name, 0) to it.name } }
        Sheet(if (defaultsOnly && platform == 98) "启动环境与盘位" else if (defaultsOnly) "默认盘位" else "启动盘位",
            { if (!launching && !activity.isWorking()) launchGame = null }) {
                Text(game.name, style = MaterialTheme.typography.titleMedium)
                if (platform == 98 && showBootSettings) {
                    SettingChoice("启动环境", BootEnvironment.label(system), systems.map(BootEnvironment::label), !launching && !activity.isWorking()) { index ->
                        system = systems[index]
                        if (system != "auto" && system != "dos62") memory = "none"
                    }
                    if (system != "existing") {
                        if (memoryDrivers && (system == "auto" || system == "dos62")) {
                            SettingChoice("DOS 内存驱动", BootEnvironment.MEMORY_LABELS[BootEnvironment.MEMORIES.indexOf(memory).coerceAtLeast(0)], BootEnvironment.MEMORY_LABELS.toList(), !activity.isWorking()) { memory = BootEnvironment.MEMORIES[it] }
                            Text("XMS / EMS 使用 DOS 6.2 驱动，扩展内存沿用系统设置。", style = MaterialTheme.typography.bodySmall)
                        }
                        val sources = game.media.filter { !game.generatedMedia.containsKey(it.file.name) }
                        SettingChoice("游戏数据盘", game.disk(source)?.name ?: "自动选择", listOf("自动选择") + sources.map { it.name }, !activity.isWorking()) { index -> source = if (index == 0) "" else sources[index - 1].file.name; command = "" }
                        if (source.isNotEmpty()) OutlinedTextField(command, onValueChange = { command = it }, label = { Text("启动文件 · 留空自动选择，可填 DRESS/DRESS.BAT") }, enabled = !activity.isWorking(), modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { activity.prepareStartup(game, system, memory, source, command, defaultsOnly) }, enabled = system in systems && !launching && !activity.isWorking(), modifier = Modifier.fillMaxWidth()) { Text("准备启动环境") }
                    if (game.startupHint.isNotEmpty()) Text(game.startupHint, style = MaterialTheme.typography.bodySmall)
                }
                MediaPicker("软驱 A", label(game, media.floppyA), floppies, !launching && !activity.isWorking()) { selected ->
                    if (same(selected, media.floppyB)) launchError = "这张盘已经放在软驱 B"
                    else {
                        val bootDevice = if (selected == null && media.hardDisk != null) "hdd" else media.bootDevice
                        media = LaunchMedia(selected, media.floppyB, media.hardDisk, bootDevice)
                        launchError = ""
                    }
                }
                MediaPicker("软驱 B", label(game, media.floppyB), floppies, !launching && !activity.isWorking()) { selected ->
                    if (same(selected, media.floppyA)) launchError = "这张盘已经放在软驱 A"
                    else { media = LaunchMedia(media.floppyA, selected, media.hardDisk, media.bootDevice); launchError = "" }
                }
                if (platform == 98) {
                    MediaPicker("硬盘槽", label(game, media.hardDisk), disks, !launching && !activity.isWorking()) { selected ->
                        val bootDevice = when {
                            selected == null -> "floppy"
                            media.floppyA == null -> "hdd"
                            else -> media.bootDevice
                        }
                        media = LaunchMedia(media.floppyA, media.floppyB, selected, bootDevice)
                        launchError = ""
                    }
                    if (media.hardDisk != null && media.floppyA != null) {
                        SettingChoice("引导设备", if (media.bootDevice == "hdd") "硬盘" else "软驱 A", listOf("硬盘", "软驱 A"), !launching && !activity.isWorking()) { index ->
                            media = LaunchMedia(media.floppyA, media.floppyB, media.hardDisk, if (index == 0) "hdd" else "floppy")
                        }
                    }
                    Text("A、B 为模拟软驱槽，DOS 盘符由游戏系统分配。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showBootSettings = !showBootSettings }, enabled = !launching && !activity.isWorking()) {
                        Text(if (showBootSettings) "收起启动环境设置" else "启动环境设置")
                    }
                }
                if (!defaultsOnly) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberMedia, onCheckedChange = { rememberMedia = it }, enabled = !launching)
                    Text("记住这次装盘")
                }
                if (launchError.isNotEmpty()) Text(launchError, color = MaterialTheme.colorScheme.error)
                if (launching || activity.isWorking()) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { launchGame = null }, enabled = !launching && !activity.isWorking(), modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(shape = RoundedCornerShape(12.dp), onClick = {
                        try {
                            media.validate(game, platform)
                            launchError = ""
                            if (defaultsOnly) activity.saveDefaultMedia(game, media)
                            else { launching = true; activity.launch(game, media, rememberMedia) }
                        } catch (error: Exception) { launchError = error.message ?: "盘位有误" }
                    }, enabled = !launching && !activity.isWorking(), modifier = Modifier.weight(1f)) { Text(if (defaultsOnly) "保存" else "启动") }
                }
        }
    }
    @Composable private fun MediaPicker(title: String, selected: String, choices: List<Pair<LaunchMedia.Ref, String>>, enabled: Boolean, change: (LaunchMedia.Ref?) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().background(Color(0xff1e2520), RoundedCornerShape(11.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.width(60.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(when (title) { "软驱 A" -> "FDD A"; "软驱 B" -> "FDD B"; else -> "HDD" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = LibraryMuted)
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text(selected, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
                    DropdownMenuItem(text = { Text("空") }, onClick = { change(null); expanded = false })
                    choices.forEach { (ref, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { change(ref); expanded = false }) }
                }
            }
        }
    }
    @Composable private fun DosWizard(draft: DosLibrary.Draft) {
        var templateId by remember(draft.game.id) { mutableStateOf(activity.preferredDosTemplate()) }
        val selected = dosTemplates.firstOrNull { it.file.name == templateId } ?: dosTemplates.firstOrNull()
        var entry by remember(draft.game.id) { mutableStateOf(draft.executables.first()) }
        var directory by remember(draft.game.id) { mutableStateOf(entry.substringBeforeLast('/', "")) }
        var drive by remember(draft.game.id, selected?.file?.name) { mutableStateOf(selected?.defaultDrive ?: "A:") }
        var initialization by remember(selected?.file?.name) { mutableStateOf(selected?.autoexec ?: "@ECHO OFF\r\n") }
        var advanced by remember(selected?.file?.name) { mutableStateOf(selected?.builtin != true) }
        Sheet("建立 DOS 游戏硬盘", {
            if (!activity.isWorking()) { activity.discardDos(draft); dosDraft = null }
        }) {
                Text(draft.game.name, style = MaterialTheme.typography.titleMedium)
                Text("${draft.entries.size} 个文件项目 · ${draft.bytes / 1048576} MB", style = MaterialTheme.typography.bodySmall)
                if (dosTemplates.isNotEmpty()) SettingChoice("启动环境", selected?.name ?: "选择模板", dosTemplates.map { it.name + " · " + it.info }) { templateId = dosTemplates[it].file.name }
                if (selected?.builtin == true) Text("已准备内置 DOS，将为这款游戏建立独立硬盘。", style = MaterialTheme.typography.bodySmall)
                else OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { activity.chooseDosTemplate() }, enabled = !activity.isWorking()) { Text("导入 DOS 系统 HDI") }
                if (selected?.builtin != true) selected?.let { Text(it.info, style = MaterialTheme.typography.bodySmall) }
                SettingChoice("启动文件", entry, draft.executables) { index -> entry = draft.executables[index]; directory = entry.substringBeforeLast('/', "") }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级启动设置" else "高级启动设置") }
                if (advanced) {
                if (selected?.builtin == true) Text(selected.info, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(directory, onValueChange = { directory = it }, label = { Text("工作目录 · 空值为游戏根目录") }, modifier = Modifier.fillMaxWidth())
                SettingChoice("模板启动后的 DOS 盘符", drive, listOf("A:", "B:", "C:", "D:", "E:", "F:")) { drive = listOf("A:", "B:", "C:", "D:", "E:", "F:")[it] }
                OutlinedTextField(initialization, onValueChange = { initialization = it }, label = { Text("DOS 初始化命令") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                Text("保留模板的驱动初始化命令，按需删除旧游戏的自启动命令。游戏启动入口会在初始化后执行。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { activity.chooseDosTemplate() }, enabled = !activity.isWorking()) { Text("使用其他 DOS 系统 HDI") }
                }
                if (dosError.isNotEmpty()) Text(dosError, color = MaterialTheme.colorScheme.error)
                if (activity.isWorking()) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { activity.discardDos(draft); dosDraft = null }, enabled = !activity.isWorking(), modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(shape = RoundedCornerShape(12.dp), onClick = { selected?.let { activity.finishDos(draft, it, entry, directory, drive, initialization) } }, enabled = selected != null && !activity.isWorking(), modifier = Modifier.weight(1f)) { Text("建立硬盘") }
                }
        }
    }

    @Composable private fun Settings(game: GameLibrary.Game, platform: Int) {
        val draft = remember(game.id) { mutableStateMapOf<String, String>().apply { putAll(game.machineOptions) } }
        var mode by remember(game.id) { mutableIntStateOf(game.mode) }
        var fontSelection by remember(game.id) { mutableStateOf(game.fontSelection) }
        val expanded = remember(game.id) { mutableStateMapOf<Int, Boolean>() }
        LaunchedEffect(importedFont) { if (importedFont.isNotEmpty()) { fontSelection = importedFont; importedFont = "" } }
        Scaffold(modifier = Modifier.imePadding(), containerColor = LibraryBackground, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            Header("PC-$platform 系统设置", { settingsGame = null }, action = {
                TextButton(onClick = { activity.saveSettings(game, mode, HashMap(draft), fontSelection) }, enabled = !activity.isWorking()) { Text("保存") }
            })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(game.name, style = MaterialTheme.typography.titleLarge)
                    Text("独立保存 · 下次启动生效", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (activity.isWorking()) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (platform == 88) item {
                    SettingChoice("BASIC 模式", MachineOptions.PC88_BOOT_MODES[mode], MachineOptions.PC88_BOOT_MODES.toList()) { mode = it }
                }
                for ((group, title) in listOf(0 to "机器与 CPU", 4 to "兼容与 DIP-SW", 1 to "模拟音源", 2 to "混音器", 3 to "高级音源", 5 to "画面与布局", 6 to "手机输出与速度")) {
                    val options = MachineOptions.all(platform).filter { it.group == group }
                    if (options.isEmpty()) continue
                    val advanced = group == 3 || group == 4
                    item {
                        if (advanced) TextButton(onClick = { expanded[group] = !(expanded[group] ?: false) }) { Text(title + if (expanded[group] == true) "  −" else "  ＋") }
                        else Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (advanced && expanded[group] != true) continue
                    items(options, key = { it.key }) { option ->
                        val value = option.value(draft, mode)
                        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, LibraryLine), color = LibrarySurface) {
                            Column(Modifier.padding(16.dp)) {
                                if (option.key == "m88_dipsw") {
                                    Dips(draft)
                                } else if (option.range()) {
                                    Text("${option.label} · ${option.display(value)}")
                                    Slider(value.toFloat(), { draft[option.key] = it.roundToInt().toString() }, valueRange = option.min.toFloat()..option.max.toFloat())
                                } else SettingChoice(option.label, option.display(value), option.names.toList()) { index -> draft[option.key] = option.values[index] }
                            }
                        }
                    }
                    if (group == 6) item {
                        OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { HostAudio.settings(activity) }, modifier = Modifier.fillMaxWidth()) { Text("采样率与缓冲 · 所有游戏") }
                    }
                    if (group == 0 && platform == 98) item {
                        val base = draft["np2kai_clk_base"] ?: "2.4576 MHz"
                        val multiplier = draft["np2kai_clk_mult"] ?: "4"
                        val actual = base.substringBefore(" ").toDoubleOrNull()?.times(multiplier.toDoubleOrNull() ?: 4.0)
                        Text("CPU 实际频率 · %.4f MHz".format(actual ?: 9.8304), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text("字库与固件", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (platform == 98) {
                        val ids = listOf("") + fontCandidates.map { it.id }
                        val names = listOf("自动按来源优先级选择") + fontCandidates.map { "${it.name} · ${it.source}" + if (it.error != null) " · ${it.error}" else "" }
                        SettingChoice("本游戏字库", names.getOrElse(ids.indexOf(fontSelection)) { "所选字库已移除" }, names) { fontSelection = ids[it] }
                        OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { activity.chooseGameFont(game) }, modifier = Modifier.fillMaxWidth()) { Text("导入并手选字库") }
                    }
                    OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { activity.chooseGameResources(game) }, modifier = Modifier.fillMaxWidth()) { Text("导入本游戏固件 / PC-88 字库") }
                    if (game.lastMachineInfo.isNotEmpty()) {
                        Text("上次启动实际配置", style = MaterialTheme.typography.labelLarge)
                        Text(game.lastMachineInfo, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text("退出与切到后台时自动保存会话，再次启动可选择继续游戏或重新启动。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    @Composable private fun Dips(draft: MutableMap<String, String>) {
        val value = draft["m88_dipsw"]?.toIntOrNull() ?: 1829
        val labels = listOf("启动模式" to listOf("终端", "BASIC"), "每行字符" to listOf("80 字", "40 字"),
            "画面行数" to listOf("25 行", "20 行"), "S 参数" to listOf("启用", "禁用"),
            "DEL 接收" to listOf("处理", "忽略"), "校验方式" to listOf("有校验", "无校验"),
            "奇偶指定" to listOf("偶数", "奇数"), "数据位" to listOf("8 位", "7 位"),
            "停止位" to listOf("2 位", "1 位"), "X 参数" to listOf("启用", "禁用"),
            "通信方式" to listOf("半双工", "全双工"), "软盘引导" to listOf("自动", "关闭"))
        Text("DIP-SW · 0x%03X".format(value), style = MaterialTheme.typography.titleSmall)
        labels.forEachIndexed { bit, (name, choices) ->
            val index = (value shr bit) and 1
            SettingChoice(name, choices[index], choices) { selected ->
                draft["m88_dipsw"] = ((value and (1 shl bit).inv()) or (selected shl bit)).toString()
            }
        }
        TextButton(onClick = { draft["m88_dipsw"] = "1829" }) { Text("恢复 0.25 默认 DIP") }
    }
    @Composable private fun SettingChoice(title: String, selected: String, choices: List<String>, enabled: Boolean = true, change: (Int) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(shape = RoundedCornerShape(12.dp), onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(selected) }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
                    choices.forEachIndexed { index, value -> DropdownMenuItem(text = { Text(value) }, onClick = { change(index); expanded = false }) }
                }
            }
        }
    }
}
