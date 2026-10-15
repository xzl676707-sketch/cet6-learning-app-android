@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cet6.study

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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

private enum class Page(val title: String) { HOME("首页"), WORDS("单词"), READING("阅读"), TRANSLATION("翻译"), WRITING("作文"), NOTE_REVIEW("笔记复习"), ALL_NOTES("全部笔记") }
private enum class WordListMode { NEW, REVIEW, ALL, FAVORITES }
private data class NoteTarget(val type: String, val id: Int, val title: String, val sourceText: String = "", val storageType: String = type, val existingNoteId: Long? = null)
private sealed class ReviewQueueEntry {
    abstract val dueAt: Long
    data class Word(val wordId: Int, override val dueAt: Long) : ReviewQueueEntry()
    data class Note(val note: UserNote) : ReviewQueueEntry() { override val dueAt: Long get() = note.nextReview }
}

@Composable
private fun Cet6StudyApp() {
    val context = LocalContext.current
    val repo = remember { ContentDb.get(context) }
    val userDb = remember { UserDatabase.get(context) }
    val dao = userDb.dao()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var page by remember { mutableStateOf(Page.HOME) }
    var selectedWordId by remember { mutableStateOf<Int?>(null) }
    var wordQueue by remember { mutableStateOf(emptyList<Int>()) }
    var wordLearningSession by remember { mutableStateOf(false) }
    var wordListMode by remember { mutableStateOf(WordListMode.NEW) }
    var noteTarget by remember { mutableStateOf<NoteTarget?>(null) }
    var allNotesRefresh by remember { mutableIntStateOf(0) }
    var studied by remember { mutableIntStateOf(0) }
    var due by remember { mutableIntStateOf(0) }
    var dueNotes by remember { mutableIntStateOf(0) }
    val goalPrefs = remember { context.getSharedPreferences("daily_word_goals", android.content.Context.MODE_PRIVATE) }
    val todayKey = java.time.LocalDate.now().toString()
    var newGoal by remember { mutableIntStateOf(goalPrefs.getInt("new_goal", 10).coerceIn(0, 100)) }
    var todayAddedWordIds by remember { mutableStateOf(emptySet<Int>()) }
    var newDoneToday by remember { mutableIntStateOf(0) }
    var sharedLookupWord by remember { mutableStateOf<WordDetail?>(null) }
    var sharedLookupChoices by remember { mutableStateOf(emptyList<WordCard>()) }
    var sharedLookupIsStudied by remember { mutableStateOf(false) }
    var sharedLookupAlreadyAdded by remember { mutableStateOf(false) }
    var sharedWordFormIndex by remember { mutableStateOf<Map<String,List<WordCard>>?>(null) }
    suspend fun showSharedLookupWord(wordId:Int) {
        val loaded=withContext(Dispatchers.IO) { repo.getWord(wordId) }
        if(loaded==null) {
            snackbarHostState.showSnackbar("无：2500 词库中没有这个词")
            return
        }
        sharedLookupWord=loaded
        sharedLookupChoices=emptyList()
        sharedLookupIsStudied=withContext(Dispatchers.IO) { dao.status(wordId)!=null }
        val seen=withContext(Dispatchers.IO) { dao.studiedWordIds().toSet() }
        val planned=withContext(Dispatchers.IO) {
            repo.getNewWordIds((newGoal-newDoneToday).coerceAtLeast(0),seen).contains(wordId)
        }
        sharedLookupAlreadyAdded=wordId in todayAddedWordIds || planned
    }
    fun addSharedLookupToToday(wordId:Int) {
        scope.launch {
            val alreadyAdded=wordId in todayAddedWordIds
            val seen=withContext(Dispatchers.IO) { dao.studiedWordIds().toSet() }
            val alreadyPlanned=withContext(Dispatchers.IO) {
                repo.getNewWordIds((newGoal-newDoneToday).coerceAtLeast(0),seen).contains(wordId)
            }
            if(!alreadyAdded && !alreadyPlanned) {
                val updated=todayAddedWordIds+wordId
                todayAddedWordIds=updated
                goalPrefs.edit().putStringSet("today_added_words_$todayKey",updated.map{it.toString()}.toSet()).apply()
            }
            sharedLookupAlreadyAdded=true
            snackbarHostState.showSnackbar(if(alreadyAdded || alreadyPlanned) "这个词已在今天的学习计划中" else "已加入今天的学习计划")
        }
    }
    fun lookupSelectedText(selection:String) {
        val term=Regex("[A-Za-z]+(?:['-][A-Za-z]+)*").find(selection)?.value?.lowercase()
        if(term.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar("无：2500 词库中没有这个词") }
            return
        }
        scope.launch {
            val index=sharedWordFormIndex ?: withContext(Dispatchers.IO) {
                buildWordFormIndex(repo.getWordFormSources())
            }.also { sharedWordFormIndex=it }
            val allMatches=index[term].orEmpty()
            val exact=allMatches.firstOrNull { it.lemma.equals(term,ignoreCase=true) }
            val matches=if(exact!=null) listOf(exact) else allMatches
            when(matches.size) {
                0 -> snackbarHostState.showSnackbar("无：2500 词库中没有这个词")
                1 -> showSharedLookupWord(matches.first().wordId)
                else -> {
                    sharedLookupWord=null
                    sharedLookupChoices=matches
                }
            }
        }
    }
    var reviewDoneToday by remember { mutableIntStateOf(0) }
    val readyWords = remember { repo.readyWordCount() }
    val totalWords = remember { repo.wordCount() }

    LaunchedEffect(todayKey) {
        if (goalPrefs.getString("progress_date", null) != todayKey) {
            goalPrefs.edit().putString("progress_date", todayKey).putInt("new_done", 0).putInt("review_done", 0).apply()
        }
        newDoneToday = goalPrefs.getInt("new_done", 0)
        reviewDoneToday = goalPrefs.getInt("review_done", 0)
        todayAddedWordIds = goalPrefs.getStringSet("today_added_words_$todayKey", emptySet())
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    }

    suspend fun refreshCounts() {
        val counts = withContext(Dispatchers.IO) { dao.studiedCount() to dao.dueCount(System.currentTimeMillis()) }
        studied = counts.first
        due = counts.second
        dueNotes = withContext(Dispatchers.IO) { dao.dueNoteCount(System.currentTimeMillis()) }
    }
    LaunchedEffect(Unit) { refreshCounts() }

    Scaffold(
        containerColor = AppBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectedWordId == null && noteTarget == null && page != Page.NOTE_REVIEW && page != Page.ALL_NOTES) NavigationBar(containerColor = Color.White, tonalElevation = 3.dp) {
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
                page == Page.NOTE_REVIEW -> DailyReviewScreen(
                    repo = repo,
                    dao = dao,
                                        snackbarHostState = snackbarHostState,
                    onWordLookup = ::lookupSelectedText,
                    onBack = { page = Page.HOME; scope.launch { refreshCounts() } },
                    onReviewDone = {
                        reviewDoneToday += 1
                        goalPrefs.edit().putInt("review_done", reviewDoneToday).apply()
                        scope.launch { refreshCounts() }
                    },
                    onReviewUndo = {
                        reviewDoneToday = (reviewDoneToday - 1).coerceAtLeast(0)
                        goalPrefs.edit().putInt("review_done", reviewDoneToday).apply()
                        scope.launch { refreshCounts() }
                    }
                )
                selectedWordId != null -> WordDetailScreen(
                    wordId = selectedWordId!!,
                    progress = (wordQueue.indexOf(selectedWordId!!) + 1).coerceAtLeast(1) to wordQueue.size,
                    repo = repo,
                    dao = dao,
                    onWordLookup = ::lookupSelectedText,
                    isLearningSession = wordLearningSession,
                    onBack = { selectedWordId = null; scope.launch { refreshCounts() } },
                    onRated = { completedNewWord ->
                        if (wordLearningSession) {
                            if (completedNewWord) {
                                newDoneToday += 1
                                goalPrefs.edit().putInt("new_done", newDoneToday).apply()
                            }
                        } else {
                            reviewDoneToday += 1
                            goalPrefs.edit().putInt("review_done", reviewDoneToday).apply()
                        }
                        val currentId = selectedWordId
                        if (currentId != null) {
                            val remaining = wordQueue.dropWhile { it != currentId }.drop(1)
                            val retryAfterOtherWords = wordLearningSession && !completedNewWord
                            wordQueue = remaining + if (retryAfterOtherWords) listOf(currentId) else emptyList()
                            selectedWordId = wordQueue.firstOrNull()
                        }
                        scope.launch { refreshCounts() }
                    }
                )
                else -> when(page) {
                    Page.HOME -> HomeScreen(readyWords, totalWords, studied, due, dueNotes, newGoal, todayAddedWordIds.size, newDoneToday, reviewDoneToday,
                        onNewWords = { wordListMode = WordListMode.NEW; page = Page.WORDS },
                        onReview = { wordListMode = WordListMode.REVIEW; page = Page.NOTE_REVIEW },
                        onWords = { wordListMode = WordListMode.ALL; page = Page.WORDS },
                        onReading = { page = Page.READING }, onTranslation = { page = Page.TRANSLATION }, onWriting = { page = Page.WRITING },
                        onNoteReview = { page = Page.NOTE_REVIEW },
                        onAllNotes = { page = Page.ALL_NOTES },
                        onGoalChange = { newValue ->
                            newGoal = newValue
                            goalPrefs.edit().putInt("new_goal", newValue).apply()
                        })
                    Page.WORDS -> WordListScreen(repo, dao, wordListMode, newGoal, newDoneToday, todayAddedWordIds, onMode = { wordListMode = it }, onWord = { id, queue ->
                        wordQueue = queue.dropWhile { it != id }
                        wordLearningSession = wordListMode == WordListMode.NEW
                        selectedWordId = id
                    })
                    Page.READING -> ReadingScreen(
                        repo, dao, todayAddedWordIds,
                        onNote = { noteTarget = it },
                        onWordNotFound = { scope.launch { snackbarHostState.showSnackbar("无：2500 词库中没有这个词") } },
                        onAddToToday = { wordId ->
                            scope.launch {
                                val alreadyPlanned=withContext(Dispatchers.IO) {
                                    val seen=dao.studiedWordIds().toSet()
                                    repo.getNewWordIds((newGoal-newDoneToday).coerceAtLeast(0),seen).contains(wordId)
                                }
                                if(!alreadyPlanned) {
                                    todayAddedWordIds = todayAddedWordIds + wordId
                                    goalPrefs.edit().putStringSet("today_added_words_$todayKey", todayAddedWordIds.map { it.toString() }.toSet()).apply()
                                }
                                snackbarHostState.showSnackbar(if(alreadyPlanned) "这个词已在今天的新词队列中" else "已加入今天的新词任务")
                            }
                        }
                    )
                    Page.TRANSLATION -> TranslationScreen(repo, onNote = { noteTarget = it }, onWordLookup = ::lookupSelectedText)
                    Page.WRITING -> WritingScreen(repo, dao, onNote = { noteTarget = it }, onWordLookup = ::lookupSelectedText)
                    Page.NOTE_REVIEW -> Unit
                    Page.ALL_NOTES -> AllNotesScreen(
                        dao = dao,
                        refreshToken = allNotesRefresh,
                        onBack = { page = Page.HOME },
                        onOpenNote = { note ->
                            noteTarget = NoteTarget(note.itemType, note.itemId, "学习笔记 · ${noteTypeLabel(note.itemType)}", note.sourceText, existingNoteId = note.noteId)
                        }
                    )
                }
            }
            if(sharedLookupChoices.isNotEmpty()) AlertDialog(
                onDismissRequest={sharedLookupChoices=emptyList()},
                title={Text("找到多个词条，请选择",color=DarkGreen)},
                text={
                    Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        sharedLookupChoices.forEach { choice ->
                            TextButton(onClick={
                                scope.launch { showSharedLookupWord(choice.wordId) }
                            },modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(choice.lemma,fontWeight=FontWeight.SemiBold,color=DarkGreen)
                                    Text(choice.meanings.replace(",","；"),fontSize=12.sp,color=Muted)
                                }
                            }
                        }
                    }
                },
                confirmButton={TextButton(onClick={sharedLookupChoices=emptyList()}){Text("关闭",color=Green)}}
            )
            sharedLookupWord?.let { word ->
                AlertDialog(
                    onDismissRequest={sharedLookupWord=null},
                    title={Text("${word.lemma} · 第${word.rank}位",color=DarkGreen)},
                    text={
                        Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("${word.level} · 真题出现 ${word.totalCount} 次 · 覆盖 ${word.paperCount} 套",fontSize=12.sp,color=Muted)
                            word.senses.forEach { sense ->
                                Text("${sense.pos}  ${sense.meaning}",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                                if(sense.examples.isEmpty()) Text("暂无默认例句",fontSize=12.sp,color=Muted)
                                sense.examples.forEach { example ->
                                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppBg).padding(9.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                        Text(example.english,fontSize=13.sp,lineHeight=18.sp,color=Ink)
                                        if(example.chinese.isNotBlank()) Text(example.chinese,fontSize=11.sp,lineHeight=16.sp,color=Muted)
                                    }
                                }
                            }
                            if(word.senses.isEmpty()) Text("暂无词义数据",fontSize=12.sp,color=Muted)
                        }
                    },
                    confirmButton={
                        when {
                            sharedLookupIsStudied -> TextButton(onClick={sharedLookupWord=null}) { Text("已学过",color=Muted) }
                            sharedLookupAlreadyAdded -> TextButton(onClick={sharedLookupWord=null}) { Text("已加入今天",color=Green) }
                            else -> TextButton(onClick={addSharedLookupToToday(word.wordId)}) { Text("加入今天学习",color=Green) }
                        }
                    },
                    dismissButton={TextButton(onClick={sharedLookupWord=null}){Text("关闭")}}
                )
            }
            noteTarget?.let { target ->
                Box(Modifier.fillMaxSize().background(AppBg)) {
                    NotesScreen(
                        target = target,
                        dao = dao,
                        onBack = { noteTarget = null; scope.launch { refreshCounts() } },
                        onSaved = {
                            allNotesRefresh += 1
                            scope.launch { refreshCounts(); snackbarHostState.showSnackbar("笔记已保存") }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(readyWords: Int,totalWords: Int,studied: Int,due: Int,dueNotes: Int,newGoal:Int,addedTodayCount:Int,newDone:Int,reviewDone:Int,onNewWords:()->Unit,onReview:()->Unit,onWords:()->Unit,onReading:()->Unit,onTranslation:()->Unit,onWriting:()->Unit,onNoteReview:()->Unit,onAllNotes:()->Unit,onGoalChange:(Int)->Unit) {
    var showGoalDialog by remember { mutableStateOf(false) }
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
                        StatPill("待复习",(due+dueNotes).toString()); StatPill("已学词",studied.toString()); StatPill("可背新词",readyWords.toString())
                    }
                    DailyGoalProgress("新词", newDone, newGoal + addedTodayCount, Green)
                    DailyGoalProgress("复习", reviewDone, reviewDone + due + dueNotes, Color(0xFF8C9F63))
                    Row(horizontalArrangement=Arrangement.spacedBy(9.dp), verticalAlignment=Alignment.CenterVertically) {
                        Button(onClick=onReview,enabled=(due+dueNotes)>0,colors=ButtonDefaults.buttonColors(containerColor=PaleBlue,contentColor=DarkGreen,disabledContainerColor=Color.White.copy(alpha=.55f),disabledContentColor=Muted),shape=RoundedCornerShape(14.dp)) { Text("开始复习 ${due+dueNotes}",fontWeight=FontWeight.SemiBold) }
                        Button(onClick=onNewWords,enabled=newDone<(newGoal+addedTodayCount),colors=ButtonDefaults.buttonColors(containerColor=Color.White.copy(alpha=.8f),contentColor=DarkGreen,disabledContainerColor=Color.White.copy(alpha=.5f),disabledContentColor=Muted),shape=RoundedCornerShape(14.dp)) { Text("学习新词  →",fontWeight=FontWeight.SemiBold) }
                        TextButton(onClick={showGoalDialog=true},contentPadding=PaddingValues(horizontal=4.dp)) { Text("设目标",fontSize=12.sp,color=Green) }
                    }
                    Text("新词按每日目标安排；复习数量由间隔周期和今天实际到期的词、笔记决定。",color=Muted,fontSize=12.sp)
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
        item { ModuleCard("今日复习","单词 $due · 笔记 $dueNotes",Icons.Outlined.BookmarkBorder,Modifier.fillMaxWidth(),onNoteReview) }
        item { ModuleCard("全部笔记","查看和编辑所有已保存的笔记",Icons.Outlined.EditNote,Modifier.fillMaxWidth(),onAllNotes) }
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
    if(showGoalDialog) DailyGoalDialog(newGoal,{showGoalDialog=false}) { newValue ->
        onGoalChange(newValue)
        showGoalDialog=false
    }
}

@Composable
private fun DailyGoalProgress(label:String,done:Int,goal:Int,color:Color) {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("今日$label",fontSize=13.sp,color=DarkGreen,fontWeight=FontWeight.SemiBold)
            Text("$done / $goal",fontSize=12.sp,color=Muted)
        }
        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha=.75f))) {
            Box(Modifier.fillMaxWidth(if(goal<=0) 0f else (done.toFloat()/goal).coerceIn(0f,1f)).height(7.dp).clip(RoundedCornerShape(8.dp)).background(color))
        }
    }
}

