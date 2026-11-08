# chat-cloud

chat 项目的后端微服务聚合工程（由原 `chat-parent` 改造而来）。

- 父工程：`org.example:chat-cloud`，`packaging=pom`，**只做版本管理和模块聚合，不写业务代码**
- Spring Boot：`4.1.1`
- Spring Cloud：`2025.1.3`（Oakwood，官方对应 Spring Boot 4.0.x / 4.1.x）
- Spring Cloud Alibaba：`2025.1.0.0`（对应 Spring Cloud 2025.1.x，内含 nacos-client 3.1.1）
- 注册中心：**Nacos**（外部独立服务，不是本工程的模块）
- JDK：17（本机 JDK 23 可正常编译，通过 `maven.compiler.release=17` 锁定字节码版本）

> 版本纪律：Spring Cloud 是 release train，Boot / Cloud / Alibaba 三者的版本必须成对，不要在子模块里自己写版本号。

## 模块与端口

| 模块 | 说明 | 端口 | 关键依赖 |
| --- | --- | --- | --- |
| `chat-common` | 公共 DTO / 统一返回体 / 常量（普通 jar，不可打成可执行 jar） | - | 无 |
| `chat-gateway` | API 网关（WebFlux 版） | 8080 | gateway-server-webflux + nacos-discovery + loadbalancer |
| `chat-auth` | 认证服务，签发/校验 token | 8081 | web + nacos-discovery + data-redis |
| `chat-user` | 用户服务，被 message 服务通过 Feign 调用 | 8082 | web + nacos-discovery |
| `chat-message` | 消息服务，演示服务间调用 | 8083 | web + nacos-discovery + openfeign + loadbalancer |

> **没有 `chat-registry` 模块**：Nacos 是独立部署的服务端（像 MySQL、Redis 一样），不需要自己写一个注册中心。

## 先启动 Nacos（外部依赖）

服务发现依赖 Nacos 服务端，用 Docker 起一个单机版即可：

```bash
docker run -d --name nacos \
  -p 8848:8848 \
  -p 9848:9848 \
  -e MODE=standalone \
  nacos/nacos-server:v3.1.0
```

- `8848`：主端口（服务注册/发现走这里）
- `9848`：gRPC 端口（Nacos 2.x/3.x 的客户端长连接必需，**少映射这个会连不上**）

> Nacos 3.x 的控制台默认在容器内的 8080 端口。如果和本机 `chat-gateway`（也是 8080）冲突，**不要映射控制台端口**即可 —— 服务发现只依赖 8848 + 9848，不需要控制台。

## 启动顺序

**Nacos 必须先就绪**（服务注册不上会一直重试）：

```bash
cd chat-cloud

# 1. 业务服务（三个窗口分别执行）
mvnw.cmd -pl chat-user spring-boot:run
mvnw.cmd -pl chat-message spring-boot:run
mvnw.cmd -pl chat-auth spring-boot:run

# 2. 网关最后起
mvnw.cmd -pl chat-gateway spring-boot:run
```

也可以先整体打包，再直接跑 jar：

```bash
mvnw.cmd -DskipTests clean package
java -jar chat-user/target/chat-user-0.0.1-SNAPSHOT.jar
# 其余模块同理
```

## 验证（走网关）

统一约定：外部只访问网关的 `/api/<service>/**`，网关 `StripPrefix=1` 去掉 `/api` 后转发。

```bash
# 用户服务：/api/user/list  ->  转发到 chat-user 的 /user/list
curl http://localhost:8080/api/user/1
curl http://localhost:8080/api/user/list

# 消息服务：内部会通过 Feign 调用 chat-user 补全发送者用户名
curl http://localhost:8080/api/message/100
```

`curl http://localhost:8080/api/message/100` 返回的 `senderName` 是 `alice`（注册用户的用户名），就证明 `chat-message → chat-user` 的服务发现 + Feign 调用链路已经通了。

## 配置约定

每个业务服务的 `application.yml` 都指向同一个 Nacos：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        # 跨服务器部署时改成 Nacos 的真实内网地址，如 192.168.1.10:8848
        server-addr: localhost:8848
      config:
        enabled: false   # 只用服务发现，关掉配置中心
```

服务间调用一律用**服务名**，不写 ip:port：

```java
@FeignClient(name = ServiceNames.CHAT_USER)
public interface UserClient {
    @GetMapping("/user/{id}")
    Result<UserDTO> getById(@PathVariable("id") Long id);
}
```

网关路由同理，用 `lb://服务名`：

```yaml
- id: chat-user
  uri: lb://chat-user        # 靠 Nacos 解析成真实 ip:port
  predicates:
    - Path=/api/user/**
```

## 注意事项（踩坑点）

1. **`chat-common` 不能加 `spring-boot-maven-plugin`**。加了会被 repackage 成可执行 jar，其他模块引不到里面的类。
2. **网关不能引 `spring-boot-starter-web`**。Gateway 是 WebFlux 响应式的，混入 Spring MVC 会启动失败。
3. **Spring Cloud Gateway 5.x 的配置前缀变了**：`spring.cloud.gateway.server.webflux.routes`，旧的 `spring.cloud.gateway.routes` 已废弃。
4. **starter 名字变了**：`spring-cloud-starter-gateway-server-webflux`（旧名 `spring-cloud-starter-gateway` 已废弃）。
5. **JDK 21+ 必须显式声明 Lombok 注解处理器**，否则 `@Data` 静默失效（不报错但不生成 getter）。
6. 依赖版本统一在父 pom 的 `dependencyManagement` 管理，子模块**不要写版本号**。
7. 父工程 `chat-cloud/pom.xml` 是 `packaging=pom`，业务代码全部下沉到各子服务。

## 后续可以加的东西

- Nacos 配置中心（`spring-cloud-starter-alibaba-nacos-config`）
- 熔断限流（Sentinel / Resilience4j）
- 链路追踪（Micrometer Tracing + Zipkin）
- 每个服务独立数据库 + MyBatis-Plus / JPA
- 聊天场景的实时消息：单独起 `chat-ws` 服务，用 WebSocket/Netty，网关做 ws 路由
