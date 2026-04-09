# Campus Life Assistant

校园智能学习生活助手 Agent 系统。项目面向大学生，围绕“通过自然语言完成校园学习与生活服务”这一目标，提供课表查询、课程资料检索、提醒管理、天气查询、路线规划、出行准备建议等能力，并配套一个 Vue 3 单页聊天前端。

## 1. 项目概述

本项目采用“前后端分离 + Agent 驱动 + Tool 调用 + RAG 检索 + MCP 外部能力接入”的实现方式。用户在前端输入自然语言问题，后端 Agent 会根据问题自动判断：

- 是否需要查询课表
- 是否需要查询提醒
- 是否需要做课程资料检索
- 是否需要调用天气 MCP 工具
- 是否需要调用地图 MCP 工具
- 是否需要根据课表和天气综合生成出行准备建议

整个系统既支持普通同步问答，也支持流式响应，前端会边接收边渲染 AI 回复。

## 2. 核心功能

### 2.1 智能聊天问答

统一通过 `/chat` 接口完成所有能力调用，用户不需要切换页面。

支持示例：

- `我明天第一节课是什么？`
- `数据库期末试卷有哪些？`
- `提醒我明天带实验报告`
- `我有哪些提醒？`
- `明天上课需要带伞吗？`
- `从图书馆到实验楼怎么走？`
- `明天上课需要带什么？`

### 2.2 课表查询

根据自然语言提取日期和节次信息，调用本地 `getSchedule` Tool 查询当天课程安排。

能力包括：

- 今天 / 明天 / 后天课表查询
- 第几节课查询
- 当天课程概览

### 2.3 课程资料检索（RAG）

课程资料存储在数据库中，启动后由 RAG 模块加载、切分、向量化并写入 Redis 向量存储。用户输入课程资料相关问题时，Agent 会优先调用检索能力返回摘要结果。

支持示例：

- `数据库期末试卷有哪些？`
- `操作系统实验报告模板是什么？`
- `Spring Boot 实训指导有哪些要求？`

### 2.4 提醒管理

支持自然语言添加提醒与查询提醒。

支持示例：

- `提醒我明天带实验报告`
- `今晚记得提交数据库作业`
- `我有哪些提醒？`

### 2.5 天气查询

项目接入 `open-meteo-mcp` 作为天气 MCP 服务。Agent 在遇到天气类问题时，会优先调用 MCP 工具而不是凭空回答。

支持示例：

- `今天会下雨吗？`
- `明天上课需要带伞吗？`
- `今天出门穿什么合适？`

### 2.6 路线规划

项目接入 `@amap/amap-maps-mcp-server` 作为地图 MCP 服务，用于地点查询和路线规划。

支持示例：

- `从图书馆到实验楼怎么走？`
- `从宿舍到教学楼要多久？`
- `去操场怎么走？`

### 2.7 出行提醒智能决策

这是项目里一个比较重要的能力。对于“出行准备”类问题，Agent 不只做单点查询，而是按规则综合分析。

典型问题：

- `明天上课需要带什么？`
- `今天出门要准备什么？`
- `早八要不要带伞？`

Agent 会按规则：

1. 解析日期
2. 先查询课表
3. 再查询天气
4. 结合课程关键词、天气、温度、是否早八，生成出行建议

输出内容会包含：

- 日期说明
- 当天课程概览
- 天气情况
- 学习用品建议
- 出行物品建议
- 时间提醒

## 3. 技术栈

### 3.1 后端技术栈

- JDK 21
- Spring Boot 3.3.5
- Maven
- MyBatis-Plus 3.5.5
- MySQL 8.x
- Redis / RedisSearch
- LangChain4j 1.4.0-beta10
- LangChain4j Reactor
- LangChain4j MCP
- LangChain4j Community Redis Starter

### 3.2 前端技术栈

- Vue 3
- Vite
- Axios
- Fetch Stream API
- 原生 CSS

### 3.3 外部能力

- 高德地图 MCP Server：`@amap/amap-maps-mcp-server`
- 天气 MCP Server：`open-meteo-mcp`
- DashScope 兼容 OpenAI API 模式

## 4. 技术架构

### 4.1 后端分层结构

