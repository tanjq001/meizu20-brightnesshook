# Brightness Curve Hook

> 作者：酷安 折翼之舞007

基于 LSPosed/Xposed 的 Android 自动亮度调节模块，通过 hook 系统框架
（`com.android.server.display.AutomaticBrightnessController`）实现对亮度曲线的精细化控制。

## 背景

原方案 `brctl` 是一个 Magisk/KernelSU 模块，通过轮询 sysfs 背光节点
（`/sys/class/backlight/*/brightness`）在系统调整亮度后再做乘法修正。这种方式存在以下问题：

- 轮询有延迟，且与系统自动亮度"打架"，容易闪屏
- 修改的是亮度"输出"，系统曲线本身没有变化

本项目改用 **LSPosed hook** 直接在框架层拦截，不碰 sysfs、不碰签名、无平台密钥限制。

## 工作原理

hook 目标类：`com.android.server.display.AutomaticBrightnessController`（system_server 进程）。

数据流：

```
光感传感器
  → handleLightSensorEvent(long 时间戳, float lux)   ← Hook 1（输入：倍率/中值滤波/延迟）
  → 环形缓冲 + 短/长时窗滤波
  → 亮度曲线（lux → nits）
  → getAutomaticScreenBrightness(BrightnessEvent)     ← Hook 2（输出：分段倍率）
  → 屏幕背光
```

防抖时间在构造函数中初始化，通过反射修改（Hook 3）。

### Hook 1：光感输入拦截

拦截 `handleLightSensorEvent(long, float)`，在 lux 进入系统曲线**之前**修改：

- **倍率**：`lux × lux_inc`
- **滤波**：在 `smooth_window` 时间窗内取中值（或均值，见 `filter_mode`）
- **最小值**：光感低于 `lux_min` 时按 `lux_min` 算

### Hook 2：亮度输出拦截

拦截 `getAutomaticScreenBrightness(BrightnessEvent)`，对系统算出的亮度值（0~1 归一化）做分段倍率：

```
percent < low_max  → × low_inc
low_max ≤ percent < mid_max → × mid_inc
percent ≥ mid_max  → × hig_inc
```

### Hook 3：防抖时间修改

hook 所有构造函数，反射修改：

- `mBrighteningLightDebounceConfig`（变亮防抖）
- `mDarkeningLightDebounceConfig`（变暗防抖）

## 参数配置

所有参数通过**系统属性**（`persist.brctl.*`）配置，`setprop` 后**立即生效**，
`persist.` 前缀保证重启后保留。

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `persist.brctl.lux_inc` | 1.3 | 光感倍率，>1 更亮，<1 更暗 |
| `persist.brctl.lux_min` | 0 | 光感最小值，低于按此值算（lux） |
| `persist.brctl.smooth_window` | 4000 | 滤波时间窗（毫秒） |
| `persist.brctl.filter_mode` | median | 滤波方式：median 中值 / mean 均值 |
| `persist.brctl.low_max` | 30 | 低亮度档上限（百分比 0~100） |
| `persist.brctl.mid_max` | 60 | 中亮度档上限（百分比 0~100） |
| `persist.brctl.low_inc` | 1.0 | 低亮度输出倍率 |
| `persist.brctl.mid_inc` | 1.0 | 中亮度输出倍率 |
| `persist.brctl.hig_inc` | 1.0 | 高亮度输出倍率 |
| `persist.brctl.brighten_debounce` | 1000 | 变亮防抖（毫秒） |
| `persist.brctl.darken_debounce` | 2000 | 变暗防抖（毫秒） |

### 示例

```bash
# 查看当前配置
su -c 'getprop | grep persist.brctl'

# 整体调亮（系统以为环境更亮）
su -c 'setprop persist.brctl.lux_inc 1.5'

# 中值滤波（抑制明暗跳跃）
su -c 'setprop persist.brctl.lux_min 10'
su -c 'setprop persist.brctl.smooth_window 1000'

# 提高响应速度（缩短防抖）
su -c 'setprop persist.brctl.brighten_debounce 500'
su -c 'setprop persist.brctl.darken_debounce 1500'

# 暗处额外增亮（输出分段倍率）
su -c 'setprop persist.brctl.low_inc 1.3'
```

## 验证生效

```bash
# 查看 hook 加载日志
su -c 'grep BrightnessHook /data/adb/lspd/log/modules_*.log'

# 查看光感拦截日志（原始 lux → 修改后 lux）
su -c 'grep "lux .* ->" /data/adb/lspd/log/modules_*.log | tail -20'

# 查看系统实际防抖值
su -c 'dumpsys display | grep -E "DebounceConfig"'
```

## 构建

依赖（Termux 环境）：

```bash
pkg install openjdk-17 aapt2 apksigner d8
```

构建步骤：

```bash
cd ~/xposed

# 1. 编译 Java（含 API 桩）
javac -source 8 -target 8 -encoding UTF-8 -d build/classes $(find src -name "*.java")

# 2. 转 dex（只打包 com/brctl 自己的类，API 桩仅用于编译）
d8 --output build/ --lib build/classes \
    build/classes/com/brctl/brightnesshook/Main.class \
    build/classes/com/brctl/brightnesshook/Main\$*.class

# 3. 链接 APK（-I 指向 framework-res.apk）
aapt2 link -o build/app-unsigned.apk -I framework-res.apk \
    --manifest AndroidManifest.xml

# 4. 打包 dex 和 assets
cd build && mkdir -p assets && cp ../assets/xposed_init assets/
jar uf app-unsigned.apk classes.dex assets/xposed_init

# 5. 签名
keytool -genkeypair -keystore testkey.jks -alias testkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=test"
apksigner sign --ks testkey.jks --ks-pass pass:android \
    --key-pass pass:android --out brightnesshook.apk app-unsigned.apk

# 6. 安装
su -c 'pm install -r brightnesshook.apk'
```

## 安装与启用

1. 安装模块 APK（`pm install`）
2. 安装 LSPosed 管理器（KernelSU 模块 `zygisk_lsposed` 自带 `manager.apk`）
3. 打开 LSPosed → 模块 → 启用 **Brightness Curve Hook**
4. 作用域勾选 **系统框架（System Framework）**
5. 重启手机

## 项目结构

```
xposed/
├── AndroidManifest.xml                  # 模块清单（xposedmodule 声明）
├── assets/
│   └── xposed_init                      # 入口类声明
├── src/
│   ├── com/brctl/brightnesshook/
│   │   └── Main.java                    # 核心 hook 逻辑
│   └── de/robv/android/xposed/          # API 桩（仅编译用）
│       ├── IXposedHookLoadPackage.java
│       ├── XC_MethodHook.java
│       ├── XposedBridge.java
│       ├── XposedHelpers.java
│       └── callbacks/XC_LoadPackage.java
└── build/                               # 构建产物（git 忽略）
```

> 注：`de.robv.android.xposed.*` 是编译用的**桩类**，签名与 LSPosed 真实 API 一致。
> 运行时由 LSPosed 框架提供真实实现，模块 APK 不打包这些类。

## 适用范围

- 已 root（KernelSU/Magisk）+ 已装 LSPosed（Zygisk）
- 在 MEIZU 20（Flyme 10.5 / Android 14，SDK 34）上开发测试
- 目标类与方法基于 Android 14 框架，其他版本可能需要调整 hook 点
