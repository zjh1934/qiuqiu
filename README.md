# 球球 —— 拾字 + 放大镜 二合一

一个 Android 悬浮球，把「读屏拾字」和「放大镜」两件事合成一个球。

原本要用两个 APP、开两个权限才能做到的事，这里**只要一个无障碍服务**。

面向低视力 / 视障用户：字太小看不清就按住放大，看都不想看就点一下让手机念给你听。

## 手势

| 操作 | 干什么 |
|---|---|
| 轻点球 | 球变绿，进入"选字"状态 |
| 再点屏幕上的字 | 从那一句开始往下读 |
| 朗读中轻点球 | 停止 |
| 按住球不动 | 放大，手指挪动画面跟着走 |
| 拖动球 | 挪球位置 |
| 闲置 2.5 秒 | 球自动变淡 |

## 核心设计：朗读和放大不交叉

**放大时不朗读，朗读时不放大。**

一按住要放大，朗读立刻停、选字态立刻取消——不交叉。

这是刻意的取舍：放大状态下屏幕坐标体系和正常态完全不同，
此时抓字会抓错位置。与其给个错的结果，不如干脆不做。

## 跟"两个独立 APP"方案的区别

- **只要一个无障碍服务**（原来要"悬浮窗权限 + 无障碍"两个）
- 一个服务同时声明 `canRetrieveWindowContent`（抓字）和
  `canControlMagnification`（放大）

## 权限

装完去 **设置 → 无障碍** 找到「球球」打开。就这一个。

`SYSTEM_ALERT_WINDOW` 在 manifest 里也声明了，但正常流程用不到——
只有个别 ROM 的无障碍窗口加不进去时，代码会退到
`TYPE_APPLICATION_OVERLAY`，那条兜底路径才需要它。

## 编译

### AIDE（本项目实际在用的方式）

1. 用 AIDE 打开**工程根目录**（含 `build.gradle` / `settings.gradle` 的那层）
2. 直接 Build

根目录 `build.gradle` 刻意保持最小（只声明 repositories），
不写 `buildscript` / AGP 版本 —— AIDE 自带插件，写死版本反而容易冲突报错。

### Android Studio

wrapper 没入库，先补上：

1. 在工程根目录执行 `gradle wrapper`（需本地装 Gradle 6.7+），
   或让 Android Studio 打开工程时自动生成
