package com.luyuan.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.luyuan.data.Contact
import com.luyuan.data.ContactRepository
import com.luyuan.data.Course
import com.luyuan.data.Expense
import com.luyuan.data.NoteRepository
import com.luyuan.data.V2EntityRepository
import com.luyuan.domain.Note
import com.luyuan.platform.JournalReminder
import com.luyuan.platform.ReminderScheduler
import com.luyuan.platform.TodayWidgetProvider
import com.luyuan.platform.StorageLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class LuyuanViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    /** 今天的日记（日记页编辑/语音归档用；主列表不含日记） */
    private val _todayDiary = MutableStateFlow<Note?>(null)
    val todayDiary: StateFlow<Note?> = _todayDiary

    /** 全部日记（含今天，新→旧）：日记页「之前的日记」+ 连续天数共用 */
    private val _diaries = MutableStateFlow<List<Note>>(emptyList())
    val diaries: StateFlow<List<Note>> = _diaries

    /** 联系人（contacts/ 目录，与 PC 端人脉页互通） */
    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts

    /** SYNC_FORMAT v2 实体（记账/课程页，B1 只读） */
    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    val expenses: StateFlow<List<Expense>> = _expenses
    private val _courses = MutableStateFlow<List<Course>>(emptyList())
    val courses: StateFlow<List<Course>> = _courses

    /** 课务（PC）打包参考数据（vc85）：null=还没同步到（学业页显示等待提示） */
    private val _keiwuEvents = MutableStateFlow<com.luyuan.data.KeiwuEventsBundle?>(null)
    val keiwuEvents: StateFlow<com.luyuan.data.KeiwuEventsBundle?> = _keiwuEvents
    private val _keiwuGrades = MutableStateFlow<com.luyuan.data.KeiwuGradesBundle?>(null)
    val keiwuGrades: StateFlow<com.luyuan.data.KeiwuGradesBundle?> = _keiwuGrades
    private val _keiwuLedger = MutableStateFlow<com.luyuan.data.KeiwuLedgerBundle?>(null)
    val keiwuLedger: StateFlow<com.luyuan.data.KeiwuLedgerBundle?> = _keiwuLedger
    private val _keiwuRef = MutableStateFlow<com.luyuan.data.KeiwuRefBundle?>(null)
    val keiwuRef: StateFlow<com.luyuan.data.KeiwuRefBundle?> = _keiwuRef

    /** 只重读课务打包件（PC 课务导出 → Syncthing 到货后任意一路都能触发） */
    private fun loadKeiwu() {
        _keiwuEvents.value = com.luyuan.data.KeiwuStore.events(ctx)
        _keiwuGrades.value = com.luyuan.data.KeiwuStore.grades(ctx)
        _keiwuLedger.value = com.luyuan.data.KeiwuStore.ledger(ctx)
        _keiwuRef.value = com.luyuan.data.KeiwuStore.ref(ctx)
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** 每完成一次刷新 +1：下拉刷新的指示器靠它收回（refreshing 布尔会被撞帧合并吞掉，代际不会） */
    private val _refreshDone = MutableStateFlow(0)
    val refreshDone: StateFlow<Int> = _refreshDone

    /** 主页输入框聚焦请求（代际计数）：桌面小部件「记一笔」直达用 */
    private val _focusDraft = MutableStateFlow(0)
    val focusDraft: StateFlow<Int> = _focusDraft

    /** 全局搜索词（悬浮胶囊搜索态写入，笔记列表实时过滤；退出搜索态清空） */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    /** 笔记页多选模式（路河拍板：多选时收起悬浮胶囊，防遮挡批量操作区） */
    private val _multiSelect = MutableStateFlow(false)
    val multiSelect: StateFlow<Boolean> = _multiSelect

    fun setMultiSelect(on: Boolean) {
        _multiSelect.value = on
    }

    fun requestDraftFocus() {
        _focusDraft.value += 1
    }

    private val _trash = MutableStateFlow<List<Note>>(emptyList())
    val trash: StateFlow<List<Note>> = _trash

    /** 心情时间线开关（本地偏好记忆，默认开） */
    private val moodPrefs = ctx.getSharedPreferences("luyuan_prefs", Context.MODE_PRIVATE)
    private val _moodEnabled = MutableStateFlow(moodPrefs.getBoolean("mood_enabled", true))
    val moodEnabled: StateFlow<Boolean> = _moodEnabled

    // ---------- 日记提醒（负一屏） ----------

    private val _journalEnabled = MutableStateFlow(JournalReminder.isEnabled(ctx))
    val journalEnabled: StateFlow<Boolean> = _journalEnabled

    private val _journalTime = MutableStateFlow(JournalReminder.time(ctx))
    val journalTime: StateFlow<Pair<Int, Int>> = _journalTime

    // ---------- 语音（系统识别为主，键盘兜底） ----------

    private val _liveText = MutableStateFlow("")
    val liveText: StateFlow<String> = _liveText

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    /** 本次是否归入今天的日记 */
    private val _diaryMode = MutableStateFlow(false)
    val diaryMode: StateFlow<Boolean> = _diaryMode

    /** 识别出错信息（null=无错）；"unavailable" 表示系统识别用不了，应切键盘 */
    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError

    /** 最近一次成功保存的内容（用于「已记下」反馈） */
    private val _savedMsg = MutableStateFlow("")
    val savedMsg: StateFlow<String> = _savedMsg

    /**
     * Q12（路河拍板）：超级终端每次把内容存进「今天的日记」就 +1。
     * 用代际计数而非 Boolean——StateFlow 连续同值会撞帧被吞掉。
     * 主页据此弹可点回执「📔 已存入今天的日记 · 去看看」，点一下跳日记页。
     */
    private val _diaryEcho = MutableStateFlow(0)
    val diaryEcho: StateFlow<Int> = _diaryEcho

    private var speech: SpeechRecognizer? = null
    private var currentDiary = false

    /**
     * ⚠️ 防自激刷新（2026-09-10 修"只有路远卡，1.18 起"）：
     * 之前 refresh() 结束时会发广播通知桌面「今日卡」重算，而广播经广播链路又回到 VM 的 refresh()，
     * 同时 FileObserver 又把刷新产生的文件变动当成"外部变化"再触发一次 —— 形成
     * 「刷新→写盘→监听→刷新」死循环，把手机 CPU 吃满、整个 App 卡死。
     * 这里让 refresh() 自己发出的后续动作（广播/监听回调）不再回头触发 refresh。
     */
    private val selfRefreshGuard = java.util.concurrent.atomic.AtomicInteger(0)

    private inline fun suppressSelfTriggered(block: () -> Unit) {
        selfRefreshGuard.incrementAndGet()
        try {
            block()
        } finally {
            selfRefreshGuard.decrementAndGet()
        }
    }

    private fun isSelfTriggered(): Boolean = selfRefreshGuard.get() > 0

    init {
        refresh()
        startNotesWatcher()
        // 接管电脑端同步过来的提醒（含错过补弹）+ 日记提醒；开机由 BootReceiver 兜底
        viewModelScope.launch(Dispatchers.IO) {
            ReminderScheduler.rescheduleAll(ctx)
            JournalReminder.reschedule(ctx)
        }
    }

    // ---------- 共享目录监视：Syncthing 同步/PC 转写回写 → 列表自动更新（免手动刷新） ----------

    private var notesWatcher: android.os.FileObserver? = null
    private var contactsWatcher: android.os.FileObserver? = null

    /** 写盘最小间隔：Syncthing 批量同步几十个事件只刷一次，且与上次刷新至少隔 8 秒（防事件风暴） */
    @Volatile
    private var lastDiskRefreshAt = 0L
    private val DISK_REFRESH_MIN_INTERVAL_MS = 8000L

    private fun startNotesWatcher() {
        // 防抖：Syncthing 批量同步时几十个事件 → 合并成一次刷新
        val pending = java.util.concurrent.atomic.AtomicBoolean(false)
        fun scheduleRefresh() {
            // 自己刷新引起的文件变动不回头再刷（否则死循环）
            if (isSelfTriggered()) return
            // 距上次写盘刷新不足 8 秒的直接丢弃，等下一次真实变动
            val now = System.currentTimeMillis()
            if (now - lastDiskRefreshAt < DISK_REFRESH_MIN_INTERVAL_MS) return
            if (!pending.compareAndSet(false, true)) return
            lastDiskRefreshAt = now
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                }
                _notes.value = NoteRepository.listNotes(ctx)
                _todayDiary.value = NoteRepository.todayDiaryNote(ctx)
                _contacts.value = ContactRepository.listContacts(ctx)
                // vc85：课表/课务打包件也进监听刷新——PC 课务改了，手机自动跟（电脑端为准）
                _courses.value = V2EntityRepository.listCourses(ctx)
                loadKeiwu()
                pending.set(false)
            }
        }
        // 注意：FileObserver(File,int) 是 API 29+，minSdk 24 必须用路径字符串构造
        notesWatcher = object : android.os.FileObserver(
            StorageLocator.getRoot(ctx).absolutePath,
            android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.MOVED_TO
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path.endsWith(".json")) scheduleRefresh()
            }
        }.also { it.startWatching() }
        // contacts/ 子目录单独监听（FileObserver 非递归）：手机勾选/电脑改动互相同步显示
        val cdir = java.io.File(StorageLocator.getRoot(ctx), "contacts")
        if (cdir.isDirectory) {
            contactsWatcher = object : android.os.FileObserver(
                cdir.absolutePath,
                android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.MOVED_TO
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.endsWith(".json")) scheduleRefresh()
                }
            }.also { it.startWatching() }
        }
    }

    override fun onCleared() {
        notesWatcher?.stopWatching()
        contactsWatcher?.stopWatching()
        super.onCleared()
    }

    /**
     * 换共享目录后调用：重建文件监听 + 重新读盘。
     * 旧实现对已开始的 FileObserver 不会自动跟着换目录 → 换目录后新目录的变动感知不到，
     * 且列表可能仍显示旧目录内容。这里先停旧的、再按新目录重建，最后强制刷新。
     */
    fun onRootChanged() {
        try {
            notesWatcher?.stopWatching()
        } catch (_: Throwable) {
        }
        try {
            contactsWatcher?.stopWatching()
        } catch (_: Throwable) {
        }
        notesWatcher = null
        contactsWatcher = null
        lastDiskRefreshAt = 0L  // 立刻允许一次磁盘刷新（换目录是用户主动操作，不该被间隔闸挡住）
        startNotesWatcher()
        refresh()
    }

    fun refresh() {
        // ⚠️ 关键：本次刷新引起的文件写入/广播，不得再回头触发 refresh（防「刷新→写盘→监听→刷新」死循环）
        suppressSelfTriggered {
            // 先同步置位再起协程：下拉刷新的指示器靠它联动，避免竞态提前收起
            _refreshing.value = true
            viewModelScope.launch(Dispatchers.IO) {
                _notes.value = NoteRepository.listNotes(ctx)
                _todayDiary.value = NoteRepository.todayDiaryNote(ctx)
                _diaries.value = NoteRepository.listDiaries(ctx)
                _contacts.value = ContactRepository.listContacts(ctx)
                _expenses.value = V2EntityRepository.listExpenses(ctx)
                _courses.value = V2EntityRepository.listCourses(ctx)
                loadKeiwu()
                _refreshing.value = false
                _refreshDone.value += 1
                // 通知桌面「今日卡」组件重算（组件无周期刷新，靠 App 打开/数据变动主动推）
                try {
                    ctx.sendBroadcast(
                        Intent(ctx, TodayWidgetProvider::class.java).apply {
                            action = TodayWidgetProvider.ACTION_REFRESH
                        }
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 只重扫笔记/日记（写笔记类操作后调用，不碰联系人/账目/课程，避免无谓全量重扫） */
    fun refreshNotes() {
        suppressSelfTriggered {
            _refreshing.value = true
            viewModelScope.launch(Dispatchers.IO) {
                _notes.value = NoteRepository.listNotes(ctx)
                _todayDiary.value = NoteRepository.todayDiaryNote(ctx)
                _diaries.value = NoteRepository.listDiaries(ctx)
                _refreshing.value = false
                _refreshDone.value += 1
                // 通知桌面「今日卡」组件重算（组件无周期刷新，靠数据变动主动推）
                try {
                    ctx.sendBroadcast(
                        Intent(ctx, TodayWidgetProvider::class.java).apply {
                            action = TodayWidgetProvider.ACTION_REFRESH
                        }
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 只重扫 v2 实体（账目/课程/课务打包件）——写记账/加课后调用 */
    fun refreshV2() {
        suppressSelfTriggered {
            viewModelScope.launch(Dispatchers.IO) {
                _expenses.value = V2EntityRepository.listExpenses(ctx)
                _courses.value = V2EntityRepository.listCourses(ctx)
                loadKeiwu()
            }
        }
    }

    fun toggleMood() {
        val next = !_moodEnabled.value
        moodPrefs.edit().putBoolean("mood_enabled", next).apply()
        _moodEnabled.value = next
    }

    fun setJournalEnabled(on: Boolean) {
        JournalReminder.setEnabled(ctx, on)
        _journalEnabled.value = on
    }

    fun setJournalTime(hour: Int, minute: Int) {
        JournalReminder.setTime(ctx, hour, minute)
        _journalTime.value = hour to minute
    }

    fun setReminder(id: String, remindAtIso: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            NoteRepository.setReminder(ctx, id, remindAtIso)
            ReminderScheduler.cancel(ctx, id)
            ReminderScheduler.rescheduleAll(ctx)
            _notes.value = NoteRepository.listNotes(ctx)
        }
    }

    fun toggleContactTodo(contactId: String, todoId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ContactRepository.toggleTodo(ctx, contactId, todoId)
            _contacts.value = ContactRepository.listContacts(ctx)
        }
    }

    /** 待办贪睡（v1.11）：hours=null=取消提醒；写回后刷新列表并重排闹钟 */
    fun snoozeContactTodo(contactId: String, todoId: String, hours: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            ContactRepository.snoozeTodo(ctx, contactId, todoId, hours)
            ReminderScheduler.cancel(ctx, "ctodo_" + todoId)
            ReminderScheduler.rescheduleAll(ctx)
            _contacts.value = ContactRepository.listContacts(ctx)
        }
    }

    /** v2 名片页：加一件待办（回车即存），写回后刷新列表并重排闹钟 */
    fun addContactTodo(contactId: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ContactRepository.addTodo(ctx, contactId, text)
            ReminderScheduler.rescheduleAll(ctx)
            _contacts.value = ContactRepository.listContacts(ctx)
        }
    }

    /** 解析生日串为 月/日（支持 "10月20日" / "10-20" / "2026-10-20" / "10/20"） */
    private fun parseBirthdayMd(s: String): Pair<Int, Int>? {
        val m = Regex("""(\d{1,2})\s*[月/\-./]\s*(\d{1,2})""").find(s) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        if (a in 1..12 && b in 1..31) return a to b
        return null
    }

    /** v2 名片页：生日提前 3 天提醒——复用待办提醒通道（不新增联系人字段、不改 SYNC_FORMAT） */
    fun setContactBirthdayReminder(contactId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val c = ContactRepository.findContact(ctx, contactId) ?: return@launch
            val md = parseBirthdayMd(c.birthday) ?: return@launch
            val now = java.time.LocalDate.now()
            var born = java.time.LocalDate.of(now.year, md.first, md.second)
            if (!born.isAfter(now)) born = born.plusYears(1)
            val remind = born.minusDays(3).atTime(9, 0)
                .atOffset(java.time.ZoneOffset.of("+08:00")).toString()
            val exists = c.todos.any { !it.done && it.text.contains("生日") }
            if (!exists) {
                ContactRepository.addTodoWithRemind(ctx, contactId, "生日提醒：${c.birthday}", remind)
            }
            ReminderScheduler.rescheduleAll(ctx)
            _contacts.value = ContactRepository.listContacts(ctx)
        }
    }

    // ---------- 记事 / 日记 ----------

    fun addManual(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            NoteRepository.createManual(ctx, t)
            refreshNotes()
        }
    }

    fun saveDiary(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            NoteRepository.saveDiary(ctx, t)
            _diaryEcho.value = _diaryEcho.value + 1
            refreshNotes()
        }
    }

    fun saveDictation(text: String, diary: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (diary) NoteRepository.saveDiary(ctx, t) else NoteRepository.createManual(ctx, t)
            _savedMsg.value = t
            refreshNotes()
        }
    }

    fun updateNote(id: String, text: String, tags: List<String> = emptyList(), images: List<String>? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            NoteRepository.updateNote(ctx, id, text = text, tags = tags, images = images)
            refreshNotes()
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NoteRepository.softDelete(ctx, id)
            refreshNotes()
        }
    }

    /** 批量软删（多选删除，进回收站可恢复，与 PC 端语义一致） */
    fun deleteMany(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) NoteRepository.softDelete(ctx, id)
            refreshNotes()
        }
    }

    /** 一键整理（路河 09-10）：选中多条笔记 → 按时间拼成一条（打「整理」标签），原笔记软删进回收站可恢复 */
    fun mergeNotes(ids: Collection<String>) {
        if (ids.size < 2) return
        viewModelScope.launch(Dispatchers.IO) {
            val ns = ids.mapNotNull { NoteRepository.getNote(ctx, it) }
                .sortedBy { it.created_at }
            if (ns.size >= 2) {
                val merged = ns.joinToString("\n\n") { it.text }
                NoteRepository.createManual(ctx, merged, tags = listOf("整理"))
                for (n in ns) NoteRepository.softDelete(ctx, n.id)
            }
            _notes.value = NoteRepository.listNotes(ctx)
        }
    }

    fun refreshTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            _trash.value = NoteRepository.listTrash(ctx)
        }
    }

    fun restoreNote(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NoteRepository.restoreNote(ctx, id)
            _trash.value = NoteRepository.listTrash(ctx)
            _notes.value = NoteRepository.listNotes(ctx)
        }
    }

    fun purgeNote(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NoteRepository.purgeNote(ctx, id)
            _trash.value = NoteRepository.listTrash(ctx)
        }
    }

    /** 批量恢复（回收站多选；恢复后笔记列表同步刷新） */
    fun restoreNotes(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) NoteRepository.restoreNote(ctx, id)
            _trash.value = NoteRepository.listTrash(ctx)
            _notes.value = NoteRepository.listNotes(ctx)
        }
    }

    /** 批量彻底删除（物理删，调用方负责确认弹窗） */
    fun purgeNotes(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) NoteRepository.purgeNote(ctx, id)
            _trash.value = NoteRepository.listTrash(ctx)
        }
    }

    // ---------- 语音识别：系统引擎（vivo 内置讯飞系），失败自动退键盘 ----------

    fun startRecording(diary: Boolean = false) {
        if (_isRecording.value) return
        currentDiary = diary
        _diaryMode.value = diary
        _liveText.value = ""
        _voiceError.value = null
        _savedMsg.value = ""

        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        } catch (_: Exception) {
            null
        }
        if (sr == null) {
            _voiceError.value = "unavailable"
            return
        }
        speech = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: ""
                releaseRecognizer(sr)
                _isRecording.value = false
                viewModelScope.launch(Dispatchers.IO) { handleFinalText(text, currentDiary) }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let {
                    if (it.isNotBlank()) _liveText.value = it
                }
            }

            override fun onError(error: Int) {
                releaseRecognizer(sr)
                _isRecording.value = false
                _voiceError.value = when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到说话（错误码 $error）"
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再试一次（错误码 $error）"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限（错误码 $error）"
                    else -> {
                        // 本机没有可用的系统语音服务：记住，以后直接进键盘模式
                        moodPrefs.edit().putString("voice_mode", "dictation").apply()
                        "unavailable($error)"
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            sr.startListening(intent)
            _isRecording.value = true
        } catch (_: Exception) {
            releaseRecognizer(sr)
            _isRecording.value = false
            moodPrefs.edit().putString("voice_mode", "dictation").apply()
            _voiceError.value = "unavailable(start)"
        }
    }

    /** 点停止：让识别器把最后一段说完返回 */
    fun stopRecording() {
        try {
            speech?.stopListening()
        } catch (_: Exception) {
        }
    }

    /** 取消本次识别/录音（切模式时用） */
    fun cancelRecording() {
        speech?.let { releaseRecognizer(it) }
        wavRecorder?.stop()
        wavRecorder = null
        wavId = null
        _wavStartedAt.value = 0L
        try {
            ctx.stopService(Intent(ctx, com.luyuan.platform.LuyuanService::class.java))
        } catch (_: Exception) {
        }
        _isRecording.value = false
        _liveText.value = ""
    }

    private fun releaseRecognizer(sr: SpeechRecognizer) {
        try {
            sr.destroy()
        } catch (_: Exception) {
        }
        if (speech === sr) speech = null
    }

    /** 初始语音模式（auto=跟系统走 / dictation / record；本机系统识别失败过就直接录音待转写） */
    fun preferredVoiceMode(): String =
        moodPrefs.getString("voice_mode", "auto") ?: "auto"

    fun rememberVoiceMode(mode: String) {
        moodPrefs.edit().putString("voice_mode", mode).apply()
    }

    /** 给今天日记加一张图（相册/相机），压缩后放共享 images/ 目录 */
    fun addDiaryImage(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val rel = NoteRepository.importImage(ctx, uri) ?: return@launch
            val imgs = (_todayDiary.value?.images ?: emptyList()) + rel
            NoteRepository.setDiaryImages(ctx, imgs)
            refreshNotes()
        }
    }

    /** 从今天日记移除一张配图（文件保留，仅去掉引用，与 PC 端行为一致） */
    fun removeDiaryImage(rel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val imgs = (_todayDiary.value?.images ?: emptyList()) - rel
            NoteRepository.setDiaryImages(ctx, imgs)
            refreshNotes()
        }
    }

    // ---------- 贴纸（Iconify SVG：Fluent 3D / OpenMoji，下载缓存到共享 images/stickers/） ----------

    private val _stickerMsg = MutableStateFlow("")
    val stickerMsg: StateFlow<String> = _stickerMsg

    /** 下载贴纸到共享目录并作为配图插入今天日记；已缓存直接插 */
    fun insertSticker(pack: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(StorageLocator.getRoot(ctx), "images/stickers").apply { mkdirs() }
                val fname = "${pack}_${name}.svg"
                val f = java.io.File(dir, fname)
                if (!f.exists() || f.length() < 100L) {
                    val url = "https://api.iconify.design/$pack/$name.svg"
                    val data = java.net.URL(url).readBytes()
                    if (data.size < 100) throw IllegalStateException("empty svg")
                    f.writeBytes(data)
                }
                val rel = "images/stickers/$fname"
                val cur = _todayDiary.value?.images ?: emptyList()
                if (rel !in cur) {
                    NoteRepository.setDiaryImages(ctx, cur + rel)
                    refreshNotes()
                }
                _stickerMsg.value = ""
            } catch (e: Exception) {
                _stickerMsg.value = "贴纸下载失败，检查网络后重试"
            }
        }
    }

    // ---------- 录音待转写：原声 wav 经 Syncthing 回电脑，SenseVoice 转写后同步回来 ----------

    private var wavRecorder: com.luyuan.data.AudioRecorder? = null
    private var wavId: String? = null

    private val _wavStartedAt = MutableStateFlow(0L)
    val wavStartedAt: StateFlow<Long> = _wavStartedAt

    fun startWavRecording(diary: Boolean = false) {
        if (_isRecording.value) return
        currentDiary = diary
        _diaryMode.value = diary
        _liveText.value = ""
        _voiceError.value = null
        _savedMsg.value = ""
        val id = UUID.randomUUID().toString()
        wavId = id
        _wavStartedAt.value = System.currentTimeMillis()
        wavRecorder = com.luyuan.data.AudioRecorder(
            java.io.File(StorageLocator.audioDir(ctx), "$id.wav")
        ) { }
        try {
            wavRecorder?.start()
            _isRecording.value = true
            val intent = Intent(ctx, com.luyuan.platform.LuyuanService::class.java).apply {
                putExtra(com.luyuan.platform.LuyuanService.EXTRA_TEXT, "录音中…")
            }
            ctx.startForegroundService(intent)
        } catch (_: Exception) {
            wavRecorder = null
            wavId = null
            _wavStartedAt.value = 0L
            _isRecording.value = false
            _voiceError.value = "录音启动失败（检查麦克风权限）"
        }
    }

    fun stopWavRecording() {
        wavRecorder?.stop()
        wavRecorder = null
        try {
            ctx.stopService(Intent(ctx, com.luyuan.platform.LuyuanService::class.java))
        } catch (_: Exception) {
        }
        _isRecording.value = false
        _wavStartedAt.value = 0L
        val id = wavId
        val diary = currentDiary
        wavId = null
        if (id == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val now = NoteRepository.nowIso()
            NoteRepository.saveNote(
                ctx,
                Note(
                    id = id,
                    created_at = now,
                    updated_at = now,
                    text = "（语音待转写）",
                    source = "voice",
                    tags = if (diary) listOf("日记") else emptyList(),
                    device = "phone",
                    audio = "audio/$id.wav",
                    transcribed = false,
                    schema = 1
                )
            )
            _savedMsg.value = "已录音，等电脑转写"
            refreshNotes()
        }
    }

    // ---------- 离线识别（实验）：sherpa-onnx 内置小模型，无网转写；失败退回录音待转写 ----------

    private val _offlineBusy = MutableStateFlow(false)
    val offlineBusy: StateFlow<Boolean> = _offlineBusy

    /** APK 里是否打包了离线模型（决定录音页显示不显示该模式） */
    fun offlineBundled(): Boolean = com.luyuan.data.OfflineStt.bundled(ctx)

    /** 进离线模式时后台预热模型：首次识别免等加载 */
    fun warmupOffline() {
        viewModelScope.launch(Dispatchers.IO) {
            com.luyuan.data.OfflineStt.preload(ctx)
        }
    }

    /**
     * 离线模式点停止：录音先落 wav，本机转写成功 → 直接出文字落库（transcribed=true，原声保留）；
     * 识别不出 → 原样按「录音待转写」落库，电脑 SenseVoice 接手，内容永不丢。
     */
    fun stopOfflineRecording() {
        val id = wavId
        wavRecorder?.stop()
        wavRecorder = null
        try {
            ctx.stopService(Intent(ctx, com.luyuan.platform.LuyuanService::class.java))
        } catch (_: Exception) {
        }
        _isRecording.value = false
        _wavStartedAt.value = 0L
        if (id == null) return
        val diary = currentDiary
        wavId = null
        _offlineBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val now = NoteRepository.nowIso()
            val wav = java.io.File(StorageLocator.audioDir(ctx), "$id.wav")
            val text = try {
                com.luyuan.data.OfflineStt.transcribeWav(ctx, wav)
            } catch (t: Throwable) {
                // 必须兜 Throwable：JNI 库缺失/ABI 不符抛 UnsatisfiedLinkError（Error 家族），
                // 只接 Exception 会在离线模式闪退而不是退回录音待转写
                android.util.Log.e("OfflineStt", "offline transcribe failed", t)
                ""
            }.trim()
            if (text.isNotEmpty()) {
                NoteRepository.saveNote(
                    ctx,
                    Note(
                        id = id,
                        created_at = now,
                        updated_at = now,
                        text = text,
                        source = "voice",
                        tags = if (diary) listOf("日记") else emptyList(),
                        device = "phone",
                        audio = "audio/$id.wav",
                        transcribed = true,
                        schema = 1
                    )
                )
                _savedMsg.value = text
            } else {
                NoteRepository.saveNote(
                    ctx,
                    Note(
                        id = id,
                        created_at = now,
                        updated_at = now,
                        text = "（语音待转写）",
                        source = "voice",
                        tags = if (diary) listOf("日记") else emptyList(),
                        device = "phone",
                        audio = "audio/$id.wav",
                        transcribed = false,
                        schema = 1
                    )
                )
                _voiceError.value = "离线识别没出文字，已改为录音待电脑转写"
            }
            _offlineBusy.value = false
            refreshNotes()
        }
    }

    /** 识别完成 → 落库（普通笔记 / 并入今天日记） */
    private suspend fun handleFinalText(text: String, diary: Boolean) {
        if (text.isBlank()) {
            _voiceError.value = "没听到说话"
            return
        }
        if (diary) {
            val existing = NoteRepository.todayDiaryNote(ctx)
            if (existing != null) {
                NoteRepository.updateNote(
                    ctx, existing.id,
                    text = (existing.text + "\n" + text).trim(),
                    tags = existing.tags
                )
            } else {
                val now = NoteRepository.nowIso()
                NoteRepository.saveNote(
                    ctx,
                    Note(
                        id = UUID.randomUUID().toString(),
                        created_at = now,
                        updated_at = now,
                        text = text,
                        source = "voice",
                        tags = listOf("日记"),
                        device = "phone",
                        transcribed = true,
                        schema = 1
                    )
                )
            }
        } else {
            val now = NoteRepository.nowIso()
            NoteRepository.saveNote(
                ctx,
                Note(
                    id = UUID.randomUUID().toString(),
                    created_at = now,
                    updated_at = now,
                    text = text,
                    source = "voice",
                    tags = emptyList(),
                    device = "phone",
                    transcribed = true,
                    schema = 1
                )
            )
        }
        _savedMsg.value = text
        refreshNotes()
    }
}
