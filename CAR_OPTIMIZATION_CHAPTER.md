# 车载 App 生产级加固（60–80 分钟）讲义与录制脚本

适用对象：已完成本课程 3 个实战项目录制，正在进行第二轮生产级加固。

本章定位：**功能开发是第一步，生产加固是第二步。** 前三章我们完成了功能实现——播放、控制、跨 App 通信，都能跑。但这些实现是开发态的，还没有经过车机生产环境的打磨。车机的运行环境（硬件弱、省电策略激进、后台限制严格）和手机完全不同，本章专门做一轮三维加固：**性能、稳定性、功耗**。

每个实战都做到"量化现状 → 定位热点 → 精确改动 → 指标证明"，全程贴着真实代码行号走，不讲玄学。

> 说明：本章不包含"断电 / 杀进程续播（持久化策略）"，因为你在前文已讲过。

---

## 三项目加固速览

| 项目 | 开发态现状（功能可用） | 生产加固目标 |
|------|---------------------|------------|
| **MediaCenter** | `loadMusicList()` 在 `onCreate()` 里**同步执行**，遍历所有 raw 音频文件用 `MediaMetadataRetriever` 读元数据（主线程 I/O），冷启动被拖慢 500ms-2000ms | `loadMusicList()` 改为协程异步，`onCreate()` 立即返回，前台通知瞬间出现 |
| **CarLauncherSimulator** | `checkConnectionRunnable` 每 2 秒无条件调用 `updateUI()`（即使无变化），重连靠固定间隔轮询 | UI 刷新改为事件驱动 + 重连改为指数退避，减少无效 IPC |
| **DLNA Renderer** | `statusUpdateTimer` 和 `lastChangeTimer` 两个 1 秒定时器在 PAUSED/STOPPED 时依然运行，叠加播放器层共三层定时器 | 按播放状态启停/降频，减少后台空跑 |

---

## 总体结构与时间分配（推荐 65 分钟，可上下浮动 10 分钟）

- **5 min**：开场——为什么车载 App 需要生产级加固
- **实战 1（15 min）**：MediaCenter `loadMusicList()` 异步化——"把主线程 I/O 移出 onCreate"
- **实战 2（20 min）**：CarLauncherSimulator 跨进程通信重构——事件驱动 + 指数退避重连
- **实战 3（20 min）**：DLNA Renderer 后台定时器重构——按状态启停与降频
- **5 min**：总结与迁移清单

---

## 开场（5 min）：逐段大纲

> 录制建议：开场讲清楚"为什么要单独用一章讲加固"，让学员建立"功能开发 vs 生产加固"的双阶段意识。录屏从 logcat 展示一个具体数字开始，直接进入量化环节。

### 0:00–0:30 破题（30s）

- **讲什么**：前面三章我们把三个项目从零做到了功能完备——能播放、能控制、能跨 App 通信。但这只是第一步。真实车机上，这些功能会面临三个维度的考验：**性能**（主线程压力）、**稳定性**（连接恢复）、**功耗**（后台空跑）。今天我们就拿这三个已经能跑的项目，做一轮生产级加固。
- **过渡句**：加固不是补 bug，而是让 App 在车机上"长跑不出事"。

### 0:30–2:00 为什么要单独加固（1.5min）

- **讲什么**：
  - 手机开发：功能跑通 = 完成
  - 车载开发：功能跑通只是起点，还需要过生产环境这一关
  - 车机硬件弱、系统策略激进（省电 / 后台限制严格）、用户长时间停留——这些问题手机开发里不明显，车机上会被放大
  - 本章的三个实战，分别对应**性能**、**稳定性**、**功耗**三个维度
- **过渡句**：互联网公司里功能开发完了还要经过性能测试和稳定性加固。今天我们就走这个流程。

### 2:00–5:00 三指标体系（3min）

- **性能**：不卡顿（主线程 / 系统服务压力）
- **交互**：不慢（跨进程链路）
- **能耗**：不耗（后台唤醒 / 无效工作）

**过渡句**：没有量化，就没有加固。我们先说工具。

### 工具清单（3min）

本章只用两类工具，录课不翻车：

**1. log 计数（主力手段）**
- 在关键函数里打点，统计"每分钟触发次数"
- 过滤 TAG：`MediaService` / `MediaSessionHelper` / `MediaRendererService` / `DLNAService`
- 例如：在 `updateNotification()` 里加一行 `Log.i(TAG, "count: $count")`，60 秒后打印一次

**2. Android Studio Profiler（CPU，可选）**
- Android Studio → Profiler → 选进程 → CPU 曲线
- 加固前后的对比：曲线是否更平、尖刺是否消失
- 不熟悉 Perfetto 的直接跳过，不用讲

### 三句话总结（可直接口播）

