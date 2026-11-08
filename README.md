# Chat

正在开发中的即时通信项目，包含 Spring Boot 微服务后端和 Vue / Electron 桌面客户端，支持单聊、群聊、消息发送与接收、送达和已读回执，以及本地 SQLite 消息存储。

## 项目结构

| 目录 | 用途 |
| --- | --- |
| `chat-cloud/` | Maven 后端聚合工程 |
| `chat-web/` | Vue 页面、Electron 主进程和本地存储 |
| `docs/` | 消息协议、群聊和多实例等设计文档 |

| 后端模块 | 用途 | 示例端口 |
| --- | --- | --- |
| `chat-common` | 公共 DTO、常量和共享组件 | — |
| `chat-gateway` | HTTP / WS 入口与鉴权 | 8080 |
| `chat-auth` | 登录、注册与凭证管理 | 8081 |
| `chat-user` | 用户信息与头像 | 8082 |
| `chat-message` | 单聊、群聊、回执和消息分发 | 8083 |
| `chat-relation` | 好友关系与群成员管理 | 8084 |
| `chat-connection` | WS 连接、在线路由和推送 | 8085 |

实际端口以各服务的本地配置和启动参数为准。

## 开发环境

- JDK 17 或以上；项目编译目标为 Java 17。
- Maven，或使用 `chat-cloud/mvnw` / `mvnw.cmd`。
- Node.js 24，与当前开发环境一致；Electron 主进程的本地存储使用 `node:sqlite`。
- MySQL 8、Redis、RabbitMQ 和 Nacos。

## 本地配置

每个服务都提供 `src/main/resources/application.example.yml`。新环境需要在同一目录复制为 `application.yml`，再按实际环境调整。

在项目根目录使用 PowerShell 复制缺失的配置文件，已有文件不会被覆盖：

```powershell
Get-ChildItem .\chat-cloud -Directory | ForEach-Object {
    $resources = Join-Path $_.FullName 'src/main/resources'
    $example = Join-Path $resources 'application.example.yml'
    $local = Join-Path $resources 'application.yml'
    if ((Test-Path -LiteralPath $example) -and !(Test-Path -LiteralPath $local)) {
        Copy-Item -LiteralPath $example -Destination $local
    }
}
```

在启动服务的环境中设置以下变量；示例配置不包含这些凭证的默认值：

| 环境变量 | 用途 |
| --- | --- |
| `CHAT_MYSQL_USER` | 数据库用户名，示例默认 `root` |
| `CHAT_MYSQL_PASSWORD` | 数据库密码，user / relation / message 必填 |
| `CHAT_RABBIT_USER` | RabbitMQ 用户名，示例默认 `guest` |
| `CHAT_RABBIT_PASSWORD` | RabbitMQ 密码，message 必填 |
| `CHAT_INTERNAL_TOKEN` | 服务内部调用凭证，message 和 connection 必须一致 |

数据库结构需要另行准备；按仓库提交范围要求，SQL 文件和数据库备份不在仓库中。

认证服务从 `classpath:rsa/private_key.pem` 加载私钥。仅在新环境尚无私钥时，从项目根目录执行：

```powershell
node chat-cloud/scripts/generate-rsa-keypair.cjs
```

该脚本会写入私钥文件；已有密钥的环境不要重复执行。私钥和本地配置已被 Git 忽略。

## 构建和启动

先启动 MySQL、Redis、RabbitMQ、Nacos，再启动业务服务和网关。首次运行前完成本地配置及数据库准备。

后端构建：

```powershell
cd chat-cloud
.\mvnw.cmd "-Dmaven.test.skip=true" package
```

分别运行各服务 `target/` 下生成的可执行 JAR，或在 IDE 中启动对应 Application。多实例启动方法见 `docs/multi-instance.md`。

桌面客户端开发：

```powershell
cd chat-web
npm ci
npm run electron:dev
```

客户端默认访问本机网关 `http://localhost:8080`。跨机器连接时，HTTP API 使用前端构建变量 `VITE_API_BASE`；Electron WS 使用进程环境变量 `CHAT_WS_URL`。

## 仓库提交范围

仓库保留业务源码、构建文件、依赖锁文件、脱敏配置示例和业务文档。

本地测试与压测代码、SQL、真实配置、RSA 私钥、上传附件、静态媒体、本地数据库、日志、构建产物和 IDE 文件不提交。前端运行必需的 Vue、HTML 和 CSS 文件保留。
