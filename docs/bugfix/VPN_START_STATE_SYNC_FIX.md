# VPN 启动状态同步修复

## 问题描述

### 现象
点击首页启动 VPN 按钮后,UI 一直显示"启动中"状态,无法切换到"已连接"状态,即使 VPN 实际上已经成功启动。

### 日志证据
```
14:13:03.296  SingBoxService  I  KunBox VPN started successfully
14:13:03.298  SingBoxService  I  Periodic health check started
14:13:16.294  SingBoxService  I  Sing-box core initialization complete, VPN is now fully ready
```

从日志可以看出 VPN 服务已成功启动,但 UI 仍显示"启动中"。

## 根本原因

### 问题分析
在 `SingBoxService.kt` 的 `startVpn()` 方法中,状态标志更新的时机存在问题:

```kotlin
// 第 2611-2623 行 (修复前)
isRunning = true                              // ✓ 设置运行标志
stopForeignVpnMonitor()
setLastError(null)
Log.i(TAG, "KunBox VPN started successfully")

VpnTileService.persistVpnState(applicationContext, true)
VpnStateStore.setMode(applicationContext, VpnStateStore.CoreMode.VPN)
startTrafficStatsMonitor()
VpnTileService.persistVpnPending(applicationContext, "")
updateServiceState(ServiceState.RUNNING)     // ✓ 更新服务状态为 RUNNING
updateTileState()

// ... 后续代码

} finally {
    isStarting = false  // ✗ 在 finally 块才重置 isStarting
    // 第 2700 行
}
```

### 时间线问题

1. **T0**: `isStarting = true` (第 2258 行)
2. **T1**: VPN 启动成功,`isRunning = true` (第 2611 行)
3. **T2**: `updateServiceState(ServiceState.RUNNING)` (第 2622 行)
4. **T3**: 很久之后,`finally` 块执行 `isStarting = false` (第 2700 行)

**关键问题**: 在 T2-T3 之间存在时间窗口,此时:
- `isRunning = true` ✓
- `isStarting = true` ✗ (应该是 false)
- UI 判断逻辑:`isRunning || isStarting` → 显示"启动中"

### UI 判断逻辑

`DashboardViewModel.kt` 和 `MainActivity.kt` 使用以下逻辑判断连接状态:

```kotlin
val isConnected = SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value
```

因为 `isStarting` 在启动成功后仍为 `true`,UI 会误判为"启动中"而非"已连接"。

## 修复方案

### 代码修改

在 `SingBoxService.kt` 第 2616-2617 行添加立即重置 `isStarting` 标志:

```kotlin
isRunning = true
stopForeignVpnMonitor()
setLastError(null)
Log.i(TAG, "KunBox VPN started successfully")

// 立即重置 isStarting 标志,确保UI能正确显示已连接状态
isStarting = false  // ← 新增

VpnTileService.persistVpnState(applicationContext, true)
VpnStateStore.setMode(applicationContext, VpnStateStore.CoreMode.VPN)
```

### 修复后的时间线

1. **T0**: `isStarting = true` (第 2258 行)
2. **T1**: VPN 启动成功,`isRunning = true` (第 2611 行)
3. **T2**: **立即** `isStarting = false` (第 2617 行) ← 新增
4. **T3**: `updateServiceState(ServiceState.RUNNING)` (第 2625 行)
5. **T4**: `finally` 块再次确保 `isStarting = false` (第 2703 行)

### 为什么安全

1. **成功路径**: 在第 2617 行立即重置 `isStarting`,UI 能正确显示"已连接"
2. **失败路径**: `finally` 块 (第 2703 行) 仍会执行,确保异常情况下也能重置标志
3. **幂等性**: 多次设置 `isStarting = false` 是安全的,不会引起副作用

## 影响范围

### 修改文件
- `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt` (1 处修改)

### 受益场景
1. **首页启动 VPN**: UI 能正确从"启动中"切换到"已连接"
2. **自动重连**: 网络切换后自动重连,状态显示正确
3. **Tile 快速启动**: 从通知栏 Tile 启动 VPN,状态同步正常
4. **热切换节点**: 切换节点后状态显示正确

## 测试验证

### 测试步骤
1. 打开应用,点击首页大按钮启动 VPN
2. 观察状态变化:
   - 点击后立即显示"启动中"(圆圈动画)
   - 等待 2-5 秒
   - **预期**: 自动切换到"已连接"状态(绿色勾)
   - **实际**: 一直停在"启动中"状态 ✗ → 修复后正常 ✓

### 验证指标
- [ ] 首页启动 VPN,5秒内切换到"已连接"
- [ ] 通知栏显示"已连接"而非"启动中"
- [ ] Tile 图标显示正确状态(绿色激活)
- [ ] 再次点击按钮能正常停止 VPN

## 关联问题

### 相关 Issue
- 用户反馈:点击启动后一直转圈,无法显示已连接

### 相关代码位置
- `SingBoxRemote.kt:47,63,79` - 状态监听
- `DashboardViewModel.kt:364` - `isStarting` 状态收集
- `MainActivity.kt:246` - `isStarting` 状态判断

## 后续建议

### 代码改进
考虑使用 `enum class` 替代多个布尔标志:

```kotlin
sealed class VpnState {
    object Stopped : VpnState()
    object Starting : VpnState()
    object Running : VpnState()
    object Stopping : VpnState()
}
```

这样可以避免 `isRunning` 和 `isStarting` 同时为 `true` 的矛盾状态。

### 监控增强
在状态切换时添加更多日志,便于追踪问题:

```kotlin
isStarting = false
Log.d(TAG, "State transition: isStarting=false, isRunning=$isRunning")
```

## 结论

通过在 VPN 启动成功后立即重置 `isStarting` 标志,修复了 UI 状态显示不同步的问题。修改简洁且安全,不影响现有逻辑。