1. **功能开发是第一步**：能跑不代表能上生产。
2. **车机环境更苛刻**：硬件弱、策略激进，小问题会被放大。
3. **加固靠数据说话**：先量化，再动手，最后验证。

---

## 实战 1（15 min）：MediaCenter `loadMusicList()` 异步化——"把主线程 I/O 移出 onCreate"

对应项目：`media_center`
对应文件：`app/src/main/java/com/max/media_center/MediaService.kt`

### 1.1 场景与目标（2min）

**为什么要加固这里？**

MediaCenter 的播放功能已经完备，但开发态实现中有一个在车机生产环境里会被放大的问题：`MediaService.onCreate()` 里 `loadMusicList()` 是**同步执行**的。

`loadMusicList()` 遍历 `res/raw` 下所有音频文件，逐个调用 `MediaMetadataRetriever` 读取元数据（磁盘 I/O + 解码），全部跑在**主线程**上。如果有 50 首本地音乐，这一步可能吃掉 800ms-2000ms，直接拖累冷启动时间。

用户点击 App 图标时，调用链是：

```
MainActivity.onCreate()
  → startForegroundService(MediaService)    // 启动服务
  → MediaService.onCreate()                // 在主线程执行
    → loadMusicList()                     // ← 这里是瓶颈：同步读元数据
    → mediaPlayer = MediaPlayer()
    → mediaSession = MediaSessionCompat()
    → restorePlaybackState()
```

**本节目标**：把 `loadMusicList()` 改为异步执行，让 `onCreate()` **立即返回**，前台通知瞬间出现。

### 1.2 量化现状（3 min）

**定位当前实现**

1. 打开 `MediaService.kt`
2. 找到第 151 行——`loadMusicList()` 调用，位于 `onCreate()` 内

```kotlin
// MediaService.kt 第 144 行（改动前）
loadMusicList()
Log.d(TAG, "MediaService loaded ${musicList.size} music items")
```

3. 找到第 365-407 行——`loadMusicList()` 方法本体：

```kotlin
// MediaService.kt 第 356-396 行（改动前）
private fun loadMusicList() {
    try {
        val fields: Array<Field> = R.raw::class.java.fields
        for (field in fields) {
            val resourceId = field.getInt(null)
            val resourceName = field.name
            val musicItem = MusicItem(resourceName, resourceId)

            // 获取音频文件的元数据
            try {
                val retriever = MediaMetadataRetriever()
                val uri = Uri.parse("android.resource://${packageName}/raw/${resourceName}")
                retriever.setDataSource(applicationContext, uri)   // ← 磁盘 I/O

                musicItem.title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                musicItem.artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""

                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    val options = BitmapFactory.Options().apply { inSampleSize = IMAGE_COMPRESSION_RATIO }
                    musicItem.coverArt = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
                }
                retriever.release()
            } catch (e: Exception) { ... }

            musicList.add(musicItem)
        }
    } catch (e: Exception) { ... }
}
```

**量化数据（录屏展示）**

在 `onCreate()` 开头和 `loadMusicList()` 返回后分别打点：

```kotlin
override fun onCreate() {
    super.onCreate()
    val startTime = System.nanoTime()
    Log.d(TAG, "onCreate start")

    // ... 中间代码 ...

    loadMusicList()
    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
    Log.i(TAG, "onCreate end, loadMusicList took ${elapsedMs}ms")
}
```

录屏展示：播放 60 秒，log 打印 `onCreate end, loadMusicList took XXXms`——这个数字就是我们要改进的基线（通常 500ms-2000ms）。

### 1.3 原理拆解（3 min）

**关键认知：Service.onCreate() 在主线程执行，阻塞则 App 无响应**

Android 的 Service 生命周期回调（`onCreate()`、`onStartCommand()`）默认在主线程运行。`onCreate()` 里做耗时的同步操作，会直接拖慢冷启动——用户点图标后，通知栏出现得很慢，感觉 App "没反应"。

**为什么元数据读取适合异步？**

- `MediaMetadataRetriever` 的 `setDataSource()` 和 `extractMetadata()` 是**磁盘 I/O**，不是 CPU 密集型
- 元数据读取不需要在 onCreate 同步完成，`musicList` 加载完成后 `restorePlaybackState()` 再执行即可
- 用协程（`Dispatchers.IO`）处理 I/O 操作是 Android 官方推荐方式

**协程的作用域设计**

- 用 `MainScope()`（等价于 `CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())`）与 Service 生命周期绑定
- `onDestroy()` 中调用 `serviceScope.cancel()` 确保不泄漏

### 1.4 精确改动（7 min）

> 改动集中在 `MediaService.kt`，只改 5 处，强调"改动小、收益大"。

**改动 1：新增协程 import（第 35-40 行）**

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

**改动 2：新增协程作用域（第 65-66 行）**

