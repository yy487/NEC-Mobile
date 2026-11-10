package org.necmobile

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
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

    fun changed() { revision++ }
    fun observe() { revision }
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
    fun launchFailed(message: String?) { launching = false; launchError = message ?: "启动未完成" }
    fun playerReturned() { launching = false; changed() }
    fun openDos(draft: DosLibrary.Draft, templates: List<DosLibrary.Template>) { dosDraft = draft; dosTemplates = templates; dosError = "" }
    fun dosTemplatesLoaded(templates: List<DosLibrary.Template>) { dosTemplates = templates }
    fun dosFinished() { dosDraft = null; dosError = "" }
    fun dosFailed(error: String?) { dosError = error ?: "DOS 目录导入未完成" }
    fun createView(): View = ComposeView(activity).apply {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff76e0c0), surface = Color(0xff1b232f), background = Color(0xff10151f))) {
                Surface(Modifier.fillMaxSize()) { this@LibraryUi.Content() }
            }
        }
    }
    @Composable private fun Content() {
        val platform = activity.currentPlatform()
        BackHandler(enabled = platform != 0 || settingsGame != null || launchGame != null || dosDraft != null) {
            when {
                launching || activity.isWorking() -> Unit
                dosDraft != null -> { activity.discardDos(dosDraft!!); dosDraft = null }
                launchGame != null -> launchGame = null
                settingsGame != null -> settingsGame = null
                else -> activity.selectPlatform(0)
            }
        }
        val settings = settingsGame
        if (settings != null) Settings(settings, platform) else if (platform == 0) Platforms() else Games(platform)
        launchGame?.let { LaunchSheet(it, platform) }
        dosDraft?.let { DosWizard(it) }
    }
    @Composable private fun Platforms() {
        Scaffold(topBar = { TopAppBar(title = { Text("NEC Mobile") }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("选择你的游戏平台", style = MaterialTheme.typography.headlineSmall)
                Text("${BuildConfig.VERSION_NAME} · Android PC-88 / PC-98", color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (platform in listOf(88, 98)) {
                    Card(onClick = { activity.selectPlatform(platform) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("PC-$platform", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                            Text(if (platform == 88) "N88 / N80 · D88 多盘游戏" else "PC-9801 · 软盘与硬盘游戏")
                            Text("进入独立游戏库  →", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
    @Composable private fun Games(platform: Int) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("PC-$platform 游戏库") }, navigationIcon = {
                TextButton(onClick = { activity.selectPlatform(0) }, enabled = !activity.isWorking()) { Text("返回") }
            }, actions = { TextButton(onClick = { activity.settings() }, enabled = !activity.isWorking()) { Text("设置") } })
        }, floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { activity.addGame() }) { Text("＋ 添加游戏") }
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (activity.isWorking()) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (activity.progressText().isNotEmpty()) Text(activity.progressText(), color = MaterialTheme.colorScheme.primary)
                        Text("${activity.gameEntries().size} 款游戏", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (activity.gameEntries().isEmpty()) item {
                    Card(Modifier.fillMaxWidth()) { Text("选择单款游戏的文件夹或 ZIP，磁盘与配套资源会一起导入。", Modifier.padding(24.dp)) }
                }
                items(activity.gameEntries(), key = { it.id }) { game -> GameCard(game) }
            }
        }
    }
    @Composable private fun GameCard(game: GameLibrary.Game) {
        Card(onClick = { activity.start(game) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val image = activity.artwork(game.id)
                    if (image != null) Image(image.asImageBitmap(), game.name, Modifier.size(104.dp, 78.dp).background(Color.Black), contentScale = ContentScale.Fit)
                    else Box(Modifier.size(104.dp, 78.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        Text(game.name.take(2), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(game.name, style = MaterialTheme.typography.titleLarge)
                        Text(mediaSummary(game), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (game.played == 0L) "已准备好" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(game.played)), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { activity.start(game) }, enabled = !activity.isWorking(), modifier = Modifier.weight(1f)) { Text("启动") }
                    OutlinedButton(onClick = { activity.manage(game) }, enabled = !activity.isWorking(), modifier = Modifier.weight(1f)) { Text("游戏管理") }
                }
                if (activity.hasSession(game.id)) OutlinedButton(onClick = { activity.continueSession(game) }, enabled = !activity.isWorking(), modifier = Modifier.fillMaxWidth()) { Text("继续会话") }
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
        var media by remember(game.id, defaultsOnly) { mutableStateOf(LaunchMedia.defaults(game)) }
        var rememberMedia by remember(game.id) { mutableStateOf(false) }
        val floppies = remember(game.id) { GameLibrary.media(game).filter { !GameLibrary.hardDisk(it.disk) }.map { LaunchMedia.Ref(it.disk.file.name, it.index) to it.label() } }
        val disks = remember(game.id) { game.media.filter { GameLibrary.hardDisk(it) }.map { LaunchMedia.Ref(it.file.name, 0) to it.name } }
        ModalBottomSheet(onDismissRequest = { if (!launching && !activity.isWorking()) launchGame = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (defaultsOnly) "默认盘位" else "本次装盘", style = MaterialTheme.typography.headlineSmall)
                Text(game.name, style = MaterialTheme.typography.titleMedium)
                MediaPicker("软驱 A", label(game, media.floppyA), floppies, !launching) { selected ->
                    if (same(selected, media.floppyB)) launchError = "这张盘已经放在软驱 B"
                    else { media = LaunchMedia(selected, media.floppyB, if (selected == null) media.hardDisk else null); launchError = "" }
                }
                MediaPicker("软驱 B", label(game, media.floppyB), floppies, !launching) { selected ->
                    if (same(selected, media.floppyA)) launchError = "这张盘已经放在软驱 A"
                    else { media = LaunchMedia(media.floppyA, selected, if (selected == null) media.hardDisk else null); launchError = "" }
                }
                if (platform == 98) {
                    MediaPicker("硬盘槽", label(game, media.hardDisk), disks, !launching) { selected ->
                        media = if (selected == null) LaunchMedia(media.floppyA, media.floppyB, null) else LaunchMedia(null, null, selected)
                        launchError = ""
                    }
                    Text("A、B 为模拟软驱槽，DOS 盘符由游戏系统分配。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!defaultsOnly) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberMedia, onCheckedChange = { rememberMedia = it }, enabled = !launching)
                    Text("记住这次装盘")
                }
                if (launchError.isNotEmpty()) Text(launchError, color = MaterialTheme.colorScheme.error)
                if (launching || activity.isWorking()) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { launchGame = null }, enabled = !launching && !activity.isWorking(), modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = {
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
    }
    @Composable private fun MediaPicker(title: String, selected: String, choices: List<Pair<LaunchMedia.Ref, String>>, enabled: Boolean, change: (LaunchMedia.Ref?) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(selected) }
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
        var drive by remember(draft.game.id) { mutableStateOf("A:") }
        var initialization by remember(selected?.file?.name) { mutableStateOf(selected?.autoexec ?: "@ECHO OFF\r\n") }
        var advanced by remember(selected?.file?.name) { mutableStateOf(selected?.builtin != true) }
        ModalBottomSheet(onDismissRequest = {
            if (!activity.isWorking()) { activity.discardDos(draft); dosDraft = null }
        }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("建立 DOS 游戏硬盘", style = MaterialTheme.typography.headlineSmall)
                Text(draft.game.name, style = MaterialTheme.typography.titleMedium)
                Text("${draft.entries.size} 个文件项目 · ${draft.bytes / 1048576} MB", style = MaterialTheme.typography.bodySmall)
                if (dosTemplates.isNotEmpty()) SettingChoice("启动环境", selected?.name ?: "选择模板", dosTemplates.map { it.name + " · " + it.info }) { templateId = dosTemplates[it].file.name }
                if (selected?.builtin == true) Text("已准备内置 DOS，将为这款游戏建立独立硬盘。", style = MaterialTheme.typography.bodySmall)
                else OutlinedButton(onClick = { activity.chooseDosTemplate() }, enabled = !activity.isWorking()) { Text("导入 DOS 系统 HDI") }
                if (selected?.builtin != true) selected?.let { Text(it.info, style = MaterialTheme.typography.bodySmall) }
                SettingChoice("启动文件", entry, draft.executables) { index -> entry = draft.executables[index]; directory = entry.substringBeforeLast('/', "") }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级启动设置" else "高级启动设置") }
                if (advanced) {
                if (selected?.builtin == true) Text(selected.info, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(directory, onValueChange = { directory = it }, label = { Text("工作目录 · 空值为游戏根目录") }, modifier = Modifier.fillMaxWidth())
                SettingChoice("模板启动后的 DOS 盘符", drive, listOf("A:", "B:", "C:", "D:", "E:", "F:")) { drive = listOf("A:", "B:", "C:", "D:", "E:", "F:")[it] }
                OutlinedTextField(initialization, onValueChange = { initialization = it }, label = { Text("DOS 初始化命令") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                Text("保留模板的驱动初始化命令，按需删除旧游戏的自启动命令。游戏启动入口会在初始化后执行。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { activity.chooseDosTemplate() }, enabled = !activity.isWorking()) { Text("使用其他 DOS 系统 HDI") }
                }
                if (dosError.isNotEmpty()) Text(dosError, color = MaterialTheme.colorScheme.error)
                if (activity.isWorking()) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { activity.discardDos(draft); dosDraft = null }, enabled = !activity.isWorking(), modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { selected?.let { activity.finishDos(draft, it, entry, directory, drive, initialization) } }, enabled = selected != null && !activity.isWorking(), modifier = Modifier.weight(1f)) { Text("建立硬盘") }
                }
            }
        }
    }

    @Composable private fun Settings(game: GameLibrary.Game, platform: Int) {
        val draft = remember(game.id) { mutableStateMapOf<String, String>().apply { putAll(game.machineOptions) } }
        var mode by remember(game.id) { mutableIntStateOf(game.mode) }
        var fontSelection by remember(game.id) { mutableStateOf(game.fontSelection) }
        var resumeEnabled by remember(game.id) { mutableStateOf(game.resumeEnabled) }
        val expanded = remember(game.id) { mutableStateMapOf<Int, Boolean>() }
        LaunchedEffect(importedFont) { if (importedFont.isNotEmpty()) { fontSelection = importedFont; importedFont = "" } }
        Scaffold(topBar = { TopAppBar(title = { Text("PC-$platform 系统设置") }, navigationIcon = {
            TextButton(onClick = { settingsGame = null }, enabled = !activity.isWorking()) { Text("返回") }
        }, actions = { TextButton(onClick = { activity.saveSettings(game, mode, HashMap(draft), fontSelection, resumeEnabled) }, enabled = !activity.isWorking()) { Text("保存") } }) }) { padding ->
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
                        Card(Modifier.fillMaxWidth()) {
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
                        OutlinedButton(onClick = { HostAudio.settings(activity) }, modifier = Modifier.fillMaxWidth()) { Text("采样率与缓冲 · 所有游戏") }
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
                        OutlinedButton(onClick = { activity.chooseGameFont(game) }, modifier = Modifier.fillMaxWidth()) { Text("导入并手选字库") }
                    }
                    OutlinedButton(onClick = { activity.chooseGameResources(game) }, modifier = Modifier.fillMaxWidth()) { Text("导入本游戏固件 / PC-88 字库") }
                    if (game.lastMachineInfo.isNotEmpty()) {
                        Text("上次启动实际配置", style = MaterialTheme.typography.labelLarge)
                        Text(game.lastMachineInfo, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(resumeEnabled, onCheckedChange = { resumeEnabled = it })
                        Text("  自动保存上次会话")
                    }
                    Text("启用后从游戏卡片的“继续会话”读取独立恢复槽。", style = MaterialTheme.typography.bodySmall)
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
    @Composable private fun SettingChoice(title: String, selected: String, choices: List<String>, change: (Int) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected) }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 340.dp)) {
                    choices.forEachIndexed { index, value -> DropdownMenuItem(text = { Text(value) }, onClick = { change(index); expanded = false }) }
                }
            }
        }
    }
}
