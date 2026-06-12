package com.dahaoshen.maxlock.redisson;

import com.dahaoshen.maxlock.core.LockHandle;
import com.dahaoshen.maxlock.core.LockSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(60)
@Testcontainers(disabledWithoutDocker = true)
class RedissonDistributedLockIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static RedissonClient client;
    static RedissonDistributedLock lock;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        client = Redisson.create(config);
        lock = new RedissonDistributedLock(client);
    }

    @AfterAll
    static void tearDown() {
        client.shutdown();
    }

    @Test
    void 互斥_并发递增无竞态() throws Exception {
        int threads = 8;
        int loops = 50;
        int[] counter = {0};
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                for (int j = 0; j < loops; j++) {
                    lock.execute("it-counter", () -> counter[0]++);
                }
                done.countDown();
            });
        }
        assertThat(done.await(50, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(counter[0]).isEqualTo(threads * loops);
    }

    @Test
    void 默认启用Watchdog_指定leaseTime则关闭() {
        try (LockHandle watchdog = lock.lock(LockSpec.of("wd-on"))) {
            assertThat(watchdog.isWatchdogEnabled()).isTrue();
        }
        try (LockHandle fixed = lock.lock(LockSpec.of("wd-off").leaseTime(Duration.ofSeconds(10)))) {
            assertThat(fixed.isWatchdogEnabled()).isFalse();
        }
    }

    @Test
    void tryLock_被其他线程占用时返回null() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (LockHandle ignored = lock.lock("it-busy")) {
                acquired.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        acquired.await();

        assertThat(lock.tryLock("it-busy")).isNull();
        assertThat(lock.isLocked("it-busy")).isTrue();

        release.countDown();
        holder.join();
        await().atMost(5, TimeUnit.SECONDS).until(() -> !lock.isLocked("it-busy"));
    }

    @Test
    void leaseTime到期自动释放() {
        lock.tryLock(LockSpec.of("it-lease").leaseTime(Duration.ofSeconds(1)));
        // 故意不释放，等待 lease 过期
        await().atMost(10, TimeUnit.SECONDS).until(() -> !lock.isLocked("it-lease"));
    }

    @Test
    void forceUnlock可跨线程强制释放() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock("it-force");
            acquired.countDown();
        });
        holder.start();
        acquired.await();
        holder.join();

        assertThat(lock.isLocked("it-force")).isTrue();
        assertThat(lock.forceUnlock("it-force")).isTrue();
        assertThat(lock.isLocked("it-force")).isFalse();
    }
}