```kotlin
/** 主线程 Handler，用于延迟任务与 Toast */
private val handler = Handler(Looper.getMainLooper())
/** 与 Service 生命周期绑定的协程作用域（主线程 + SupervisorJob） */
private val serviceScope = MainScope()
```

**改动 3：`onCreate()` 里异步调用（第 151-157 行）**

```kotlin
// 从 res/raw 扫描所有音频并解析元数据（异步，后台线程执行）
serviceScope.launch {
    loadMusicList()
    Log.d(TAG, "MediaService loaded ${musicList.size} music items")
    // 加载完成后恢复播放状态
    restorePlaybackState()
}
```

> 注意：原来的 `restorePlaybackState()` 同步调用（第 216 行）需要删除，因为它现在在协程内等待 `loadMusicList()` 完成后再执行。

**改动 4：`loadMusicList()` 改为 `suspend` 函数（第 365-407 行）**

```kotlin
/**
 * 通过反射遍历 R.raw 下所有资源，构建 MusicItem 列表并解析每首的标题、艺术家、封面（若有）。
 * 在 IO 线程执行，避免阻塞主线程。
 */
private suspend fun loadMusicList() = withContext(Dispatchers.IO) {
    try {
        val fields: Array<Field> = R.raw::class.java.fields
        for (field in fields) {
            val resourceId = field.getInt(null)
            val resourceName = field.name
            val musicItem = MusicItem(resourceName, resourceId)

            try {
                val retriever = MediaMetadataRetriever()
                val uri = Uri.parse("android.resource://${packageName}/raw/${resourceName}")
                retriever.setDataSource(applicationContext, uri)

                musicItem.title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
                musicItem.artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""

                val artBytes = retriever.embeddedPicture
                if (artBytes != null) {
                    val options = BitmapFactory.Options().apply { inSampleSize = IMAGE_COMPRESSION_RATIO }
                    musicItem.coverArt = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
                }
                retriever.release()
            } catch (e: Exception) { Log.e(TAG, "Error getting metadata for $resourceName", e) }

            musicList.add(musicItem)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error loading music list", e)
        withContext(Dispatchers.Main) {
            showToast(getString(R.string.error_load_music_list_failed))
        }
    }
}
```

**改动 5：`onDestroy()` 中取消协程（第 881 行）**

```kotlin
override fun onDestroy() {
    savePlaybackState()
    abandonAudioFocus()
    handler.removeCallbacks(progressUpdater)
    serviceScope.cancel()          // ← 新增：取消协程，防止泄漏
    mediaSession.release()
    mediaPlayer.release()
    wakeLock?.release()
    super.onDestroy()
}
```

### 1.5 验证与量化（3 min）

**方法一：logcat 打点（精确，但需要改代码）**

在 `onCreate()` 开头和 `sessionToken = mediaSession.sessionToken` 之后分别打点，计算同步阶段的耗时：

```kotlin
// MediaService.kt（改动后）
override fun onCreate() {
    super.onCreate()
    val onCreateStart = System.nanoTime()
    Log.d(TAG, "onCreate start")

    // ... 全部同步初始化代码（协程内的 loadMusicList 不计入）...

    sessionToken = mediaSession.sessionToken  // ← 同步部分结束点（第 212 行）
    val onCreateSyncEnd = System.nanoTime()
    val syncElapsedMs = (onCreateSyncEnd - onCreateStart) / 1_000_000
    Log.i(TAG, "onCreate sync part done, took ${syncElapsedMs}ms")
}
```

- **加固前**：`onCreate()` 总耗时 = 同步耗时 = 500ms-2000ms（全部卡在主线程）
- **加固后**：`onCreate()` 同步部分耗时 ≈ **< 50ms**（协程异步加载，通知瞬间出现）

**方法二：logcat 测量通知出现时间（测用户体验）**

`am start -W` 测的是 **Activity** 冷启动（`onCreate` → `onResume`），而 `startForegroundService()` 是**异步**的——它不等待 `MediaService.onCreate()` 完成就返回了。所以在这个优化里 `am start -W` **看不出差异**。

正确的测量对象是：**用户点图标 → 前台通知出现**。用 logcat 两次打点即可：

```kotlin
// MediaService.kt - onCreate() 开头
private var serviceStartTimeNanos = 0L
private var firstNotificationShown = false

override fun onCreate() {
    super.onCreate()
    serviceStartTimeNanos = System.nanoTime()
    Log.d(TAG, "MediaService onCreate start")
    // ...
}

// MediaService.kt - updateNotification() 第一次出现时
private fun updateNotification() {
    if (!firstNotificationShown) {
        firstNotificationShown = true
        val elapsedMs = (System.nanoTime() - serviceStartTimeNanos) / 1_000_000
        Log.i(TAG, "First notification appeared: ${elapsedMs}ms after onCreate")
    }
    // ...
}
```

