你是一位专业前端开发，请帮我根据以下信息来生成对应的前端项目代码放置在F:
\AICODE\codex\CampusLifeAssistant\campuslifeassistant_frontend中。
一、项目目标

只实现一个“聊天页面”，所有功能（课表查询、资料检索、提醒、天气等）都通过对话完成，不需要单独页面。

二、技术栈要求
Vue 3
Vite
Axios
基础CSS（无需UI框架）
三、核心功能（必须实现）
1. 聊天界面（唯一页面）

路径：/

功能：

用户输入消息
调用后端接口
展示AI回复
支持多轮对话

接口：
POST http://localhost:8088/chat

请求：
{
"message": "用户输入"
}

响应：
{
"reply": "AI回复"
}

2. UI要求（简洁版 ChatGPT 风格）

页面布局：

上方：标题（校园智能助手）
中间：聊天记录区域（可滚动）
下方：输入框 + 发送按钮

聊天气泡：

用户消息：右侧
AI消息：左侧
3. 交互要求（必须实现）
   回车发送消息
   发送后清空输入框
   自动滚动到底部
   显示“AI正在思考...”状态
   请求失败提示
7. API封装

api.js：

使用axios封装
提供 sendMessage(message) 方法
8. 输出要求（非常重要）

请按顺序输出完整代码：

package.json
vite.config.js
main.js
App.vue
api.js
所有组件代码
CSS样式

必须是完整可运行项目，不要省略代码。

9. 风格要求
   代码简洁
   不使用复杂框架
   样式清爽（类似ChatGPT）
   不要伪代码

现在请开始生成完整前端代码。
