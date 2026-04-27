package io.github.thebusybiscuit.slimefun4.utils;

import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import org.bukkit.Bukkit;

public class ThreadUtils {

    /**
     * Executes a task synchronously on the main thread.
     * If the current thread is already the main thread, the task runs immediately.
     * Otherwise, the task is scheduled to run on the main thread.
     *
     * @param runnable The task to execute synchronously
     */
    public static void executeSync(Runnable runnable, com.molean.folia.adapter.SchedulerContext context) {
        runSyncNMS(runnable, context);
    }

    /**
     * Executes a task synchronously on the main thread with scheduling.
     * If the current thread is already the main thread, the task is scheduled for later execution.
     * Otherwise, the task is scheduled to run on the main thread.
     *
     * @param runnable The task to execute synchronously
     */
    public static void executeSyncSched(Runnable runnable, com.molean.folia.adapter.SchedulerContext context) {
        runSyncNMS(runnable, context);
    }

    /**
     * Creates a FutureTask from a Callable.
     *
     * @param <T> The type of the result
     * @param callable The callable to wrap
     * @return A FutureTask wrapping the callable
     */
    public static <T> FutureTask<T> getFutureTask(Callable<T> callable) {
        return new FutureTask<>(callable);
    }

    /**
     * Creates a FutureTask from a Runnable.
     * If the input is already a FutureTask, it is cast and returned.
     * Otherwise, a new FutureTask is created with the Runnable and null result.
     *
     * @param runnable The runnable to wrap
     * @return A FutureTask wrapping the runnable
     */
    public static FutureTask<Void> getFutureTask(Runnable runnable) {
        return runnable instanceof FutureTask<?> future
                ? (FutureTask<Void>) future
                : new FutureTask<>(runnable, (Void) null);
    }

    /**
     * Creates a FutureTask from a Runnable with a specific result value.
     *
     * @param <T> The type of the result
     * @param runnable The runnable to execute
     * @param val The result value to return
     * @return A FutureTask that executes the runnable and returns the specified value
     */
    public static <T> FutureTask<T> getFutureTask(Runnable runnable, T val) {
        return new FutureTask<>(runnable, val);
    }

    /**
     * Executes a runnable on the main thread using the NMS main executor.
     * This is an internal method used by other ThreadUtils methods.
     *
     * @param runnable The task to execute on the main thread
     */
    private static void runSyncNMS(Runnable runnable, com.molean.folia.adapter.SchedulerContext context) {
        context.runTask(Slimefun.instance(), runnable);
    }
}
