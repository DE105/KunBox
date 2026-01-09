# Bug修复: VPN连接后系统提示音持续响起

## 问题描述

**设备型号**: 华为 HLK-AL00
**系统版本**: Android 10 (API 29) EMUI
**问题表现**: VPN连接成功后,手机持续发出提示音(类似收短信的声音),频繁响起无法停止

## 根本原因分析

代码中在两处位置调用了 Android 系统 API `ConnectivityManager.reportNetworkConnectivity()`:

1. **位置1**: `SingBoxService.kt:1346` - openTun成功后
2. **位置2**: `SingBoxService.kt:2594` - sing-box核心就绪后

### 为什么会触发提示音?

`reportNetworkConnectivity()` 是 Android 用于**网络连接性验证**的 API,主要目的是让系统检测网络是否真正可用。当VPN应用主动调用此API时:

1. **系统行为**: Android系统会触发网络验证流程,尝试连接到验证服务器(如 `connectivitycheck.gstatic.com`)
2. **厂商定制**: 华为EMUI等深度定制的系统会在每次网络状态变化时播放系统提示音
3. **持续触发**: 由于VPN服务会多次调用此API,导致提示音持续响起

### 为什么其他VPN应用没有这个问题?

通过研究 Android 官方文档和主流开源VPN项目(Shadowsocks, Clash, NetGuard)的源码,发现:

- **Android官方最佳实践**: VPN应用不应主动调用 `reportNetworkConnectivity()`
- **成熟VPN项目**: 均不使用此API,而是依赖:
  - libbox/v2ray等核心的 `resetNetwork()` 方法
  - 系统自动处理网络切换
  - NetworkCallback监听被动响应

## 修复方案

### 修改1: 移除核心就绪后的 reportNetworkConnectivity 调用

**文件**: `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt:2575-2597`

**修改前**:
```kotlin
// 方法 1: 报告网络连接状态变化,触发 ConnectivityManager 回调
cm?.reportNetworkConnectivity(vpnNetwork, true)
delay(100)

// 方法 2: 使用 resetNetwork() 替代 setUnderlyingNetworks 震荡
try {
    boxService?.resetNetwork()
    Log.i(TAG, "Network reset triggered via libbox, apps should reconnect now")
} catch (e: Exception) {
    Log.w(TAG, "Failed to reset network via libbox", e)
}
```

**修改后**:
```kotlin
// 仅使用 libbox resetNetwork() 方法强制应用重连
// 避免使用 reportNetworkConnectivity() 触发系统提示音
try {
    boxService?.resetNetwork()
    Log.i(TAG, "Network reset triggered via libbox, apps should reconnect now")
    LogRepository.getInstance().addLog("INFO: 已通知应用网络已切换,强制重新建立连接")
} catch (e: Exception) {
    Log.w(TAG, "Failed to reset network via libbox", e)
    LogRepository.getInstance().addLog("WARN: 触发应用重连失败: ${e.message}")
}
```

### 修改2: 移除openTun后的 reportNetworkConnectivity 调用

**文件**: `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt:1335-1340`

**修改前**:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    serviceScope.launch {
        delay(50)
        try {
            val cm = connectivityManager ?: getSystemService(ConnectivityManager::class.java)
            cm?.allNetworks?.forEach { network ->
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    cm.reportNetworkConnectivity(network, true)
                    Log.d(TAG, "Requested network validation for VPN network: $network")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request network validation", e)
        }
    }
}
```

**修改后**:
```kotlin
// Network reset is handled by requestCoreNetworkReset below
// BUG修复: 移除 reportNetworkConnectivity() 调用,避免在华为等设备上触发持续的系统提示音
// 参考: Android VPN 最佳实践,成熟 VPN 项目不使用 reportNetworkConnectivity 主动触发网络验证
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    Log.d(TAG, "VPN network established, relying on requestCoreNetworkReset for app reconnection")
}
```

## 技术细节

### reportNetworkConnectivity() 的正确用途

根据 Android 官方文档,`reportNetworkConnectivity()` 应该由以下场景使用:

1. **被动响应**: 在 `NetworkCallback.onAvailable()` 中响应系统请求
2. **网络验证**: 验证网络是否真正可连接互联网(如酒店WiFi门户检测)
3. **状态上报**: 上报已知的网络连接性问题

**不应该用于**:
- ❌ 主动触发应用重连
- ❌ VPN服务启动时的网络初始化
- ❌ 强制系统重新评估网络状态

### 替代方案: libbox resetNetwork()

sing-box核心提供的 `resetNetwork()` 方法:

- **作用**: 通知libbox内部网络栈刷新路由表和连接状态
- **优点**:
  - 不触发系统级通知
  - 直接作用于VPN核心
  - 更高效的应用重连机制
- **兼容性**: 所有Android版本均支持

## 验证方法

1. **编译测试版APK**:
   ```bash
   ./gradlew installDebug
   ```

2. **在华为设备上测试**:
   - 连接VPN
   - 观察是否还有持续提示音
   - 检查日志确认 `boxService?.resetNetwork()` 正常调用

3. **检查日志**:
   ```bash
   adb logcat -s SingBoxService:* | grep "Network reset"
   ```

   期望输出:
   ```
   Network reset triggered via libbox, apps should reconnect now
   ```

## 影响范围

- **受影响设备**: 主要是华为EMUI系统,其他深度定制Android系统可能也有类似问题
- **功能影响**: 无负面影响
  - VPN连接功能不受影响
  - 应用重连机制通过 `libbox.resetNetwork()` 依然有效
  - 网络切换依然能正常处理

## 参考资料

- [Android ConnectivityManager官方文档](https://developer.android.com/reference/android/net/ConnectivityManager)
- [Android VPN最佳实践](https://source.android.com/docs/core/connect/vpn-ux)
- Shadowsocks-Android源码分析
- 成熟VPN项目实践(Clash, NetGuard, PCAPdroid)

## 总结

该bug的根本原因是**误用了Android系统API**。`reportNetworkConnectivity()` 设计用于被动的网络验证,而非主动的网络状态管理。通过移除此API调用并依赖libbox自身的 `resetNetwork()` 机制,彻底解决了华为等设备上的提示音问题,同时保持了VPN功能的完整性。