@Composable
private fun DailyGoalDialog(newGoal:Int,onDismiss:()->Unit,onSave:(Int)->Unit) {
    var newValue by remember(newGoal) { mutableIntStateOf(newGoal) }
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("每日单词目标")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("设置每天计划学习的新词数。复习数量按间隔周期自动安排，到期内容会全部进入今日队列。",fontSize=13.sp,color=Muted,lineHeight=19.sp)
                GoalStepper("新词",newValue,0,100,{newValue=(newValue-1).coerceAtLeast(0)},{newValue=(newValue+1).coerceAtMost(100)})
            }
        },
        confirmButton={TextButton(onClick={onSave(newValue)}){Text("保存",color=Green)}},
        dismissButton={TextButton(onClick=onDismiss){Text("取消")}}
    )
}

@Composable
private fun GoalStepper(label:String,value:Int,minimum:Int,maximum:Int,onMinus:()->Unit,onPlus:()->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,color=Ink,fontWeight=FontWeight.SemiBold)
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onMinus,enabled=value>minimum){Text("−")}
            Text("$value 个",modifier=Modifier.widthIn(min=48.dp),color=DarkGreen,fontWeight=FontWeight.Bold)
            TextButton(onClick=onPlus,enabled=value<maximum){Text("+")}
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
private fun WordListScreen(repo:ContentDb,dao:UserDao,mode:WordListMode,newGoal:Int,newDone:Int,addedWordIds:Set<Int>,onMode:(WordListMode)->Unit,onWord:(Int,List<Int>)->Unit) {
    var search by remember { mutableStateOf("") }
    var dueIds by remember { mutableStateOf(emptyList<Int>()) }
    var newIds by remember { mutableStateOf(emptyList<Int>()) }
    var words by remember { mutableStateOf(emptyList<WordCard>()) }
    var folders by remember { mutableStateOf(emptyList<UserWordFolder>()) }
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }
    var favoriteIds by remember { mutableStateOf(emptyList<Int>()) }
    var folderMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        folders=withContext(Dispatchers.IO) {
            if(dao.wordFolders().isEmpty()) dao.insertWordFolder(UserWordFolder(name="默认收藏"))
            dao.wordFolders()
        }
        if(selectedFolderId==null) selectedFolderId=folders.firstOrNull()?.folderId
    }
    LaunchedEffect(search) { words=withContext(Dispatchers.IO){repo.getWords(search,2500)} }
    LaunchedEffect(mode,newGoal,newDone,addedWordIds) {
        withContext(Dispatchers.IO) {
            val pending=dao.incompleteWordIds()
            val seen=dao.studiedWordIds().toSet()
            dueIds=dao.dueWordIds(System.currentTimeMillis())
            val newRemaining=(newGoal-newDone).coerceAtLeast(0)
            val manuallyAdded=addedWordIds.filter { it !in seen && it !in pending }
            val freshNeeded=(newRemaining-pending.size-manuallyAdded.size).coerceAtLeast(0)
            val planned=repo.getNewWordIds(freshNeeded,seen)
            newIds=(planned+manuallyAdded+pending).distinct()
        }
    }
    LaunchedEffect(selectedFolderId) {
        favoriteIds=selectedFolderId?.let { folderId ->
            withContext(Dispatchers.IO) { dao.wordFavorites(folderId).map { it.wordId } }
        }.orEmpty()
    }
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        ScreenHeading("单词", "按最终背诵顺序浏览，例句不足的词会标出")
        OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("查单词或中文释义")},leadingIcon={Icon(Icons.Outlined.Search,null)},shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Green, unfocusedBorderColor=Color(0xFFDDE5DE)))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top=5.dp,bottom=5.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=mode==WordListMode.NEW,onClick={onMode(WordListMode.NEW)},label={Text("新词 ${newIds.size}")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleGreen))
            FilterChip(selected=mode==WordListMode.REVIEW,onClick={onMode(WordListMode.REVIEW)},label={Text("复习 ${dueIds.size}")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleBlue))
            FilterChip(selected=mode==WordListMode.ALL,onClick={onMode(WordListMode.ALL)},label={Text("全部 2500 词")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleGreen))
            FilterChip(selected=mode==WordListMode.FAVORITES,onClick={onMode(WordListMode.FAVORITES)},label={Text("收藏 ${favoriteIds.size}")},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=PaleYellow))
        }
        if(mode==WordListMode.FAVORITES) {
            Box {
                OutlinedButton(onClick={folderMenuExpanded=true},shape=RoundedCornerShape(12.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Green)) {
                    Text(folders.firstOrNull{it.folderId==selectedFolderId}?.name ?: "选择收藏夹")
                    Text("  ⌄")
                }
                DropdownMenu(expanded=folderMenuExpanded,onDismissRequest={folderMenuExpanded=false}) {
                    folders.forEach { folder ->
                        DropdownMenuItem(text={Text(folder.name)},onClick={selectedFolderId=folder.folderId;folderMenuExpanded=false})
                    }
                }
            }
        }
        val plannedIds=when(mode){
            WordListMode.NEW->newIds
            WordListMode.REVIEW->dueIds
            WordListMode.ALL->emptyList()
            WordListMode.FAVORITES->favoriteIds
        }
        val wordsById=words.associateBy { it.wordId }
        val visibleWords=if(mode!=WordListMode.ALL) plannedIds.mapNotNull { wordsById[it] } else words
        LazyColumn(contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            if(visibleWords.isEmpty()) item {
                val message=when(mode) {
                    WordListMode.REVIEW -> "当前没有到期复习的单词。"
                    WordListMode.NEW -> "新词已学完。"
                    WordListMode.FAVORITES -> "这个收藏夹还没有单词。"
                    WordListMode.ALL -> "没有找到匹配的单词。"
                }
                Text(if(search.isBlank() || mode==WordListMode.NEW || mode==WordListMode.REVIEW || mode==WordListMode.FAVORITES) message else "没有找到匹配的单词。",color=Muted,fontSize=14.sp,modifier=Modifier.padding(18.dp))
            }
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
private fun WordDetailScreen(wordId:Int,progress:Pair<Int,Int>,repo:ContentDb,dao:UserDao,onWordLookup:(String)->Unit = {},onBack:()->Unit,onRated:(Boolean)->Unit,onMastered:(()->Unit)?=null,isLearningSession:Boolean=false) {
    var detail by remember(wordId) { mutableStateOf<WordDetail?>(null) }
    var currentStatus by remember(wordId) { mutableStateOf<UserWordStatus?>(null) }
    var folders by remember(wordId) { mutableStateOf(emptyList<UserWordFolder>()) }
    var currentFavorite by remember(wordId) { mutableStateOf<UserWordFavorite?>(null) }
    var showFavoriteDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var saving by remember(wordId) { mutableStateOf(false) }
    var revealed by remember(wordId) { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(wordId) {
        withContext(Dispatchers.IO) {
            detail=repo.getWord(wordId)
            currentStatus=dao.status(wordId)
            if(dao.wordFolders().isEmpty()) dao.insertWordFolder(UserWordFolder(name="默认收藏"))
            folders=dao.wordFolders()
            currentFavorite=dao.wordFavorite(wordId)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title={Text("单词详情")},
            navigationIcon={TextButton(onClick=onBack){Text("‹ 返回",color=Green)}},
            actions={
                TextButton(enabled=!saving,onClick={showFavoriteDialog=true}){Text(if(currentFavorite!=null) "★ 已收藏" else "☆ 收藏",color=Green)}
                if(onMastered!=null && currentStatus?.isMastered!=true) TextButton(enabled=!saving,onClick={saving=true;onMastered()}){Text("彻底熟悉",color=Green)}
            },
            colors=TopAppBarDefaults.topAppBarColors(containerColor=AppBg)
        )
        val w=detail
        if(w==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(color=Green)} else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    Text("${if(progress.second>0) "${progress.first} / ${progress.second} · " else ""}${if(isLearningSession || currentStatus==null) "新词学习" else "今日复习"}",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=4.dp))
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
                                Text("暂无默认例句",color=Muted,fontSize=12.sp,modifier=Modifier.clip(RoundedCornerShape(10.dp)).background(AppBg).padding(10.dp))
                            } else sense.examples.forEachIndexed { i,example ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppBg).padding(11.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text("例句 ${i+1} · ${example.source}",fontSize=10.sp,color=Muted)
                                    SelectableReadingText(
                                        text=example.english,
                                        onBlankClick={},
                                        onNoteSelection={},
                                        onWordLookup=onWordLookup,
                                        showNoteAction=false
                                    )
                                    if(example.chinese.isNotBlank()) Text(example.chinese,color=Muted,fontSize=12.sp,lineHeight=17.sp)
                                }
                            }
                        }
                    }
                }
                if(revealed) item {
                    Text("记忆情况",fontWeight=FontWeight.SemiBold,fontSize=18.sp,color=Ink)
                    Text(if(isLearningSession) "选择“熟悉”才计入今日新词；“不会/有点了解”会在本组其他新词后重看。" else "选择后自动进入下一个计划单词；复习时间由应用自动安排。",fontSize=12.sp,lineHeight=18.sp,color=Muted)
                    if(currentStatus?.isMastered==true) {
                        Text("已彻底熟悉，不会再进入自动复习队列。",color=Green,fontSize=13.sp)
                    } else {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            ReviewButton("不会",Modifier.weight(1f),saving){ rate("不会",w.wordId,dao,scope,isLearningSession,{saving=true}) { completedNew -> onRated(completedNew) } }
                            ReviewButton("有点了解",Modifier.weight(1f),saving){ rate("有点了解",w.wordId,dao,scope,isLearningSession,{saving=true}) { completedNew -> onRated(completedNew) } }
                            ReviewButton("熟悉",Modifier.weight(1f),saving){ rate("熟悉",w.wordId,dao,scope,isLearningSession,{saving=true}) { completedNew -> onRated(completedNew) } }
                        }
                        currentStatus?.let { Text("上次选择：${it.familiarity} · 已复习 ${it.reviewCount} 次",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=6.dp)) }
                    }
                }
            }
        }
    }
    val favoriteWord=detail
    if(showFavoriteDialog && favoriteWord!=null) {
        AlertDialog(
            onDismissRequest={showFavoriteDialog=false},
            title={Text("收藏「${favoriteWord.lemma}」",color=DarkGreen)},
            text={
                Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("选择一个收藏夹，也可以新建分类。",fontSize=13.sp,color=Muted)
                    folders.forEach { folder ->
                        TextButton(onClick={
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.saveWordFavorite(UserWordFavorite(favoriteWord.wordId,folder.folderId)) }
                                currentFavorite=withContext(Dispatchers.IO) { dao.wordFavorite(favoriteWord.wordId) }
                                showFavoriteDialog=false
                            }
                        },modifier=Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(folder.name,color=Ink)
                                Text(if(currentFavorite?.folderId==folder.folderId) "当前收藏" else "收藏到这里",color=Green,fontSize=12.sp)
                            }
                        }
                    }
                    OutlinedTextField(value=newFolderName,onValueChange={newFolderName=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("新收藏夹名称")})
                }
            },
            confirmButton={
                TextButton(onClick={
                    val name=newFolderName.trim()
                    if(name.isNotEmpty()) scope.launch {
                        val folderId=withContext(Dispatchers.IO) {
                            val inserted=dao.insertWordFolder(UserWordFolder(name=name))
                            val resolved=if(inserted>0) inserted else dao.wordFolders().firstOrNull{it.name==name}?.folderId ?: 0L
                            if(resolved>0) dao.saveWordFavorite(UserWordFavorite(favoriteWord.wordId,resolved))
                            resolved
                        }
                        folders=withContext(Dispatchers.IO) { dao.wordFolders() }
                        currentFavorite=withContext(Dispatchers.IO) { dao.wordFavorite(favoriteWord.wordId) }
                        newFolderName=""
                        showFavoriteDialog=false
                    }
                }){Text("新建并收藏",color=Green)}
            },
            dismissButton={
                Row {
                    if(currentFavorite!=null) TextButton(onClick={
                        scope.launch {
                            withContext(Dispatchers.IO) { dao.removeWordFavorite(favoriteWord.wordId) }
                            currentFavorite=null
                            showFavoriteDialog=false
                        }
                    }){Text("取消收藏",color=Color(0xFFB15C4B))}
                    TextButton(onClick={showFavoriteDialog=false}){Text("关闭",color=Muted)}
                }
            }
        )
    }
}

