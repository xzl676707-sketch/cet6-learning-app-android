@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cet6.study

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.cet6.study.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AppBg = Color(0xFFF1F7EC)
private val CardBg = Color(0xFFFCFDFB)
private val Green = Color(0xFF52784D)
private val DarkGreen = Color(0xFF34513A)
private val PaleGreen = Color(0xFFDCEBD2)
private val PaleBlue = Color(0xFFE7F1E4)
private val PaleYellow = Color(0xFFFFF4C9)
private val Ink = Color(0xFF24352E)
private val Muted = Color(0xFF78877F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Cet6StudyTheme { Cet6StudyApp() } }
    }
}

@Composable
private fun Cet6StudyTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Green, onPrimary = Color.White, secondary = Color(0xFF8C9F63),
        background = AppBg, surface = CardBg, onSurface = Ink, onBackground = Ink,
        primaryContainer = PaleGreen, onPrimaryContainer = DarkGreen
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}

private enum class Page(val title: String) { HOME("首页"), WORDS("单词"), READING("阅读"), TRANSLATION("翻译"), WRITING("作文"), NOTE_REVIEW("笔记复习") }
private enum class WordListMode { NEW, REVIEW, ALL }
private data class NoteTarget(val type: String, val id: Int, val title: String, val sourceText: String = "")

