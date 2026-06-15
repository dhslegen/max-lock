package com.dahaoshen.maxlock.redisson;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import java.util.function.Function;

/**
 * Redisson 自动配置助手（纯 Redisson，不依赖 Spring）。
 * <p>
 * 仅依据显式配置解析 Redis 连接信息（不默认探测本机 6379），并以低超时、单次重试的方式
 * 探测连通性。解析不到配置返回 {@code null}，连接失败返回 {@code null}，由上层据此决定
 * 启用 Redis 分布式锁还是回退本地 JVM 锁。
 *
 * @author zhaowenhao
 * @since 2026-06-15
 */
@Slf4j
public final class RedissonAutoConfigurer {

    private RedissonAutoConfigurer() {
    }

    /** 解析后的 Redis 连接信息 */
    public static final class Resolved {
        public final String address;
        public final String password;
        public final int database;
        public final int timeoutMs;

        public Resolved(String address, String password, int database, int timeoutMs) {
            this.address = address;
            this.password = password;
            this.database = database;
            this.timeoutMs = timeoutMs;
        }

        /** 用于日志展示的简短地址（不含密码） */
        public String display() {
            return address + (database > 0 ? "#" + database : "");
        }
    }

    /**
     * 解析 Redis 连接配置。优先级：max.lock.redis.address &gt; max.lock.redis.host:port
     * &gt; spring.data.redis.host:port（spring.redis.* 兼容）。均缺失时返回 {@code null}。
     *
     * @param enabled   是否允许构建（false 直接返回 null）
     * @param address   完整地址（可含/不含 scheme）
     * @param host      主机名
     * @param port      端口（null 默认 6379）
     * @param password  密码（null 回退 spring 配置）
     * @param database  库号（null 回退 spring 配置，默认 0）
     * @param timeoutMs 探测超时毫秒（null 默认 1000）
     * @param env       配置读取器（如 Spring {@code Environment::getProperty}）
     * @return 解析结果，无任何连接配置时返回 null
     */
    public static Resolved resolve(boolean enabled, String address, String host, Integer port,
                                   String password, Integer database, Integer timeoutMs,
                                   Function<String, String> env) {
        if (!enabled) {
            return null;
        }
        int db = database != null ? database
                : firstInt(env, 0, "spring.data.redis.database", "spring.redis.database");
        int timeout = timeoutMs != null ? timeoutMs : 1000;
        String pwd = password != null ? password
                : first(env, "spring.data.redis.password", "spring.redis.password");
        if (notBlank(address)) {
            return new Resolved(withScheme(address), pwd, db, timeout);
        }
        if (notBlank(host)) {
            int p = port != null ? port : 6379;
            return new Resolved("redis://" + host + ":" + p, pwd, db, timeout);
        }
        String springHost = first(env, "spring.data.redis.host", "spring.redis.host");
        if (notBlank(springHost)) {
            int p = firstInt(env, 6379, "spring.data.redis.port", "spring.redis.port");
            return new Resolved("redis://" + springHost + ":" + p, pwd, db, timeout);
        }
        return null;
    }

    /**
     * 按解析结果构建并探测 RedissonClient。连接失败时关闭客户端并返回 {@code null}。
     *
     * @param r 解析结果（非空）
     * @return 连通的客户端，失败返回 null
     */
    public static RedissonClient tryConnect(Resolved r) {
        Config config = new Config();
        SingleServerConfig single = config.useSingleServer()
                .setAddress(r.address)
                .setConnectTimeout(r.timeoutMs)
                .setTimeout(r.timeoutMs)
                .setRetryAttempts(1)
                .setRetryInterval(200)
                .setDatabase(r.database);
        if (notBlank(r.password)) {
            single.setPassword(r.password);
        }
        RedissonClient client = null;
        try {
            client = Redisson.create(config);
            // 主动触发一次轻量命令验证真实连通性（Redisson.create 本身不保证已建连）
            client.getBucket("max-lock:__probe__").isExists();
            return client;
        } catch (Exception e) {
            log.debug("max-lock: 探测 Redis（{}）失败：{}", r.display(), e.toString());
            if (client != null) {
                try {
                    client.shutdown();
                } catch (Exception ignore) {
                    // 探测失败后的清理异常无需关心
                }
            }
            return null;
        }
    }

    private static String withScheme(String address) {
        return address.contains("://") ? address : "redis://" + address;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String first(Function<String, String> env, String... keys) {
        for (String k : keys) {
            String v = env.apply(k);
            if (notBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static int firstInt(Function<String, String> env, int defaultValue, String... keys) {
        String v = first(env, keys);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