private fun rate(value:String,wordId:Int,dao:UserDao,scope:kotlinx.coroutines.CoroutineScope,isLearningSession:Boolean,onStart:()->Unit,onComplete:(Boolean)->Unit) {
    onStart()
    scope.launch(Dispatchers.IO) {
        val old=dao.status(wordId)
        val now=System.currentTimeMillis()
        val stageBefore=old?.reviewStage ?: -1
        val isUnfinishedLearning=isLearningSession && old?.isLearningComplete!=true
        val completesNewWord=isUnfinishedLearning && value=="熟悉"
        val schedule=if(isUnfinishedLearning) scheduleNextReview("不会",-1,now) else scheduleNextReview(value,stageBefore,now)
        val isLearningComplete=if(isLearningSession) old?.isLearningComplete==true || value=="熟悉" else old?.isLearningComplete ?: true
        dao.recordReview(
            UserWordStatus(
                wordId=wordId,
                familiarity=value,
                nextReview=schedule.nextReviewMillis,
                lastReviewed=now,
                reviewCount=(old?.reviewCount ?: 0)+1,
                reviewStage=schedule.stage,
                isMastered=old?.isMastered ?: false,
                isLearningComplete=isLearningComplete
            ),
            UserReviewHistory(wordId=wordId,reviewedAt=now,familiarity=value,stageBefore=stageBefore,stageAfter=schedule.stage,intervalDays=schedule.intervalDays)
        )
        withContext(Dispatchers.Main) { onComplete(completesNewWord) }
    }
}

