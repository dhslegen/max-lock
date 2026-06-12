package com.dahaoshen.maxlock.boot3;

import com.dahaoshen.maxlock.core.DistributedLock;
import com.dahaoshen.maxlock.local.LocalJvmDistributedLock;
import com.dahaoshen.maxlock.redisson.RedissonDistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * max-lock 自动装配（Spring Boot 3.x）：
 * <ol>
 *   <li>有 RedissonClient Bean 且 provider ∈ {auto, redisson} → Redis 分布式锁</li>
 *   <li>否则 provider ∈ {auto, local} → 本地 JVM 锁（auto 降级时告警）</li>
 *   <li>provider = none → 不装配任何锁（切面由 {@link MaxLockAspectAutoConfiguration} 按 Bean 存在性决定）</li>
 * </ol>
 * <p>
 * 设计说明：RedissonLockConfiguration 和 LocalLockConfiguration 作为嵌套配置类，
 * 通过类级别的 @Conditional(AnyNestedCondition) 在 PARSE_CONFIGURATION 阶段按 provider 过滤，
 * 然后在 Bean 方法上用 @ConditionalOnMissingBean 互斥。
 * RedissonLockConfiguration 中使用 ObjectProvider 而非 @ConditionalOnBean 避免用户 Bean 可见性问题。
 * 切面装配已迁移至 {@link MaxLockAspectAutoConfiguration}，以 DistributedLock Bean 存在为条件，
 * 支持用户在 provider=none 时自定义 DistributedLock Bean 并自动激活切面。
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(MaxLockProperties.class)
@Import({MaxLockAutoConfiguration.RedissonLockConfiguration.class,
        MaxLockAutoConfiguration.LocalLockConfiguration.class})
public class MaxLockAutoConfiguration {

    /**
     * Redisson 锁配置：需要 RedissonClient 类在 classpath，provider 为 auto 或 redisson。
     * 通过 ObjectProvider 运行时获取 RedissonClient，有则装配 Redis 锁，无则降级本地锁（仅 auto）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RedissonClient.class, RedissonDistributedLock.class})
    @Conditional(RedissonProviderCondition.class)
    static class RedissonLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(DistributedLock.class)
        public DistributedLock redissonDistributedLock(ObjectProvider<RedissonClient> redissonClientProvider,
                                                       MaxLockProperties properties) {
            RedissonClient client = redissonClientProvider.getIfAvailable();
            if (client != null) {
                log.info("max-lock: 装配 RedissonDistributedLock");
                return new RedissonDistributedLock(client);
            }
            if (properties.getProvider() == MaxLockProperties.Provider.REDISSON) {
                throw new IllegalStateException(
                        "max-lock: provider=redisson 但未找到 RedissonClient Bean，请检查 Redisson 配置");
            }
            // provider=auto 但无 RedissonClient Bean，降级为本地 JVM 锁
            log.warn("max-lock: RedissonClient 类在 classpath 但无 Bean，降级为本地 JVM 锁——" +
                    "集群部署时不具备分布式互斥语义！如需 Redis 锁请配置 RedissonClient Bean");
            return new LocalJvmDistributedLock();
        }
    }

    /**
     * 本地 JVM 锁配置：
     * <ul>
     *   <li>provider=local：强制本地锁</li>
     *   <li>provider=auto 且 RedissonClient 类不在 classpath：自动降级</li>
     * </ul>
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(LocalActivationCondition.class)
    static class LocalLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(DistributedLock.class)
        public DistributedLock localJvmDistributedLock(MaxLockProperties properties) {
            if (properties.getProvider() == MaxLockProperties.Provider.AUTO) {
                log.warn("max-lock: 未检测到 Redisson，降级为本地 JVM 锁——集群部署时不具备分布式互斥语义！" +
                        "如需 Redis 锁请引入 max-lock-redisson 与 Redisson 依赖");
            } else {
                log.info("max-lock: 装配 LocalJvmDistributedLock");
            }
            return new LocalJvmDistributedLock();
        }
    }

    /** provider ∈ {auto(缺省), redisson} */
    static class RedissonProviderCondition extends AnyNestedCondition {
        RedissonProviderCondition() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "provider",
                havingValue = "auto", matchIfMissing = true)
        static class OnAuto { }

        @ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "provider", havingValue = "redisson")
        static class OnRedisson { }
    }

    /**
     * 本地锁激活条件：
     * <ul>
     *   <li>provider=local</li>
     *   <li>或 provider=auto 且 RedissonClient 类不在 classpath</li>
     * </ul>
     */
    static class LocalActivationCondition extends AnyNestedCondition {
        LocalActivationCondition() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "provider", havingValue = "local")
        static class OnLocal { }

        @ConditionalOnProperty(prefix = MaxLockProperties.PREFIX, name = "provider",
                havingValue = "auto", matchIfMissing = true)
        @ConditionalOnMissingClass("org.redisson.api.RedissonClient")
        static class OnAutoWithoutRedisson { }
    }
}