```text
com.student.agent
├── controller      # REST 接口层
├── service         # 业务逻辑层
├── agent           # Agent 接口与提示词定义
├── tools           # LangChain4j Tool 集合
├── rag             # 文档检索与课程资料查询
├── mcp             # 外部天气 / 路线能力封装
├── entity          # 数据库实体
├── repository      # MyBatis-Plus Mapper
├── config          # 配置类、MCP、CORS、异常、模型装配
```

### 4.2 系统调用流程

用户请求进入后端后，大致流程如下：

1. 前端向 `/chat` 发起请求
2. `ChatController` 接收请求并返回流式响应
3. `ChatService` 优先走流式 Agent
4. Agent 根据问题意图选择：
   - 本地 Tool
   - RAG 检索
   - MCP 工具
5. 后端将生成内容以流的形式回传前端
6. 前端边接收边显示消息内容

## 5. 技术亮点

### 5.1 Agent + Tool + RAG + MCP 混合架构

很多项目只做到“大模型问答”或“RAG 检索”中的一种。本项目把四类能力组合到了一起：

- Agent 负责意图理解和决策
- Tool 负责本地确定性业务
- RAG 负责课程资料检索
- MCP 负责接入外部实时能力

这种结构比单纯 prompt 问答更可控，也比纯规则系统更灵活。

### 5.2 流式对话体验

后端使用 `Flux<String>` 返回流式响应，前端使用 `fetch + ReadableStream` 按分片读取并实时渲染，避免长请求阻塞等待。

用户体验上体现为：

- 不再受固定 15 秒超时限制
- AI 回复可以逐步显示
- 对长回答和外部工具调用更友好

### 5.3 MCP 接入外部工具能力

项目没有把地图和天气接口直接写死在业务层，而是通过 MCP 模式接入外部能力，让 Agent 能像调用工具一样调用：

- 高德地图能力
- Open-Meteo 天气能力

这意味着项目未来可以继续扩展更多 MCP Server，而不需要推翻现有架构。

### 5.4 规则化的出行准备建议

“出行准备”不是简单的天气查询，而是一个多步骤决策能力。项目通过 Prompt 规则把这类问题定义成明确流程：

- 先查课表
- 再查天气
- 最后按课程关键词、天气、温度、时间生成建议

这比直接让模型自由回答更稳定。

### 5.5 支持会话记忆

通过 `X-Memory-Id` 可以区分不同用户会话，Agent 会保留一定范围内的上下文消息，支持多轮对话。

### 5.6 支持降级运行

当流式模型不可用或 AI 能力未开启时，系统仍然保留降级 Agent，便于本地演示和接口联调。

## 6. 数据库设计

### 6.1 schedule

课表表，用于存储课程安排。

关键字段：

- `course_name`
- `teacher`
- `classroom`
- `day_of_week`
- `section_index`
- `start_time`
- `end_time`

### 6.2 reminder

提醒表，用于记录用户提醒事项。

关键字段：

- `content`
- `remind_time`
- `status`

### 6.3 course_material

课程资料表，用于存储课程资料原文，供 RAG 检索使用。

关键字段：

- `course_name`
- `title`
- `content`
- `source`

数据库初始化脚本：

- `src/main/resources/db/schema.sql`
- `src/main/resources/db/data.sql`

## 7. RAG 设计

项目中的 RAG 模块主要用于课程资料检索。

流程如下：

1. 读取课程资料
2. 进行文档切分
3. 调用 Embedding 模型生成向量
4. 写入 Redis 向量存储
5. 查询时根据用户问题做相似度检索
6. 返回 TopK 摘要结果

关键能力：

- `DocumentSplitters.recursive(500, 100)`
- `EmbeddingStoreIngestor`
- `EmbeddingStoreContentRetriever`
- Redis 向量索引

## 8. MCP 设计

### 8.1 地图 MCP

通过 `@amap/amap-maps-mcp-server` 接入地图服务。

能力包括：

- 地点查询
- 步行路线规划
- 出行建议辅助

### 8.2 天气 MCP

通过 `open-meteo-mcp` 接入天气服务。

能力包括：

- 指定日期天气查询
- 温度 / 湿度 / 天气类型查询
- 配合课表生成出行准备建议

## 9. 前端说明

前端位于：

- `campuslifeassistant_frontend`

前端是一个单页聊天应用，整体风格参考 ChatGPT，包含：

- 顶部标题区
- 中间聊天区
- 底部输入区

已实现：

