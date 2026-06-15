package com.dahaoshen.maxlock.boot;

import com.dahaoshen.maxlock.aspect.LockAspect;
import com.dahaoshen.maxlock.core.DistributedLock;
import com.dahaoshen.maxlock.local.LocalJvmDistributedLock;
import com.dahaoshen.maxlock.redisson.RedissonDistributedLock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 约定优于配置的自动装配测试。
 * <p>
 * redisson 已是 starter 非可选传递依赖，RedissonClient 类必然在 classpath；
 * 是否启用 Redis 锁由「用户 Bean / 配置 + 连通探测」自动决策。
 */
class MaxLockAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MaxLockAutoConfiguration.class));

    private final ApplicationContextRunner runnerWithAspect = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MaxLockAutoConfiguration.class,
                    MaxLockAspectAutoConfiguration.class));

    @Test
    void 无Redis配置时默认本地JVM锁() {
        // provider=auto（默认）且无任何 Redis 连接配置 → 内部客户端为 null → 本地 JVM 锁
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DistributedLock.class);
            assertThat(ctx.getBean(DistributedLock.class)).isInstanceOf(LocalJvmDistributedLock.class);
        });
    }

    @Test
    void 有RedissonClientBean时复用并启用Redis锁() {
        // 用户自定义 RedissonClient → @ConditionalOnMissingBean 跳过内部自建 → 直接复用
        runner.withBean(RedissonClient.class, () -> Mockito.mock(RedissonClient.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DistributedLock.class);
                    assertThat(ctx.getBean(DistributedLock.class)).isInstanceOf(RedissonDistributedLock.class);
                });
    }

    @Test
    void provider强制local时即使有RedissonClient也用本地锁() {
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
    void 配置Redis但不可达时auto回退本地锁() {
        // 配置指向不可达地址，探测失败 → auto 醒目告警并回退本地 JVM 锁
        runner.withPropertyValues(
                        "max.lock.redis.host=127.0.0.1",
                        "max.lock.redis.port=1",
                        "max.lock.redis.probe-timeout=300")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DistributedLock.class);
                    assertThat(ctx.getBean(DistributedLock.class)).isInstanceOf(LocalJvmDistributedLock.class);
                });
    }

    @Test
    void provider为redisson且配置不可达时启动失败() {
        runner.withPropertyValues(
                        "max.lock.provider=redisson",
                        "max.lock.redis.host=127.0.0.1",
                        "max.lock.redis.port=1",
                        "max.lock.redis.probe-timeout=300")
                .run(ctx -> assertThat(ctx.getStartupFailure()).isNotNull());
    }

    @Test
    void provider为redisson但无任何Redis配置时启动失败() {
        runner.withPropertyValues("max.lock.provider=redisson")
                .run(ctx -> assertThat(ctx.getStartupFailure()).isNotNull());
    }

    @Test
    void 默认装配LockAspect_可通过配置关闭() {
        runnerWithAspect.run(ctx -> assertThat(ctx).hasSingleBean(LockAspect.class));
        runnerWithAspect.withPropertyValues("max.lock.aspect-enabled=false")
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

    @Test
    void provider为none但用户提供DistributedLock时仍装配切面() {
        runnerWithAspect
                .withPropertyValues("max.lock.provider=none")
                .withBean("customLock", DistributedLock.class, LocalJvmDistributedLock::new)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DistributedLock.class);
                    assertThat(ctx).hasSingleBean(LockAspect.class);
                });
    }

    @Test
    void 外部AutoConfig先提供RedissonClient时复用且不重复装配() {
        // 模拟 redisson-spring-boot-starter：另一个 AutoConfiguration（经 @AutoConfigureBefore 强制先于本类）
        // 提供 RedissonClient。@ConditionalOnMissingBean(RedissonClient) 应感知并退避，不自建客户端，
        // 复用外部 Bean → 仅一个 RedissonClient，DistributedLock 为 Redisson 锁，且不抛 NoUniqueBeanDefinitionException。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ForeignRedissonAutoConfiguration.class, MaxLockAutoConfiguration.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RedissonClient.class);
                    assertThat(ctx).hasSingleBean(DistributedLock.class);
                    assertThat(ctx.getBean(DistributedLock.class)).isInstanceOf(RedissonDistributedLock.class);
                });
    }

    /** 模拟第三方（redisson-spring-boot-starter）提供 RedissonClient 的 AutoConfiguration，强制先于本库装配 */
    @AutoConfiguration
    @AutoConfigureBefore(MaxLockAutoConfiguration.class)
    static class ForeignRedissonAutoConfiguration {
        @Bean
        RedissonClient foreignRedissonClient() {
            return Mockito.mock(RedissonClient.class);
        }
    }
}
