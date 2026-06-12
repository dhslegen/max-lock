package com.dahaoshen.maxlock.local;

import com.dahaoshen.maxlock.core.DistributedLock;
import com.dahaoshen.maxlock.core.LockHandle;
import com.dahaoshen.maxlock.core.LockSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class LocalJvmDistributedLockTest {

    private final LocalJvmDistributedLock lock = new LocalJvmDistributedLock();

    @Test
    void 互斥_并发递增计数应无竞态() throws Exception {
        int threads = 8;
        int loops = 200;
        int[] counter = {0};
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                for (int j = 0; j < loops; j++) {
                    lock.execute("counter", () -> counter[0]++);
                }
                done.countDown();
            });
        }
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(counter[0]).isEqualTo(threads * loops);
    }

    @Test
    void tryLock_被他人占用时立即返回null() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (LockHandle ignored = lock.lock("busy")) {
                acquired.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        acquired.await();

        assertThat(lock.tryLock("busy")).isNull();
        assertThat(lock.isLocked("busy")).isTrue();

        release.countDown();
        holder.join();
        assertThat(lock.isLocked("busy")).isFalse();
    }

    @Test
    void tryLock_带等待时间应在锁释放后获取成功() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (LockHandle ignored = lock.lock("wait-key")) {
                acquired.countDown();
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        acquired.await();

        LockHandle handle = lock.tryLock(LockSpec.of("wait-key").waitTime(Duration.ofSeconds(5)));
        assertThat(handle).isNotNull();
        assertThat(handle.isWatchdogEnabled()).isFalse();
        assertThat(handle.getHoldDuration()).isGreaterThanOrEqualTo(Duration.ZERO);
        handle.release();
        holder.join();
    }

    @Test
    void 可重入_同线程嵌套获取() {
        try (LockHandle outer = lock.lock("reentrant")) {
            try (LockHandle inner = lock.lock("reentrant")) {
                assertThat(inner).isNotNull();
            }
            assertThat(lock.isLocked("reentrant")).isTrue();
        }
        assertThat(lock.isLocked("reentrant")).isFalse();
    }

    @Test
    void release幂等_重复释放无异常() {
        LockHandle handle = lock.lock("idempotent");
        handle.release();
        handle.release();
        assertThat(handle.isReleased()).isTrue();
        assertThat(lock.isLocked("idempotent")).isFalse();
    }

    @Test
    void 全部释放后内部map应清空_防泄漏() {
        lock.execute("a", () -> null);
        lock.execute("b", () -> null);
        assertThat(lock.entryCount()).isZero();
    }

    @Test
    void forceUnlock_当前线程持有时可强制全部释放() {
        DistributedLock dl = lock;
        dl.lock("force");
        dl.lock("force");  // 重入两层
        assertThat(dl.forceUnlock("force")).isTrue();
        assertThat(dl.isLocked("force")).isFalse();
    }

    @Test
    void forceUnlock后再释放残留handle_不重复递减引用计数() {
        LockHandle h1 = lock.lock("force-residual");
        LockHandle h2 = lock.lock("force-residual");  // 重入两层
        assertThat(lock.forceUnlock("force-residual")).isTrue();

        // forceUnlock 已归还全部计数；残留 handle 再 release 不应重复递减
        h1.release();
        h2.release();
        assertThat(lock.isLocked("force-residual")).isFalse();
        assertThat(lock.entryCount()).isZero();
    }
}
