# Notes: NoMoreWalls 订阅解析兼容性

## Sources

### Source 1: 项目代码库
- URL: local repo
- Key points:
  - TBD

### Source 2: NekoBoxForAndroid
- URL: https://github.com/MatsuriDayo/NekoBoxForAndroid
- Key points:
  - TBD

## Synthesized Findings

### 订阅内容特征
- 订阅响应为 Base64 文本，解码后为多条 `vmess://` 链接。
- 抽样节点包含 `net=tcp` 与少量 `net=ws`，`path` 多为空或 `/`。
- 列表前段存在 `add=127.0.0.53` 的条目，后段存在真实域名/IP。

### NekoBox 解析差异
- NekoBox 使用 `decodeBase64UrlSafe()` 解码全文，然后按行解析 `vmess://`。
- 解析入口见 `RawUpdater.parseRaw` 与 `Formats.parseProxies`。
- `RawUpdater.kt` 在解析 `flow` 时若包含 `xtls-rprx-vision`，会归一化为 `xtls-rprx-vision`（避免不支持的变体）。

### 解析差异
- `ConfigRepository.parseVMessLink` 默认 `packetEncoding = "xudp"`。
- `NodeLinkParser.parseVMessLink` 不设置 `packetEncoding`。
- NekoBox 的 VMess 解析未见强制 `packetEncoding` 默认值。

### 修复方案
- VMess 解析仅在订阅提供 `packetEncoding` 时写入，避免强制 `xudp`。
- 运行时归一化 `flow`，将 `xtls-rprx-vision-*` 修正为 `xtls-rprx-vision`。