录屏时：
```bash
adb logcat -v threadtime | grep "First notification appeared"
```

| 指标 | 加固前 | 加固后 |
|------|--------|--------|
| 通知出现时间 | 500-2000ms（同步等 loadMusicList） | **< 50ms**（异步，后台加载） |

**方法一 vs 方法二对比**

| 维度 | logcat 打点（方法一） | 通知出现时间（方法二） |
|------|----------------------|----------------------|
| 需要改代码 | 是（onCreate 内打点） | 是（onCreate + updateNotification） |
| 测量范围 | `MediaService.onCreate()` 同步阶段 | 用户感知：图标→通知 |
| 适合场景 | 验证优化效果（同步部分变快） | 验证用户体验（通知瞬间出现） |
| 推荐顺序 | **①** | **②** |

> **方法三（可选）：Android Studio CPU Profiler** → 选择 **"Java/Kotlin"** 模式，可看完整调用栈和各函数耗时分布。

**录屏展示步骤**：

1. 先用方法一/方法二展示加固前基线（数字 500-2000ms）
2. 改代码，加固
3. 再次测量，数字显著下降

**用户体验差异**

| 场景 | 加固前 | 加固后 |
|------|--------|--------|
| 点击图标 | 等待 500-2000ms，通知才出现 | 通知几乎瞬间出现 |
| 后台加载 | 无 | 通知出现后，后台异步加载元数据 |
| 播放位置恢复 | 即时 | 加载完成后恢复（有短暂延迟，用户无感知） |

---

## 实战 2（20 min）：CarLauncherSimulator 跨进程通信重构——事件驱动 + 指数退避重连

对应项目：`CarLauncherSimulator`
对应文件：
- `app/src/main/java/com/max/carlaunchersimulator/MainActivity.kt`
- `app/src/main/java/com/max/carlaunchersimulator/MediaSessionHelper.kt`

### 2.1 场景与目标（2min）

**为什么要加固这里？**

CarLauncherSimulator 的跨 App 控制功能已经完备，但开发态实现中有两个在车机生产环境里会被放大的问题：

- **问题 A**：UI 刷新靠 2 秒固定轮询，即使歌曲和状态没变也每秒刷新——浪费 IPC 和渲染资源
- **问题 B**：连接断开后靠同一个轮询 timer 驱动重连，间隔固定 2 秒，成功后不会重置退避状态

**本节目标**：将 UI 刷新改为事件驱动，重连策略改为指数退避。

### 2.2 量化现状（2 min）

**定位当前实现**

打开 `MainActivity.kt`，找到第 42-54 行：

```kotlin
// MainActivity.kt 第 42-54 行
private val checkConnectionRunnable = object : Runnable {
    override fun run() {
        try {
            if (!mediaSessionHelper.isConnected()) {
                mediaSessionHelper.tryReconnect()
            }
            updateUI()  // ← 第 48 行：无条件刷新 UI，不管有没有变化
        } catch (e: Exception) {
            Log.e(TAG, "定期检查连接/更新UI异常: ${e.message}", e)
        }
        handler.postDelayed(this, 2000)  // 每 2 秒跑一次
    }
}
```

再看 `MediaSessionHelper.kt` 第 218-222 行的 `tryReconnect()`：

```kotlin
// MediaSessionHelper.kt 第 218-222 行
fun tryReconnect() {
    if (mediaController == null) {
        connectToMusicApp()  // 简单重连，无退避策略
    }
}
```

**量化数据（录屏展示）**

1. 启动 MediaCenter，开始播放；启动 CarLauncherSimulator
2. logcat 过滤 `MainActivity`
3. 静止 30 秒，观察 `updateUI` 日志：约 15 条（每 2 秒一条，即使无变化）
4. 强停 MediaCenter，观察重连日志：每 2 秒一次 `connectToMusicApp()`，持续不断

### 2.3 原理拆解——MediaSession API 的事件推送设计（3 min）

**为什么 UI 刷新不需要轮询？**

看 `MediaSessionHelper.kt` 第 153-163 行，`MediaControllerCallback` 已经有事件推送：

```kotlin
// MediaSessionHelper.kt 第 153-163 行
private inner class MediaControllerCallback : MediaControllerCompat.Callback() {
    override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
        updateCurrentSong()  // 歌曲变化了才需要刷新
    }

    override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
        state?.let {
            isPlaying = it.state == PlaybackStateCompat.STATE_PLAYING
        }
    }
}
```

这个回调是 Android MediaSession 框架内置的事件推送——MediaCenter 调用 `setPlaybackState()` 或 `setMetadata()` 时，所有连接的 `MediaController` 都会收到通知。根本不需要轮询去"探测"。