@Composable
private fun DailyReviewScreen(
    repo:ContentDb,
    dao:UserDao,
    snackbarHostState:SnackbarHostState,
    onWordLookup:(String)->Unit,
    onBack:()->Unit,
    onReviewDone:()->Unit,
    onReviewUndo:()->Unit
) {
    var entries by remember { mutableStateOf(emptyList<ReviewQueueEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var index by remember { mutableIntStateOf(0) }
    var completedCount by remember { mutableIntStateOf(0) }
    val scope=rememberCoroutineScope()

    LaunchedEffect(Unit) {
        entries=withContext(Dispatchers.IO) {
            val now=System.currentTimeMillis()
            val words=dao.dueWordStatuses(now).map { ReviewQueueEntry.Word(it.wordId,it.nextReview) }
            val notes=dao.dueNotes(now).map { ReviewQueueEntry.Note(it) }
            (words+notes).sortedBy { it.dueAt }
        }
        loading=false
    }

    val current=entries.getOrNull(index)
    when {
        loading -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator(color=Green) }
        current==null -> Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
            TopAppBar(title={Text("今日复习")},navigationIcon={TextButton(onClick=onBack){Text("‹ 返回",color=Green)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=AppBg))
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("当前没有到期的单词或笔记",fontSize=20.sp,fontWeight=FontWeight.Bold,color=DarkGreen)
                    Text("未完成的到期内容会继续保留在队列里。",color=Muted)
                    Button(onClick=onBack,colors=ButtonDefaults.buttonColors(containerColor=Green)){Text("返回首页")}
                }
            }
        }
        current is ReviewQueueEntry.Word -> WordDetailScreen(
            wordId=current.wordId,
            progress=(completedCount+1) to (completedCount+entries.size),
            repo=repo,
            dao=dao,
            onWordLookup=onWordLookup,
            onBack=onBack,
            onRated={
                onReviewDone()
                completedCount+=1
                entries=entries.toMutableList().apply { removeAt(index) }
            },
            onMastered={
                val masteredEntry=current
                scope.launch {
                    withContext(Dispatchers.IO) { dao.setWordMastered(masteredEntry.wordId,true,Long.MAX_VALUE) }
                    onReviewDone()
                    completedCount+=1
                    entries=entries.toMutableList().apply { removeAt(index) }
                    val result=snackbarHostState.showSnackbar(
                        message="已标记为彻底熟悉",
                        actionLabel="撤销",
                        withDismissAction=true,
                        duration=SnackbarDuration.Short
                    )
                    if(result==SnackbarResult.ActionPerformed) {
                        withContext(Dispatchers.IO) { dao.setWordMastered(masteredEntry.wordId,false,System.currentTimeMillis()) }
                        onReviewUndo()
                        completedCount=(completedCount-1).coerceAtLeast(0)
                        entries=entries.toMutableList().apply { add(index.coerceIn(0,size),masteredEntry.copy(dueAt=System.currentTimeMillis())) }
                    }
                }
            }
        )
        current is ReviewQueueEntry.Note -> ReviewNoteCard(
            note=current.note,
            progress=(completedCount+1) to (completedCount+entries.size),
            dao=dao,
            onBack=onBack,
            onComplete={
                onReviewDone()
                completedCount+=1
                entries=entries.toMutableList().apply { removeAt(index) }
            }
        )
    }
}

