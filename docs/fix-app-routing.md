# 应用分流不生效修复记录

## 背景
应用分流（按应用/包名）不生效，但规则集分流正常。

## 现象
- `route.rules` 中已生成 `package_name` 规则（例如 Telegram）。
- 运行时实际流量不命中 `package_name` 规则。
- 规则集（`rule_set`）相关分流正常。

## 原因
`package_name` 匹配依赖运行时链路：

1. 连接 -> UID（通过 `findConnectionOwner`）
2. UID -> 包名（通过 `packageNameByUid`）

在部分设备/ROM 场景下，UID 虽可解析，但 UID->包名结果不稳定/为空，导致 `package_name` 无法命中。

## 修复方案
1. **路由规则增加 UID 兜底**
   - 生成应用分流规则时，同时写入 `user_id`（UID）规则。
   - 即使 `package_name` 不可用，也能通过 UID 命中分流。

2. **运行时包名反查缓存兜底**
   - `getPackagesForUid(uid)` 为空/异常时，从已安装应用构建 `uid -> packageName` 缓存并回退。

## 代码改动
- `app/src/main/java/com/kunk/singbox/model/SingBoxConfig.kt`
  - `DnsRule` 增加字段：`user_id`

- `app/src/main/java/com/kunk/singbox/repository/ConfigRepository.kt`
  - `buildAppRoutingRules()`：
    - 对每条 `AppRule/AppGroup` 生成两类规则：
      - `RouteRule(user_id=[uid])`
      - `RouteRule(package_name=[pkg])`
  - 应用专属 DNS 规则同样增加 `user_id` 兜底。

- `app/src/main/java/com/kunk/singbox/service/SingBoxService.kt`
  - `packageNameByUid()`：增加 `uid -> packageName` 缓存兜底。

## 验证方法
1. 生成运行配置 JSON，确认存在：
   - `route.rules` 内同时出现 `user_id` 与 `package_name` 规则。
   - `dns.rules` 内应用规则同时出现 `user_id` 与 `package_name`（如开启 FakeIP/应用 DNS 规则）。

2. 功能验证：
   - 为某个 App（如 Telegram）设置不同出口（例如 PROXY/DIRECT/指定节点）。
   - 启动 VPN 后验证该 App 的出口与其它 App 不同。

## 注意事项
- `user_id` 分流依赖 UID 稳定性；多用户/工作资料环境下 UID 可能变化，规则需随配置重新生成。
- `package_name` 依赖系统包名可见性与反查结果；本修复仍保留 `package_name` 以兼容内核行为，并通过 UID/缓存增强稳定性。
