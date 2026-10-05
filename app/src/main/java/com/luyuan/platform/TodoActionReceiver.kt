package com.luyuan.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.luyuan.data.PendingMessageTodoStore
import com.luyuan.data.TodoStore

/**
 * 消息待办通知栏直达动作（2026-09-15 路河拍板→复板三动作）：
 * 「已完成」= 转正式待办并立刻标 done；「收下」= 转未办待办；「不要」= 丢弃。全程不进 App。
 */
class TodoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // vc107：自动收录的正式待办（路河拍板「明天及以后直接入库不用问」）走这条——
        // 已完成=setDone(true)，不要=软删墓碑。与待确认动作共用一个 Receiver 省一个清单项。
        val todoId = intent.getStringExtra(EXTRA_TODO_ID)
        if (todoId != null) {
            when (intent.action) {
                ACTION_DONE -> try { TodoStore.setDone(context, todoId, true) } catch (_: Throwable) { }
                ACTION_DROP -> try { TodoStore.deleteSoft(context, todoId) } catch (_: Throwable) { }
            }
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(TAG, todoId.hashCode())
            } catch (_: Throwable) { }
            return
        }
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pending = PendingMessageTodoStore.list(context).firstOrNull { it.id == id } ?: return
        when (intent.action) {
            ACTION_DONE -> {
                try {
                    // vc98：入库走 createFromPending（跨库查重防重复）；done 语义不变
                    TodoStore.createFromPending(context, pending, done = true)
                } catch (_: Throwable) { }
                PendingMessageTodoStore.remove(context, id)
            }
            ACTION_DROP -> PendingMessageTodoStore.remove(context, id)
        }
        // 撤掉这条通知
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(TAG, id.hashCode())
        } catch (_: Throwable) { }
    }

    companion object {
        const val ACTION_DONE = "com.luyuan.action.TODO_DONE"
        const val ACTION_DROP = "com.luyuan.action.TODO_DROP"
        const val EXTRA_ID = "todo_id"
        const val EXTRA_TODO_ID = "todo_id_confirmed"   // vc107：正式库待办 id（自动收录通知用）
        const val TAG = "msg_todo"
    }
}