**为什么重连不需要靠固定间隔轮询？**

看 `MediaSessionHelper.kt` 第 119-123 行：

```kotlin
// MediaSessionHelper.kt 第 119-123 行
override fun onConnectionSuspended() {
    Log.w(TAG, "MediaBrowser连接中断")
    mediaController = null
    connectionListener?.onDisconnected()  // ← 系统主动通知，问题是这里没有启动退避重连
}
```

`onConnectionSuspended()` 是系统主动推送的回调，连接断开时框架会主动告知我们。当前代码里这个回调没有启动退避重连逻辑，而是把重连任务全交给了轮询 timer——这是开发态的权宜之计，生产环境应该用退避策略。

### 2.4 改动 A：UI 刷新改为事件驱动（5 min）

**核心思路**：`updateUI()` 只在 `MediaControllerCallback` 收到变化时触发，不再在轮询 runnable 里调用。

**改动点 1：MediaSessionHelper 新增 UI 更新 listener**

```kotlin
// MediaSessionHelper.kt 新增接口
interface UiUpdateListener {
    fun onUiNeedsUpdate()
}

private var uiUpdateListener: UiUpdateListener? = null
fun setUiUpdateListener(listener: UiUpdateListener?) { this.uiUpdateListener = listener }

private inner class MediaControllerCallback : MediaControllerCompat.Callback() {
    override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
        updateCurrentSong()
        uiUpdateListener?.onUiNeedsUpdate()  // 歌曲变化才通知
    }

    override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
        state?.let { isPlaying = it.state == PlaybackStateCompat.STATE_PLAYING }
        uiUpdateListener?.onUiNeedsUpdate()  // 播放状态变化才通知
    }
}
```

**改动点 2：MainActivity 实现 listener，移除轮询中的 UI 刷新**

```kotlin
class MainActivity : AppCompatActivity(), MediaSessionHelper.ConnectionListener,
                      MediaSessionHelper.UiUpdateListener {  // 新增接口

    // onCreate 中设置
    mediaSessionHelper.setUiUpdateListener(this)

    // 实现 onUiNeedsUpdate
    override fun onUiNeedsUpdate() {
        runOnUiThread { updateUI() }
    }

    // 轮询 runnable 改为只做"保底重连"，不再调用 updateUI()
    private val checkConnectionRunnable = object : Runnable {
        override fun run() {
            if (!mediaSessionHelper.isConnected()) {
                mediaSessionHelper.tryReconnect()
            }
            // updateUI() 已移除：UI 刷新由 MediaControllerCallback 事件驱动
            handler.postDelayed(this, 2000)
        }
    }
}
```

### 2.5 改动 B：指数退避重连（5 min）

**核心思路**：连接断开时，在 MediaSessionHelper 内部启动带指数退避的重连 timer，不依赖外部轮询。

在 `MediaSessionHelper.kt` 中新增：

```kotlin
// MediaSessionHelper.kt 新增字段
private var reconnectRunnable: Runnable? = null
private var currentReconnectDelayMs = 2_000L      // 初始 2 秒
private val maxReconnectDelayMs = 60_000L         // 上限 60 秒
private val backoffMultiplier = 2.0               // 指数因子

private fun startReconnectWithBackoff() {
    reconnectRunnable?.let { handler.removeCallbacks(it) }

    reconnectRunnable = Runnable {
        if (mediaController == null) {
            Log.d(TAG, "退避重连: delay=${currentReconnectDelayMs}ms")
            connectToMusicApp()
            currentReconnectDelayMs = (currentReconnectDelayMs * backoffMultiplier).toLong()
                .coerceAtMost(maxReconnectDelayMs)
            handler.postDelayed(reconnectRunnable!!, currentReconnectDelayMs)
        }
    }
    handler.postDelayed(reconnectRunnable!!, currentReconnectDelayMs)
}

private fun resetReconnectState() {
    reconnectRunnable?.let { handler.removeCallbacks(it) }
    reconnectRunnable = null
    currentReconnectDelayMs = 2_000L
}

// 连接成功时重置退避
override fun onConnected() {
    Log.d(TAG, "MediaBrowser连接成功！")
    resetReconnectState()
    val sessionToken = mediaBrowser?.sessionToken
    // ...原有代码...
}

// 连接断开时启动退避重连
override fun onConnectionSuspended() {
    Log.w(TAG, "MediaBrowser连接中断")
    mediaController = null
    connectionListener?.onDisconnected()
    startReconnectWithBackoff()  // 新增：自动开始退避重连
}
```

### 2.6 改动 C（可选）：用户点击播放时立即重连

用户点击播放但连接未恢复时，允许立即触发一次重连：

```kotlin
fun play() {
    if (mediaController == null) {
        resetReconnectState()
        currentReconnectDelayMs = 0L
        startReconnectWithBackoff()
    } else {
        mediaController?.transportControls?.play()
    }
}
```

