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
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pending = PendingMessageTodoStore.list(context).firstOrNull { it.id == id } ?: return
        when (intent.action) {
            ACTION_DONE -> {
                try {
                    val done = TodoStore.fromPending(pending).copy(
                        done = true, done_at = PendingMessageTodoStore.nowIso()
                    )
                    TodoStore.write(context, done)
                } catch (_: Throwable) { }
                PendingMessageTodoStore.remove(context, id)
            }
            ACTION_KEEP -> {
                try { TodoStore.write(context, TodoStore.fromPending(pending)) } catch (_: Throwable) { }
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
        const val ACTION_KEEP = "com.luyuan.action.TODO_KEEP"
        const val EXTRA_ID = "todo_id"
        const val TAG = "msg_todo"
    }
}
