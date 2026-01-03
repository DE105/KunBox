# 英文国际化实施计划

## 概述

本计划旨在为 KunBox Android 应用添加英文语言支持。当前应用仅支持中文，所有用户界面文本均硬编码在 Kotlin 源代码中。

## 现状分析

### 当前状态

1. **strings.xml 极其简陋** - 仅包含：
   - `app_name`: "KunBox"
   - 4个快捷方式相关字符串

2. **硬编码中文文本分布广泛** - 涉及 300+ 处，分布在：
   - UI 屏幕文件 (20+ 文件)
   - UI 组件文件 (10+ 文件)
   - ViewModel 文件 (6+ 文件)
   - Model 枚举类 (10+ 枚举)
   - Service 文件 (3+ 文件)

### 需要国际化的内容类别

| 类别 | 文件数量 | 预估字符串数 | 优先级 |
|------|----------|--------------|--------|
| UI 屏幕 | 20+ | 200+ | P0 |
| UI 组件 | 10+ | 80+ | P0 |
| Model 枚举 displayName | 10+ | 50+ | P0 |
| ViewModel Toast 消息 | 6+ | 30+ | P1 |
| Service 通知文本 | 3+ | 10+ | P1 |

## 技术方案

### 方案选择：标准 Android 资源国际化

采用 Android 原生的 `values/strings.xml` 方案，因为：
- 无需引入额外依赖
- 与 Android 系统语言设置自动集成
- Jetpack Compose 通过 `stringResource()` 原生支持
- 便于后续添加更多语言

### 资源目录结构

```
app/src/main/res/
├── values/               # 默认语言（英文）
│   └── strings.xml
├── values-zh/            # 中文
│   └── strings.xml
└── values-zh-rCN/        # 简体中文（可选，更精确）
    └── strings.xml
```

### 枚举类国际化策略

对于 Model 层的枚举 `displayName`，有两种处理方式：

**方式A：资源ID方式（推荐）**
```kotlin
enum class RoutingMode(@StringRes val displayNameRes: Int) {
    RULE(R.string.routing_mode_rule),
    GLOBAL_PROXY(R.string.routing_mode_global_proxy),
    GLOBAL_DIRECT(R.string.routing_mode_global_direct);
}

// 使用时
Text(stringResource(mode.displayNameRes))
```

**方式B：扩展函数方式**
```kotlin
@Composable
fun RoutingMode.displayName(): String = when(this) {
    RULE -> stringResource(R.string.routing_mode_rule)
    GLOBAL_PROXY -> stringResource(R.string.routing_mode_global_proxy)
    GLOBAL_DIRECT -> stringResource(R.string.routing_mode_global_direct)
}
```

推荐方式A，因为更简洁且类型安全。

## 字符串命名规范

### 命名格式

```
[category]_[screen/component]_[element]_[description]
```

### 示例

```xml
<!-- 通用 -->
<string name="common_confirm">Confirm</string>
<string name="common_cancel">Cancel</string>
<string name="common_save">Save</string>
<string name="common_delete">Delete</string>
<string name="common_edit">Edit</string>

<!-- 连接状态 -->
<string name="connection_status_idle">Disconnected</string>
<string name="connection_status_connecting">Connecting...</string>
<string name="connection_status_connected">Connected</string>
<string name="connection_status_disconnecting">Disconnecting...</string>
<string name="connection_status_error">Error</string>

<!-- 仪表盘 -->
<string name="dashboard_routing_mode">Routing Mode</string>
<string name="dashboard_update_subscription">Update Subscription</string>
<string name="dashboard_latency_test">Latency Test</string>
<string name="dashboard_logs">Logs</string>
<string name="dashboard_diagnostics">Diagnostics</string>

<!-- 设置 -->
<string name="settings_title">Settings</string>
<string name="settings_general">General</string>
<string name="settings_network">Network</string>
<string name="settings_tools">Tools</string>
<string name="settings_data_management">Data Management</string>
<string name="settings_about">About</string>

<!-- 路由模式 -->
<string name="routing_mode_rule">Rule Mode</string>
<string name="routing_mode_global_proxy">Global Proxy</string>
<string name="routing_mode_global_direct">Global Direct</string>

<!-- 出站规则 -->
<string name="outbound_direct">Direct</string>
<string name="outbound_proxy">Proxy</string>
<string name="outbound_block">Block</string>
```

## 实施步骤

### 阶段一：基础设施搭建（P0）

#### 任务 1.1：创建资源文件结构
- [x] 创建 `values/strings.xml` (英文，作为默认)
- [x] 创建 `values-zh/strings.xml` (中文)
- [x] 迁移现有 strings.xml 内容

#### 任务 1.2：定义通用字符串
- [x] 添加所有通用按钮文本 (确认、取消、保存、删除等)
- [x] 添加所有连接状态文本
- [x] 添加所有路由模式文本

### 阶段二：Model 层国际化（P0）

#### 任务 2.1：重构枚举类
需要重构的枚举类列表：

**RoutingModels.kt:**
- [x] `RuleType` - 8个值
- [x] `OutboundTag` - 3个值
- [x] `RuleSetType` - 2个值
- [x] `RuleSetOutboundMode` - 6个值

**Settings.kt:**
- [x] `LatencyTestMethod` - 3个值
- [x] `TunStack` - 3个值 (已是英文，可保留)
- [x] `VpnRouteMode` - 2个值
- [x] `VpnAppMode` - 3个值
- [x] `DnsStrategy` - 5个值 (已是英文，可保留)
- [x] `RoutingMode` - 3个值
- [x] `DefaultRule` - 3个值
- [x] `AppThemeMode` - 3个值
- [x] `GhProxyMirror` - 2个值

