package com.student.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.student.agent.entity.Schedule;
import com.student.agent.repository.ScheduleMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ScheduleService {

    private static final Map<String, Integer> SECTION_MAPPING = Map.of(
            "第一节", 1,
            "第二节", 2,
            "第三节", 3,
            "第四节", 4
    );

    private final ScheduleMapper scheduleMapper;

    public ScheduleService(ScheduleMapper scheduleMapper) {
        this.scheduleMapper = scheduleMapper;
    }

    public String getScheduleByNaturalLanguage(String query) {
        int targetDayOfWeek = resolveTargetDay(query);
        Integer targetSection = resolveSection(query);
        List<Schedule> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<Schedule>()
                .eq(Schedule::getDayOfWeek, targetDayOfWeek)
                .orderByAsc(Schedule::getSectionIndex));

        if (schedules.isEmpty()) {
            return "当天没有课程安排。";
        }

        if (targetSection != null) {
            return schedules.stream()
                    .filter(item -> item.getSectionIndex().equals(targetSection))
                    .findFirst()
                    .map(this::formatSingleSchedule)
                    .orElse("没有查询到对应节次的课程。");
        }

        StringBuilder builder = new StringBuilder(describeDayLabel(targetDayOfWeek)).append("课表如下：");
        for (Schedule schedule : schedules) {
            builder.append("\n- ").append(formatSingleSchedule(schedule));
        }
        return builder.toString();
    }

    private String formatSingleSchedule(Schedule schedule) {
        return String.format("%s，第%s节，%s-%s，地点：%s，教师：%s",
                schedule.getCourseName(),
                schedule.getSectionIndex(),
                schedule.getStartTime(),
                schedule.getEndTime(),
                schedule.getClassroom(),
                schedule.getTeacher());
    }

    private int resolveTargetDay(String query) {
        LocalDate today = LocalDate.now();
        if (query.contains("明天")) {
            return today.plusDays(1).getDayOfWeek().getValue();
        }
        if (query.contains("后天")) {
            return today.plusDays(2).getDayOfWeek().getValue();
        }
        return today.getDayOfWeek().getValue();
    }

    private Integer resolveSection(String query) {
        return SECTION_MAPPING.entrySet().stream()
                .filter(entry -> query.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private String describeDayLabel(int dayOfWeek) {
        return switch (DayOfWeek.of(dayOfWeek)) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }
}