@Composable
private fun Cet6StudyApp() {
    val context = LocalContext.current
    val repo = remember { ContentDb.get(context) }
    val userDb = remember { UserDatabase.get(context) }
    val dao = userDb.dao()
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.HOME) }
    var selectedWordId by remember { mutableStateOf<Int?>(null) }
    var wordQueue by remember { mutableStateOf(emptyList<Int>()) }
    var ratedWordIds by remember { mutableStateOf(emptySet<Int>()) }
    var wordListMode by remember { mutableStateOf(WordListMode.NEW) }
    var noteTarget by remember { mutableStateOf<NoteTarget?>(null) }
    var studied by remember { mutableIntStateOf(0) }
    var due by remember { mutableIntStateOf(0) }
    var dueNotes by remember { mutableIntStateOf(0) }
    val readyWords = remember { repo.readyWordCount() }
    val totalWords = remember { repo.wordCount() }

    suspend fun refreshCounts() {
        val counts = withContext(Dispatchers.IO) { dao.studiedCount() to dao.dueCount(System.currentTimeMillis()) }
        studied = counts.first
        due = counts.second
        dueNotes = withContext(Dispatchers.IO) { dao.dueNoteCount(System.currentTimeMillis()) }
    }
    LaunchedEffect(Unit) { refreshCounts() }

    Scaffold(
        containerColor = AppBg,
        bottomBar = {
            if (selectedWordId == null && noteTarget == null && page != Page.NOTE_REVIEW) NavigationBar(containerColor = Color.White, tonalElevation = 3.dp) {
                val entries = listOf(
                    Triple(Page.HOME, Icons.Outlined.Home, "首页"),
                    Triple(Page.WORDS, Icons.Outlined.BookmarkBorder, "单词"),
                    Triple(Page.READING, Icons.Outlined.MenuBook, "阅读"),
                    Triple(Page.TRANSLATION, Icons.Outlined.Translate, "翻译"),
                    Triple(Page.WRITING, Icons.Outlined.EditNote, "作文")
                )
                entries.forEach { (target, icon, label) ->
                    NavigationBarItem(selected = page == target, onClick = { page = target }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Green, selectedTextColor = Green, indicatorColor = PaleGreen))
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                noteTarget != null -> NotesScreen(noteTarget!!, dao, onBack = { noteTarget = null; scope.launch { refreshCounts() } })
                page == Page.NOTE_REVIEW -> NoteReviewScreen(dao, onBack = { page = Page.HOME; scope.launch { refreshCounts() } })
                selectedWordId != null -> WordDetailScreen(
                    wordId = selectedWordId!!,
                    progress = (wordQueue.indexOf(selectedWordId!!) + 1).coerceAtLeast(1) to wordQueue.size,
                    repo = repo,
                    dao = dao,
                    onBack = { selectedWordId = null; scope.launch { refreshCounts() } },
                    onRated = {
                        val currentId = selectedWordId
                        if (currentId != null) {
                            val completedIds = ratedWordIds + currentId
                            ratedWordIds = completedIds
                            val nextId = wordQueue.dropWhile { it != currentId }
                                .drop(1)
                                .firstOrNull { it !in completedIds }
                            selectedWordId = nextId
                        }
                        scope.launch { refreshCounts() }
                    }
                )
                else -> when(page) {
                    Page.HOME -> HomeScreen(readyWords, totalWords, studied, due, dueNotes,
                        onNewWords = { wordListMode = WordListMode.NEW; page = Page.WORDS },
                        onReview = { wordListMode = WordListMode.REVIEW; page = Page.WORDS },
                        onWords = { wordListMode = WordListMode.ALL; page = Page.WORDS },
                        onReading = { page = Page.READING }, onTranslation = { page = Page.TRANSLATION }, onWriting = { page = Page.WRITING },
                        onNoteReview = { page = Page.NOTE_REVIEW })
                    Page.WORDS -> WordListScreen(repo, dao, wordListMode, onMode = { wordListMode = it }, onWord = { id, queue ->
                        wordQueue = queue
                        ratedWordIds = emptySet()
                        selectedWordId = id
                    })
                    Page.READING -> ReadingScreen(repo, dao, onNote = { noteTarget = it })
                    Page.TRANSLATION -> TranslationScreen(repo, onNote = { noteTarget = it })
                    Page.WRITING -> WritingScreen(repo, onNote = { noteTarget = it })
                    Page.NOTE_REVIEW -> Unit
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(readyWords: Int,totalWords: Int,studied: Int,due: Int,dueNotes: Int,onNewWords:()->Unit,onReview:()->Unit,onWords:()->Unit,onReading:()->Unit,onTranslation:()->Unit,onWriting:()->Unit,onNoteReview:()->Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(top=22.dp,bottom=24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("CET-6", color=DarkGreen,fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("今天也向前一点", color=Ink,fontSize=25.sp,fontWeight=FontWeight.Bold)
                    Text("把六级真题，变成每天的小进步", color=Muted,fontSize=14.sp)
                }
                Box(Modifier.size(48.dp).clip(CircleShape).background(PaleGreen),contentAlignment=Alignment.Center){Text("✦",color=Green,fontSize=23.sp)}
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick=onWords),colors=CardDefaults.cardColors(containerColor=PaleGreen),shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Column { Text("今日学习",color=Muted,fontSize=13.sp); Text("词汇学习",color=DarkGreen,fontSize=21.sp,fontWeight=FontWeight.Bold) }
                        Icon(Icons.Outlined.CheckCircle,null,tint=Green,modifier=Modifier.size(27.dp))
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        StatPill("待复习",due.toString()); StatPill("已学词",studied.toString()); StatPill("可背新词",readyWords.toString())
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        Button(onClick=onReview,enabled=due>0,colors=ButtonDefaults.buttonColors(containerColor=PaleBlue,contentColor=DarkGreen,disabledContainerColor=Color.White.copy(alpha=.55f),disabledContentColor=Muted),shape=RoundedCornerShape(14.dp)) { Text("今日复习 $due",fontWeight=FontWeight.SemiBold) }
                        Button(onClick=onNewWords,colors=ButtonDefaults.buttonColors(containerColor=Color.White.copy(alpha=.8f),contentColor=DarkGreen),shape=RoundedCornerShape(14.dp)) { Text("学习新词  →",fontWeight=FontWeight.SemiBold) }
                    }
                    Text("新词每天最多显示 10 个；例句未齐的词暂不进入背诵",color=Muted,fontSize=12.sp)
                }
            }
        }
        item { Text("学习模块",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Ink) }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                ModuleCard("阅读精读","逐句翻译与结构分析",Icons.Outlined.MenuBook,Modifier.weight(1f),onReading)
                ModuleCard("翻译训练","参考译文与优化译文",Icons.Outlined.Translate,Modifier.weight(1f),onTranslation)
            }
        }
        item { ModuleCard("作文积累","题目、范文与个人笔记",Icons.Outlined.EditNote,Modifier.fillMaxWidth(),onWriting) }
        item { ModuleCard("笔记复习","到期笔记 $dueNotes 条",Icons.Outlined.BookmarkBorder,Modifier.fillMaxWidth(),onNoteReview) }
        item {
            Card(colors=CardDefaults.cardColors(containerColor=PaleYellow),shape=RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("离线学习",fontWeight=FontWeight.SemiBold,color=Ink)
                    Spacer(Modifier.height(4.dp))
                    Text("内容和进度保存在本机。词库补齐后可以继续加入，不会清掉你的学习记录。",fontSize=13.sp,color=Muted)
                    Text("当前词库：$totalWords 个词 · $readyWords 个词已具备 3 条默认例句",fontSize=12.sp,color=DarkGreen,modifier=Modifier.padding(top=7.dp))
                }
            }
        }
    }
}

@Composable private fun StatPill(label:String,value:String) { Column(Modifier.clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha=.58f)).padding(horizontal=13.dp,vertical=9.dp)) { Text(value,color=DarkGreen,fontWeight=FontWeight.Bold,fontSize=18.sp); Text(label,color=Muted,fontSize=11.sp) } }

@Composable
private fun ModuleCard(title:String,subtitle:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier,onClick:()->Unit) {
    Card(modifier.clickable(onClick=onClick),colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(20.dp),elevation=CardDefaults.cardElevation(defaultElevation=1.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(PaleGreen),contentAlignment=Alignment.Center){Icon(icon,null,tint=Green)}
            Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold,color=Ink);Text(subtitle,fontSize=12.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)}
            Text("›",fontSize=24.sp,color=Muted)
        }
    }
}

