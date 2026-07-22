package com.raindrop.core;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class TaskExecutor {
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public static ExecutorService getExecutor() {
        return executor;
    }

    public static void submit(Runnable task) {
        executor.submit(task);
    }

    public static <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    public static void runOnFx(Runnable fxTask) {
        Platform.runLater(fxTask);
    }

    /** Called from RaindropApp.stop() to let pending I/O virtual threads unblock and exit. */
    public static void shutdown() {
        executor.shutdownNow();
    }
}