2. 根目录 `build.gradle` 补上 AGP classpath，例如 4.2.2
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`

#### AIDE 可能遇到的报错

| 报错 | 改法 |
|---|---|
| `<queries> 未知元素` | 删掉 `AndroidManifest.xml` 里整个 `<queries>...</queries>` 块。引擎枚举有第二条路兜底，不影响 |
| `找不到 Build Tools 30.x` | 已去掉 `buildToolsVersion`，用 AGP 默认版本。若仍报，把 `compileSdkVersion` 改成 29 |
| `程序包 android.speech.tts.Voice 不存在` | SDK 低于 21。真遇到就把 `TtsManager` 里 `listVoicesZhFirst` 的 `getVoices()` 那段删掉 |
| `程序包 R 不存在` | 界面是纯 Java 写的，只用了 `android.R`。加 `Build → Rebuild` |

## 文件说明

```
ComboService.java   唯一服务：手势分发 + 状态机 + 保活通知
BallController.java 手势判定（拖动 / 按住放大 / 轻点）
BallView.java       球的绘制，四态（待命/放大/选取/朗读）
ZoomController.java 系统放大控制
TextGrabber.java    抓字：多窗口取根 → 单趟收集 → 阅读顺序 → 精确到句 → 续读
TextLayout.java     纯排版逻辑（阅读顺序 / 断句），无 Android 依赖，可单测
TtsManager.java     TTS 引擎与音色管理
Prefs.java          设置读写
MainActivity.java   三个 Tab 的界面
```

## 抓字是怎么做的

这块参考了 AutoX / Assists 等同类方案的踩坑经验，几个关键点：

- **多窗口遍历**：`getRootInActiveWindow()` 只在窗口内容变化时才取得到数据，
  经常返回 null，也拿不到弹窗 / 输入法 / 分屏另一半。
  所以走 `getWindows()` 按 layer 从高到低扫，哪个窗口点得到字就用哪个。
- **可见性过滤**：用 `isVisibleToUser()` 过滤掉滚出屏幕的节点，
  否则会把列表里看不见的几十条也读出来。
- **节点数上限**：病态节点树（超长列表）会把主线程拖死，
  设了 10000 的上限；字符位置请求的官方上限是 20000。
- **阅读顺序**：不能只按 `(top, left)` 排，多列布局会读乱序
  （右列第二行混进左列第一行之间）。做法是先把垂直重叠过半的
  归成一行，行内再按 left 排。
- **单趟遍历**：收集只走一遍树，避免 O(n²) 和双倍 IPC。
- **节点回收**：`AccessibilityNodeInfo` 是有限系统资源，
  漏 `recycle()` 会让后续 `getChild()` 直接抛异常，
  表现为"读一会儿就抓不到字了"。所有路径都在 `finally` 里回收。

## 球不显示？按顺序排查

1. **无障碍开了吗** —— 没开的话点开关是无效的，会直接带你去设置页。
   设置 → 无障碍 → 找到「球球」→ 打开
2. **看界面上那行状态** —— 现在会明确写出卡在哪一步：
   - `服务未运行（去系统设置开启无障碍）`
   - `球显示失败：BadTokenException: ...` ← 这个会把系统给的具体原因打出来
   - `球已显示`
3. **个别 ROM 加不进无障碍窗口** —— 代码会自动退到
   `TYPE_APPLICATION_OVERLAY` / `TYPE_PHONE`，
   前一条需要「悬浮窗」权限（manifest 里已声明，
   部分 ROM 还要在 设置→应用→权限 里手动给）。

窗口类型用的是**服务自己的 Context**，不是 applicationContext——
这点很关键，用 applicationContext 建的无障碍窗口在多数 ROM 上加不进去。

## 运行日志

主界面「球」页最底下有个「诊断与日志 ▸」折叠入口。出问题时导出一份，贴到 Issue 里能直接定位。

- 默认折叠；展开后只显示最近 8 条，要看全部点「显示全部」（上限 200 条）
- 「导出日志」→ 存到 `Downloads/球球日志_MMDD_HHmm.txt`
- 「清空日志」

**记什么：** `G/gesture` 手势时序、`S/state` 状态变化、`E/...` 错误含堆栈、
窗口类型尝试结果、TTS 引擎枚举与初始化结果。

**不记什么：** 只记代码内部事件，**不记你读到的任何文字内容**——
唯一例外是抓字成功时记「抓到 N 字，开头：「…」」的前 20 字，用来确认抓的位置对不对。

文件同时存在 APP 私有目录（界面上会显示路径），超过 150KB 自动砍掉前面一半。

## 自检

开发机若没有 JDK / Android SDK，用 Python 镜像兜底：

```bash
python verify_all.py     # 跑三遍，全绿才准提交
```

- `verify_structure.py` —— 括号配对 + 跨文件符号解析（替代编译）
- `verify_textlayout.py` —— `TextLayout` 纯逻辑镜像（阅读顺序 / 断句）
- `verify_grabber.py` —— 完整流程镜像（收集 / 排序 / 拼装 / 节点回收）

> 这套检查替代不了真机编译，发布前还是请在 Android Studio 里 build 一次。

## 授权

**本项目目前未附带开源协议。**

代码公开可见，但在协议明确之前默认适用版权法——**保留所有权利**，
他人无权复制、修改、分发或用于衍生作品。

想让它真正成为开源项目（例如 MIT / Apache-2.0 / GPL-3.0），
补一个 `LICENSE` 文件即可。告诉我选哪个，我加上去。

## 包名说明

显示名是「球球」，包名仍是 `com.floating.combo`（原名「拾放」遗留）。
改包名要动目录结构和全部 import，风险大过收益，所以没改。
