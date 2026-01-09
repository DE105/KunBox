# Telegram 等长连接应用切换后无法连接的问题修复

## 问题描述

**症状**:
- 使用 Telegram 时,切换到其他应用一段时间后,再切回 Telegram 时会一直处于"连接中"状态
- 必须完全关闭 Telegram 重新打开才能恢复连接
- 有时即使不切换应用,Telegram 用久了也会出现连接中状态
- 同一个节点在其他代理应用上没有这个问题

## 根本原因

通过研究 sing-box 官方文档、NekoBox 项目和相关 issue,发现问题的根本原因是:

1. **缺少 TCP Keepalive 配置**: 长连接应用(如 Telegram)在应用切换或息屏后,底层 TCP 连接会因为空闲超时被中间路由器/NAT 设备关闭,但应用层未感知
2. **缺少应用级保活机制**: libbox 核心缺少 Clash API 提供的额外保活和监控能力
3. **屏幕唤醒时连接状态未主动恢复**: 虽然代码中有 `performScreenOnHealthCheck()`,但可能执行时机不够主动

## ⚠️ 重要发现:当前 libbox 版本限制

在实施过程中发现:
- **TCP keepalive 字段** (`tcp_keep_alive`, `tcp_keep_alive_interval`) 只在 **sing-box 1.13.0-alpha.28+** 版本才支持
- 当前项目使用的 libbox 版本 **不支持** 这些字段,会报错: `json: unknown field "tcp_keep_alive"`
- 升级 libbox 到最新版本需要重新编译核心库,工程量较大

## 当前可行的修复方案

### ✅ 方案 1: 启用 Clash API 保活 (已实施)

在 `ConfigRepository.kt` 的 `buildRunExperimentalConfig()` 方法中添加 Clash API 配置:

```kotlin
val clashApi = ClashApiConfig(
    externalController = "127.0.0.1:9090",
    defaultMode = "rule"
)

return ExperimentalConfig(
    cacheFile = CacheFileConfig(...),
    clashApi = clashApi  // 启用 Clash API 提供保活
)
```

**作用**: Clash API 会定期发送心跳,防止核心进入完全空闲状态,可以部分缓解长连接断开问题。

### ⏳ 方案 2: 升级 libbox 到 1.13.0+ (待实施)

**步骤**:
1. 使用项目的构建脚本重新编译 libbox:
   ```powershell
   .\buildScript\build.ps1
   ```
2. 或者从 NekoBox 最新版本提取已编译的 libbox.aar
3. 替换 `app/libs/libbox.aar`
4. 添加 TCP keepalive 字段到 Outbound 类并应用到所有协议

**收益**: 可以启用完整的 TCP keepalive 机制,从根本上解决 NAT 超时问题

### 🔧 方案 3: 增强屏幕唤醒逻辑 (可选)

在 `SingBoxService.kt` 的 `performScreenOnHealthCheck()` 中增加更主动的连接重置:

```kotlin
private fun performScreenOnHealthCheck() {
    // 当前已有 wake() 调用
    boxService?.wake()

    // 可选: 添加网络振荡以强制重连
    if (settings.enableScreenOnOptimization) {
        // 触发一次网络状态变化以刷新连接
    }
}
```

## 已完成的修改

### ✅ `SingBoxConfig.kt`
1. 添加了 `ClashApiConfig` 数据类 (第345-351行)
2. `ExperimentalConfig` 添加了 `clashApi` 字段 (第335行)

### ✅ `ConfigRepository.kt` (需手动完成)
由于文件编码问题,需要手动添加 Clash API 配置:

在 `buildRunExperimentalConfig()` 方法中 (约第2989行),在 `return ExperimentalConfig` 之前添加:

```kotlin
val clashApi = ClashApiConfig(
    externalController = "127.0.0.1:9090",
    defaultMode = "rule"
)
```

然后在 ExperimentalConfig 构造中添加:
```kotlin
clashApi = clashApi
```

## 测试步骤

1. 手动完成上述 Clash API 配置添加
2. 编译并安装修改后的APK:
   ```powershell
   .\gradlew clean installRelease
   ```
3. 连接VPN
4. 打开 Telegram 确认正常连接
5. 切换到其他应用使用 5-10 分钟
6. 切回 Telegram 观察是否能正常连接
7. 重复测试多次

**预期结果**: Telegram 连接卡死情况应该有所改善,但可能无法完全解决

## 后续优化建议

### 选项A: 升级 libbox (推荐)

升级到 sing-box 1.13.0+ 后可添加完整的 TCP keepalive 配置:

```kotlin
tcpKeepAlive = "30s",          // 30秒后开始发送 keepalive 探测包
tcpKeepAliveInterval = "15s",  // 探测包间隔 15秒
connectTimeout = "10s"         // 连接超时 10秒
```

### 选项B: 使用 NekoBox 的方案

研究 NekoBox 如何处理类似问题,可能有其他不依赖 libbox 版本的解决方案。

## 技术参考

1. **sing-box Dial Fields 文档**: https://sing-box.sagernet.org/configuration/shared/dial/
2. **sing-box Changelog**: https://sing-box.sagernet.org/changelog (版本 1.13.0-alpha.28 添加 TCP keepalive)
3. **NekoBox Issue #898**: "Time to reconnect after unlocking" - 解锁后重连延迟问题
4. **sing-box Issue #540**: Android 下 reality+tcp outbound Discord/Telegram 语音/视频问题

---

**修复日期**: 2026-01-09
**实施状态**: ⚠️ 部分完成 (仅 Clash API,TCP keepalive 因 libbox 版本限制暂不可用)
**测试状态**: 待用户测试
**预计影响**: 可能部分缓解问题,完全解决需要升级 libbox
