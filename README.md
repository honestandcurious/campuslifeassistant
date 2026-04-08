# Campus Life Assistant

校园智能学习生活助手 Agent 系统，基于 Spring Boot 3、LangChain4j、MyBatis-Plus、MySQL、RedisSearch 构建，面向大学生提供课表查询、课程资料检索、提醒管理、天气查询与路线规划能力。

## 技术栈

- JDK 21
- Maven 3.9+
- Spring Boot 3.3.x
- LangChain4j 0.36.2
- MyBatis-Plus
- MySQL 8.x
- Redis Stack / RediSearch

## 项目结构

```text
com.student.agent
├── controller
├── service
├── agent
├── tools
├── rag
├── mcp
├── entity
├── repository
├── config
```

## 核心能力

- 课表查询：例如“我明天第一节课是什么？”
- RAG 资料检索：例如“数据库期末试卷有哪些？”
- 天气与出行建议：例如“明天上课需要带伞吗？”
- 提醒管理：例如“提醒我明天带实验报告”
- 路线规划：例如“从图书馆到实验楼怎么走？”
- 简单对话记忆：通过 `X-Memory-Id` 隔离会话

## 环境准备

### 1. MySQL

创建数据库：

```sql
CREATE DATABASE campus_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

项目启动时会自动执行以下脚本：

- `src/main/resources/db/schema.sql`
- `src/main/resources/db/data.sql`

默认配置：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/campus_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
```

### 2. Redis Stack / RediSearch

必须使用带 RediSearch 模块的 Redis Stack，而不是普通 Redis。

Docker 启动示例：

```bash
docker run -d --name redis-stack -p 6379:6379 redis/redis-stack:latest
```

默认 Redis 配置：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

app:
  rag:
    redis:
      enabled: true
      host: localhost
      port: 6379
      index-name: campus_material_index
      prefix: "material:"
      dimension: 1536
```

说明：

- `dimension=1536` 对应 `text-embedding-3-small`
- 如果你切换 Embedding 模型，必须同步修改 `dimension`
- `app.ai.enabled=false` 时项目仍可启动，但 RAG 走本地降级检索逻辑

### 3. OpenAI Key

Windows PowerShell:

```powershell
$env:OPENAI_API_KEY="your_openai_api_key"
```

启用真实 Agent + Embedding：

```yaml
app:
  ai:
    enabled: true
```

## 高德地图 MCP 配置

可在 MCP 客户端中使用以下配置：

```json
{
  "mcpServers": {
    "amap-maps": {
      "command": "npx",
      "args": [
        "-y",
        "@amap/amap-maps-mcp-server"
      ],
      "env": {
        "AMAP_MAPS_API_KEY": "api_key"
      }
    }
  }
}
```

本项目内部同时提供了简化版 MCP API 调用层：

- `com.student.agent.mcp.WeatherMcpClient`
- `com.student.agent.mcp.RouteMcpClient`

如果未配置 `AMAP_MAPS_API_KEY`，路线规划会自动返回模拟结果。

## 启动步骤

### 1. 检查 Java 与 Maven

```bash
java -version
mvn -version
```

要求：

- Java 21
- Maven 3.9+

### 2. 启动项目

```bash
mvn spring-boot:run
```

或先打包：

```bash
mvn clean package -DskipTests
java -jar target/campus-life-assistant-1.0.0.jar
```

## 接口说明

### POST /chat

请求头：

```http
X-Memory-Id: student-001
Content-Type: application/json
```

请求体：

```json
{
  "message": "我明天第一节课是什么？"
}
```

响应体：

```json
{
  "reply": "数据库原理，第1节，08:00-09:35，地点：教学楼C305，教师：陈老师"
}
```

### curl 示例

课表查询：

```bash
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: application/json" ^
  -H "X-Memory-Id: student-001" ^
  -d "{\"message\":\"我明天第一节课是什么？\"}"
```

RAG 检索：

```bash
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"数据库期末试卷有哪些？\"}"
```

提醒添加：

```bash
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"提醒我明天带实验报告\"}"
```

提醒查询：

```bash
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"我有哪些提醒？\"}"
```

天气与建议：

```bash
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"明天上课需要带伞吗？\"}"
```

路线规划：

```bash
curl -X POST http://localhost:8080/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"message\":\"从图书馆到实验楼怎么走？\"}"
```

## 运行模式

### 模式一：演示模式

配置：

```yaml
app:
  ai:
    enabled: false
```

特点：

- 不依赖 OpenAI Key
- Agent 走本地规则降级逻辑
- 课程资料检索走本地余弦相似度近似实现
- 适合本地演示和接口联调

### 模式二：完整 AI 模式

配置：

```yaml
app:
  ai:
    enabled: true
```

特点：

- 使用 LangChain4j `AiServices`
- 自动注册 `@Tool`
- 自动注入 `ContentRetriever`
- 文档通过 `EmbeddingStoreIngestor` 切分、向量化并写入 RedisSearch

## 数据表说明

### schedule

- 课表信息
- 支持按星期与节次检索

### reminder

- 提醒事项
- 支持新增与列表查询

### course_material

- 课程资料原文
- 启动时加载并参与 RAG ingest

## 关键类说明

- `StudentAssistantAgent`：Agent 接口，定义系统提示词
- `StudentAssistantFallbackAgent`：无 LLM 时的降级实现
- `StudentAssistantTools`：统一 Tool 注册入口
- `CourseMaterialRagService`：课程资料加载、切分、检索
- `LangChainConfig`：LLM、Embedding、RedisEmbeddingStore、Retriever 装配
- `ChatController`：统一 `/chat` 接口

## RedisSearch 说明

本项目现在使用 LangChain4j 官方 `RedisEmbeddingStore` 作为向量库接入层。只要满足以下条件，RAG 检索就会走真实 RedisSearch：

- `app.ai.enabled=true`
- `app.rag.redis.enabled=true`
- Redis 服务为 Redis Stack，并已加载 RediSearch 模块
- `OPENAI_API_KEY` 已配置

如果你的 Redis 不是 Redis Stack，向量索引无法建立，项目会在真实 AI 模式下启动失败。这种情况下请改为：

```yaml
app:
  ai:
    enabled: false
```

或把 `app.rag.redis.enabled` 暂时改成 `false`。

## 后续扩展建议

- 增加用户维度的课表与提醒隔离
- 接入真实校园教务系统
- 接入文件上传与 PDF 自动解析入库
- 为 Reminder 增加定时任务和到期通知
- 将路线规划改为真实经纬度与多出行方式查询

## 已知限制

- 当前环境如果不是 JDK 21，将无法编译
- 当前环境如果没有 Maven，将无法直接执行 `mvn` 命令
- 路线与天气模块默认带有降级模拟结果，便于无 Key 场景启动