@Composable
private fun ReviewNoteCard(note:UserNote,progress:Pair<Int,Int>,dao:UserDao,onBack:()->Unit,onComplete:()->Unit) {
    var revealed by remember(note.noteId) { mutableStateOf(false) }
    var noteHighlights by remember(note.noteId) { mutableStateOf(emptyList<UserNoteHighlight>()) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(note.noteId) { noteHighlights=withContext(Dispatchers.IO){dao.noteHighlights(note.noteId)} }
    val scope=rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal=18.dp)) {
        TopAppBar(title={Text("今日复习")},navigationIcon={TextButton(onClick=onBack){Text("‹ 返回",color=Green)}},colors=TopAppBarDefaults.topAppBarColors(containerColor=AppBg))
        Text("${progress.first} / ${progress.second} · 笔记",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=10.dp,bottom=10.dp))
        Card(Modifier.fillMaxWidth().weight(1f).clickable(enabled=!revealed){revealed=true},colors=CardDefaults.cardColors(containerColor=if(revealed) CardBg else PaleGreen),shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(if(note.sourceText.isBlank()) "回忆这条笔记" else note.sourceText,fontSize=20.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold,color=DarkGreen)
                if(revealed) Text(highlightedNoteContent(note.content,noteHighlights),fontSize=15.sp,lineHeight=24.sp,color=Ink)
                else Text("点卡片空白处查看笔记",fontSize=15.sp,lineHeight=24.sp,color=Ink)
            }
        }
        if(revealed) {
            Text("这次记得怎么样？",fontSize=14.sp,color=Muted,modifier=Modifier.padding(top=16.dp,bottom=8.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ReviewButton("不会",Modifier.weight(1f),saving){ saveNoteRating("不会",note,dao,scope,{saving=true}){saving=false;onComplete()} }
                ReviewButton("有点了解",Modifier.weight(1f),saving){ saveNoteRating("有点了解",note,dao,scope,{saving=true}){saving=false;onComplete()} }
                ReviewButton("熟悉",Modifier.weight(1f),saving){ saveNoteRating("熟悉",note,dao,scope,{saving=true}){saving=false;onComplete()} }
            }
        }
        Spacer(Modifier.height(20.dp))
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
private fun ReadingScreen(repo:ContentDb,dao:UserDao,todayAddedWordIds:Set<Int>,onNote:(NoteTarget)->Unit,onWordNotFound:()->Unit,onAddToToday:(Int)->Unit) {
    var lookupWord by remember { mutableStateOf<WordDetail?>(null) }
    var lookupChoices by remember { mutableStateOf(emptyList<WordCard>()) }
    var wordFormIndex by remember(repo) { mutableStateOf<Map<String,List<WordCard>>?>(null) }
    var lookupIsStudied by remember { mutableStateOf(false) }
    var lookupAlreadyAdded by remember { mutableStateOf(false) }
    suspend fun getWordFormIndex():Map<String,List<WordCard>> {
        val cached=wordFormIndex
        if(cached!=null) return cached
        val built=withContext(Dispatchers.IO) { buildWordFormIndex(repo.getWordFormSources()) }
        wordFormIndex=built
        return built
    }
    suspend fun showLookupWord(wordId:Int) {
        lookupChoices=emptyList()
        lookupWord=withContext(Dispatchers.IO) { repo.getWord(wordId) }
        lookupIsStudied=withContext(Dispatchers.IO) { dao.status(wordId)!=null }
        lookupAlreadyAdded=wordId in todayAddedWordIds
    }
    var papers by remember { mutableStateOf(emptyList<PaperCard>()) }
    val context=LocalContext.current
    val paperPrefs=remember(context){context.getSharedPreferences("last_paper_selection",android.content.Context.MODE_PRIVATE)}
    val (selectedId,selectPaper)=rememberSelectedPaperId("reading",papers,paperPrefs)
    var selectedType by remember { mutableStateOf("") }
    var readingTypes by remember { mutableStateOf(emptyList<String>()) }
    var sentences by remember { mutableStateOf(emptyList<ReadingSentence>()) }
    var questions by remember { mutableStateOf(emptyList<ReadingQuestion>()) }
    var wordBank by remember { mutableStateOf(emptyList<String>()) }
    var selectedBlankId by remember { mutableIntStateOf(-1) }
    var statuses by remember { mutableStateOf(emptyList<UserReadingStatus>()) }
    var selectedPassage by remember { mutableIntStateOf(1) }
    var sentenceIndex by remember { mutableIntStateOf(0) }
    var readerMode by remember { mutableStateOf("逐句精读") }
    var inReader by remember { mutableStateOf(false) }
    var revealed by remember(selectedId,selectedType,selectedPassage,sentenceIndex) { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(Unit) { papers=withContext(Dispatchers.IO){repo.getPapers("reading")} }
    LaunchedEffect(selectedId) { if(selectedId.isNotBlank()) { readingTypes=withContext(Dispatchers.IO){repo.getReadingTypes(selectedId)}; selectedType=readingTypes.firstOrNull().orEmpty() } }
    LaunchedEffect(selectedId,selectedType) {
        if(selectedId.isNotBlank() && selectedType.isNotBlank()) {
            val loaded=withContext(Dispatchers.IO) { Triple(repo.getReadingSentences(selectedId,selectedType),repo.getQuestions(selectedId,selectedType),dao.readingStatuses(selectedId,selectedType)) }
            sentences=loaded.first
            questions=loaded.second
            wordBank=if(selectedType=="word_bank") withContext(Dispatchers.IO){repo.getWordBank(selectedId)} else emptyList()
            statuses=loaded.third
            selectedBlankId=-1
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
            ScreenHeading("阅读精读", "逐句精读；长按任意文字范围可记笔记")
            PaperSelector(papers,selectedId,selectPaper)
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
                    if(selectedType!="word_bank") OutlinedButton(onClick={readerMode="题目答案";inReader=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Green)) { Text("查看本套阅读题目与答案") }
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
                val blankQuestions=questions.filter{it.evidenceUnitId==sentence.unitId && it.questionNo!=null}
                val selectedBlank=blankQuestions.firstOrNull{it.unitId==selectedBlankId}
                Text("第 ${sentenceIndex+1} / ${passageSentences.size} 句 · ${status?.familiarity ?: "未完成"}",fontSize=13.sp,color=Muted,modifier=Modifier.padding(bottom=8.dp))
                Card(Modifier.fillMaxWidth().weight(1f).clickable(enabled=!revealed){revealed=true},colors=CardDefaults.cardColors(containerColor=if(revealed || selectedBlank!=null) CardBg else PaleGreen),shape=RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(sentence.paragraphNo!=null && selectedType=="matching") Text("第 ${sentence.paragraphNo} 段",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Green)
                        SelectableReadingText(
                            text=sentence.text,
                            onBlankClick={number->blankQuestions.firstOrNull{it.questionNo==number}?.let{selectedBlankId=it.unitId}},
                            onNoteSelection={selection->onNote(NoteTarget("reading",sentence.unitId,"阅读摘录笔记",sourceText=selection))},
                            onWordLookup={selection ->
                                val term=Regex("[A-Za-z]+(?:['-][A-Za-z]+)*").find(selection)?.value?.lowercase()
                                lookupWord=null
                                lookupChoices=emptyList()
                                if(term.isNullOrBlank()) onWordNotFound() else scope.launch {
                                    val allMatches=getWordFormIndex()[term].orEmpty()
                                    val exact=allMatches.firstOrNull { it.lemma.equals(term,ignoreCase=true) }
                                    val matches=if(exact!=null) listOf(exact) else allMatches
                                    when(matches.size) {
                                        0 -> onWordNotFound()
                                        1 -> showLookupWord(matches.first().wordId)
                                        else -> lookupChoices=matches
                                    }
                                }
                            }
                        )
                        if(selectedType=="word_bank" && blankQuestions.isNotEmpty()) {
                            HorizontalDivider(color=PaleGreen)
                            Text("本篇可选词",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Green)
                            wordBank.chunked(5).forEach { rowWords ->
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                    rowWords.forEach { word -> Text(word,fontSize=12.sp,maxLines=1,color=Ink,modifier=Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(AppBg).padding(horizontal=7.dp,vertical=8.dp)) }
                                    repeat(5-rowWords.size){Spacer(Modifier.weight(1f))}
                                }
                            }
                        }
                        if(selectedType=="word_bank" && selectedBlank!=null) {
                            HorizontalDivider(color=PaleGreen)
                            Text("译文",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Green)
                            Text(sentence.translation.ifBlank{"暂无译文"},fontSize=15.sp,lineHeight=24.sp,color=Ink)
                            val chosenWord=selectedBlank.optionText.ifBlank{selectedBlank.answer}
                            Text("第 ${selectedBlank.questionNo} 空，正确答案：$chosenWord",fontSize=14.sp,fontWeight=FontWeight.Bold,color=DarkGreen)
                            if(selectedBlank.optionText.isNotBlank() && selectedBlank.answer.isNotBlank() && !selectedBlank.answer.equals(selectedBlank.optionText,true)) Text("答案选项：${selectedBlank.answer}",fontSize=12.sp,color=Muted)
                            if(selectedBlank.explanation.isNotBlank()) Text("为什么选这个：${cleanMarkup(selectedBlank.explanation)}",fontSize=14.sp,lineHeight=22.sp,color=Muted)
                            if(sentence.analysis.isNotBlank()) Text("句子分析：${cleanMarkup(sentence.analysis)}",fontSize=14.sp,lineHeight=22.sp,color=Muted)
                        } else if(revealed) {
                            HorizontalDivider(color=PaleGreen)
                            Text("译文",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Green)
                            Text(sentence.translation.ifBlank{"暂无译文"},fontSize=15.sp,lineHeight=24.sp,color=Ink)
                            Text("怎么看",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Green)
                            Text(cleanMarkup(sentence.analysis).ifBlank{"暂无结构分析"},fontSize=14.sp,lineHeight=22.sp,color=Muted)
                        } else Text(if(selectedType=="word_bank") "点击空格查看该空答案；没有空格时点句子或卡片空白处查看译文。长按文字范围可记笔记" else "点句子查看译文和分析；长按文字范围可记笔记",fontSize=13.sp,color=Muted)
                    }
                }
                if(revealed || selectedBlank!=null) {
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
                if(q.options.isNotEmpty()) {
                    Text("选项",color=Green,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                    q.options.forEach { option -> Text("${option.label}. ${option.text}",color=if(option.label==q.answer) DarkGreen else Muted,fontSize=13.sp,lineHeight=19.sp,fontWeight=if(option.label==q.answer) FontWeight.SemiBold else FontWeight.Normal) }
                }
                Text("正确答案：${q.answer.ifBlank{"待核对"}}${if(q.optionText.isNotBlank()) ". ${q.optionText}" else ""}",color=DarkGreen,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                if(q.explanation.isNotBlank()) {
                    Text("为什么选这个",color=Green,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                    Text(cleanMarkup(q.explanation),color=Muted,fontSize=13.sp,lineHeight=19.sp)
                }
                if(q.evidence.isNotBlank()) {
                    Text("原文依据",color=Green,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                    Text(q.evidence,color=Muted,fontSize=13.sp,lineHeight=19.sp)
                }
              } } }
            }
            }
        }
    }
    if(lookupChoices.isNotEmpty()) AlertDialog(
        onDismissRequest={lookupChoices=emptyList()},
        title={Text("找到多个词条，请选择",color=DarkGreen)},
        text={
            Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                lookupChoices.forEach { choice ->
                    TextButton(onClick={scope.launch { showLookupWord(choice.wordId) }},modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(choice.lemma,fontWeight=FontWeight.SemiBold,color=DarkGreen)
                            Text(choice.meanings.replace(",","；"),fontSize=12.sp,color=Muted)
                        }
                    }
                }
            }
        },
        confirmButton={TextButton(onClick={lookupChoices=emptyList()}){Text("关闭",color=Green)}}
    )
    lookupWord?.let { word ->
        AlertDialog(
            onDismissRequest={lookupWord=null},
            title={Text("${word.lemma} · 第${word.rank}位",color=DarkGreen)},
            text={
                Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("${word.level} · 真题出现 ${word.totalCount} 次 · 覆盖 ${word.paperCount} 套",fontSize=12.sp,color=Muted)
                    word.senses.forEach { sense ->
                        Text("${sense.pos}  ${sense.meaning}",fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=Ink)
                        sense.examples.forEach { example ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AppBg).padding(9.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text(example.english,fontSize=13.sp,lineHeight=18.sp,color=Ink)
                                if(example.chinese.isNotBlank()) Text(example.chinese,fontSize=11.sp,lineHeight=16.sp,color=Muted)
                            }
                        }
                    }
                    if(word.senses.sumOf { it.examples.size }==0) Text("暂无默认例句",fontSize=12.sp,color=Muted)
                }
            },
            confirmButton={
                if(lookupIsStudied) TextButton(onClick={lookupWord=null}) { Text("已学过",color=Muted) }
                else if(lookupAlreadyAdded) TextButton(onClick={lookupWord=null}) { Text("已加入今天",color=Green) }
                else TextButton(
                    onClick={onAddToToday(word.wordId);lookupAlreadyAdded=true}
                ) { Text("加入今天学习",color=Green) }
            },
            dismissButton={TextButton(onClick={lookupWord=null}) { Text("关闭") }}
        )
    }
}