### 阶段三：UI 层国际化（P0）

#### 任务 3.1：核心屏幕
- [x] DashboardScreen.kt (~30 字符串)
- [x] SettingsScreen.kt (~40 字符串)
- [x] ProfilesScreen.kt (~25 字符串)
- [x] NodesScreen.kt (~20 字符串)

#### 任务 3.2：设置子屏幕
- [x] RoutingSettingsScreen.kt
- [x] TunSettingsScreen.kt
- [x] DnsSettingsScreen.kt
- [x] ConnectionSettingsScreen.kt

#### 任务 3.3：其他屏幕
- [x] RuleSetsScreen.kt
- [x] RuleSetHubScreen.kt
- [x] LogsScreen.kt
- [x] DiagnosticsScreen.kt
- [x] AppRoutingScreen.kt / AppRulesScreen.kt / AppGroupsScreen.kt
- [x] DomainRulesScreen.kt
- [x] CustomRulesScreen.kt
- [x] NodeDetailScreen.kt
- [x] ProfileEditorScreen.kt

### 阶段四：组件层国际化（P0）

#### 任务 4.1：对话框组件
- [x] CommonDialogs.kt (~50 字符串)
- [x] ExportImportDialogs.kt (~20 字符串)

#### 任务 4.2：卡片组件
- [x] NodeCard.kt (~5 字符串)
- [x] ProfileCard.kt (~5 字符串)

#### 任务 4.3：其他组件
- [x] AppRoutingComponents.kt
- [x] EditableSettingItem.kt
- [x] ClickableDropdownField.kt

### 阶段五：ViewModel 和 Service 层（P1）

#### 任务 5.1：ViewModel Toast 消息
- [x] DashboardViewModel.kt
- [x] ProfilesViewModel.kt
- [x] NodesViewModel.kt
- [x] DiagnosticsViewModel.kt

#### 任务 5.2：Service 通知文本
- [x] SingBoxService.kt (通知标题、按钮)
- [x] VpnTileService.kt

### 阶段六：测试验证（P0）

#### 任务 6.1：功能测试
- [ ] 在英文系统语言下测试所有界面
- [ ] 在中文系统语言下测试所有界面
- [ ] 测试语言切换后的界面更新

#### 任务 6.2：边缘情况
- [ ] 验证长文本的布局适配
- [ ] 验证 RTL 语言支持（如果需要）

## 涉及的文件清单

### UI 屏幕文件 (ui/screens/)
1. DashboardScreen.kt
2. SettingsScreen.kt
3. ProfilesScreen.kt
4. NodesScreen.kt
5. RoutingSettingsScreen.kt
6. TunSettingsScreen.kt
7. DnsSettingsScreen.kt
8. ConnectionSettingsScreen.kt
9. RuleSetsScreen.kt
10. RuleSetHubScreen.kt
11. LogsScreen.kt
12. DiagnosticsScreen.kt
13. AppRoutingScreen.kt
14. AppRulesScreen.kt
15. AppGroupsScreen.kt
16. DomainRulesScreen.kt
17. CustomRulesScreen.kt
18. NodeDetailScreen.kt
19. ProfileEditorScreen.kt

### UI 组件文件 (ui/components/)
1. CommonDialogs.kt
2. ExportImportDialogs.kt
3. NodeCard.kt
4. ProfileCard.kt
5. AppRoutingComponents.kt
6. EditableSettingItem.kt
7. ClickableDropdownField.kt

### Model 文件 (model/)
1. RoutingModels.kt
2. Settings.kt
3. UiModels.kt

### ViewModel 文件 (viewmodel/)
1. DashboardViewModel.kt
2. ProfilesViewModel.kt
3. NodesViewModel.kt
4. DiagnosticsViewModel.kt

### Service 文件 (service/)
1. SingBoxService.kt
2. VpnTileService.kt

### Repository 文件 (repository/)
1. InstalledAppsRepository.kt

### 其他文件
1. MainActivity.kt
2. QrScannerActivity.kt (ui/scanner/)

## 字符串估算

| 类别 | 预估数量 |
|------|----------|
| 通用按钮/标签 | 30 |
| 连接状态 | 10 |
| 仪表盘 | 30 |
| 设置 | 50 |
| 配置管理 | 30 |
| 节点管理 | 25 |
| 路由设置 | 40 |
| 对话框 | 50 |
| Toast 消息 | 40 |
| 通知 | 10 |
| 枚举值 | 50 |
| **总计** | **~365** |

## 注意事项

### 技术注意事项

1. **Context 获取**：在非 Composable 函数中使用字符串资源需要 Context
   ```kotlin
   // 在 ViewModel 中
   private val context: Context = application.applicationContext
   context.getString(R.string.xxx)
   ```

2. **格式化字符串**：使用占位符处理动态内容
   ```xml
   <string name="nodes_count">%d nodes</string>
   ```
   ```kotlin
   stringResource(R.string.nodes_count, count)
   ```

3. **复数形式**：Android 支持 plurals 资源
   ```xml
   <plurals name="nodes_count">
       <item quantity="one">%d node</item>
       <item quantity="other">%d nodes</item>
   </plurals>
   ```

### 翻译注意事项

1. **保持一致性**：同一概念使用相同的翻译
2. **适当本地化**：某些技术术语保留英文更好理解
3. **长度考虑**：英文通常比中文长，需注意 UI 布局

## 预期成果

完成后：
- 应用将支持中英文双语
- 用户系统语言为英文时自动显示英文界面
- 用户系统语言为中文时显示中文界面
- 代码中不再有硬编码的用户界面文本