@Composable
private fun WordListScreen(repo:ContentDb,dao:UserDao,mode:WordListMode,onMode:(WordListMode)->Unit,onWord:(Int,List<Int>)->Unit) {
    var search by remember { mutableStateOf("") }
    var dueIds by remember { mutableStateOf(emptyList<Int>()) }
    var newIds by remember { mutableStateOf(emptyList<Int>()) }
    var words by remember { mutableStateOf(emptyList<WordCard>()) }
    LaunchedEffect(search) { words=withContext(Dispatchers.IO){repo.getWords(search,2500)} }
    LaunchedEffect(mode) {
        withContext(Dispatchers.IO) {
            dueIds=dao.dueWordIds(System.currentTimeMillis())
            val seen=dao.studiedWordIds().toSet()
            newIds=repo.getNewWordIds(10,seen)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        ScreenHeading("单词", "按最终背诵顺序浏览，例句不足的词会标出")
        OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("查单词或中文释义")},leadingIcon={Icon(Icons.Outlined.Search,null)},shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Green, unfocusedBorderColor=Color(0xFFDDE5DE)))
        Row(Modifier.padding(top=5.dp,bottom=5.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=mode==WordListMode.NEW,onClick={onMode(WordListMode.NEW)},label={Text("新词 ${newIds.size}")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleGreen))
            FilterChip(selected=mode==WordListMode.REVIEW,onClick={onMode(WordListMode.REVIEW)},label={Text("复习 ${dueIds.size}")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleBlue))
            FilterChip(selected=mode==WordListMode.ALL,onClick={onMode(WordListMode.ALL)},label={Text("全部 2500 词")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleGreen))
        }
        val plannedIds=when(mode){WordListMode.NEW->newIds;WordListMode.REVIEW->dueIds;WordListMode.ALL->emptyList()}
        val wordsById=words.associateBy { it.wordId }
        val visibleWords=if(mode!=WordListMode.ALL) plannedIds.mapNotNull { wordsById[it] }.filter { it.exampleCount>=3 } else words
        LazyColumn(contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            if(visibleWords.isEmpty()) item { Text(if(mode!=WordListMode.ALL && search.isBlank()) if(mode==WordListMode.REVIEW) "当前没有到期复习的单词。" else "新词已学完。例句未齐的词会等补齐后再进入新词。" else "没有找到匹配的单词。",color=Muted,fontSize=14.sp,modifier=Modifier.padding(18.dp)) }
            items(visibleWords,key={it.wordId}) { word ->
                Card(Modifier.fillMaxWidth().clickable { onWord(word.wordId, if(mode==WordListMode.ALL) listOf(word.wordId) else visibleWords.map { it.wordId }) },colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(word.rank.toString().padStart(2,'0'),color=Green,fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.width(38.dp))
                        Column(Modifier.weight(1f)) { Text(word.lemma,fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=Ink);Text(word.meanings.replace(",","；"),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis) }
                        Column(horizontalAlignment=Alignment.End) { Text(word.level,fontSize=10.sp,color=Green);Text("${word.exampleCount}/3 例句",fontSize=10.sp,color=if(word.exampleCount>=3)Muted else Color(0xFFB17A35)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordDetailScreen(wordId:Int,progress:Pair<Int,Int>,repo:ContentDb,dao:UserDao,onBack:()->Unit,onRated:()->Unit) {
    var detail by remember { mutableStateOf<WordDetail?>(null) }
    var currentStatus by remember { mutableStateOf<UserWordStatus?>(null) }
    var saving by remember { mutableStateOf(false) }
    var revealed by remember(wordId) { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(wordId) { withContext(Dispatchers.IO) { detail=repo.getWord(wordId); currentStatus=dao.status(wordId) } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title={Text("单词详情")},navigationIcon={TextButton(onClick=onBack){Text("‹ 返回",color=Green)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=AppBg))
        val w=detail
        if(w==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=Green)} else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    Text("${if(progress.second>0) "${progress.first} / ${progress.second} · " else ""}${if(currentStatus==null) "新词学习" else "今日复习"}",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=4.dp))
                    Card(Modifier.fillMaxWidth().clickable(enabled=!revealed){revealed=true},colors=CardDefaults.cardColors(containerColor=PaleGreen),shape=RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("第 ${w.rank} 位 · ${w.level}",color=Muted,fontSize=12.sp)
                        Text(w.lemma,color=DarkGreen,fontSize=36.sp,fontWeight=FontWeight.Bold)
                        Text("真题出现 ${w.totalCount} 次 · 覆盖 ${w.paperCount} 套",color=Muted,fontSize=13.sp)
                        Text(if(revealed) "释义与例句" else "先想想它的意思 · 点卡片揭晓",color=Green,fontSize=13.sp,modifier=Modifier.padding(top=5.dp))
                    } }
                }
                if(revealed) items(w.senses,key={it.senseId}) { sense ->
                    Card(colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Text(sense.pos,color=Green,fontSize=12.sp,fontWeight=FontWeight.Bold);Text(sense.meaning,color=Ink,fontSize=17.sp,fontWeight=FontWeight.SemiBold)}
                            if(sense.examples.isEmpty()) {
                                Text("这个词的默认例句还在补充，暂不进入今日新词。",color=Color(0xFF9B6B2F),fontSize=12.sp,modifier=Modifier.clip(RoundedCornerShape(10.dp)).background(PaleYellow).padding(10.dp))
                            } else sense.examples.forEachIndexed { i,example ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppBg).padding(11.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text("例句 ${i+1} · ${example.source}",fontSize=10.sp,color=Muted)
                                    Text(example.english,color=Ink,fontSize=14.sp,lineHeight=20.sp)
                                    if(example.chinese.isNotBlank()) Text(example.chinese,color=Muted,fontSize=12.sp,lineHeight=17.sp)
                                }
                            }
                        }
                    }
                }
                if(revealed) item {
                    if(w.senses.sumOf{it.examples.size}<3) Text("默认例句目前 ${w.senses.sumOf{it.examples.size}}/3；补充后会自动出现在这里。",fontSize=12.sp,color=Muted)
                    Text("记忆情况",fontWeight=FontWeight.SemiBold,fontSize=18.sp,color=Ink)
                    Text("选择后自动进入下一个计划单词；复习时间由应用自动安排。",fontSize=12.sp,lineHeight=18.sp,color=Muted)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        ReviewButton("不会",Modifier.weight(1f),saving){ rate("不会",w.wordId,dao,scope,{saving=true},onRated) }
                        ReviewButton("有点了解",Modifier.weight(1f),saving){ rate("有点了解",w.wordId,dao,scope,{saving=true},onRated) }
                        ReviewButton("熟悉",Modifier.weight(1f),saving){ rate("熟悉",w.wordId,dao,scope,{saving=true},onRated) }
                    }
                    currentStatus?.let { Text("上次选择：${it.familiarity} · 已复习 ${it.reviewCount} 次",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp)) }
                }
            }
        }
    }
}

