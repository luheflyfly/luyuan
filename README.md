# 路远 · 安卓 App（Luyuan for Android）

[![Build APK](https://github.com/luheflyfly/luyuan/actions/workflows/build.yml/badge.svg)](https://github.com/luheflyfly/luyuan/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/luheflyfly/luyuan?label=release)](https://github.com/luheflyfly/luyuan/releases)
[![License](https://img.shields.io/github/license/luheflyfly/luyuan?label=license)](LICENSE)

去中心化、离线优先的个人语音记事与日程 App。

录音在**本机**转写（sherpa-onnx zipformer，不外传）；记事存成 JSON 放在 Syncthing 共享目录，与电脑端「路远」双向同步——**电脑开不开都不影响手机端使用**。

- 当前版本：**v1.52.0（versionCode 131）**
- 平台：Android 8.0+（minSdk 26 / targetSdk 34），只打 **arm64-v8a**
- 技术栈：Kotlin · Jetpack Compose · Material3

> 电脑端项目在本地 `D:\Luyuan`（未开源）；本仓库只含**手机端 App 源码 + 自动编译配置**。

---

## 一、现在做到哪

一套自绘的「纸墨」设计系统 + 七个常驻页签，加上若干独立页面：

| 页签 | 做什么 |
|---|---|
| 笔记 | 手动记 / 录音转写 / 来源徽章 / 列表内即时搜索 / 详情行内编辑 / 软删进回收站 |
| 待办 | 提醒 chips（不提醒·15 分·30 分·1 时·2 时·1 天）、详情页全文与 OCR 原文可复制、自动补提醒兜底 |
| 记账 | 粘贴导入、待确认支出、按日汇总 |
| 日记 | 连续天数、按日回看 |
| 课表 | 学期分段（按日期切）、stickyHeader 中段切视图不丢位置 |
| 人脉 | 通讯录归类、群发与备注 |
| 路远远 | 未读在上、全文可复制、点开即标已读、可删除 |

独立页面：录音、详情编辑、回收站、复盘（Review）、问答（Ask / Ask 密钥）、引导（Onboarding）、设置。

## 二、能力清单

**记录与识别**
- 录音 → 本地流式识别 → 存文本 + `audio/<id>.wav`；识别失败可从录音抢救（RecordingRescue）
- 识别模型：`sherpa-onnx-streaming-zipformer-small-ctc-zh-int8`（25MB，实测优于 62MB 的 transducer，勿回退大模型）

**今天与提醒**
- 课表、课前提醒、天气一句话、周报；调度器统一接管（不再用 `while True` 轮询）
- 自动闹钟：到点自动排程，改完即重排

**与电脑端的两条线**
- **文件线**：Syncthing + JSON（离线优先，冲突按 SyncPolicy 处理）
- **问答线**：北极星提问 → 手机**通知带按钮** → 点一下直接作答并回报（不打开 App）

**通知收件箱（vc131 新增）**
- 开关默认开；最近 20 条已通知 id 做去重表（同一条问题只弹一次）
- 点按先撤通知再回传，弱网也不卡界面

**位置上报（vc131 新增）**
- 前台服务按间隔上报，接收地址默认 `8787`（旧系统占 8777；切流后改设置页即可，不用重装）

**别人的消息也能接**
- 通知监听 → 关键词规则 → 待办提取（MessageNotificationListener / MessageKeywordRules / MessageBuffer 去重）

**悬浮岛 / 终端胶囊**
- 单窗常驻 + 显式 measure + 热替换；姿势在 `island-demo` 仓库单独验证过

## 三、架构

```
app/src/main/java/com/luyuan/
├── MainActivity.kt            单 Activity 宿主 + 导航骨架
├── ui/                        23 个：七个页签 + 详情/录音/回收站/复盘/问答 + 设计系统(theme, Seg, UndoBar)
├── domain/                    4 个：Note / Semester / SyncPolicy / ChineseDue（"下周三"这类中文日期解析）
├── data/                      22 个：仓储与设置（Note/Todo/Report/Contact/Keiwu）、录音、离线识别、通知与位置设置
└── platform/                  20 个：通知监听、悬浮岛、前台服务、崩溃日志、权限引导、收件箱通知与按钮
```

数据侧只碰三样：**Syncthing 目录里的 JSON**、**App 私有 SharedPreferences（只存开关与去重表）**、**录音文件**。没有服务端、没有账号、没有遥测。

## 四、构建

**云端（推荐）**：push 到 `main` 即触发 [Build APK](.github/workflows/build.yml)，产物在 Actions 的 `app-debug` artifact（保留 5 天）；正式包挂在 [Releases](https://github.com/luheflyfly/luyuan/releases)。

**本地**：

```bash
# 需要 JDK 17 + Android SDK 34；local.properties 里写 sdk.dir
gradle testDebugUnitTest assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

CI 已踩过的坑写在 workflow 注释里（runner 镜像的 sdkmanager 报错、GitHub cache service 故障改直连 Gradle 发行包），改动前先读那几段注释。

## 五、版本节奏

一版一个 `vcNNN`，commit message 即变更单。最近几版：

| 版本 | 内容 |
|---|---|
| vc122 / 1.45.0 | 待办详情页 + 提醒兜底 |
| vc123 / 1.46.0 | 切页顺滑：页码读取移出组合、读盘下后台线程 |
| vc124 / 1.47.0 | 层级与视觉对齐批 |
| vc125 / 1.48.0 | 「路远远」汇报页（底栏第七槽 + 红点） |
| vc127 / 1.49.0 | 前端重做：纸墨令牌 + 自绘底栏/头部/抽屉 + 分层动效 |
| **vc131 / 1.52.0** | **通知收件箱（按钮作答）+ 位置上报 + 离线识别换 zipformer；补齐 128–130 未推改动** |

## 六、许可

[MIT](LICENSE) © 2026 luheflyfly（路河）
