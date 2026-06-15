package com.dahaoshen.maxlock.boot;

import com.dahaoshen.maxlock.core.LockSpec;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * max-lock 配置属性
 *
 * @author zhaowenhao
 * @since 2026-06-12
 */
@Data
@ConfigurationProperties(prefix = MaxLockProperties.PREFIX)
public class MaxLockProperties {

    public static final String PREFIX = "max.lock";

    /** 锁提供者 */
    public enum Provider {
        /** 自动：有 Redisson 用 Redis 锁，否则降级本地 JVM 锁 */
        AUTO,
        /** 强制 Redisson（缺少依赖时启动报错） */
        REDISSON,
        /** 强制本地 JVM 锁 */
        LOCAL,
        /** 不装配任何锁 */
        NONE
    }

    /** 锁提供者，默认 AUTO */
    private Provider provider = Provider.AUTO;

    /** @Lock 注解未指定 prefix 时的全局默认前缀 */
    private String keyPrefix = LockSpec.DEFAULT_PREFIX;

    /** 是否启用 @Lock 注解切面 */
    private boolean aspectEnabled = true;

    /** Redis 连接配置：用于构建 max-lock 内部 Redisson 客户端（仅 provider ∈ {auto, redisson} 时生效） */
    private Redis redis = new Redis();

    /**
     * Redis 连接配置。
     * <p>
     * 仅依据显式配置启用 Redis 锁，不默认探测本机；未配置任何连接信息时 auto 模式自动回退本地 JVM 锁。
     * 地址优先级：{@code address} &gt; {@code host:port} &gt; {@code spring.data.redis.host:port}。
     */
    @Data
    public static class Redis {

        /** 是否允许构建内部 Redisson 客户端，默认 true；false 时即便有配置也不启用 Redis 锁 */
        private boolean enabled = true;

        /** 完整地址，如 {@code redis://127.0.0.1:6379}，优先级高于 host/port */
        private String address;

        /** 主机名（address 未配置时使用） */
        private String host;

        /** 端口，默认 6379 */
        private Integer port;

        /** 密码，未配置时回退 {@code spring.data.redis.password} */
        private String password;

        /** 库号，未配置时回退 {@code spring.data.redis.database}，默认 0 */
        private Integer database;

        /** 连接探测超时（毫秒），默认 1000 */
        private Integer probeTimeout;
    }
}