private fun rate(value:String,wordId:Int,dao:UserDao,scope:kotlinx.coroutines.CoroutineScope,onStart:()->Unit,onComplete:()->Unit) {
    onStart()
    scope.launch(Dispatchers.IO) {
        val old=dao.status(wordId)
        val now=System.currentTimeMillis()
        val stageBefore=old?.reviewStage ?: -1
        val schedule=scheduleNextReview(value,stageBefore,now)
        dao.recordReview(
            UserWordStatus(wordId,value,schedule.nextReviewMillis,now,(old?.reviewCount ?: 0)+1,schedule.stage),
            UserReviewHistory(wordId=wordId,reviewedAt=now,familiarity=value,stageBefore=stageBefore,stageAfter=schedule.stage,intervalDays=schedule.intervalDays)
        )
        withContext(Dispatchers.Main) { onComplete() }
    }
}

@Composable
private fun NoteReviewScreen(dao:UserDao,onBack:()->Unit) {
    var notes by remember { mutableStateOf(emptyList<UserNote>()) }
    var index by remember { mutableIntStateOf(0) }
    var revealed by remember(index) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit) { notes=withContext(Dispatchers.IO) { dao.dueNotes(System.currentTimeMillis()) } }
    Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
        TopAppBar(title={Text("笔记复习")},navigationIcon={TextButton(onClick=onBack){Text("‹ 返回",color=Green)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=AppBg))
        val note=notes.getOrNull(index)
        if(note==null) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) { Text("今天没有到期笔记",fontSize=20.sp,fontWeight=FontWeight.Bold,color=DarkGreen); Text("新记的笔记会进入后续复习安排。",color=Muted); Button(onClick=onBack,colors=ButtonDefaults.buttonColors(containerColor=Green)){Text("返回首页")} } }
        } else {
            Text("${index+1} / ${notes.size} 条待复习",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=14.dp,bottom=10.dp))
            Card(Modifier.fillMaxWidth().weight(1f).clickable(enabled=!revealed){revealed=true},colors=CardDefaults.cardColors(containerColor=PaleGreen),shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text(if(note.sourceText.isBlank()) "回忆这条笔记" else note.sourceText,fontSize=20.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold,color=DarkGreen)
                    Text(if(revealed) note.content else "先回忆笔记内容，点卡片查看",fontSize=15.sp,lineHeight=24.sp,color=Ink)
                }
            }
            if(revealed) {
                Text("这次记得怎么样？",fontSize=14.sp,color=Muted,modifier=Modifier.padding(top=16.dp,bottom=8.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    ReviewButton("不会",Modifier.weight(1f),saving){ saveNoteRating("不会",note,dao,scope,{saving=true}){ saving=false; index++ } }
                    ReviewButton("有点了解",Modifier.weight(1f),saving){ saveNoteRating("有点了解",note,dao,scope,{saving=true}){ saving=false; index++ } }
                    ReviewButton("熟悉",Modifier.weight(1f),saving){ saveNoteRating("熟悉",note,dao,scope,{saving=true}){ saving=false; index++ } }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun saveNoteRating(value:String,note:UserNote,dao:UserDao,scope:kotlinx.coroutines.CoroutineScope,onStart:()->Unit,onComplete:()->Unit) {
    onStart()
    scope.launch(Dispatchers.IO) {
        val now=System.currentTimeMillis()
        val schedule=scheduleNextReview(value,note.reviewStage,now)
        dao.saveNote(note.copy(familiarity=value,reviewStage=schedule.stage,nextReview=schedule.nextReviewMillis,lastReviewed=now,reviewCount=note.reviewCount+1,updatedAt=now))
        withContext(Dispatchers.Main) { onComplete() }
    }
}

@Composable private fun ReviewButton(label:String,modifier:Modifier,saving:Boolean,onClick:()->Unit) {
    OutlinedButton(onClick=onClick,modifier=modifier,enabled=!saving,shape=RoundedCornerShape(13.dp),contentPadding=PaddingValues(horizontal=4.dp,vertical=10.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Green)) { Text(label,fontSize=12.sp) }
}

@Composable
private fun ReadingScreen(repo:ContentDb,dao:UserDao,onNote:(NoteTarget)->Unit) {
    var papers by remember { mutableStateOf(emptyList<PaperCard>()) }
    var selectedId by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("") }
    var readingTypes by remember { mutableStateOf(emptyList<String>()) }
    var sentences by remember { mutableStateOf(emptyList<ReadingSentence>()) }
    var questions by remember { mutableStateOf(emptyList<ReadingQuestion>()) }
    var statuses by remember { mutableStateOf(emptyList<UserReadingStatus>()) }
    var selectedPassage by remember { mutableIntStateOf(1) }
    var sentenceIndex by remember { mutableIntStateOf(0) }
    var readerMode by remember { mutableStateOf("逐句精读") }
    var inReader by remember { mutableStateOf(false) }
    var revealed by remember(selectedId,selectedType,selectedPassage,sentenceIndex) { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit) { papers=withContext(Dispatchers.IO){repo.getPapers("reading")}; if(papers.isNotEmpty()) selectedId=papers.first().paperId }
    LaunchedEffect(selectedId) { if(selectedId.isNotBlank()) { readingTypes=withContext(Dispatchers.IO){repo.getReadingTypes(selectedId)}; selectedType=readingTypes.firstOrNull().orEmpty() } }
    LaunchedEffect(selectedId,selectedType) {
        if(selectedId.isNotBlank() && selectedType.isNotBlank()) {
            val loaded=withContext(Dispatchers.IO) { Triple(repo.getReadingSentences(selectedId,selectedType),repo.getQuestions(selectedId,selectedType),dao.readingStatuses(selectedId,selectedType)) }
            sentences=loaded.first
            questions=loaded.second
            statuses=loaded.third
            selectedPassage=loaded.first.firstOrNull()?.passageNo ?: 1
            sentenceIndex=0
        }
    }
    val passageNos=sentences.mapNotNull{it.passageNo}.distinct().sorted()
    val passageSentences=sentences.filter{it.passageNo==selectedPassage}
    val sentence=passageSentences.getOrNull(sentenceIndex)
    val statusByUnit=statuses.associateBy{it.unitId}
    val completedPassages=passageNos.count { no ->
        val rows=sentences.filter{it.passageNo==no}
        rows.isNotEmpty() && rows.all{statusByUnit[it.unitId]?.familiarity in setOf("完全解决","熟悉")}
    }
    if(!inReader) {
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
            ScreenHeading("阅读精读", "先选试卷和文章，再进入阅读")
            PaperSelector(papers,selectedId,{selectedId=it})
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) { readingTypes.forEach { type -> FilterChip(selected=selectedType==type,onClick={selectedType=type},label={Text(typeLabel(type),fontSize=12.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleGreen,selectedLabelColor=DarkGreen)) } }
            Text("本套已精读 $completedPassages / ${passageNos.size} 篇",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=12.dp,bottom=8.dp))
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(passageNos) { passageNo ->
                    val rows=sentences.filter{it.passageNo==passageNo}
                    val done=rows.count{statusByUnit[it.unitId]?.familiarity in setOf("完全解决","熟悉")}
                    Card(colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                Text("${typeLabel(selectedType)} · 第${passageNo}篇",fontWeight=FontWeight.Bold,fontSize=17.sp,color=DarkGreen)
                                Text("$done / ${rows.size} 句",fontSize=12.sp,color=Muted)
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick={selectedPassage=passageNo;sentenceIndex=0;readerMode="逐句精读";inReader=true},modifier=Modifier.weight(1f),colors=ButtonDefaults.buttonColors(containerColor=Green),shape=RoundedCornerShape(13.dp),contentPadding=PaddingValues(vertical=10.dp)) { Text("开始精读") }
                                OutlinedButton(onClick={selectedPassage=passageNo;sentenceIndex=0;readerMode="整篇通读";inReader=true},modifier=Modifier.weight(1f),shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Green),contentPadding=PaddingValues(vertical=10.dp)) { Text("整篇阅读") }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick={readerMode="题目答案";inReader=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Green)) { Text("查看本套阅读题目与答案") }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
            Row(Modifier.fillMaxWidth().padding(top=4.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={inReader=false},contentPadding=PaddingValues(horizontal=4.dp,vertical=6.dp)) { Text("‹ 返回文章",color=Green,fontSize=14.sp) }
                Spacer(Modifier.width(10.dp))
                Text(if(readerMode=="题目答案") "阅读题目" else "${typeLabel(selectedType)} · 第${selectedPassage}篇",fontSize=16.sp,fontWeight=FontWeight.SemiBold,color=DarkGreen,maxLines=1)
            }
            when(readerMode) {
            "逐句精读" -> if(sentence!=null) {
                val status=statusByUnit[sentence.unitId]
                Text("第 ${sentenceIndex+1} / ${passageSentences.size} 句 · ${status?.familiarity ?: "未完成"}",fontSize=13.sp,color=Muted,modifier=Modifier.padding(bottom=8.dp))
                Card(Modifier.fillMaxWidth().weight(1f).clickable(enabled=!revealed){revealed=true},colors=CardDefaults.cardColors(containerColor=if(revealed) CardBg else PaleGreen),shape=RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(sentence.paragraphNo!=null && selectedType=="matching") Text("第 ${sentence.paragraphNo} 段",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Green)
                        SelectableNoteText(androidx.compose.ui.text.AnnotatedString(sentence.text),"reading",sentence.unitId,"阅读笔记",onNote)
                        if(revealed) {
                            HorizontalDivider(color=PaleGreen)
                            Text("译文",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Green)
                            Text(sentence.translation.ifBlank{"暂无译文"},fontSize=15.sp,lineHeight=24.sp,color=Ink)
                            Text("怎么看",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Green)
                            Text(cleanMarkup(sentence.analysis).ifBlank{"暂无结构分析"},fontSize=14.sp,lineHeight=22.sp,color=Muted)
                        } else Text("先自己理解，点卡片查看译文和分析",fontSize=13.sp,color=Muted)
                    }
                }
                if(revealed) {
                    Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf("完全解决","熟悉","还不熟").forEach { rating ->
                            ReviewButton(rating,Modifier.weight(1f),false) {
                                scope.launch(Dispatchers.IO) {
                                    dao.saveReadingStatus(UserReadingStatus(sentence.unitId,selectedId,selectedType,sentence.passageNo ?: selectedPassage,rating))
                                    val updated=dao.readingStatuses(selectedId,selectedType)
                                    withContext(Dispatchers.Main) {
                                        statuses=updated
                                        if(sentenceIndex<passageSentences.lastIndex) sentenceIndex++
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={sentenceIndex=(sentenceIndex-1).coerceAtLeast(0)}){Text("上一句",color=Green)}
                    TextButton(onClick={sentenceIndex=(sentenceIndex+1).coerceAtMost((passageSentences.size-1).coerceAtLeast(0))}){Text("下一句",color=Green)}
                }
            } else Text("这篇暂无可显示的句子。",color=Muted,modifier=Modifier.padding(20.dp))
            "整篇通读" -> if(passageSentences.isNotEmpty()) {
                val paragraphs=passageSentences.groupIntoReadableParagraphs()
                Text("整篇阅读 · ${passageSentences.size} 句",fontSize=13.sp,color=Muted,modifier=Modifier.padding(bottom=8.dp))
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(paragraphs) { paragraph -> Card(colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        paragraph.paragraphNo?.let { Text("第${it}段",fontSize=11.sp,fontWeight=FontWeight.Bold,color=Green) }
                        SelectableNoteText(androidx.compose.ui.text.AnnotatedString(paragraph.sentences.joinToString(" "){it.text}),"reading",paragraph.sentences.first().unitId,"阅读篇章笔记",onNote)
                    }} }
                }
                Button(onClick={
                    scope.launch(Dispatchers.IO) {
                        passageSentences.forEach { dao.saveReadingStatus(UserReadingStatus(it.unitId,selectedId,selectedType,it.passageNo ?: selectedPassage,"熟悉")) }
                        val updated=dao.readingStatuses(selectedId,selectedType)
                        withContext(Dispatchers.Main) { statuses=updated }
                    }
                },modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Green),shape=RoundedCornerShape(16.dp)){Text("标记本篇已精读")}
            }
            else -> LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
              items(questions,key={it.unitId}) { q -> Card(colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(17.dp)) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text(q.questionNo?.let{"第 $it 题"} ?: "题目",color=Green,fontSize=11.sp,fontWeight=FontWeight.Bold)
                Text(q.text,color=Ink,fontSize=14.sp,lineHeight=20.sp)
                Text("答案：${q.answer.ifBlank{"待核对"}}${if(q.optionText.isNotBlank()) "  ${q.optionText}" else ""}",color=DarkGreen,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
                if(q.optionText.isNotBlank()) {
                    Text("对应选项",color=Green,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                    Text(q.optionText,color=Muted,fontSize=12.sp,lineHeight=18.sp)
                } else if(q.evidence.isNotBlank()) {
                    Text("对应段落原文",color=Green,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                    Text(q.evidence,color=Muted,fontSize=12.sp,lineHeight=18.sp)
                }
              } } }
            }
            }
        }
    }
}

