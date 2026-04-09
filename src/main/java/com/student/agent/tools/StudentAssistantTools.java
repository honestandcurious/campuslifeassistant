package com.student.agent.tools;

import com.student.agent.service.MaterialService;
import com.student.agent.service.ReminderService;
import com.student.agent.service.RouteService;
import com.student.agent.service.ScheduleService;
import com.student.agent.service.WeatherService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class StudentAssistantTools {

    private final ScheduleService scheduleService;
    private final MaterialService materialService;
    private final ReminderService reminderService;
    private final WeatherService weatherService;
    private final RouteService routeService;

    public StudentAssistantTools(ScheduleService scheduleService,
                                 MaterialService materialService,
                                 ReminderService reminderService,
                                 WeatherService weatherService,
                                 RouteService routeService) {
        this.scheduleService = scheduleService;
        this.materialService = materialService;
        this.reminderService = reminderService;
        this.weatherService = weatherService;
        this.routeService = routeService;
    }

    @Tool("根据自然语言查询学生课表")
    public String getSchedule(String query) {
        return scheduleService.getScheduleByNaturalLanguage(query);
    }

    @Tool("查询课程资料、期末试卷、复习提纲等 RAG 内容")
    public String queryMaterial(String query) {
        return materialService.queryMaterials(query);
    }

    @Tool("添加提醒事项")
    public String addReminder(String content) {
        return reminderService.addReminderFromText(content);
    }

    @Tool("查询当前提醒列表")
    public String listReminders(String ignored) {
        return reminderService.listReminders();
    }

    @Tool("查询校园天气信息")
    public String getWeather(String query) {
        return weatherService.getWeather(query);
    }

    @Tool("查询路线规划结果")
    public String queryroute(String query) {
        return routeService.queryRoute(query);
    }
}
