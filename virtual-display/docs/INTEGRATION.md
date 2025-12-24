# 接入指南（迁移到另一个项目）

下面假设你的新项目是一个标准的 Android Gradle 工程，并且你希望在 App 内启动 Shower server（`app_process + shower-server.jar`），再通过 Binder 控制虚拟屏并拉取视频帧。

---

## 0. 你需要准备的能力（关键前置）

Shower 的 server 是通过 shell 身份启动的，并且会调用系统服务创建 `VirtualDisplay`。因此宿主 App 需要具备「能执行 shell 命令」的能力，至少要能执行：

- `cp ... /data/local/tmp/shower-server.jar`
- `CLASSPATH=/data/local/tmp/shower-server.jar app_process / com.ai.assistance.shower.Main &`
- `pkill -f com.ai.assistance.shower.Main`

常见实现方式：

- ADB / 调试环境（`adb shell` 权限）
- Shizuku（shell 权限）
- root（不推荐作为唯一方案，但可用）

本包的 `showerclient` 不绑定具体实现，而是通过 `ShellRunner` 由宿主注入。

---

## 1. 拷贝模块并引入工程

把 `virtual-display/android/showerclient/` 复制到你的新项目（例如放到仓库根目录 `showerclient/`）。

注意：

- `showerclient` 当前 `minSdk = 26`（如需更低版本支持，需要你自行调整并处理兼容性）
- `virtual-display/android/showerclient/build.gradle.kts` 已改成不依赖 version catalog（`libs.*`），但依赖版本你可以按项目统一升级/降级

在新项目 `settings.gradle(.kts)` 中加入：

```kotlin
include(":showerclient")
```

在 App 模块 `build.gradle(.kts)` 中加入：

```kotlin
dependencies {
    implementation(project(":showerclient"))
}
```

---

## 2. 注入 ShellRunner（必须）

`showerclient` 通过 `ShowerEnvironment.shellRunner` 获取执行 shell 的能力：

```kotlin
class YourShellRunner : ShellRunner {
    override suspend fun run(command: String, identity: ShellIdentity): ShellCommandResult {
        // 1) 执行 command（按 identity 选择 DEFAULT/SHELL/ROOT 等映射）
        // 2) 将 stdout/stderr/exitCode 转换为 ShellCommandResult 返回
    }
}

class YourApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShowerEnvironment.shellRunner = YourShellRunner()
    }
}
```

`showerclient` 模块已经自带 `assets/shower-server.jar`，宿主 App 不需要额外拷贝 jar 文件；运行时会先把它从 assets 拷贝到 `/sdcard/Download/Operit/`，再复制到 `/data/local/tmp/` 并用 `app_process` 启动。

---

## 3. 接收 Binder 交接广播（必须）

Shower server 启动后会发送一个广播，把 `IShowerService` 的 `IBinder` 交给客户端缓存（库内只负责“缓存”，不负责“接收广播”）。

### 3.1 广播约定（当前 jar 的默认值）

server 端默认常量在 `com.ai.assistance.shower.Main`：

- Action：`com.ai.assistance.operit.action.SHOWER_BINDER_READY`
- Extra key：`binder_container`（类型 `com.ai.assistance.shower.ShowerBinderContainer`）

如果你不改 server jar，**新项目里也需要监听这个 Action**。

另外，当前 server 会显式指定广播目标包名：

- `intent.setPackage("com.ai.assistance.operit")`

这意味着：如果你的新项目包名不是 `com.ai.assistance.operit`，将收不到 Binder 广播，`ShowerBinderRegistry` 会一直为空。

迁移到新项目时通常有两种做法（二选一）：

- 修改 `com.ai.assistance.shower.Main`：去掉 `intent.setPackage(...)` 或改成你的新项目包名，然后重新构建 `shower-server.jar`
- 保持不改：把新项目包名也设置为 `com.ai.assistance.operit`（一般不现实，不推荐）

### 3.2 参考 Receiver

```kotlin
class ShowerBinderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.ai.assistance.operit.action.SHOWER_BINDER_READY") return
        val container = intent.getParcelableExtra<ShowerBinderContainer>("binder_container")
        val service = container?.binder?.let { IShowerService.Stub.asInterface(it) }
        ShowerBinderRegistry.setService(service)
    }
}
```

然后在 `AndroidManifest.xml` 注册它（或按需动态注册）。

因为这是“来自进程外”的广播，Manifest 静态注册时通常需要设置 `android:exported="true"`（以你项目的 targetSdk/系统版本要求为准）。

---

## 4. 启动 server + 创建虚拟屏

```kotlin
// 1) 启动 server，并等待 Binder 就绪（最多约 10s）
val ok = ShowerServerManager.ensureServerStarted(context)
if (!ok) return

// 2) 创建/更新虚拟屏
val displayOk = ShowerController.ensureDisplay(
    context = context,
    width = 1080,
    height = 2400,
    dpi = 480,
    bitrateKbps = 8000,
)
if (!displayOk) return
```

---

## 5. 输入注入 / 截图 / 视频渲染（可选）

- 输入：`tap` / `swipe` / `touchDown` / `touchMove` / `touchUp` / `key`
- 截图：`ShowerController.requestScreenshot()`
- 视频：推荐直接复用 `com.ai.assistance.showerclient.ui.ShowerSurfaceView`

更详细的 API 示例见：`virtual-display/android/showerclient/README.md`。

---

## 6. 排错

- server 端日志：`/data/local/tmp/shower.log`
- 常见失败点：
  - 没有注入 `ShowerEnvironment.shellRunner`
  - shell 权限不足导致 `app_process` 启动失败或无法创建 `VirtualDisplay`
  - 没有注册/接收到 Binder 广播，导致 `ShowerBinderRegistry` 一直为空

---

## 7. 混淆（可选）

如果宿主 App 开启了 R8/ProGuard，建议加上 keep 规则避免 Binder 接口被混淆（按需裁剪）：

```
-keep class com.ai.assistance.shower.IShowerService { *; }
-keep class com.ai.assistance.shower.IShowerVideoSink { *; }
-keep class com.ai.assistance.shower.ShowerBinderContainer { *; }
```