private data class ReadingParagraph(val paragraphNo:Int?,val sentences:List<ReadingSentence>)

/** Use paragraph numbers from the source data where available. For records without them,
 * keep each source sentence as its own spaced block instead of flattening the entire essay. */
private fun List<ReadingSentence>.groupIntoReadableParagraphs():List<ReadingParagraph> {
    val groups=mutableListOf<ReadingParagraph>()
    for(sentence in this) {
        val number=sentence.paragraphNo
        val previous=groups.lastOrNull()
        if(number!=null && previous?.paragraphNo==number) {
            groups[groups.lastIndex]=previous.copy(sentences=previous.sentences+sentence)
        } else groups += ReadingParagraph(number,listOf(sentence))
    }
    return groups
}

@Composable
private fun SelectableNoteText(value:androidx.compose.ui.text.AnnotatedString,type:String,itemId:Int,title:String,onNote:(NoteTarget)->Unit) {
    var field by remember(value) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.text.BasicTextField(value=field,onValueChange={field=it},readOnly=true,textStyle=MaterialTheme.typography.bodyLarge.copy(color=Ink,lineHeight=25.sp),cursorBrush=androidx.compose.ui.graphics.SolidColor(Green),modifier=Modifier.fillMaxWidth())
        if(!field.selection.collapsed) {
            val selected=field.annotatedString.text.substring(field.selection.min,field.selection.max)
            TextButton(onClick={if(selected.isNotBlank()) onNote(NoteTarget(type,itemId,title,selected))}) { Text("为选中内容做笔记",color=Green) }
        }
    }
}

