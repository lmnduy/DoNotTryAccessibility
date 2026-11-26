# DoNotTryAccessibility · Zygisk Edition

让指定应用认为设备**没有开启无障碍服务**。

本仓库是 [lmnduy/DoNotTryAccessibility](https://github.com/lmnduy/DoNotTryAccessibility) 的 Zygisk 改造版：

| | 原版 | 本版 |
|---|---|---|
| 运行方式 | LSPosed（Xposed） | **仅 Zygisk**，不依赖 LSPosed |
| 注入位置 | system_server | system_server（延迟到系统就绪） |
| 配置入口 | 独立 APK | **模块内置 WebUI**（KernelSU / APatch），无 APK |
| 自动填充 | 手动逐个勾选 | 首次开启后只自动加入之后新安装的应用 |
| USB / ADB | 无 | **明确不处理，只隐藏无障碍** |
| 构建 | 本地 Gradle | **GitHub Actions** + 本地脚本 |

---

## 功能

### 无障碍隐藏

在 system_server 中按调用方 UID 判断包名，命中后：

- `AccessibilityManagerService#addClient` 返回 `0`，应用侧 `AccessibilityManager` 保持“未启用”；
- `getEnabledAccessibilityServiceList` 返回空列表；
- `getInstalledAccessibilityServiceList` 返回空 `ParceledListSlice`；
- `ContentProvider.Transport#call` 拦截 `settings` 的 `GET_secure`：
  - `enabled_accessibility_services` → `""`
  - `accessibility_enabled` → `"0"`

### 不隐藏 USB 调试 / ADB

本版**只处理无障碍**：

- 不再 hook `adb_enabled` / `adb_wifi_enabled` / `development_settings_enabled`；
- 不向任何应用进程注入代码，不处理 `init.svc.adbd`、`sys.usb.state`、`/dev/socket/adbd`、JDWP 等 ADB 运行时表面；
- 因此 Duck Detector 的 **ADB 运行时**检测仍会命中，这是预期行为。

这样模块只保留 system_server 钩子，启动风险最低。

### 自动填充

第一次在 WebUI 打开“自动填充”时，system_server 为每个 Android 用户记录“已安装用户应用”基线：

- 只有基线里没有的新包才会被自动加入拦截列表；
- 同一次扫描出现多个新包时，只加入安装时间最新的那个；
- 应用更新、未变化的应用不会重新加入；
- 手动取消勾选的应用进入白名单，不会被自动加回；
- 关闭自动填充会清空基线，重新开启重新取基线；
- 通过系统 `PackageManagerInternal.PackageListObserver` + `ACTION_PACKAGE_ADDED` 事件驱动，**不做定时轮询**；只在开启自动填充和开机时各做一次基线/补偿扫描。

### 模块 WebUI

- 配置存放在 `Settings.Global`：
  - `dnta_config`：JSON 配置
  - `dnta_status`：模块心跳（含 `boot_id`，用于判断本次开机是否生效）
- WebUI 通过 KernelSU / APatch 的 `ksu.exec` 以 root 身份读写 `settings`，不需要 APK。
- 支持：总开关、无障碍隐藏、自动填充、应用搜索/筛选/勾选、已勾选置顶、清空自动填充记录。

> Magisk 本身没有模块 WebUI。Magisk 用户可安装第三方 WebUI Host，或手动执行
> `adb shell su -c "settings put global dnta_config '<json>'"`。

---

## 架构

```
Zygisk  postServerSpecialize（system_server）
        │
        ├─ InMemoryDexClassLoader 加载内嵌 hook dex
        ├─ 链接进 .so 的 Pine 注册 JNI
        └─ Bootstrap.start()
              │
              ├─ 后台等待 system_server 就绪（SettingsProvider 可读、services.jar 可加载）
              ├─ HookInstaller：
              │     • AccessibilityManagerService.addClient
              │     • getEnabledAccessibilityServiceList
              │     • getInstalledAccessibilityServiceList
              │     • ContentProvider$Transport.call（仅无障碍键）
              ├─ Config ContentObserver
              ├─ AutoFill（安装事件 + 开机补偿）
              └─ Status 心跳
```

无障碍检测几乎都在 system_server，因此**不需要任何应用进程注入**。这也是本版稳定的关键。

---

## 构建

### GitHub Actions

仓库包含 [`.github/workflows/build.yml`](.github/workflows/build.yml)：

- push / PR / 手动 `workflow_dispatch` 自动构建；
- artifact：`DoNotTryAccessibility-zygisk-flashable`，下载后**直接刷入**（zip 根目录就是 `module.prop`）；
- 打 `v*` tag 自动创建 Release。

### 本地

Windows：

```powershell
.\build.ps1
```

Linux / WSL：

```bash
./build.sh
```

产物输出到 `dist/`。

---

## 安装

1. 刷入模块 zip（Actions artifact 或 `dist/DoNotTryAccessibility-zygisk-*.zip`）；
2. 确认 Zygisk（Zygisk Next）已启用，重启；
3. 打开模块 WebUI；
4. 打开总开关 / 无障碍隐藏；
5. 按需打开“自动填充”。

### 状态

WebUI 比较 `dnta_status.bootId` 与 `/proc/sys/kernel/random/boot_id`，一致才显示“已生效”。

### 日志

```bash
adb logcat -s DNTA-Zygisk
```

---

## 配置格式

`Settings.Global → dnta_config`：

```json
{
  "enabled": true,
  "hideAccessibility": true,
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
- `autoUsers`：每个用户的安装基线；
- 实际拦截集合 = `(block ∪ auto) − allow`。

---

## 已知限制

- **只覆盖无障碍**：不隐藏 USB 调试 / ADB，Duck Detector 的 ADB 运行时检测仍会命中。
- system_server 钩子仍有风险；模块连续 3 次未跑到 `late_start` 会自动写 `disable`。
  应急：`adb shell su -c "touch /data/adb/modules/donottryaccessibility_zygisk/disable"` 后重启。
- 只覆盖基于 `Settings` / `AccessibilityManagerService` 的检测；直接扫描 `/proc` 等方式不在范围内。
- 自动填充只处理用户应用，系统应用不会被自动加入。
- WebUI 依赖 KernelSU / APatch 的 `ksu` 接口；Magisk 无原生 WebUI。

---

## 目录结构

```
.
├── .github/workflows/build.yml   GitHub Actions
├── build.sh / build.ps1          构建脚本
├── hook/                         注入 system_server 的 Java 代码
├── native/                       Zygisk 模块 + Pine + 内嵌 dex
├── module/                       module.prop / 脚本 / webroot
└── dist/                         构建产物
```

---

## 致谢与许可

- 原项目：[Nitsuya/DoNotTryAccessibility](https://github.com/Nitsuya/DoNotTryAccessibility)（GPL-3.0）
- ART hook：[canyie/Pine](https://github.com/canyie/pine)（Anti-996 License）
- Zygisk API：Magisk / Zygisk Next
- WebUI API：KernelSU

本仓库作为原项目的衍生修改，沿用 GPL-3.0；Pine 许可见 `third_party/licenses/NOTICE`。
