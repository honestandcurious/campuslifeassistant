package com.student.agent.agent;

import com.student.agent.tools.StudentAssistantTools;
import org.springframework.stereotype.Component;

@Component
public class StudentAssistantFallbackAgent implements StudentAssistantAgent {

    private final StudentAssistantTools tools;

    public StudentAssistantFallbackAgent(StudentAssistantTools tools) {
        this.tools = tools;
    }

    @Override
    public String chat(String memoryId, String message) {
        if (message.contains("带伞") || message.contains("天气")) {
            String weather = tools.getWeather(message);
            String schedule = tools.getSchedule("明天课表");
            return weather + "\n" + schedule + "\n建议：如果天气描述包含降雨或阵雨，出门带伞并提前10分钟出发。";
        }
        if (message.contains("路线") || message.contains("怎么去") || message.contains("导航")) {
            return tools.queryroute(message);
        }
        if ((message.contains("提醒我") || message.contains("记得")) && !message.contains("有哪些")) {
            return tools.addReminder(message);
        }
        if (message.contains("提醒") && (message.contains("哪些") || message.contains("什么"))) {
            return tools.listReminders("all");
        }
        if (message.contains("试卷") || message.contains("资料") || message.contains("复习") || message.contains("课件")) {
            return tools.queryMaterial(message);
        }
        if (message.contains("课") || message.contains("课表") || message.contains("第一节")) {
            return tools.getSchedule(message);
        }
        return """
                我可以处理以下校园场景：
                1. 课表查询，例如：我明天第一节课是什么？
                2. 课程资料检索，例如：数据库期末试卷有哪些？
                3. 提醒管理，例如：提醒我明天带实验报告
                4. 天气与出行建议，例如：明天上课需要带伞吗？
                5. 路线规划，例如：从图书馆到实验楼怎么走？
                """;
    }
}
