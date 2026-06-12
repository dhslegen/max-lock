package com.dahaoshen.maxlock.boot;

import com.dahaoshen.maxlock.aspect.LockAspect;
import com.dahaoshen.maxlock.core.DistributedLock;
import com.dahaoshen.maxlock.local.LocalJvmDistributedLock;
import com.dahaoshen.maxlock.redisson.RedissonDistributedLock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MaxLockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaxLockAutoConfiguration.class));

    @Test
    void 无Redisson类时降级本地JVM锁() {
        runner.withClassLoader(new FilteredClassLoader(RedissonClient.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DistributedLock.class);
                    assertThat(ctx.getBean(DistributedLock.class)).isInstanceOf(LocalJvmDistributedLock.class);
                });
    }

    @Test
    void 有RedissonClientBean时使用Redis锁() {
        runner.withBean(RedissonClient.class, () -> Mockito.mock(RedissonClient.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DistributedLock.class);
                    assertThat(ctx.getBean(DistributedLock.class)).isInstanceOf(RedissonDistributedLock.class);
                });
    }

    @Test
    void provider强制local时即使有Redisson也用本地锁() {
        runner.withBean(RedissonClient.class, () -> Mockito.mock(RedissonClient.class))
                .withPropertyValues("max.lock.provider=local")
                .run(ctx -> assertThat(ctx.getBean(DistributedLock.class))
                        .isInstanceOf(LocalJvmDistributedLock.class));
    }

    @Test
    void provider为none时不装配任何锁() {
        runner.withPropertyValues("max.lock.provider=none")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DistributedLock.class));
    }

    @Test
    void 默认装配LockAspect_可通过配置关闭() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(LockAspect.class));
        runner.withPropertyValues("max.lock.aspect-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LockAspect.class));
    }

    @Test
    void 用户自定义DistributedLockBean优先() {
        DistributedLock custom = new LocalJvmDistributedLock();
        runner.withBean("customLock", DistributedLock.class, () -> custom)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DistributedLock.class);
                    assertThat(ctx.getBean(DistributedLock.class)).isSameAs(custom);
                });
    }
}