private fun buildWordFormIndex(sources:List<WordFormSource>):Map<String,List<WordCard>> {
    val byLemma=sources.associateBy { it.word.lemma.lowercase() }
    val forms=linkedMapOf<String,LinkedHashSet<WordCard>>()
    fun register(form:String,word:WordCard) {
        if(form.length>=2) forms.getOrPut(form.lowercase()) { linkedSetOf() }.add(word)
    }
    fun consonantY(word:String)=word.length>1 && word.endsWith("y") && word[word.lastIndex-1].lowercaseChar() !in "aeiou"
    fun shouldDouble(word:String):Boolean {
        if(word.length<3 || word.last() in "wxy") return false
        val tail=word.takeLast(3)
        val vowelGroups=Regex("[aeiouy]+").findAll(word).count()
        return vowelGroups==1 && tail[0].lowercaseChar() !in "aeiou" &&
            tail[1].lowercaseChar() in "aeiou" && tail[2].lowercaseChar() !in "aeiou"
    }

    val irregular=mapOf(
        "be" to listOf("am","is","are","was","were","been"),
        "go" to listOf("goes","went","gone"),
        "do" to listOf("does","did","done"),
        "have" to listOf("has","had"),
        "say" to listOf("says","said"),
        "make" to listOf("made"),
        "take" to listOf("takes","took","taken"),
        "come" to listOf("comes","came"),
        "get" to listOf("gets","got","gotten"),
        "give" to listOf("gives","gave","given"),
        "know" to listOf("knows","knew","known"),
        "think" to listOf("thinks","thought"),
        "see" to listOf("sees","saw","seen"),
        "find" to listOf("finds","found"),
        "tell" to listOf("tells","told"),
        "become" to listOf("becomes","became"),
        "begin" to listOf("begins","began","begun"),
        "bring" to listOf("brings","brought"),
        "buy" to listOf("buys","bought"),
        "build" to listOf("builds","built"),
        "choose" to listOf("chooses","chose","chosen"),
        "feel" to listOf("feels","felt"),
        "leave" to listOf("leaves","left"),
        "keep" to listOf("keeps","kept"),
        "hold" to listOf("holds","held"),
        "hear" to listOf("hears","heard"),
        "run" to listOf("runs","ran"),
        "write" to listOf("writes","wrote","written"),
        "speak" to listOf("speaks","spoke","spoken"),
        "break" to listOf("breaks","broke","broken"),
        "drive" to listOf("drives","drove","driven"),
        "eat" to listOf("eats","ate","eaten"),
        "fall" to listOf("falls","fell","fallen"),
        "grow" to listOf("grows","grew","grown"),
        "draw" to listOf("draws","drew","drawn"),
        "fly" to listOf("flies","flew","flown"),
        "wear" to listOf("wears","wore","worn"),
        "throw" to listOf("throws","threw","thrown"),
        "stand" to listOf("stands","stood"),
        "understand" to listOf("understands","understood"),
        "win" to listOf("wins","won"),
        "lose" to listOf("loses","lost"),
        "pay" to listOf("pays","paid"),
        "meet" to listOf("meets","met"),
        "sit" to listOf("sits","sat"),
        "sleep" to listOf("sleeps","slept"),
        "lead" to listOf("leads","led"),
        "teach" to listOf("teaches","taught"),
        "catch" to listOf("catches","caught"),
        "fight" to listOf("fights","fought"),
        "seek" to listOf("seeks","sought"),
        "sell" to listOf("sells","sold"),
        "send" to listOf("sends","sent"),
        "deal" to listOf("deals","dealt"),
        "mean" to listOf("means","meant"),
        "hang" to listOf("hangs","hung"),
        "shake" to listOf("shakes","shook","shaken"),
        "sing" to listOf("sings","sang","sung"),
        "swim" to listOf("swims","swam","swum"),
        "drink" to listOf("drinks","drank","drunk"),
        "ring" to listOf("rings","rang","rung"),
        "rise" to listOf("rises","rose","risen"),
        "steal" to listOf("steals","stole","stolen"),
        "shine" to listOf("shines","shone"),
        "lay" to listOf("lays","laid"),
        "lie" to listOf("lies","lay","lain"),
        "child" to listOf("children"),
        "leaf" to listOf("leaves"),
        "life" to listOf("lives"),
        "knife" to listOf("knives"),
        "wife" to listOf("wives"),
        "shelf" to listOf("shelves"),
        "self" to listOf("selves"),
        "half" to listOf("halves"),
        "wolf" to listOf("wolves"),
        "loaf" to listOf("loaves"),
        "axis" to listOf("axes"),
        "basis" to listOf("bases"),
        "diagnosis" to listOf("diagnoses"),
        "oasis" to listOf("oases"),
        "person" to listOf("people"),
        "man" to listOf("men"),
        "woman" to listOf("women"),
        "foot" to listOf("feet"),
        "tooth" to listOf("teeth"),
        "mouse" to listOf("mice"),
        "goose" to listOf("geese"),
        "analysis" to listOf("analyses"),
        "crisis" to listOf("crises"),
        "thesis" to listOf("theses"),
        "criterion" to listOf("criteria"),
        "phenomenon" to listOf("phenomena")
    )

    for(source in sources) {
        val word=source.word
        val lemma=word.lemma.lowercase()
        register(lemma,word)
        val pos=source.partsOfSpeech.lowercase()
        val tags=pos.split(',', '/', ';').map { it.trim() }
        val hasNoun=tags.any { it.startsWith("n") || it.contains("noun") || it.contains("名词") }
        val hasVerb=tags.any { it.startsWith("v") || it.contains("verb") || it.contains("动词") }
        val makeNounForms=hasNoun || (!hasNoun && !hasVerb)
        val makeVerbForms=hasVerb || (!hasNoun && !hasVerb)

        if(makeNounForms) {
            val plural=when {
                consonantY(lemma) -> lemma.dropLast(1)+"ies"
                lemma.endsWith("s") || lemma.endsWith("x") || lemma.endsWith("z") ||
                    lemma.endsWith("ch") || lemma.endsWith("sh") -> lemma+"es"
                else -> lemma+"s"
            }
            register(plural,word)
            if(lemma.endsWith("o")) register(lemma+"es",word)
        }
        if(makeVerbForms) {
            val thirdPerson=when {
                consonantY(lemma) -> lemma.dropLast(1)+"ies"
                lemma.endsWith("s") || lemma.endsWith("x") || lemma.endsWith("z") ||
                    lemma.endsWith("ch") || lemma.endsWith("sh") || lemma.endsWith("o") -> lemma+"es"
                else -> lemma+"s"
            }
            val past=when {
                lemma.endsWith("e") -> lemma+"d"
                consonantY(lemma) -> lemma.dropLast(1)+"ied"
                shouldDouble(lemma) -> lemma+lemma.last()+"ed"
                else -> lemma+"ed"
            }
            val participle=when {
                lemma.endsWith("ie") -> lemma.dropLast(2)+"ying"
                lemma.endsWith("e") && !lemma.endsWith("ee") && !lemma.endsWith("ye") && !lemma.endsWith("oe") -> lemma.dropLast(1)+"ing"
                shouldDouble(lemma) -> lemma+lemma.last()+"ing"
                else -> lemma+"ing"
            }
            register(thirdPerson,word)
            register(past,word)
            register(participle,word)
        }
    }
    for((lemma,inflections) in irregular) {
        val source=byLemma[lemma] ?: continue
        inflections.forEach { register(it,source.word) }
    }
    return forms.mapValues { it.value.toList() }
}

/** Indexes regular and common irregular forms back to existing corpus lemmas. */
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
private fun SelectableNoteText(value:androidx.compose.ui.text.AnnotatedString,type:String,itemId:Int,title:String,onNote:(NoteTarget)->Unit,userHighlightRanges:List<IntRange> = emptyList(),onHighlightSelection:((Int,Int)->Unit)? = null,onWordLookup:((String)->Unit)? = null) {
    SelectableReadingText(value.text, {}, { selection -> onNote(NoteTarget(type, itemId, title, sourceText = selection)) }, value.spanStyles.map { it.start until it.end } + userHighlightRanges, onHighlightSelection,onWordLookup)
}