### 2.7 验证与量化（2 min）

**指标 1：静止时 UI 刷新次数 / 分钟**

- **加固前**：约 30 次 / 分钟（每 2 秒刷新，即使无变化）
- **加固后**：约 0-3 次 / 分钟（只有切歌/播放暂停时才刷新）

**指标 2：重连间隔序列（log 展示）**

- **加固前**：2s → 2s → 2s → 2s → ...（固定间隔，持续撞）
- **加固后**：2s → 4s → 8s → 16s → 32s → 60s（达到上限后稳定）

录屏方式：强停 MediaCenter，logcat 过滤 `MediaSessionHelper`，展示加固前后的重连序列对比。

---

## 实战 3（20 min）：DLNA Renderer 后台定时器重构——按状态启停与降频

对应项目：`upnp/DLNA`
对应文件：
- `app/src/main/java/com/example/dlna/MediaRendererService.kt`
- `app/src/main/java/com/example/dlna/DLNAService.kt`
- `videoplayer/src/main/java/com/max/videoplayer/MediaPlayerManager.kt`

### 3.1 场景与目标（2min）

**为什么要加固这里？**

DLNA Renderer 常驻后台，开发态实现中有定时器管理的问题：播放时需要高频同步（保证控制点能实时获取进度），但暂停/停止后仍然维持高频运行，造成不必要的系统唤醒和功耗。

本节处理**三层定时器的协同问题**，目标是让后台只做必要的最小工作。

### 3.2 量化现状——定位三个定时器（3 min）

**定时器 1：`statusUpdateTimer`（MediaRendererService 层）**

打开 `MediaRendererService.kt`，找到第 194-210 行：

```kotlin
// MediaRendererService.kt 第 194-210 行
private var statusUpdateTimer: Timer? = null

private fun startStatusUpdateTimer() {
    statusUpdateTimer?.cancel()
    statusUpdateTimer = Timer().apply {
        schedule(object : TimerTask() {
            override fun run() {
                updatePlaybackStatus()  // 每秒从播放器拉状态，写入 UPnP 状态变量
            }
        }, 0, 1000)  // 每秒一次
    }
}
```

这个定时器在 `initialize()` 时启动（第 113 行），**一直跑到服务销毁**——不管播放还是暂停。暂停时状态和进度都不变，每秒重复写入相同值，没有意义。

**定时器 2：`lastChangeTimer`（DLNAService 层）**

打开 `DLNAService.kt`，找到第 250-263 行：

```kotlin
// DLNAService.kt 第 250-263 行
private fun startLastChangeTimer() {
    lastChangeTimer?.cancel()
    lastChangeTimer = java.util.Timer("LastChangeEventTimer", true).apply {
        schedule(object : java.util.TimerTask() {
            override fun run() {
                mediaRendererServiceManager?.fireLastChange()  // 每秒推送 GENA 事件
            }
        }, 1000, 1000)  // 每秒一次
    }
}
```

暂停时 `fireLastChange()` 推送的 `TransportState = PAUSED_PLAYBACK` 每秒重复一次，浪费资源。

**定时器 3（隐性）：`MediaPlayerManager` 的 `progressUpdateRunnable`**

好消息：`MediaPlayerManager` 的进度定时器**已经是按状态启停的**。找到 `videoplayer/src/main/java/com/max/videoplayer/MediaPlayerManager.kt` 第 39-49 行和第 175-188 行：

```kotlin
// MediaPlayerManager.kt 第 39-49 行
private val progressUpdateRunnable = object : Runnable {
    override fun run() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {  // ← 关键：只在播放时触发
                val position = player.currentPosition.toInt()
                stateListener?.onProgressUpdate(position)
            }
        }
        mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
    }
}

// 第 175-188 行：onIsPlayingChanged 回调中已做启停
override fun onIsPlayingChanged(isPlaying: Boolean) {
    if (isPlaying) {
        currentState = PlaybackState.PLAYING
        stateListener?.onPlaybackStateChanged(currentState)
        startProgressTimer()
    } else {
        if (exoPlayer?.playbackState == Player.STATE_READY) {
            currentState = PlaybackState.PAUSED
            stateListener?.onPlaybackStateChanged(currentState)
            stopProgressTimer()  // ← 暂停时已停
        }
    }
}
```

**三层叠加全景（录屏展示时用表格）**

| 定时器 | 位置 | PLAYING | PAUSED | STOPPED |
|--------|------|---------|--------|---------|
| `progressUpdateRunnable` | `MediaPlayerManager.kt` | ✅ 1s 触发 | ❌ 已停 | ❌ 已停 |
| `statusUpdateTimer` | `MediaRendererService.kt` | ✅ 1s 触发 | ✅ **1s 触发（浪费）** | ✅ **1s 触发（浪费）** |
| `lastChangeTimer` | `DLNAService.kt` | ✅ 1s 触发 | ✅ **1s 触发（浪费）** | ✅ **1s 触发（浪费）** |