private fun typeLabel(type:String)=when(type){"close_reading"->"仔细阅读";"matching"->"长篇匹配";"word_bank"->"选词填空";else->type}
private fun cleanMarkup(text:String)=text.replace("`","")

private fun highlightedText(text:String,json:String):androidx.compose.ui.text.AnnotatedString {
    val ranges=runCatching {
        val array=org.json.JSONArray(json)
        buildList {
            for(i in 0 until array.length()) {
                val obj=array.optJSONObject(i) ?: continue
                val start=obj.optInt("start",-1); val end=obj.optInt("end",-1)
                if(start>=0 && end>start && end<=text.length) add(start to end)
            }
        }.sortedBy{it.first}
    }.getOrDefault(emptyList())
    return androidx.compose.ui.text.buildAnnotatedString {
        var cursor=0
        ranges.forEach { (start,end) ->
            if(start<cursor) return@forEach
            append(text.substring(cursor,start))
            pushStyle(androidx.compose.ui.text.SpanStyle(background=PaleYellow,fontWeight=FontWeight.SemiBold))
            append(text.substring(start,end))
            pop()
            cursor=end
        }
        if(cursor<text.length) append(text.substring(cursor))
    }
}

@Composable
private fun TranslationScreen(repo:ContentDb,onNote:(NoteTarget)->Unit) {
    var papers by remember { mutableStateOf(emptyList<PaperCard>()) };var selectedId by remember { mutableStateOf("") };var rows by remember { mutableStateOf(emptyList<TranslationItem>()) }
    var index by remember(selectedId) { mutableIntStateOf(0) }
    var revealed by remember(selectedId,index) { mutableStateOf(false) }
    LaunchedEffect(Unit){papers=withContext(Dispatchers.IO){repo.getPapers("translation")};if(papers.isNotEmpty())selectedId=papers.first().paperId}
    LaunchedEffect(selectedId){if(selectedId.isNotBlank()){rows=withContext(Dispatchers.IO){repo.getTranslations(selectedId)};index=0}}
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        ScreenHeading("翻译训练","先自己翻译，点卡片后查看参考译文和优化译文")
        PaperSelector(papers,selectedId,{selectedId=it})
        val item=rows.getOrNull(index)
        if(item!=null) {
            Text("第 ${index+1} / ${rows.size} 句",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=12.dp,bottom=8.dp))
            Card(Modifier.fillMaxWidth().weight(1f).clickable(enabled=!revealed){revealed=true},colors=CardDefaults.cardColors(containerColor=PaleBlue),shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("先在心里翻译",fontSize=12.sp,color=Green,fontWeight=FontWeight.Bold)
                    Text(item.chinese,fontSize=19.sp,lineHeight=31.sp,color=DarkGreen,fontWeight=FontWeight.Medium)
                    if(revealed) {
                        HorizontalDivider(color=Color.White.copy(alpha=.8f))
                        if(item.reference.isNotBlank()) { Text("参考译文",fontSize=12.sp,color=Green,fontWeight=FontWeight.Bold); SelectableNoteText(androidx.compose.ui.text.AnnotatedString(item.reference),"translation",item.unitId,"翻译笔记 · 第 ${item.orderNo} 句",onNote) }
                        if(item.optimized.isNotBlank()) { Text("优化译文",fontSize=12.sp,color=Green,fontWeight=FontWeight.Bold); SelectableNoteText(androidx.compose.ui.text.AnnotatedString(item.optimized),"translation",item.unitId,"翻译笔记 · 第 ${item.orderNo} 句",onNote) }
                        if(item.reference.isBlank()&&item.optimized.isBlank()) Text("参考译文正在补入",fontSize=13.sp,color=Muted)
                    } else Text("点卡片查看译文",fontSize=13.sp,color=Muted)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={index=(index-1).coerceAtLeast(0)}){Text("上一句",color=Green)}
                if(!revealed) TextButton(onClick={revealed=true}){Text("查看译文",color=Green)}
                TextButton(onClick={index=(index+1).coerceAtMost(rows.lastIndex)}){Text("下一句",color=Green)}
            }
        }
    }
}

