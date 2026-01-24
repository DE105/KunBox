package com.kunk.singbox.aidl;

import com.kunk.singbox.aidl.ISingBoxServiceCallback;

interface ISingBoxService {
    int getState();
    String getActiveLabel();
    String getLastError();
    boolean isManuallyStopped();
    void registerCallback(ISingBoxServiceCallback callback);
    void unregisterCallback(ISingBoxServiceCallback callback);

    // 主进程通知 App 生命周期变化，用于省电模式触发
    oneway void notifyAppLifecycle(boolean isForeground);

    /**
     * 内核级热重载配置
     * 直接调用 Go 层 StartOrReloadService，不销毁 VPN 服务
     *
     * @param configContent 新的配置内容 (JSON)
     * @return 热重载结果: 0=成功, 1=VPN未运行, 2=内核错误, 3=未知错误
     */
    int hotReloadConfig(String configContent);
}