### 3.3 原理拆解——两个 UPnP 定时器的不同职责（3 min）

**`statusUpdateTimer`**：维持 UPnP 状态变量（`TransportState`、`RelativeTimePosition` 等），供控制点通过 `GetPositionInfo` 等 Action 查询。

关键点：控制点（爱奇艺/B站等）的进度同步主要靠轮询 `GetPositionInfo`，`statusUpdateTimer` 把播放器状态写入状态变量的目的，就是让 `GetPositionInfo` 返回正确进度。所以：
- PLAYING 时：每秒更新位置，必要 ✅
- PAUSED 时：位置不变，不需要每秒重复写入 ❌

**`lastChangeTimer`**：GENA 事件推送，通知订阅者状态变化。暂停时每秒推 `PAUSED_PLAYBACK` 是重复推送——降到 10 秒一次，订阅者不会感知延迟（因为暂停本来就没有实时变化需求）。

### 3.4 改动 1：状态机驱动 `statusUpdateTimer` 启停（7 min）

**改动思路**：`statusUpdateTimer` 不再在 `initialize()` 时启动，改为由播放器的状态变化驱动。

在 `MediaRendererService.kt` 中修改 `setMediaPlayerManager()`：

```kotlin
fun setMediaPlayerManager(manager: MediaPlayerManager) {
    mediaPlayerManagerRef = WeakReference(manager)
    Log.d(TAG, getString(R.string.log_media_player_manager_set))

    // 新增：监听播放器状态，驱动 statusUpdateTimer 的启停
    manager.setStateListener(object : MediaPlayerManager.MediaStateListener {
        override fun onPrepared(durationMs: Int) {}
        override fun onProgressUpdate(positionMs: Int) {}
        override fun onPlaybackStateChanged(state: MediaPlayerManager.PlaybackState) {
            when (state) {
                MediaPlayerManager.PlaybackState.PLAYING -> {
                    startStatusUpdateTimer(intervalMs = 1000L)  // 播放时启动
                }
                MediaPlayerManager.PlaybackState.PAUSED,
                MediaPlayerManager.PlaybackState.STOPPED -> {
                    stopStatusUpdateTimer()  // 暂停/停止时停止
                }
                else -> {}
            }
        }
        override fun onPlaybackCompleted() { stopStatusUpdateTimer() }
        override fun onError(errorMsg: String) { stopStatusUpdateTimer() }
        override fun onBufferingUpdate(percent: Int) {}
        override fun onVideoSizeChanged(width: Int, height: Int) {}
    })
}
```

修改 `startStatusUpdateTimer()` 支持可变间隔：

```kotlin
private var statusUpdateTimer: Timer? = null

private fun startStatusUpdateTimer(intervalMs: Long = 1000L) {
    if (statusUpdateTimer != null) return  // 已在运行则跳过
    statusUpdateTimer = Timer().apply {
        schedule(object : TimerTask() {
            override fun run() { updatePlaybackStatus() }
        }, 0, intervalMs)
    }
    Log.d(TAG, "statusUpdateTimer started, interval=${intervalMs}ms")
}

private fun stopStatusUpdateTimer() {
    statusUpdateTimer?.cancel()
    statusUpdateTimer = null
    Log.d(TAG, "statusUpdateTimer stopped")
}
```

同时移除 `initialize()` 中的 `startStatusUpdateTimer()` 调用（删除第 113 行附近的那行）。

### 3.5 改动 2：`lastChangeTimer` 降频（5 min）

暂停 / 停止时把 GENA 推送频率从 1 秒降到 10 秒。

在 `MediaRendererService.kt` 中新增播放状态回调：

```kotlin
companion object {
    // ... 现有字段 ...
    private var lastChangeTimerIntervalMs = 1000L

    /** 播放状态变化回调，用于通知 DLNAService 调整 lastChangeTimer 频率 */
    var onPlaybackStateChangedForDLNA: ((MediaPlayerManager.PlaybackState) -> Unit)? = null
}
```

修改 `setMediaPlayerManager` 中的状态监听，加入转发：

```kotlin
override fun onPlaybackStateChanged(state: MediaPlayerManager.PlaybackState) {
    when (state) {
        MediaPlayerManager.PlaybackState.PLAYING -> {
            startStatusUpdateTimer(intervalMs = 1000L)
            lastChangeTimerIntervalMs = 1000L
        }
        MediaPlayerManager.PlaybackState.PAUSED,
        MediaPlayerManager.PlaybackState.STOPPED -> {
            stopStatusUpdateTimer()
            lastChangeTimerIntervalMs = 10_000L  // 暂停/停止时降到 10 秒
        }
        else -> {}
    }
    onPlaybackStateChangedForDLNA?.invoke(state)  // 通知 DLNAService
}
```