@Composable
private fun WritingScreen(repo:ContentDb,onNote:(NoteTarget)->Unit) {
    var papers by remember { mutableStateOf(emptyList<PaperCard>()) };var selectedId by remember { mutableStateOf("") };var item by remember { mutableStateOf<WritingItem?>(null) }
    LaunchedEffect(Unit){papers=withContext(Dispatchers.IO){repo.getPapers("writing")};if(papers.isNotEmpty())selectedId=papers.first().paperId}
    LaunchedEffect(selectedId){if(selectedId.isNotBlank())item=withContext(Dispatchers.IO){repo.getWriting(selectedId)}}
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        ScreenHeading("作文积累","长按选择好词好句，加入可复习的个人笔记")
        PaperSelector(papers,selectedId,{selectedId=it})
        val current=item
        if(current!=null) LazyColumn(contentPadding=PaddingValues(top=10.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Card(colors=CardDefaults.cardColors(containerColor=PaleGreen),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("写作题目",color=Green,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text(current.prompt,color=DarkGreen,fontSize=16.sp,lineHeight=25.sp)}} }
            items(current.samples){sample->
                val label=if(sample.type=="excellent")"优秀范文" else "参考范文"
                Card(colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(22.dp)){Column(Modifier.padding(21.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
                    Text(label,color=Green,fontSize=13.sp,fontWeight=FontWeight.Bold)
                    SelectableNoteText(highlightedText(sample.text,sample.highlightsJson),"writing",sample.sampleId.toInt(),"作文笔记 · $label",onNote)
                }}
            }
            item { if(current.samples.isEmpty()) Text("这套范文正在补入。",color=Muted) }
        }
    }
}

