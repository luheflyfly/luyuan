package com.luyuan.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * vc131 通知按钮的落点（2026-11-02 路河派单）。
 *
 * 一个按钮 = 一个 `PendingIntent.getBroadcast`（见 [InboxNotifier.buildNotification]），
 * 点下去**不打开 App**，只做两件事：
 *   1. 立刻撤掉这条通知（他要的"点完就消失"，不等网络）
 *   2. 后台 `POST /api/inbox/answer`
 *
 * 为什么用 receiver 而不是 Activity：他要的是"手机在兜里点一下就答完"。开 App 会打断他，
 * 而且 vivo 上冷启动 Activity 还可能被省电策略拦一下；Broadcast 是系统直接派发，最快。
 *
 * 失败静默：网络不通就内部吞掉（[InboxNotifier.submitAnswer] 里 catch），不弹 toast、
 * 不发通知回收箱 —— 服务端那条会自己过期，按 skip 处理。
 */
class InboxActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val optionId = intent.getStringExtra(EXTRA_OPTION_ID) ?: return
        try {
            InboxNotifier.submitAnswer(context.applicationContext, id, optionId)
        } catch (_: Throwable) {
            // 静默：点按钮这条路上不允许任何弹窗
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.luyuan.action.INBOX_ANSWER"
        const val EXTRA_ID = "inbox_id"
        const val EXTRA_OPTION_ID = "inbox_option_id"
    }
}
