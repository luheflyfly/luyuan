package com.luyuan.platform

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 定位 Luyuan 共享根目录。默认 /storage/emulated/0/Luyuan/，
 * 与电脑端 D:\Luyuan\data 通过 Syncthing 双向同步（目录结构一致）。
 * 依赖 MANAGE_EXTERNAL_STORAGE 权限直接读写该目录。
 *
 * ⚠️ 2026-09-10 修复「选中笔记文件夹后主页不显示笔记」：
 *   旧实现 setRootName() 只存目录**名字**（路径最后一段），读取时用
 *   File(getExternalStorageDirectory(), name) 拼回 —— 一旦所选目录不在存储根目录
 *   直接子级（例如 AA路远/notes、SD 卡内、或任何更深层），就拼不出原路径 → 扫不到笔记 → 主页空白。
 *   现改为存**完整绝对路径**，并兼容读取旧值（旧值是没有 '/' 的纯名字）。
 */
object StorageLocator {
    private const val PREFS = "luyuan_prefs"
    private const val KEY_ROOT = "root_dir"
    private const val KEY_ROOT_PATH = "root_path"
    private const val DEFAULT = "Luyuan"

    /** 候选目录：绝对路径 + 里面扫到的笔记数（目录防呆选择器用） */
    data class Candidate(val path: String, val count: Int)

    fun getRoot(context: Context): File {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 优先用完整路径（新版存法）
        val savedPath = prefs.getString(KEY_ROOT_PATH, null)
        if (!savedPath.isNullOrBlank()) {
            return File(savedPath).also { runCatching { it.mkdirs() } }
        }
        // 兼容旧值：纯目录名 → 拼到存储根目录下
        val name = prefs.getString(KEY_ROOT, null) ?: DEFAULT
        return File(Environment.getExternalStorageDirectory(), name).also { runCatching { it.mkdirs() } }
    }

    /**
     * 设定共享根目录。传绝对路径最稳（candidates 里的 path 就是绝对路径）。
     * 同时写入完整路径与最后一段名字，旧代码读名字也不会崩。
     */
    fun setRoot(context: Context, absolutePath: String) {
        val p = absolutePath.trimEnd('/')
        if (p.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROOT_PATH, p)
            .putString(KEY_ROOT, p.substringAfterLast('/'))
            .apply()
    }

    fun notesDir(context: Context): File =
        File(getRoot(context), "notes").also { runCatching { it.mkdirs() } }

    fun audioDir(context: Context): File =
        File(getRoot(context), "audio").also { runCatching { it.mkdirs() } }

    /**
     * 扫描存储根目录，挑出可能是共享目录的候选（含笔记或名字像路远）。
     * 只扫顶层 + 顶层各自的一层子目录（避免把深层子目录当候选，选错就丢笔记）。
     */
    fun candidates(context: Context): List<Candidate> {
        val ext = Environment.getExternalStorageDirectory()
        val cur = getRoot(context).absolutePath
        val out = mutableListOf<Candidate>()
        val dirs = ext.listFiles { f -> f.isDirectory } ?: return out
        for (d in dirs) {
            if (d.name.startsWith(".")) continue
            val n = countJsonFiles(d, 0, 3)
            val looksLuyuan = d.name.contains("路远") || d.name.lowercase().contains("luyuan")
            if (n > 0 || looksLuyuan || d.absolutePath == cur) {
                out.add(Candidate(d.absolutePath, n))
            }
        }
        out.sortByDescending { it.count }
        if (out.none { it.path == cur }) {
            out.add(0, Candidate(cur, countJsonFiles(File(cur), 0, 3)))
        }
        return out
    }

    /**
     * 递归数笔记 json（跳过隐藏目录：.stversions 版本垃圾桶 / .stfolder 同步标记——
     * 否则手机端版本历史会把计数撑到几百，误导用户选错目录）。
     */
    fun countJsonFiles(dir: File?, depth: Int, maxDepth: Int): Int {
        if (dir == null || !dir.isDirectory || depth > maxDepth) return 0
        var n = 0
        for (f in dir.listFiles() ?: return 0) {
            if (f.isDirectory) {
                if (f.name.startsWith(".")) continue
                n += countJsonFiles(f, depth + 1, maxDepth)
            } else if (f.name.endsWith(".json", ignoreCase = true) &&
                !f.name.contains(".sync-conflict")
            ) n++
        }
        return n
    }
}
