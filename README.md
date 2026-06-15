# max-lock

简单、优雅的分布式锁工具包 | [dahaoshen.com](https://dahaoshen.com)

[![CI](https://github.com/dhslegen/max-lock/actions/workflows/ci.yml/badge.svg)](https://github.com/dhslegen/max-lock/actions)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

## 特性

- **LockSpec 参数对象**：一个不可变对象表达"公平/等待/持有/前缀"全部维度，告别重载爆炸
- **函数式 API**：`execute` / `tryExecute` 自动管理锁生命周期，不可能忘记释放
- **@Lock 注解**：声明式加锁，SpEL 动态 key
- **自动决策**：按配置探测 Redis 连通性——能连通用 Redis 锁，连不通则醒目告警并回退本地 JVM 锁（兜底，应用不崩溃）
- **Java 8 基线**：核心与 Boot 2 starter 编译为 Java 8 字节码，同时支持 Spring Boot 2.x 与 3.x（`spring-boot3-starter` 因 Boot 3 要求需 Java 17）

## 快速开始

```xml
<!-- Spring Boot 3.x：唯一需要引入的依赖 -->
<dependency>
    <groupId>com.dahaoshen</groupId>
    <artifactId>max-lock-spring-boot3-starter</artifactId>
    <version>1.1.0</version>
</dependency>
```

Spring Boot 2.x 将 starter 换为 `max-lock-boot-starter`。

> 自 1.1.0 起，`max-lock-redisson` 与 `redisson` 已作为 starter 的非可选传递依赖随包引入，**无需再单独声明，也无需手写 `RedissonClient` Bean**。是否启用 Redis 锁由「配置 + 连通探测」自动决策（见下方配置）；已有自定义 `RedissonClient` Bean（如 redisson-spring-boot-starter）则自动复用、互不冲突。

### 注解式

```java
@Lock(key = "order:#{#orderId}", waitTime = 3, timeoutMessage = "订单处理中，请稍后")
public void process(Long orderId) { ... }
```

> `waitTime` 默认 `10`（秒）：缺省即"限时等待 10 秒，获取失败抛超时异常"。设为 `-1` 表示阻塞等待，`0` 表示立即尝试。

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
    # auto(默认)：有可用 Redis 则 Redis 锁，否则本地 JVM 锁
    # redisson：强制 Redis 锁，无连接配置或连不通则启动报错
    # local：强制本地 JVM 锁；none：不装配任何锁
    provider: auto
    key-prefix: "LOCK"    # @Lock 未指定 prefix 时的全局默认前缀
    aspect-enabled: true  # @Lock 切面开关
    # Redis 连接（仅 provider ∈ {auto, redisson} 生效）：按配置探测，不默认探本机。
    # 已有自定义 RedissonClient Bean 时本段被忽略（直接复用该 Bean）。
    redis:
      enabled: true                    # 关掉则即便有配置也不启用 Redis 锁
      address: redis://127.0.0.1:6379  # 完整地址，优先级高于 host/port
      # host: 127.0.0.1                # 与 port 配合；address 未配置时使用
      # port: 6379
      # password:                      # 未配置时回退 spring.data.redis.password
      # database: 0                    # 未配置时回退 spring.data.redis.database
      # probe-timeout: 1000            # 连通探测超时(ms)
```

> 地址解析优先级：`max.lock.redis.address` &gt; `max.lock.redis.host:port` &gt; `spring.data.redis.host:port`。三者均未配置时，`auto` 直接使用本地 JVM 锁。

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