- 回车发送
- 自动滚动到底部
- AI 正在思考提示
- 流式消息展示
- 请求失败提示

核心文件：

- `campuslifeassistant_frontend/src/App.vue`
- `campuslifeassistant_frontend/src/api.js`
- `campuslifeassistant_frontend/src/components/ChatHeader.vue`
- `campuslifeassistant_frontend/src/components/ChatMessageList.vue`
- `campuslifeassistant_frontend/src/components/ChatInput.vue`

## 10. 启动方式

### 10.1 后端环境要求

- JDK 21
- Maven 3.9+
- MySQL 8.x
- Redis Stack / RedisSearch
- Node.js 与 npx

### 10.2 配置环境变量

PowerShell：

```powershell
$env:OPENAI_API_KEY="你的模型Key"
$env:AMAP_MAPS_API_KEY="你的高德Key"
```

如果你使用 DashScope OpenAI 兼容模式，可保留 `application.yml` 中的：

```yaml
app:
  ai:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
```

### 10.3 启动后端

```bash
mvn spring-boot:run
```

默认端口：

- `8088`

### 10.4 启动前端

```bash
cd campuslifeassistant_frontend
npm install
npm run dev
```

默认前端地址：

- `http://localhost:5173`

## 11. 接口说明

### 11.1 聊天接口

`POST /chat`

请求头：

```http
Content-Type: application/json
X-Memory-Id: default-user
```

请求体：

```json
{
  "message": "明天上课需要带什么？"
}
```

响应类型：

- `text/event-stream`

返回形式：

- 流式文本分片

## 12. 典型使用场景

### 场景一：课表查询

输入：

```text
我明天第一节课是什么？
```

系统行为：

- Agent 识别为课表查询
- 调用 `getSchedule`
- 返回课程信息

### 场景二：课程资料检索

输入：

```text
数据库期末试卷有哪些？
```

系统行为：

- Agent 识别为资料检索
- 调用 RAG
- 返回资料摘要

### 场景三：天气与出行建议

输入：

```text
明天上课需要带伞吗？
```

系统行为：

- 调用天气 MCP
- 调用课表 Tool
- 综合判断是否有早八、是否下雨
- 生成建议

### 场景四：出行准备决策

输入：

```text
明天上课需要带什么？
```

系统行为：

- 先解析日期
- 再查课表
- 再查天气
- 按规则生成学习用品和出行物品建议

## 13. 关键代码说明

- `ChatController`：统一聊天接口，返回流式响应
- `ChatService`：封装同步与流式对话逻辑
- `StudentAssistantAgent`：同步 Agent 提示词定义
- `StudentAssistantStreamingAgent`：流式 Agent 提示词定义
- `StudentAssistantTools`：本地 Tool 集合
- `CourseMaterialRagService`：课程资料检索核心实现
- `McpClientConfig`：MCP Client 与 MCP ToolProvider 装配
- `LangChainConfig`：模型、Retriever、Agent 装配

## 14. 项目亮点总结

如果从“作品完整度”和“工程设计”两个角度看，这个项目的亮点主要在以下几点：

- 它不是单一问答项目，而是一个可扩展的校园智能助手平台。
- 它不是只靠 Prompt，而是把 Tool、RAG、MCP、规则决策结合起来。
- 它既有后端 Agent 能力，也有可直接演示的前端聊天界面。
- 它支持流式输出，更接近真实 AI 产品体验。
- 它保留了结构化分层设计，后续可以继续扩展用户体系、教务接入、文件上传、任务调度等能力。

## 15. 后续可扩展方向

- 接入真实校园教务系统
- 增加用户登录与个人课表隔离
- 增加提醒定时触发与消息推送
- 增加 PDF / Word 文件上传与自动入库
- 将出行准备规则下沉为独立 Java Tool
- 增加单元测试与集成测试
- 将聊天历史持久化到 Redis 或 MySQL

## 16. 注意事项

- 本项目依赖 JDK 21，低版本无法正常编译运行。
- Redis 必须支持向量检索能力，否则 RAG 无法完整工作。
- 地图与天气 MCP 首次运行依赖 `npx` 拉取包，机器需要能联网。
- 如果你使用的是兼容 OpenAI 接口的模型服务，需要正确设置 `base-url`。
- 当前项目仍在持续演进中，部分 LangChain4j API 可能需要根据本地依赖版本微调。
