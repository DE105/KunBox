# Notes: 网络性能对比调研

## Sources

### Source 1: 本项目代码库
- URL: local repo
- Key points:
  - VPN 启动与底层网络绑定：延迟设置 setUnderlyingNetworks，启动窗口期跳过网络操作，避免 UDP 连接被关闭
  - 网络监听与预缓存：Application 层缓存物理网络供 VPN 启动使用
  - 速度/流量：使用 TrafficStats 采样，服务内做平滑处理
  - 延迟测试：libbox 优先，失败回退本地 HTTP 代理

### Source 2: NekoBoxForAndroid Releases
- URL: https://github.com/MatsuriDayo/NekoBoxForAndroid/releases
- Key points:
  - 1.4.0 提到优化 TCP Ping 与 URL Test
  - 1.3.8 提到网络变化/唤醒时重置 outbound connections；URLTest 无需停止 VPN
  - 1.3.2 提到 URL Test 使用 RTT，并在 URL Test 中使用 direct DNS

### Source 3: v2rayNG Issues
- URL: https://github.com/2dust/v2rayNG/issues/3990
- Key points:
  - 复杂路由/规则资产可能导致连接/启动延迟

- URL: https://github.com/2dust/v2rayNG/issues/4927
- Key points:
  - tun2socks/路由与 VpnService 配置相关的路由不完整问题

### Source 4: Android 官方文档
- URL: https://developer.android.com/reference/android/net/VpnService
- Key points:
  - setUnderlyingNetworks 的语义与调用时机

- URL: https://developer.android.com/reference/android/net/VpnService.Builder
- Key points:
  - setUnderlyingNetworks、setMtu 等与 VPN 性能相关的配置项

## Synthesized Findings

### 本项目代码线索
- SingBoxService: setUnderlyingNetworks 防抖、启动窗口期保护、postTunRebind 重试、TrafficStats 采样
- DefaultNetworkListener: 预缓存物理网络
- SingBoxCore: latency test 优先 libbox，回退本地 HTTP 代理
- DiagnosticsViewModel: 连通性/TCP Ping/DNS 查询/路由模拟

### 已补齐的稳定性改动（本次执行计划）
- 网络变化统一入口: DefaultNetworkListener 上报 UnderlyingNetworkEvent，由 SingBoxService 统一处理（防抖、丢失清空、恢复重绑并触发 requestCoreNetworkReset）
- 稳定性诊断指标: 断流次数、重连触发次数、最近一次重连耗时/原因，DiagnosticsViewModel 可展示
- 稳定性窗口期: 网络恢复后短时间内抑制测速，降低窗口期干扰

### 需要人工验证
- 手动切换 Wi-Fi/蜂窝与熄屏唤醒，观察日志是否记录“网络丢失/恢复/重置”
- 切网后触发测速，确认在窗口期内被抑制

### NekoBox/v2rayNG 公开资料线索
- NekoBox 更强调 URL Test/测速流程优化与网络变更时 reset outbound connections
- v2rayNG 问题反馈集中于复杂路由带来的启动/连接延迟与 tun2socks 路由异常
