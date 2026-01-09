# URL Scheme 导入功能实现文档

## 功能概述

KunBox 现已支持通过 URL Scheme 一键导入订阅,兼容机场网站一键导入场景。

## 支持的 URL Scheme

### 格式
```
singbox://install-config?url=<订阅地址>&name=<配置名称>&interval=<更新间隔>
kunbox://install-config?url=<订阅地址>&name=<配置名称>&interval=<更新间隔>
```

### 参数说明

| 参数 | 类型 | 必需 | 说明 | 默认值 |
|------|------|------|------|--------|
| `url` | String | **是** | 订阅地址 (需 URL 编码) | - |
| `name` | String | 否 | 配置名称 | "导入的订阅" |
| `interval` | Int | 否 | 自动更新间隔(小时) | 0 (不自动更新) |

## 使用示例

### 示例 1: 基本导入
```
singbox://install-config?url=https://example.com/api/v1/client/subscribe?token=xxx
```

### 示例 2: 带自定义名称
```
singbox://install-config?url=https://example.com/api/v1/client/subscribe?token=xxx&name=我的机场
```

### 示例 3: 带自动更新
```
singbox://install-config?url=https://example.com/api/v1/client/subscribe?token=xxx&name=机场A&interval=24
```

### 示例 4: URL 编码版本 (推荐)
```
singbox://install-config?url=https%3A%2F%2Fexample.com%2Fapi%2Fv1%2Fclient%2Fsubscribe%3Ftoken%3Dxxx&name=%E6%88%91%E7%9A%84%E6%9C%BA%E5%9C%BA&interval=24
```

## 机场网站集成

### HTML 一键导入按钮
```html
<a href="singbox://install-config?url=https://example.com/api/v1/client/subscribe?token=xxx&name=机场A">
    <button>一键导入到 KunBox</button>
</a>
```

### JavaScript 动态生成
```javascript
function importToKunBox(subscriptionUrl, name, interval = 24) {
    // URL 编码
    const encodedUrl = encodeURIComponent(subscriptionUrl);
    const encodedName = encodeURIComponent(name);

    // 构建 URL Scheme
    const scheme = `singbox://install-config?url=${encodedUrl}&name=${encodedName}&interval=${interval}`;

    // 跳转
    window.location.href = scheme;
}

// 使用示例
importToKunBox('https://example.com/api/v1/client/subscribe?token=xxx', '我的机场', 24);
```

## 测试方法

### 方法 1: ADB 命令测试 (推荐)
```bash
# Windows PowerShell
adb shell am start -a android.intent.action.VIEW -d "singbox://install-config?url=https://example.com/sub&name=测试订阅&interval=24"

# 带 URL 编码的版本
adb shell am start -a android.intent.action.VIEW -d "singbox://install-config?url=https%3A%2F%2Fexample.com%2Fsub&name=%E6%B5%8B%E8%AF%95%E8%AE%A2%E9%98%85&interval=24"
```

### 方法 2: 浏览器地址栏
1. 在 Android 设备上打开浏览器 (Chrome/Firefox)
2. 在地址栏输入:
   ```
   singbox://install-config?url=https://example.com/sub&name=测试订阅
   ```
3. 回车,系统会提示选择应用打开

### 方法 3: HTML 测试页面
创建一个测试 HTML 文件:
```html
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>KunBox URL Scheme 测试</title>
</head>
<body>
    <h1>KunBox URL Scheme 测试</h1>

    <h2>测试链接</h2>
    <ul>
        <li>
            <a href="singbox://install-config?url=https://example.com/sub&name=测试订阅1">
                测试 1: 基本导入
            </a>
        </li>
        <li>
            <a href="singbox://install-config?url=https://example.com/sub&name=测试订阅2&interval=24">
                测试 2: 带自动更新
            </a>
        </li>
        <li>
            <a href="kunbox://install-config?url=https://example.com/sub&name=测试订阅3">
                测试 3: kunbox:// scheme
            </a>
        </li>
    </ul>
</body>
</html>
```

将此文件发送到手机并用浏览器打开测试。

## 实现细节

### 修改的文件

1. **AndroidManifest.xml**
   - 添加了 `launchMode="singleTask"` 确保单实例
   - 添加了 URL Scheme intent-filter

2. **MainActivity.kt**
   - 添加了 `onNewIntent()` 方法处理应用已打开时的新 Intent
   - 在 `LaunchedEffect` 中添加了 `ACTION_VIEW` 处理逻辑

3. **DeepLinkHandler.kt** (新文件)
   - 单例对象,管理深度链接数据
   - 使用 StateFlow 在 MainActivity 和 ProfilesScreen 间传递数据

4. **ProfilesScreen.kt**
   - 添加了监听 DeepLinkHandler 的逻辑
   - 自动触发订阅导入

### 工作流程

```
用户点击链接
    ↓
系统检测到 singbox:// 或 kunbox:// scheme
    ↓
启动/切换到 KunBox
    ↓
MainActivity.onNewIntent() 接收 Intent
    ↓
解析 URL 参数 (url, name, interval)
    ↓
存储到 DeepLinkHandler
    ↓
导航到 profiles 页面
    ↓
ProfilesScreen 监听到数据变化
    ↓
自动调用 viewModel.importSubscription()
    ↓
显示导入进度和结果
```

## 兼容性说明

- **Android 版本**: Android 5.0+ (API 21+)
- **浏览器**: 支持所有主流浏览器 (Chrome, Firefox, Edge 等)
- **第三方应用**: 支持从微信、QQ、Telegram 等应用跳转

## 安全考虑

1. **URL 验证**: 仅接受 `http://` 和 `https://` 协议的订阅地址
2. **参数校验**: 对 `interval` 参数进行数字校验,非法值将使用默认值 0
3. **重复处理防护**: 使用 `intent.data = null` 防止同一 Intent 被重复处理
4. **单任务模式**: `launchMode="singleTask"` 防止创建多个 Activity 实例

## 已知限制

1. 如果 KunBox 被系统杀死后台,需要重新打开应用后 URL Scheme 才能正常工作
2. 部分定制 Android 系统可能需要用户手动允许 URL Scheme 跳转
3. URL 中的特殊字符必须进行 URL 编码,否则可能解析失败

## 后续优化建议

1. 添加 Toast 提示,告知用户正在导入订阅
2. 支持批量导入 (多个订阅 URL)
3. 添加导入前确认对话框 (可选)
4. 支持更多参数 (如代理模式、路由规则等)

---

**版本**: v1.0
**最后更新**: 2026-01-09