在 `DLNAService.kt` 中接收回调并动态调整：

```kotlin
private var lastChangeTimer: java.util.Timer? = null
private var lastChangeIntervalMs = 1000L

// 在 createDevice() 中设置回调
MediaRendererService.onPlaybackStateChangedForDLNA = { state ->
    val newInterval = when (state) {
        MediaPlayerManager.PlaybackState.PLAYING -> 1000L
        else -> 10_000L  // PAUSED / STOPPED 时降到 10 秒
    }
    if (newInterval != lastChangeIntervalMs) {
        lastChangeIntervalMs = newInterval
        restartLastChangeTimer()
    }
}

private fun restartLastChangeTimer() {
    lastChangeTimer?.cancel()
    lastChangeTimer = java.util.Timer("LastChangeEventTimer", true).apply {
        schedule(object : java.util.TimerTask() {
            override fun run() { mediaRendererServiceManager?.fireLastChange() }
        }, lastChangeIntervalMs, lastChangeIntervalMs)
    }
    Log.d(TAG, "lastChangeTimer restarted, interval=${lastChangeIntervalMs}ms")
}
```

### 3.6 验证与量化（2 min）

**Timer 触发次数 / 分钟（录屏展示）**

| 场景 | statusUpdateTimer 加固前 | statusUpdateTimer 加固后 | lastChangeTimer 加固前 | lastChangeTimer 加固后 |
|------|:----------------------:|:----------------------:|:-------------------:|:-------------------:|
| PLAYING 1 分钟 | 60 次 | 60 次 | 60 次 | 60 次 |
| PAUSED 1 分钟 | 60 次 | **0 次** | 60 次 | **6 次** |
| STOPPED 1 分钟 | 60 次 | **0 次** | 60 次 | **6 次** |

**录屏方式**：
1. 在 `updatePlaybackStatus()` 里加计数，60 秒打印一次
2. 在 `fireLastChange()` 调用处加计数，60 秒打印一次
3. 分别展示 PLAYING 1 分钟和 PAUSED 1 分钟的 log 输出

**DLNA 控制点体验不受影响**：PLAYING 时进度每秒同步，PAUSED 时 GENA 仍以 10 秒频率推送（`TransportState = PAUSED_PLAYBACK`），控制点完全感知不到差异。

---

## 结尾（5 min）：学员可迁移的"生产加固清单"

### 总结口播（可直接照读）

1. **功能开发是第一步**：能跑不代表能上生产，车机环境更苛刻。
2. **把轮询改为事件驱动**：变化才刷新，减少无效 IPC 和渲染工作。
3. **把后台定时器改为按状态启停**：暂停/停止时别耗电。
4. **避免定时器叠加**：确认每层定时器都有独立价值，否则去掉冗余层。

### 迁移模板（写在一页 PPT）

**拿到一个新项目，加固前先问自己三个问题**

1. 这个定时器 / 轮询在 PLAYING 状态下是否必要？→ **必要则保留**
2. 暂停 / 停止时它是否还在跑？→ **还在跑则改为按状态启停**
3. 有没有两层定时器在读同一份数据？→ **有则合并或去掉一层**

**量化三指标（每次加固前后都记录）**

| 指标类型 | 怎么量 | 工具 |
|---------|--------|------|
| 频率 | 每分钟触发次数 | log 计数（60 秒时间窗口） |
| 耗时 | 每次调用的耗时 | `System.nanoTime()` 打点 |
| 稳定性 | 重连成功率、恢复耗时 | log 统计 |

**结束句**：功能开发是第一步，生产加固是第二步。你们现在手上的项目能跑了，下一步就是用今天的方法做一轮加固。

---

## 备忘：本章关联项目路径

- MediaCenter：`/Users/chaodikong/Android/workspace/media_center`
  - 核心文件：`app/src/main/java/com/max/media_center/MediaService.kt`
- CarLauncherSimulator：`/Users/chaodikong/Android/workspace/CarLauncherSimulator`
  - 核心文件：
    - `app/src/main/java/com/max/carlaunchersimulator/MainActivity.kt`
    - `app/src/main/java/com/max/carlaunchersimulator/MediaSessionHelper.kt`
- DLNA Renderer：`/Users/chaodikong/Android/workspace/upnp/DLNA`
  - 核心文件：
    - `app/src/main/java/com/example/dlna/MediaRendererService.kt`
    - `app/src/main/java/com/example/dlna/DLNAService.kt`
    - `videoplayer/src/main/java/com/max/videoplayer/MediaPlayerManager.kt`
