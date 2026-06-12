# max-lock

简单、优雅的分布式锁工具包 | [dahaoshen.com](https://dahaoshen.com)

[![CI](https://github.com/dahaoshen/max-lock/actions/workflows/ci.yml/badge.svg)](https://github.com/dahaoshen/max-lock/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

## 特性

- **LockSpec 参数对象**：一个不可变对象表达"公平/等待/持有/前缀"全部维度，告别重载爆炸
- **函数式 API**：`execute` / `tryExecute` 自动管理锁生命周期，不可能忘记释放
- **@Lock 注解**：声明式加锁，SpEL 动态 key
- **自动降级**：有 Redisson 用 Redis 锁，没有则本地 JVM 锁兜底（启动告警）
- **Java 8 基线**：同时支持 Spring Boot 2.x 与 3.x

## 快速开始

```xml
<!-- Spring Boot 3.x -->
<dependency>
    <groupId>com.dahaoshen</groupId>
    <artifactId>max-lock-spring-boot3-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- 需要 Redis 锁时再加 -->
<dependency>
    <groupId>com.dahaoshen</groupId>
    <artifactId>max-lock-redisson</artifactId>
    <version>1.0.0</version>
</dependency>
```

Spring Boot 2.x 将 starter 换为 `max-lock-boot-starter`。

### 注解式

```java
@Lock(key = "order:#{#orderId}", waitTime = 3, timeoutMessage = "订单处理中，请稍后")
public void process(Long orderId) { ... }
```

### 编程式

```java
@Autowired
private DistributedLock lock;

// 函数式：自动释放
lock.execute("daily-report", () -> generateReport());

// 完整配置
String result = lock.tryExecute(
        LockSpec.of("order:1001").fair().waitTime(Duration.ofSeconds(3)),
        () -> doProcess(),
        () -> "系统繁忙");

// 句柄式：try-with-resources
try (LockHandle handle = lock.lock("migration")) {
    migrate();
}
```

## 配置

```yaml
max:
  lock:
    provider: auto        # auto / redisson / local / none
    key-prefix: "LOCK"    # @Lock 未指定 prefix 时的全局默认前缀
    aspect-enabled: true  # @Lock 切面开关
```

## 模块

| 模块 | 说明 |
|------|------|
| max-lock-core | API + @Lock 切面 + 本地 JVM 实现（零强制三方依赖） |
| max-lock-redisson | Redisson（Redis）实现 |
| max-lock-boot-starter | Spring Boot 2.x 自动装配 |
| max-lock-spring-boot3-starter | Spring Boot 3.x 自动装配 |
| max-lock-bom | 依赖清单 |

## 本地开发

### 单元测试

```bash
mvn test
```

不需要 Docker。`max-lock-redisson` 中的 `*IT` 集成测试由 maven-failsafe-plugin 管理，仅在 `verify` 阶段运行，`test` 阶段不会触发。

### 集成测试

```bash
mvn verify
```

需要本地有可用的 Docker 环境（用于启动 Redis Testcontainer）。在标准 Docker Desktop / GitHub Actions ubuntu 环境下零额外配置即可运行。

### colima 用户（macOS）

colima 的 Docker socket 路径与标准 Docker Desktop 不同，且较新版本的 Docker daemon 需要显式指定 API 版本，ryuk 容器因 colima 的挂载限制也需禁用。请在 `~/.testcontainers.properties` 中添加如下配置：

```properties
docker.host=unix:///Users/<你的用户名>/.colima/default/docker.sock
ryuk.disabled=true
```

然后以如下命令运行集成测试：

```bash
TESTCONTAINERS_RYUK_DISABLED=true mvn verify -DargLine="-Dapi.version=1.44"
```

> 以上均为本机环境配置，不写入项目 pom.xml。

## License

[Apache License 2.0](LICENSE)
