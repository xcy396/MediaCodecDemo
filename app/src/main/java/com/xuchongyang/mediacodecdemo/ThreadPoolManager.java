package com.xuchongyang.mediacodecdemo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 线程池管理器
 * Created by Mark Xu on 17/2/25.
 */

public class ThreadPoolManager {
    private static volatile ThreadPoolManager sDefaultInstance;
    private ExecutorService mExecutorService;

    /**
     * 单例模式
     */
    public static ThreadPoolManager getDefault() {
        if (sDefaultInstance == null) {
            synchronized (ThreadPoolManager.class) {
                if (sDefaultInstance == null) {
                    sDefaultInstance = new ThreadPoolManager();
                }
            }
        }
        return sDefaultInstance;
    }


    private ThreadPoolManager() {
        int num = Runtime.getRuntime().availableProcessors();
        this.mExecutorService = Executors.newFixedThreadPool(num * 2 + 1);
    }

    /**
     * 添加任务
     * @param runnable command
     */
    public void addTask(Runnable runnable) {
        mExecutorService.submit(runnable);
    }

    /**
     * 停止所有任务
     */
    public void shutdownNow() {
        mExecutorService.shutdownNow();
    }

    public void reset() {
        sDefaultInstance = null;
    }
}
