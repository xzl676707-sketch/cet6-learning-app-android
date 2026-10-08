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

private val AppBg = Color(0xFFECF7E1)
private val CardBg = Color(0xFFFFFFFF)
private val Green = Color(0xFF3F7658)
private val DarkGreen = Color(0xFF294D38)
private val PaleGreen = Color(0xFFD3E8B7)
private val PaleBlue = Color(0xFFCDE7FA)
private val PaleYellow = Color(0xFFFFF4B6)
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

private enum class Page(val title: String) { HOME("首页"), WORDS("单词"), READING("阅读"), TRANSLATION("翻译"), WRITING("作文") }
private enum class WordListMode { NEW, REVIEW, ALL }
private data class NoteTarget(val type: String, val id: Int, val title: String)

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
    val readyWords = remember { repo.readyWordCount() }
    val totalWords = remember { repo.wordCount() }

    suspend fun refreshCounts() {
        val counts = withContext(Dispatchers.IO) { dao.studiedCount() to dao.dueCount(System.currentTimeMillis()) }
        studied = counts.first
        due = counts.second
    }
    LaunchedEffect(Unit) { refreshCounts() }

    Scaffold(
        containerColor = AppBg,
        bottomBar = {
            if (selectedWordId == null && noteTarget == null) NavigationBar(containerColor = Color.White, tonalElevation = 3.dp) {
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
                noteTarget != null -> NotesScreen(noteTarget!!, dao, onBack = { noteTarget = null })
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
                    Page.HOME -> HomeScreen(readyWords, totalWords, studied, due,
                        onNewWords = { wordListMode = WordListMode.NEW; page = Page.WORDS },
                        onReview = { wordListMode = WordListMode.REVIEW; page = Page.WORDS },
                        onWords = { wordListMode = WordListMode.ALL; page = Page.WORDS },
                        onReading = { page = Page.READING }, onTranslation = { page = Page.TRANSLATION }, onWriting = { page = Page.WRITING })
                    Page.WORDS -> WordListScreen(repo, dao, wordListMode, onMode = { wordListMode = it }, onWord = { id, queue ->
                        wordQueue = queue
                        ratedWordIds = emptySet()
                        selectedWordId = id
                    })
                    Page.READING -> ReadingScreen(repo)
                    Page.TRANSLATION -> TranslationScreen(repo, onNote = { noteTarget = it })
                    Page.WRITING -> WritingScreen(repo, onNote = { noteTarget = it })
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(readyWords: Int,totalWords: Int,studied: Int,due: Int,onNewWords:()->Unit,onReview:()->Unit,onWords:()->Unit,onReading:()->Unit,onTranslation:()->Unit,onWriting:()->Unit) {
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
            Card(Modifier.fillMaxWidth().clickable(onClick=onWords),colors=CardDefaults.cardColors(containerColor=DarkGreen),shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                        Column { Text("今日学习",color=Color.White.copy(alpha=.78f),fontSize=13.sp); Text("词汇复习",color=Color.White,fontSize=21.sp,fontWeight=FontWeight.Bold) }
                        Icon(Icons.Outlined.CheckCircle,null,tint=Color.White.copy(alpha=.85f),modifier=Modifier.size(27.dp))
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        StatPill("待复习",due.toString()); StatPill("已学词",studied.toString()); StatPill("可背新词",readyWords.toString())
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        Button(onClick=onReview,enabled=due>0,colors=ButtonDefaults.buttonColors(containerColor=PaleBlue,contentColor=DarkGreen,disabledContainerColor=Color.White.copy(alpha=.16f),disabledContentColor=Color.White.copy(alpha=.65f)),shape=RoundedCornerShape(14.dp)) { Text("今日复习 $due",fontWeight=FontWeight.SemiBold) }
                        Button(onClick=onNewWords,colors=ButtonDefaults.buttonColors(containerColor=PaleYellow,contentColor=DarkGreen),shape=RoundedCornerShape(14.dp)) { Text("学习新词  →",fontWeight=FontWeight.SemiBold) }
                    }
                    Text("新词每天最多显示 10 个；例句未齐的词暂不进入背诵",color=Color.White.copy(alpha=.72f),fontSize=12.sp)
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

@Composable private fun StatPill(label:String,value:String) { Column(Modifier.clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha=.12f)).padding(horizontal=13.dp,vertical=9.dp)) { Text(value,color=Color.White,fontWeight=FontWeight.Bold,fontSize=18.sp); Text(label,color=Color.White.copy(alpha=.75f),fontSize=11.sp) } }

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
                    Card(Modifier.fillMaxWidth().clickable(enabled=!revealed){revealed=true},colors=CardDefaults.cardColors(containerColor=DarkGreen),shape=RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("第 ${w.rank} 位 · ${w.level}",color=Color.White.copy(alpha=.75f),fontSize=12.sp)
                        Text(w.lemma,color=Color.White,fontSize=34.sp,fontWeight=FontWeight.Bold)
                        Text("真题出现 ${w.totalCount} 次 · 覆盖 ${w.paperCount} 套",color=Color.White.copy(alpha=.78f),fontSize=13.sp)
                        Text(if(revealed) "释义与例句" else "先想想它的意思 · 点卡片揭晓",color=Color.White.copy(alpha=.9f),fontSize=13.sp,modifier=Modifier.padding(top=5.dp))
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

@Composable private fun ReviewButton(label:String,modifier:Modifier,saving:Boolean,onClick:()->Unit) {
    OutlinedButton(onClick=onClick,modifier=modifier,enabled=!saving,shape=RoundedCornerShape(13.dp),contentPadding=PaddingValues(horizontal=4.dp,vertical=10.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Green)) { Text(label,fontSize=12.sp) }
}

@Composable
private fun ReadingScreen(repo:ContentDb) {
    var papers by remember { mutableStateOf(emptyList<PaperCard>()) }
    var selectedId by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("") }
    var readingTypes by remember { mutableStateOf(emptyList<String>()) }
    var mode by remember { mutableStateOf("精读") }
    var sentences by remember { mutableStateOf(emptyList<ReadingSentence>()) }
    var questions by remember { mutableStateOf(emptyList<ReadingQuestion>()) }
    LaunchedEffect(Unit) { papers=withContext(Dispatchers.IO){repo.getPapers("reading")}; if(papers.isNotEmpty()) selectedId=papers.first().paperId }
    LaunchedEffect(selectedId) { if(selectedId.isNotBlank()) { readingTypes=withContext(Dispatchers.IO){repo.getReadingTypes(selectedId)}; selectedType=readingTypes.firstOrNull().orEmpty() } }
    LaunchedEffect(selectedId,selectedType,mode) { if(selectedId.isNotBlank() && selectedType.isNotBlank()) withContext(Dispatchers.IO) { sentences=repo.getReadingSentences(selectedId,selectedType); questions=repo.getQuestions(selectedId,selectedType) } }
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        ScreenHeading("阅读精读", "逐句查看译文与结构分析，答案按原题关联")
        PaperSelector(papers,selectedId,{selectedId=it})
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) { readingTypes.forEach { type -> FilterChip(selected=selectedType==type,onClick={selectedType=type},label={Text(typeLabel(type),fontSize=12.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleGreen,selectedLabelColor=DarkGreen)) } }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("精读","题目答案").forEach { FilterChip(selected=mode==it,onClick={mode=it},label={Text(it)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleGreen)) } }
        if(mode=="精读") LazyColumn(contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            items(sentences,key={it.unitId}) { s ->
                Card(colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(17.dp)) { Column(Modifier.fillMaxWidth().padding(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(s.paragraphNo!=null && selectedType=="matching") Text("第 ${s.paragraphNo} 段",fontSize=11.sp,fontWeight=FontWeight.Bold,color=Green)
                    Text(s.text,fontSize=15.sp,lineHeight=22.sp,color=Ink)
                    HorizontalDivider(color=Color(0xFFE8EEE9))
                    Text("译文",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Green);Text(s.translation.ifBlank{"暂无译文"},fontSize=13.sp,lineHeight=19.sp,color=Ink)
                    Text("怎么看",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Green);Text(cleanMarkup(s.analysis).ifBlank{"暂无结构分析"},fontSize=13.sp,lineHeight=19.sp,color=Muted)
                } }
            }
        } else LazyColumn(contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            items(questions,key={it.unitId}) { q -> Card(colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(17.dp)) { Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
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
    LaunchedEffect(Unit){papers=withContext(Dispatchers.IO){repo.getPapers("translation")};if(papers.isNotEmpty())selectedId=papers.first().paperId}
    LaunchedEffect(selectedId){if(selectedId.isNotBlank())rows=withContext(Dispatchers.IO){repo.getTranslations(selectedId)}}
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        ScreenHeading("翻译训练","先在心里作答，再对照参考译文和优化译文")
        PaperSelector(papers,selectedId,{selectedId=it})
        LazyColumn(contentPadding=PaddingValues(top=10.dp,bottom=22.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
            items(rows,key={it.unitId}){item-> Card(colors=CardDefaults.cardColors(containerColor=PaleBlue),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){
                Text("第 ${item.orderNo} 句",fontSize=11.sp,color=Green,fontWeight=FontWeight.Bold)
                Text(item.chinese,fontSize=15.sp,lineHeight=22.sp,color=Ink)
                if(item.reference.isBlank() && item.optimized.isBlank()) Text("参考译文正在补入",fontSize=12.sp,color=Muted)
                if(item.reference.isNotBlank()){Text("参考译文",fontSize=11.sp,color=Green,fontWeight=FontWeight.SemiBold);Text(item.reference,fontSize=13.sp,lineHeight=19.sp,color=Ink)}
                if(item.optimized.isNotBlank()){Text("优化译文",fontSize=11.sp,color=Green,fontWeight=FontWeight.SemiBold);Text(item.optimized,fontSize=13.sp,lineHeight=19.sp,color=Ink)}
                TextButton(onClick={onNote(NoteTarget("translation",item.unitId,"翻译笔记 · 第 ${item.orderNo} 句"))}){Icon(Icons.Outlined.BookmarkBorder,null);Text("添加笔记")}
            }}}
        }
    }
}

@Composable
private fun WritingScreen(repo:ContentDb,onNote:(NoteTarget)->Unit) {
    var papers by remember { mutableStateOf(emptyList<PaperCard>()) };var selectedId by remember { mutableStateOf("") };var item by remember { mutableStateOf<WritingItem?>(null) }
    LaunchedEffect(Unit){papers=withContext(Dispatchers.IO){repo.getPapers("writing")};if(papers.isNotEmpty())selectedId=papers.first().paperId}
    LaunchedEffect(selectedId){if(selectedId.isNotBlank())item=withContext(Dispatchers.IO){repo.getWriting(selectedId)}}
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        ScreenHeading("作文积累","题目与范文整篇展示，选好表达可以记进笔记")
        PaperSelector(papers,selectedId,{selectedId=it})
        val current=item
        if(current!=null) LazyColumn(contentPadding=PaddingValues(top=10.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Card(colors=CardDefaults.cardColors(containerColor=DarkGreen),shape=RoundedCornerShape(20.dp)){Column(Modifier.padding(18.dp)){Text("写作题目",color=Color.White.copy(alpha=.75f),fontSize=12.sp);Text(current.prompt,color=Color.White,fontSize=15.sp,lineHeight=23.sp,modifier=Modifier.padding(top=7.dp))}} }
            items(current.samples){sample->
                val label=if(sample.type=="excellent")"优秀范文" else "参考范文"
                Card(colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                    Text(label,color=Green,fontSize=13.sp,fontWeight=FontWeight.Bold)
                    Text(highlightedText(sample.text,sample.highlightsJson),color=Ink,fontSize=14.sp,lineHeight=22.sp)
                    TextButton(onClick={onNote(NoteTarget("writing",current.promptUnitId,"作文笔记 · $label"))}){Icon(Icons.Outlined.BookmarkBorder,null);Text("记录好词好句")}
                }}
            }
            item { if(current.samples.isEmpty()) Text("这套范文正在补入。",color=Muted) }
        }
    }
}

@Composable
private fun NotesScreen(target:NoteTarget,dao:UserDao,onBack:()->Unit) {
    var text by remember { mutableStateOf("") };var loaded by remember { mutableStateOf(false) };var saved by remember { mutableStateOf(false) };val scope=rememberCoroutineScope()
    LaunchedEffect(target){withContext(Dispatchers.IO){dao.notes(target.type,target.id)}.firstOrNull()?.let{text=it.content};loaded=true}
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        TopAppBar(title={Text("学习笔记")},navigationIcon={TextButton(onClick=onBack){Text("‹ 返回",color=Green)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=AppBg))
        Text(target.title,fontSize=19.sp,fontWeight=FontWeight.Bold,color=Ink,modifier=Modifier.padding(vertical=16.dp))
        OutlinedTextField(value=text,onValueChange={text=it;saved=false},modifier=Modifier.fillMaxWidth().weight(1f),placeholder={Text("记下短语、句式或自己的理解")},shape=RoundedCornerShape(18.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Green))
        Button(onClick={scope.launch(Dispatchers.IO){dao.saveNote(UserNote(itemType=target.type,itemId=target.id,content=text));saved=true}},enabled=loaded&&text.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(top=14.dp),shape=RoundedCornerShape(15.dp),colors=ButtonDefaults.buttonColors(containerColor=Green)){Text(if(saved)"已保存" else "保存笔记")}
    }
}

@Composable private fun ScreenHeading(title:String,subtitle:String){Column(Modifier.padding(top=18.dp,bottom=12.dp)){Text(title,fontSize=23.sp,fontWeight=FontWeight.Bold,color=Ink);Text(subtitle,fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=3.dp))}}

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