@Composable
private fun NotesScreen(target:NoteTarget,dao:UserDao,onBack:()->Unit) {
    var text by remember { mutableStateOf("") };var loaded by remember { mutableStateOf(false) };var saved by remember { mutableStateOf(false) };var current by remember(target){mutableStateOf<UserNote?>(null)};val scope=rememberCoroutineScope()
    LaunchedEffect(target){
        current=withContext(Dispatchers.IO){dao.notes(target.type,target.id)}.firstOrNull{it.sourceText==target.sourceText}
        text=current?.content.orEmpty()
        loaded=true
    }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp, vertical=12.dp)) {
        TopAppBar(title={Text("学习笔记")},navigationIcon={TextButton(onClick=onBack){Text("‹ 返回",color=Green)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=AppBg))
        Text(target.title,fontSize=22.sp,fontWeight=FontWeight.Bold,color=Ink,modifier=Modifier.padding(top=18.dp,bottom=12.dp))
        if(target.sourceText.isNotBlank()) Card(colors=CardDefaults.cardColors(containerColor=PaleYellow),shape=RoundedCornerShape(18.dp)) { Text(target.sourceText,fontSize=15.sp,lineHeight=23.sp,color=Ink,modifier=Modifier.fillMaxWidth().padding(17.dp)) }
        OutlinedTextField(value=text,onValueChange={text=it;saved=false},modifier=Modifier.fillMaxWidth().weight(1f).padding(top=14.dp),placeholder={Text("写下释义、表达用法或自己的理解")},shape=RoundedCornerShape(20.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Green))
        Button(onClick={scope.launch {
            val now=System.currentTimeMillis()
            val old=current
            val draft=old?.copy(content=text,updatedAt=now) ?: UserNote(itemType=target.type,itemId=target.id,sourceText=target.sourceText,content=text,updatedAt=now,reviewStage=-1,nextReview=now+24L*60L*60L*1000L)
            val newId=withContext(Dispatchers.IO){dao.saveNote(draft)}
            current=draft.copy(noteId=newId)
            saved=true
        }},enabled=loaded&&text.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(top=14.dp,bottom=8.dp),shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.buttonColors(containerColor=Green)){Text(if(saved)"已保存并加入笔记复习" else "保存笔记")}
    }
}

@Composable private fun ScreenHeading(title:String,subtitle:String){Column(Modifier.padding(top=22.dp,bottom=16.dp)){Text(title,fontSize=24.sp,fontWeight=FontWeight.Bold,color=Ink);Text(subtitle,fontSize=14.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(top=6.dp))}}

@Composable
private fun PaperSelector(papers:List<PaperCard>,selectedId:String,onSelect:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current=papers.firstOrNull{it.paperId==selectedId}
    Box {
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Ink)) { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(current?.title ?: "选择试卷");Text("⌄",color=Green)} }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=420.dp).background(Color.White)) {
            papers.forEach{paper-> DropdownMenuItem(text={Text(paper.title)},onClick={onSelect(paper.paperId);expanded=false}) }
        }
    }
}
