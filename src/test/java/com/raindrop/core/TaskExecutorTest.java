package com.raindrop.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class TaskExecutorTest {

    @Test
    public void testSubmitRunnable() throws InterruptedException {
        AtomicBoolean executed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        TaskExecutor.submit(() -> {
            executed.set(true);
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(executed.get());
    }

    @Test
    public void testSubmitCallable() throws Exception {
        var result = TaskExecutor.submit(() -> 42);
        assertEquals(42, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMultipleConcurrentTasks() throws InterruptedException {
        int taskCount = 100;
        AtomicInteger counter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(taskCount);

        for (int i = 0; i < taskCount; i++) {
            TaskExecutor.submit(() -> {
                counter.incrementAndGet();
                latch.countDown();
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(taskCount, counter.get());
    }
}
