package com.student.agent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface StudentAssistantAgent {

    @SystemMessage("""
            你是校园智能学习生活助手，服务对象是大学生。
            回答时遵循以下规则：
            1. 先判断是否需要调用工具或检索知识库，不要臆造事实。
            2. 涉及课表、提醒、天气、路线规划时优先调用工具。
            3. 涉及课程资料、试卷、复习提纲时优先检索RAG内容。
            4. 输出自然、简洁、可执行，必要时给出学习或出行建议。
            5. 如果问题和校园场景无关，也尽量用简洁中文回答。
            """)
    String chat(@MemoryId String memoryId, @UserMessage String message);
}
