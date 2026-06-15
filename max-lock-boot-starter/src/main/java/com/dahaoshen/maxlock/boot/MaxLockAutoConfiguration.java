package com.dahaoshen.maxlock.boot;

import com.dahaoshen.maxlock.core.DistributedLock;
import com.dahaoshen.maxlock.local.LocalJvmDistributedLock;
import com.dahaoshen.maxlock.redisson.RedissonAutoConfigurer;
import com.dahaoshen.maxlock.redisson.RedissonDistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * max-lock 自动装配（约定优于配置）：
 * <ol>
 *   <li>用户已声明 RedissonClient Bean → 直接复用，启用 Redis 分布式锁</li>
 *   <li>否则 provider ∈ {auto, redisson} 且存在 Redis 连接配置 → 内部自建 Redisson 客户端并探测连通</li>
 *   <li>探测连通 → Redis 分布式锁；探测失败（auto）→ 醒目告警并回退本地 JVM 锁；探测失败（redisson）→ 启动报错</li>
 *   <li>无任何 Redis 配置（auto）/ provider=local → 本地 JVM 锁</li>
 *   <li>provider=none → 不装配任何锁（切面由 {@link MaxLockAspectAutoConfiguration} 按 Bean 存在性决定）</li>
 * </ol>
 * <p>
 * redisson 与 max-lock-redisson 已作为 starter 的非可选传递依赖随包引入，因此 RedissonClient 类必然在
 * classpath；是否真正启用 Redis 锁完全由「配置 + 连通探测」自动决策，使用者无需手工声明任何锁相关依赖或 Bean。
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MaxLockProperties.class)
// 在 redisson-spring-boot-starter 之后装配：确保 @ConditionalOnMissingBean(RedissonClient) 能看到消费方
// 已有的 RedissonClient 并复用，避免自建客户端导致容器中出现重复 Bean。name 形式在该类缺失时安全退化。
@AutoConfigureAfter(name = "org.redisson.spring.starter.RedissonAutoConfiguration")
public class MaxLockAutoConfiguration {

    /**
     * 内部 Redisson 客户端：仅在 provider ∈ {auto, redisson} 且用户未自定义 RedissonClient Bean 时构建。
     * 依据显式配置解析连接并探测；无配置或连接失败返回 {@code null}（Spring NullBean，不触发 shutdown）。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient.class)
    @Conditional(RedissonProviderCondition.class)
    public RedissonClient maxLockRedissonClient(MaxLockProperties properties, Environment environment) {
        MaxLockProperties.Redis redis = properties.getRedis();
        boolean forceRedisson = properties.getProvider() == MaxLockProperties.Provider.REDISSON;
        RedissonAutoConfigurer.Resolved resolved = RedissonAutoConfigurer.resolve(
                redis.isEnabled(), redis.getAddress(), redis.getHost(), redis.getPort(),
                redis.getPassword(), redis.getDatabase(), redis.getProbeTimeout(),
                environment::getProperty);
        if (resolved == null) {
            if (forceRedisson) {
                throw new IllegalStateException(
                        "max-lock: provider=redisson 但未找到任何 Redis 连接配置（max.lock.redis.* 或 spring.data.redis.*）");
            }
            log.info("max-lock: 未配置 Redis 连接信息，将使用本地 JVM 锁");
            return null;
        }
        RedissonClient client = RedissonAutoConfigurer.tryConnect(resolved);
        if (client == null) {
            if (forceRedisson) {
                throw new IllegalStateException(
                        "max-lock: provider=redisson 但无法连接 Redis（" + resolved.display() + "），请检查 Redis 可用性与连接配置");
            }
            log.warn("\n========================================================================\n"
                            + "  ⚠ max-lock 警告：已配置 Redis（{}）但连接失败，自动回退到【本地 JVM 锁】！\n"
                            + "  ⚠ 集群/多实例部署下本地锁不具备跨进程互斥语义，存在并发安全风险。\n"
                            + "  ⚠ 请检查 Redis 是否可达、地址/密码/库号是否正确。\n"
                            + "========================================================================",
                    resolved.display());
            return null;
        }
        log.info("max-lock: 已连接 Redis（{}），启用 Redis 分布式锁", resolved.display());
        return client;
    }

    /**
     * DistributedLock Bean：有可用 RedissonClient（用户自定义或内部自建）则 Redis 锁，否则本地 JVM 锁。
     * provider=none 不装配（由 {@link NotNoneProviderCondition} 过滤）。
     */
    @Bean
    @ConditionalOnMissingBean(DistributedLock.class)
    @Conditional(NotNoneProviderCondition.class)
    public DistributedLock maxDistributedLock(ObjectProvider<RedissonClient> redissonClientProvider,
                                              MaxLockProperties properties) {
        // provider=local 强制本地锁，忽略任何（用户自定义或内部自建的）RedissonClient
        RedissonClient client = properties.getProvider() == MaxLockProperties.Provider.LOCAL
                ? null : redissonClientProvider.getIfAvailable();
        if (client != null) {
            log.info("max-lock: 装配 RedissonDistributedLock（Redis 分布式锁）");
            return new RedissonDistributedLock(client);
        }
        log.info("max-lock: 装配 LocalJvmDistributedLock（本地 JVM 锁）");
        return new LocalJvmDistributedLock();
    }

    /** provider ∈ {auto(缺省), redisson} */
    static class RedissonProviderCondition extends AnyNestedCondition {
        RedissonProviderCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "provider",
                havingValue = "auto", matchIfMissing = true)
        static class OnAuto { }

        @ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "provider", havingValue = "redisson")
        static class OnRedisson { }
    }

    /** provider ≠ none，即 ∈ {auto(缺省), redisson, local} */
    static class NotNoneProviderCondition extends AnyNestedCondition {
        NotNoneProviderCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "provider",
                havingValue = "auto", matchIfMissing = true)
        static class OnAuto { }

        @ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "provider", havingValue = "redisson")
        static class OnRedisson { }

        @ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "provider", havingValue = "local")
        static class OnLocal { }
    }
}
