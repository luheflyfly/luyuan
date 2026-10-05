package com.luyuan.domain

/**
 * 跨端同步策略常量。规则来源：D:\Luyuan\SYNC_FORMAT.md 第四节。
 * - 每笔记一文件：新增=新文件不冲突。
 * - 软删：不物理删文件，置 deleted=true，同步后两端都隐藏（避免一端删、另一端复活）。
 * - 冲突：Syncthing 生成 .sync-conflict 副本，应用层以 updated_at 较新者为主版本。
 */
object SyncPolicy {
    const val SCHEMA_VERSION = 1
    const val DEVICE_PHONE = "phone"
    const val DEVICE_PC = "pc"
}
