# 项目文档索引

## 🚀 快速开始

### 1. 内核优化
- **快速参考**: [QUICKREF-OPTIMIZATION.md](QUICKREF-OPTIMIZATION.md) ⭐
- **基础优化**: [docs/LIBBOX-OPTIMIZATION.md](docs/LIBBOX-OPTIMIZATION.md)
- **进阶优化**: [docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md](docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md) 🔥

### 2. 构建内核
- **构建脚本**: [buildScript/tasks/build_libbox.ps1](buildScript/tasks/build_libbox.ps1)
- **优化补丁**: [buildScript/tasks/optimization_patch.ps1](buildScript/tasks/optimization_patch.ps1) 🔥
- **使用说明**: [buildScript/README.md](buildScript/README.md)

---

## 📚 完整文档列表

### 核心文档（根目录）
- `README.md` - 项目介绍
- `CLAUDE.md` - AI 助手项目配置
- `CHANGELOG.md` - 更新日志
- `QUICKREF-OPTIMIZATION.md` - 优化快速参考 ⭐

### 优化相关
- `docs/LIBBOX-OPTIMIZATION.md` - 内核优化基础指南 ⭐
- `docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md` - 进阶优化方案 (4种) 🔥
- `docs/KERNEL-SLIMMING-ANALYSIS.md` - **内核深度瘦身分析** (协议/功能裁剪) 🔥⭐
- `docs/CUSTOM-BUILD-GUIDE.md` - **深度裁剪构建指南** (gomobile 直接构建) 🔥⭐
- `docs/APK-45MB-FIX-GUIDE.md` - **APK 45MB 问题修复指南** 🚨
- `docs/INSTALL-SIZE-OPTIMIZATION.md` - 安装后体积优化 (extractNativeLibs)
- `docs/APK-SIZE-ANALYSIS.md` - APK 膨胀原理分析
- `QUICKREF-OPTIMIZATION.md` - 快速参考卡片

### 功能指南
- `docs/URL_SCHEME_GUIDE.md` - URL Scheme 深度链接

### 构建脚本
- `buildScript/README.md` - 构建脚本说明
- `buildScript/tasks/build_libbox.ps1` - 内核构建脚本 (官方工具)
- `buildScript/tasks/build_libbox_custom.ps1` - **深度裁剪构建脚本** (自定义) 🔥⭐
- `buildScript/tasks/optimization_patch.ps1` - 编译优化补丁 🔥

### Bug 修复记录（docs/bugfix/）
- `TG_CONNECTION_FIX_FINAL_SUMMARY.md` - **Telegram连接卡死完整修复方案** 🔥⭐
- `TELEGRAM_CONNECTION_STUCK_FIX.md` - Telegram切换后连接中断技术分析
- `APP_SWITCH_RECONNECT_FIX.md` - 应用切换后连接恢复机制
- `VPN_STARTUP_CONNECTION_FIX.md` - VPN启动连接优化
- 其他历史修复记录

### MCP 设置（docs/mcp-setup/）
- MCP 服务器配置相关文档

---

## 🎯 常用操作

### 立即优化 (低风险)
```bash
# 应用编译优化补丁
.\buildScript\tasks\optimization_patch.ps1
# 重新构建内核 (减少 10-15%)
.\buildScript\tasks\build_libbox.ps1
```

### 进阶优化 (可选)
```bash
# 裁剪协议模块 (减少 30-60%, 高风险)
# 参考 docs/KERNEL-SIZE-OPTIMIZATION-ADVANCED.md
```

### 基础操作
```bash
# 优化现有 AAR
.\gradlew stripLibboxAar
cp app\build\stripped-libs\libbox-stripped-*.aar app\libs\libbox.aar

# 构建 APK
.\gradlew assembleDebug
.\gradlew installDebug
```

---

## 📂 项目结构

```
singboxforandriod/
├── app/                          # 应用源码
│   └── libs/
│       ├── libbox.aar           # 优化后内核 (15.55 MB)
│       └── libbox.aar.backup_*  # 原版备份 (66.36 MB)
│
├── buildScript/                  # 构建脚本
│   ├── README.md
│   └── tasks/
│       ├── build_libbox.ps1      # 内核构建
│       └── optimization_patch.ps1 # 编译优化 🔥
│
├── docs/                         # 文档目录
│   ├── LIBBOX-OPTIMIZATION.md    # 基础优化 ⭐
│   ├── KERNEL-SIZE-OPTIMIZATION-ADVANCED.md # 进阶优化 🔥
│   ├── URL_SCHEME_GUIDE.md
│   ├── bugfix/                   # 修复记录
│   └── mcp-setup/                # MCP 配置
│
├── README.md                     # 项目介绍
├── CLAUDE.md                     # AI 配置
├── CHANGELOG.md                  # 更新日志
└── QUICKREF-OPTIMIZATION.md      # 快速参考 ⭐
```

---

**提示**: 带 ⭐ 标记的是最常用文档
