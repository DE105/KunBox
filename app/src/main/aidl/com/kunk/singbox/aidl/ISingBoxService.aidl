package com.kunk.singbox.aidl;

import com.kunk.singbox.aidl.ISingBoxServiceCallback;

interface ISingBoxService {
    int getState();

    String getActiveLabel();

    String getLastError();

    boolean isManuallyStopped();

    void registerCallback(ISingBoxServiceCallback callback);

    void unregisterCallback(ISingBoxServiceCallback callback);

    oneway void notifyAppLifecycle(boolean isForeground);

    int hotReloadConfig(String configContent);
}