@Composable
private fun SelectableReadingText(text:String,onBlankClick:(Int)->Unit,onNoteSelection:(String)->Unit,highlightRanges:List<IntRange> = emptyList(),onHighlightSelection:((Int,Int)->Unit)? = null,onWordLookup:((String)->Unit)? = null,showNoteAction:Boolean = true) {
    val currentBlankClick by rememberUpdatedState(onBlankClick)
    val currentNoteSelection by rememberUpdatedState(onNoteSelection)
    val currentHighlightSelection = rememberUpdatedState(onHighlightSelection)
    val currentWordLookup = rememberUpdatedState(onWordLookup)
    val currentText by rememberUpdatedState(text)
    val styleKey = text to highlightRanges
    AndroidView(
        modifier=Modifier.fillMaxWidth(),
        factory={context->
            TextView(context).apply {
                setTextColor(android.graphics.Color.rgb(36,53,46))
                textSize=15f
                setLineSpacing(0f,1.4f)
                setTextIsSelectable(true)
                movementMethod=LinkMovementMethod.getInstance()
                customSelectionActionModeCallback=object:ActionMode.Callback {
                    override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean {
                        if(showNoteAction) menu.add(Menu.NONE,1,Menu.NONE,"记笔记")
                        if(currentHighlightSelection.value!=null) menu.add(Menu.NONE,2,Menu.NONE,"标黄")
                        if(currentWordLookup.value!=null) menu.add(Menu.NONE,3,Menu.NONE,"查单词")
                        return true
                    }
                    override fun onPrepareActionMode(mode:ActionMode,menu:Menu)=false
                    override fun onActionItemClicked(mode:ActionMode,item:MenuItem):Boolean {
                        if(item.itemId !in 1..3 || (item.itemId==1 && !showNoteAction)) return false
                        val selectedText=currentText
                        val start=minOf(selectionStart,selectionEnd).coerceIn(0,selectedText.length)
                        val end=maxOf(selectionStart,selectionEnd).coerceIn(start,selectedText.length)
                        if(end>start) {
                            when(item.itemId) {
                                1 -> currentNoteSelection(selectedText.substring(start,end))
                                2 -> currentHighlightSelection.value?.invoke(start,end)
                                3 -> currentWordLookup.value?.invoke(selectedText.substring(start,end))
                            }
                        }
                        mode.finish()
                        return true
                    }
                    override fun onDestroyActionMode(mode:ActionMode) {}
                }
            }
        },
        update={view->
            val styled=SpannableString(text)
            highlightRanges.forEach { range ->
                if(range.first>=0 && range.last+1<=text.length) styled.setSpan(android.text.style.BackgroundColorSpan(android.graphics.Color.rgb(255,244,201)),range.first,range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            Regex("_{2,}\\s*(\\d+)\\s*_{2,}").findAll(text).forEach { match->
                val number=match.groupValues[1].toIntOrNull() ?: return@forEach
                styled.setSpan(object:ClickableSpan() {
                    override fun onClick(widget:View) { currentBlankClick(number) }
                    override fun updateDrawState(ds:TextPaint) {
                        ds.color=android.graphics.Color.rgb(70,138,112)
                        ds.isUnderlineText=false
                        ds.bgColor=android.graphics.Color.rgb(255,243,217)
                    }
                },match.range.first,match.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if(view.text.toString()!=text || view.tag!=styleKey) {
                view.text=styled
                view.tag=styleKey
            }
        }
    )
}

private fun typeLabel(type:String)=when(type){"close_reading"->"仔细阅读";"matching"->"长篇匹配";"word_bank"->"选词填空";else->type}
private fun cleanMarkup(text:String)=text.replace("`","")

private fun highlightedText(text:String,json:String,sourceText:String=text):androidx.compose.ui.text.AnnotatedString {
    val ranges=runCatching {
        val array=org.json.JSONArray(json)
        buildList {
            for(i in 0 until array.length()) {
                val obj=array.optJSONObject(i) ?: continue
                val start=obj.optInt("start",-1); val end=obj.optInt("end",-1)
                if(start<0 || end<=start || end>sourceText.length) continue
                if(text==sourceText) {
                    if(end<=text.length) add(start to end)
                } else {
                    val selected=sourceText.substring(start,end)
                    val candidates=generateSequence(text.indexOf(selected)) { previous ->
                        val next=text.indexOf(selected,previous+1)
                        if(next>=0) next else null
                    }.toList()
                    val mapped=candidates.minByOrNull { kotlin.math.abs(it-start) }
                    if(mapped!=null) add(mapped to (mapped+selected.length))
                }
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
private fun TranslationScreen(repo:ContentDb,onNote:(NoteTarget)->Unit,onWordLookup:(String)->Unit) {
    val context=LocalContext.current
    val paperPrefs=remember(context){context.getSharedPreferences("last_paper_selection",android.content.Context.MODE_PRIVATE)}
    var papers by remember { mutableStateOf(emptyList<PaperCard>()) };val (selectedId,selectPaper)=rememberSelectedPaperId("translation",papers,paperPrefs);var rows by remember { mutableStateOf(emptyList<TranslationItem>()) }
    var index by remember(selectedId) { mutableIntStateOf(0) }
    var revealed by remember(selectedId,index) { mutableStateOf(false) }
    LaunchedEffect(Unit){papers=withContext(Dispatchers.IO){repo.getPapers("translation")}}
    LaunchedEffect(selectedId){if(selectedId.isNotBlank()){rows=withContext(Dispatchers.IO){repo.getTranslations(selectedId)};index=0}}
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        ScreenHeading("翻译训练","先自己翻译，点卡片后查看参考译文和优化译文")
        PaperSelector(papers,selectedId,selectPaper)
        val item=rows.getOrNull(index)
        if(item!=null) {
            Text("第 ${index+1} / ${rows.size} 句",fontSize=13.sp,color=Muted,modifier=Modifier.padding(top=12.dp,bottom=8.dp))
            Card(Modifier.fillMaxWidth().weight(1f).clickable(enabled=!revealed){revealed=true},colors=CardDefaults.cardColors(containerColor=PaleBlue),shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("先在心里翻译",fontSize=12.sp,color=Green,fontWeight=FontWeight.Bold)
                    SelectableNoteText(androidx.compose.ui.text.AnnotatedString(item.chinese),"translation",item.unitId,"翻译笔记 · 第 ${item.orderNo} 句",onNote,onWordLookup=onWordLookup)
                    if(revealed) {
                        HorizontalDivider(color=Color.White.copy(alpha=.8f))
                        if(item.reference.isNotBlank()) { Text("参考译文",fontSize=12.sp,color=Green,fontWeight=FontWeight.Bold); SelectableNoteText(androidx.compose.ui.text.AnnotatedString(item.reference),"translation",item.unitId,"翻译笔记 · 第 ${item.orderNo} 句",onNote,onWordLookup=onWordLookup) }
                        if(item.optimized.isNotBlank()) { Text("优化译文",fontSize=12.sp,color=Green,fontWeight=FontWeight.Bold); SelectableNoteText(androidx.compose.ui.text.AnnotatedString(item.optimized),"translation",item.unitId,"翻译笔记 · 第 ${item.orderNo} 句",onNote,onWordLookup=onWordLookup) }
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
private fun WritingScreen(repo:ContentDb,dao:UserDao,onNote:(NoteTarget)->Unit,onWordLookup:(String)->Unit) {
    val context=LocalContext.current
    val paperPrefs=remember(context){context.getSharedPreferences("last_paper_selection",android.content.Context.MODE_PRIVATE)}
    var papers by remember { mutableStateOf(emptyList<PaperCard>()) }
    var excellentPaperIds by remember { mutableStateOf(emptySet<String>()) }
    val (selectedId,selectPaper)=rememberSelectedPaperId("writing",papers,paperPrefs)
    var item by remember { mutableStateOf<WritingItem?>(null) }
    var userHighlights by remember { mutableStateOf<Map<Long,List<UserWritingHighlight>>>(emptyMap()) }
    var userTextOverrides by remember { mutableStateOf<Map<Long,String>>(emptyMap()) }
    var editingSampleId by remember { mutableStateOf<Long?>(null) }
    var editingText by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()

    LaunchedEffect(Unit) {
        papers=withContext(Dispatchers.IO){repo.getPapers("writing")}
        excellentPaperIds=withContext(Dispatchers.IO){repo.getExcellentWritingPaperIds()}
    }
    LaunchedEffect(selectedId) {
        if(selectedId.isNotBlank()) item=withContext(Dispatchers.IO){repo.getWriting(selectedId)}
    }
    LaunchedEffect(item?.samples) {
        val samples=item?.samples.orEmpty()
        val loaded=withContext(Dispatchers.IO) {
            samples.associate { sample ->
                sample.sampleId to (dao.writingText(sample.sampleId)?.textOverride ?: sample.text)
            }
        }
        userTextOverrides=loaded
        userHighlights=withContext(Dispatchers.IO) {
            samples.associate { sample -> sample.sampleId to dao.writingHighlights(sample.sampleId) }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
        ScreenHeading("作文积累","长按选择好词好句，可记笔记或标黄；点“编辑换行”可自行调整段落")
        PaperSelector(papers,selectedId,selectPaper,excellentPaperIds)
        val current=item
        if(current!=null) LazyColumn(contentPadding=PaddingValues(top=10.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors=CardDefaults.cardColors(containerColor=PaleGreen),shape=RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("写作题目",color=Green,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                        SelectableNoteText(androidx.compose.ui.text.AnnotatedString(current.prompt),"writing",current.promptUnitId,"作文题目笔记",onNote,onWordLookup=onWordLookup)
                    }
                }
            }
            items(current.samples) { sample ->
                val label=if(sample.type=="excellent") "优秀范文" else "参考范文"
                val displayText=userTextOverrides[sample.sampleId] ?: sample.text
                val savedHighlights=userHighlights[sample.sampleId].orEmpty()
                val userRanges=resolveUserHighlightRanges(displayText,savedHighlights)
                Card(colors=CardDefaults.cardColors(containerColor=CardBg),shape=RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(21.dp),verticalArrangement=Arrangement.spacedBy(13.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Text(label,color=Green,fontSize=13.sp,fontWeight=FontWeight.Bold)
                            TextButton(onClick={
                                editingSampleId=sample.sampleId
                                editingText=displayText
                            }) { Text("编辑换行",color=Green) }
                        }
                        SelectableNoteText(
                            highlightedText(displayText,sample.highlightsJson,sample.text),
                            "writing",sample.sampleId.toInt(),"作文笔记 · $label",onNote,
                            userHighlightRanges=userRanges,
                            onWordLookup=onWordLookup,
                            onHighlightSelection={start,end ->
                                if(start>=0 && end>start && end<=displayText.length) {
                                    val selected=displayText.substring(start,end)
                                    val highlight=UserWritingHighlight(
                                        sampleId=sample.sampleId,
                                        startOffset=start,
                                        endOffset=end,
                                        selectedText=selected
                                    )
                                    scope.launch {
                                        withContext(Dispatchers.IO) { dao.saveWritingHighlight(highlight) }
                                        userHighlights=userHighlights + (sample.sampleId to (savedHighlights + highlight))
                                    }
                                }
                            }
                        )
                    }
                }
            }
            item { if(current.samples.isEmpty()) Text("这套范文正在补入。",color=Muted) }
        }
    }

    val sampleId=editingSampleId
    if(sampleId!=null) {
        AlertDialog(
            onDismissRequest={editingSampleId=null},
            title={Text("调整范文分段")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("可以直接在段落之间按回车换行。保存后只调整本机显示，不修改原始范文库。",fontSize=13.sp,color=Muted,lineHeight=19.sp)
                    OutlinedTextField(
                        value=editingText,
                        onValueChange={editingText=it},
                        modifier=Modifier.fillMaxWidth().heightIn(min=180.dp,max=360.dp),
                        minLines=8,
                        maxLines=18,
                        shape=RoundedCornerShape(14.dp),
                        colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Green)
                    )
                }
            },
            confirmButton={
                TextButton(onClick={
                    val textToSave=editingText
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            dao.saveWritingText(UserWritingText(sampleId=sampleId,textOverride=textToSave))
                        }
                        userTextOverrides=userTextOverrides + (sampleId to textToSave)
                        editingSampleId=null
                    }
                }) { Text("保存",color=Green) }
            },
            dismissButton={TextButton(onClick={editingSampleId=null}){Text("取消")}}
        )
    }
}

private fun resolveUserHighlightRanges(text:String,highlights:List<UserWritingHighlight>):List<IntRange> =
    highlights.mapNotNull { highlight ->
        val selected=highlight.selectedText
        if(selected.isEmpty()) return@mapNotNull null
        val storedStart=highlight.startOffset
        val directMatches=storedStart>=0 && storedStart+selected.length<=text.length &&
            text.substring(storedStart,storedStart+selected.length)==selected
        val start=if(directMatches) storedStart else {
            val candidates=generateSequence(text.indexOf(selected)) { previous ->
                val next=text.indexOf(selected,previous+1)
                if(next>=0) next else null
            }.toList()
            candidates.minByOrNull { kotlin.math.abs(it-storedStart) } ?: -1
        }
        if(start>=0) start until (start+selected.length) else null
    }
@Composable
private fun AllNotesScreen(dao: UserDao, refreshToken: Int, onBack: () -> Unit, onOpenNote: (UserNote) -> Unit) {
    var notes by remember { mutableStateOf(emptyList<UserNote>()) }
    var highlights by remember { mutableStateOf(emptyMap<Long, List<UserNoteHighlight>>()) }
    LaunchedEffect(refreshToken) {
        val loaded = withContext(Dispatchers.IO) { dao.allNotes() to dao.allNoteHighlights() }
        notes = loaded.first
        highlights = loaded.second.groupBy { it.noteId }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = Green) }
            Text("全部笔记", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = DarkGreen)
        }
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有保存的笔记", color = Muted, fontSize = 15.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notes, key = { it.noteId }) { note ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenNote(note) },
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(noteTypeLabel(note.itemType), color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (note.sourceText.isNotBlank()) {
                                Text(note.sourceText, color = Muted, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(highlightedNoteContent(note.content, highlights[note.noteId].orEmpty()), color = Ink, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text("点击查看或继续编辑", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun noteTypeLabel(type: String): String = when (type) {
    "reading" -> "阅读笔记"
    "translation" -> "翻译笔记"
    "writing" -> "作文笔记"
    "word" -> "单词笔记"
    else -> "学习笔记"
}

private fun resolveNoteHighlightRanges(text: String, highlights: List<UserNoteHighlight>): List<IntRange> =
    highlights.mapNotNull { highlight ->
        val selected = highlight.selectedText
        if (selected.isEmpty()) return@mapNotNull null
        val storedStart = highlight.startOffset
        val directMatch = storedStart >= 0 && storedStart + selected.length <= text.length &&
            text.substring(storedStart, storedStart + selected.length) == selected
        val start = if (directMatch) storedStart else {
            val candidates = generateSequence(text.indexOf(selected)) { previous ->
                val next = text.indexOf(selected, previous + 1)
                if (next >= 0) next else null
            }.toList()
            candidates.minByOrNull { kotlin.math.abs(it - storedStart) } ?: -1
        }
        if (start >= 0 && start + selected.length <= text.length) start until (start + selected.length) else null
    }

private fun highlightedNoteContent(text: String, highlights: List<UserNoteHighlight>) = buildAnnotatedString {
    append(text)
    resolveNoteHighlightRanges(text, highlights).forEach { range ->
        addStyle(SpanStyle(background = PaleYellow), range.first, range.last + 1)
    }
}

@Composable
private fun NotesScreen(target: NoteTarget, dao: UserDao, onBack: () -> Unit, onSaved: () -> Unit) {
    var value by remember(target) { mutableStateOf(TextFieldValue("")) }
    var loaded by remember(target) { mutableStateOf(false) }
    var current by remember(target) { mutableStateOf<UserNote?>(null) }
    var highlights by remember(target) { mutableStateOf(emptyList<UserNoteHighlight>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target) {
        current = withContext(Dispatchers.IO) {
            target.existingNoteId?.let { dao.noteById(it) }
                ?: dao.notes(target.type, target.id).firstOrNull { it.sourceText == target.sourceText }
        }
        value = TextFieldValue(current?.content.orEmpty())
        highlights = current?.let { withContext(Dispatchers.IO) { dao.noteHighlights(it.noteId) } }.orEmpty()
        loaded = true
    }
    val visualTransformation = remember(highlights) {
        VisualTransformation { input ->
            TransformedText(highlightedNoteContent(input.text, highlights), OffsetMapping.Identity)
        }
    }
    val selectionStart = minOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    val selectionEnd = maxOf(value.selection.start, value.selection.end).coerceIn(0, value.text.length)
    Column(
        Modifier.fillMaxSize().background(AppBg).imePadding().padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        TopAppBar(
            title = { Text("学习笔记") },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回", color = Green) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBg)
        )
        Text(target.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
        if (target.sourceText.isNotBlank()) {
            Card(
                modifier = Modifier.heightIn(max = 118.dp),
                colors = CardDefaults.cardColors(containerColor = PaleYellow),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    target.sourceText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = Ink,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                )
            }
        }
        if (selectionEnd > selectionStart) {
            TextButton(
                onClick = {
                    val selected = value.text.substring(selectionStart, selectionEnd)
                    if (selected.isNotBlank() && highlights.none { it.startOffset == selectionStart && it.endOffset == selectionEnd && it.selectedText == selected }) {
                        highlights = highlights + UserNoteHighlight(
                            noteId = current?.noteId ?: 0L,
                            startOffset = selectionStart,
                            endOffset = selectionEnd,
                            selectedText = selected
                        )
                    }
                    value = value.copy(selection = TextRange(selectionEnd))
                },
                enabled = loaded
            ) { Text("标黄选中内容", color = Green) }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
            placeholder = { Text("写下释义、表达用法或自己的理解；选中文字后可标黄") },
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green),
            visualTransformation = visualTransformation
        )
        Button(
            onClick = {
                scope.launch {
                    val now = System.currentTimeMillis()
                    val old = current
                    val draft = old?.copy(content = value.text, updatedAt = now)
                        ?: UserNote(itemType = target.type, itemId = target.id, sourceText = target.sourceText, content = value.text, updatedAt = now, reviewStage = -1, nextReview = now + 24L * 60L * 60L * 1000L)
                    val savedId = withContext(Dispatchers.IO) { dao.saveNote(draft) }
                    val savedHighlights = highlights.map { it.copy(highlightId = 0, noteId = savedId) }
                    withContext(Dispatchers.IO) { dao.replaceNoteHighlights(savedId, savedHighlights) }
                    current = draft.copy(noteId = savedId)
                    onSaved()
                    onBack()
                }
            },
            enabled = loaded && value.text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            shape = RoundedCornerShape(17.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)
        ) { Text("保存并返回") }
    }
}

@Composable private fun ScreenHeading(title:String,subtitle:String){Column(Modifier.padding(top=22.dp,bottom=16.dp)){Text(title,fontSize=24.sp,fontWeight=FontWeight.Bold,color=Ink);Text(subtitle,fontSize=14.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(top=6.dp))}}

@Composable
private fun rememberSelectedPaperId(module: String, papers: List<PaperCard>, prefs: android.content.SharedPreferences): Pair<String, (String) -> Unit> {
    var selectedId by remember(module) { mutableStateOf("") }
    LaunchedEffect(module, papers) {
        val saved = prefs.getString("paper_$module", null)
        selectedId = papers.firstOrNull { it.paperId == saved }?.paperId
            ?: papers.firstOrNull()?.paperId.orEmpty()
        if (selectedId.isNotBlank()) prefs.edit().putString("paper_$module", selectedId).apply()
    }
    val selectPaper: (String) -> Unit = { paperId ->
        selectedId = paperId
        prefs.edit().putString("paper_$module", paperId).apply()
    }
    return selectedId to selectPaper
}

@Composable
private fun PaperSelector(papers:List<PaperCard>,selectedId:String,onSelect:(String)->Unit,highlightedPaperIds:Set<String> = emptySet()) {
    var expanded by remember { mutableStateOf(false) }
    val current=papers.firstOrNull{it.paperId==selectedId}
    Box {
        OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.outlinedButtonColors(contentColor=Ink)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(current?.let { it.title + if(it.paperId in highlightedPaperIds) " · 有优秀范文" else "" } ?: "选择试卷",maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("⌄",color=Green)
            }
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=420.dp).background(Color.White)) {
            papers.forEach{paper->
                DropdownMenuItem(
                    text={
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(paper.title)
                            if(paper.paperId in highlightedPaperIds) Text("有优秀范文",fontSize=11.sp,color=Green,modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(PaleGreen).padding(horizontal=7.dp,vertical=3.dp))
                        }
                    },
                    onClick={onSelect(paper.paperId);expanded=false}
                )
            }
        }
    }
}
