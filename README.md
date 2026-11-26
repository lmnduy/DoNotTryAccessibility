# DoNotTryAccessibility · Zygisk Edition

让指定应用认为设备**没有开启无障碍服务**，同时隐藏 **USB 调试 / 开发者选项**状态。

本仓库是 [lmnduy/DoNotTryAccessibility](https://github.com/lmnduy/DoNotTryAccessibility) 的改造版：

| | 原版 | 本版 |
|---|---|---|
| 运行方式 | LSPosed（Xposed） | **仅 Zygisk**，不依赖 LSPosed |
| 注入位置 | system_server | system_server（`postServerSpecialize`） |
| 配置入口 | 独立 APK | **模块内置 WebUI**（KernelSU / APatch），无 APK |
| 自动填充 | 手动逐个勾选 | **首次开启后，只自动加入之后新安装的应用** |
| USB 调试 | 无 | **新增隐藏**（`adb_enabled` 等） |
| 构建 | 本地 Gradle | **GitHub Actions 自动构建** + 本地脚本 |

---

## 功能

### 1. 无障碍隐藏（沿用原逻辑）

在 system_server 中按调用方 UID 判断包名，命中后：

- `AccessibilityManagerService#addClient` 返回 `0`，应用侧的 `AccessibilityManager` 保持“未启用”；
- `getEnabledAccessibilityServiceList` 返回空列表；
- `getInstalledAccessibilityServiceList` 返回空的 `ParceledListSlice`；
- `ContentProvider.Transport#call` 拦截 `settings` 的 `GET_secure`：
  - `enabled_accessibility_services` → `""`
  - `accessibility_enabled` → `"0"`

### 2. USB 调试隐藏（新增）

同一套“按包名伪造”的逻辑，额外拦截 `GET_global`：

- `adb_enabled` → `"0"`
- `adb_wifi_enabled` → `"0"`
- `development_settings_enabled` → `"0"`

因此读取 `Settings.Global.getInt(ADB_ENABLED)` 的检测会得到“未开启 USB 调试”。
对于**被勾选的用户应用**，还会在应用进程内补齐 ADB 运行时表面（Duck Detector 等会交叉检查）：

- `SystemProperties.get`：`init.svc.adbd` → `stopped`，`sys.usb.state` → `none`，`persist.sys.test_harness` / `service.adb.root` → `0`；
- `Os.stat("/dev/socket/adbd")` → `ENOENT`，`File("/dev/socket").list()` 中移除 `adbd`；
- USB sticky broadcast：`adb=false`；
- Duck Detector 的 `AdbJdwpSocketBridge.nativeConnectErrno` → `ECONNREFUSED`。

这些应用进程钩子只会注入到**已勾选/自动填充**的用户应用；启动器、系统 UI、电话、设置等启动关键进程不会注入。系统属性仍可能因 `getprop`/native 与反射源不一致而留下“属性需复核”类警告，内存扫描也可能看到 Hook 痕迹。

### 3. 自动填充（移植自 OhMyKeymint 的逻辑）

第一次在 WebUI 打开“自动填充”时，system_server 会为每个 Android 用户记录一份“已安装用户应用”基线，**基线里的应用一个都不会被选中**。

之后的扫描中：

- 只有基线里没有的新包才会被自动加入拦截列表；
- 同一次扫描出现多个新包时，**只加入 APK 安装时间最新的那个**，其余只进入基线、不选中；
- 应用更新、未变化的应用不会重新加入；
- 手动取消勾选的应用进入白名单，不会被自动加回；
- 系统应用不参与；无法读取的用户资料保留旧基线，新发现的用户第一次扫描只做基线；
- 关闭自动填充不会取消已有选择，但会清空基线；再次开启会重新取基线，关闭期间安装的应用不会被批量加入；
- 通过系统 `PackageManagerInternal.PackageListObserver` 和 `ACTION_PACKAGE_ADDED` 事件驱动，**不再定时轮询**；只有开启自动填充和开机各做一次基线/补偿扫描。

实现见 `AutoFill.java`，语义对应 OhMyKeymint 的 `injector/src/auto_targets.rs`。

### 4. 模块 WebUI

- 配置存放在 `Settings.Global`：
  - `dnta_config`：JSON 配置
  - `dnta_status`：模块心跳（含 `boot_count`，用于判断“本次开机是否生效”）
- WebUI 通过 KernelSU / APatch 的 `ksu.exec` 以 root 身份读写 `settings`，**不需要安装任何 APK**。
- 支持：总开关、无障碍隐藏、USB 调试隐藏、自动填充、应用搜索/筛选/勾选、清空自动填充记录。

> Magisk 本身没有模块 WebUI 接口。Magisk 用户可安装第三方 WebUI Host，或手动执行
> `adb shell su -c "settings put global dnta_config '<json>'"`。

---

## 架构

```
Magisk / KernelSU / APatch
        │  加载 zygisk/<abi>.so
        ▼
Zygisk  postServerSpecialize（system_server，尚未进入 SystemServer.main）
        │
        ├─ InMemoryDexClassLoader 加载内嵌 hook dex（native/src/dex_data.cpp）
        ├─ 链接进 .so 的 Pine 注册 JNI（register_Pine / register_Ruler）
        ▼
Bootstrap.start()
        │
        ├─ Pine.ensureInitialized()（关闭 hidden API 限制）
        ├─ HookInstaller：
        │     • AccessibilityManagerService.addClient
        │     • getEnabledAccessibilityServiceList
        │     • getInstalledAccessibilityServiceList
        │     • ContentProvider$Transport.call
        ├─ Config.startObserver()   → ContentObserver 监听 Settings.Global 变化
        ├─ AutoFill                  → 新安装包监听 + 补偿扫描
        └─ Ctx 启动时等待 system context → 写心跳、注册安装事件监听
```

- **无 LSPosed / 无 Xposed API**：只使用 [Pine](https://github.com/canyie/pine) 做 ART 方法替换。
- **无 APK**：配置由 WebUI 写 `Settings.Global`，system_server 直接读取。
- **无文件权限问题**：dex 和 Pine 都编译进 Zygisk 的 `.so`，不依赖 `/data/adb` 路径可读性。

---

## 构建

### GitHub Actions（推荐）

仓库已包含 [`.github/workflows/build.yml`](.github/workflows/build.yml)：

- push / PR / 手动 `workflow_dispatch` 时自动构建；
- artifact：`DoNotTryAccessibility-zygisk-flashable`，**下载后可直接刷入**（artifact 的 zip 根目录就是 `module.prop`，不要再解压套一层）；
- 打 `v*` tag 时自动创建 Release 并上传 `DoNotTryAccessibility-zygisk-v2.0.0.zip`。

在 GitHub 上 fork / 上传本目录后，Actions 会安装 `android-34`、`build-tools;35.0.0`、`ndk;26.1.10909125`、`cmake;3.22.1` 并运行 `build.sh`。

### 本地（Windows）

需要 JDK 17+、Android SDK（platform 34、build-tools 35、NDK 26、CMake 3.22）：

```powershell
.\build.ps1
# 可选：
.\build.ps1 -NdkVersion 26.1.10909125 -BuildToolsVersion 35.0.0
```

### 本地（Linux / macOS / WSL）

```bash
./build.sh
```

产物输出到 `dist/`。

---

## 安装

1. 刷入模块 zip（任选其一）：
   - GitHub Actions 的 **`DoNotTryAccessibility-zygisk-flashable`** artifact：下载后**直接刷**，它的根目录就是 `module.prop`，不要再解压套一层；
   - 本地/Release 的 `DoNotTryAccessibility-zygisk-v2.0.0.zip`；
2. 确认 Zygisk（Zygisk Next）已启用，重启；
3. 打开模块的 **WebUI**（KernelSU/APatch 模块卡片的“打开”按钮）；
4. 按需打开总开关 / 无障碍隐藏 / USB 调试隐藏；
5. 第一次打开“自动填充”会记录基线；之后系统安装回调会自动把新用户应用加入。

### 状态判断

WebUI 读取 `dnta_status` 中的 `boot` 与当前 `Settings.Global.boot_count` 比较：

- 相等 → 本次开机 system_server 钩子已成功注入；
- 不相等 / 无心跳 → 模块未生效，检查 Zygisk、模块是否启用、`adb logcat -s DNTA-Zygisk`。

### 日志

```bash
adb logcat -s DNTA-Zygisk Pine
```

---

## 配置格式

`Settings.Global → dnta_config`：

```json
{
  "enabled": true,
  "hideAccessibility": true,
  "hideAdb": true,
  "autoFill": true,
  "autoUsers": {"0": ["com.example.old"]},
  "block": ["com.example.detector"],
  "auto": ["com.example.newapp"],
  "allow": ["com.example.trusted"]
}
```

- `block`：手动勾选；
- `auto`：自动填充；
- `allow`：手动取消（白名单，优先级最高）；
- `autoUsers`：每个 Android 用户的安装基线，由 system_server 自动维护；
- 开启自动填充时会先清空基线，等待 system_server 记录新的基线；
- 实际拦截集合 = `(block ∪ auto) − allow`。

---

## 已知限制

- **system_server 钩子有风险**：如果 Pine 与某 ROM 的 ART 不兼容，可能导致 system_server 崩溃 / 开机循环。建议先备份，并了解如何进入 Magisk/KernelSU 安全模式。
  模块自带保护：连续 3 次开机都没有跑到 `late_start`，`post-fs-data.sh` 会自动写入 `disable` 并停止加载。也可手动执行
  `adb shell su -c "touch /data/adb/modules/donottryaccessibility_zygisk/disable"` 后重启。
- 只覆盖 **基于 `Settings` / `AccessibilityManagerService` 的检测**；直接扫描 `/proc`、检查 ADB 端口、读取 `ro.debuggable` 等方式不在范围内。
- 自动填充只处理**用户应用**，系统应用（含 OTA 预装）不会被自动加入，避免误伤系统 UI。
- WebUI 依赖 KernelSU 3.x / APatch 的 `ksu` 接口；Magisk 无原生 WebUI。
- 应用列表的图标/名称依赖管理器 `ksu.getPackagesInfo`；不支持时回退为包名显示。
- 本版未在实机上完整验证（本机 Android 16 已用 `app_process` 验证 Pine 的 ART hook 可用：`HOOK_INT=42`、`HOOK_TEXT=orig-hooked`）。

---

## 目录结构

```
.
├── .github/workflows/build.yml   GitHub Actions
├── build.sh                      本地/CI 构建（Linux）
├── build.ps1                     本地构建（Windows）
├── hook/                         注入 system_server 的 Java 代码（含 Pine Java API）
│   └── src/io/github/nitsuya/donottryaccessibility/hook/
├── native/                       Zygisk 模块 + Pine C++ + 内嵌 dex
│   ├── pine/                     Pine core（third-party）
│   ├── src/module.cpp            Zygisk 入口 / JNI 注入
│   └── src/dex_data.cpp          构建时生成
├── module/
│   ├── module.prop
│   ├── customize.sh
│   ├── uninstall.sh
│   ├── META-INF/...
│   └── webroot/                  WebUI（index.html / app.js / style.css）
└── dist/                         构建产物
```

---

## 致谢与许可

- 原项目：[Nitsuya/DoNotTryAccessibility](https://github.com/Nitsuya/DoNotTryAccessibility)（GPL-3.0）
- ART hook：[canyie/Pine](https://github.com/canyie/pine)（Anti-996 License）
- Zygisk API：Magisk（topjohnwu）
- WebUI API：KernelSU

本仓库作为原项目的衍生修改，沿用 GPL-3.0；Pine 的版权与许可声明见 `third_party/licenses/NOTICE`。
