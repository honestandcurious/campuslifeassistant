package com.student.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.student.agent.entity.Reminder;
import com.student.agent.repository.ReminderMapper;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReminderService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReminderMapper reminderMapper;

    public ReminderService(ReminderMapper reminderMapper) {
        this.reminderMapper = reminderMapper;
    }

    public String addReminderFromText(String text) {
        Reminder reminder = new Reminder();
        reminder.setContent(cleanReminderContent(text));
        reminder.setStatus("PENDING");
        reminder.setRemindTime(resolveReminderTime(text));
        reminderMapper.insert(reminder);
        return "提醒已添加：" + reminder.getContent() + "，提醒时间：" +
                (reminder.getRemindTime() == null ? "未指定" : reminder.getRemindTime().format(FORMATTER));
    }

    public String listReminders() {
        List<Reminder> reminders = reminderMapper.selectList(new LambdaQueryWrapper<Reminder>()
                .orderByAsc(Reminder::getStatus)
                .orderByAsc(Reminder::getRemindTime));
        if (reminders.isEmpty()) {
            return "当前没有提醒。";
        }
        StringBuilder builder = new StringBuilder("当前提醒如下：");
        for (Reminder reminder : reminders) {
            builder.append("\n- [").append(reminder.getStatus()).append("] ")
                    .append(reminder.getContent());
            if (reminder.getRemindTime() != null) {
                builder.append(" @ ").append(reminder.getRemindTime().format(FORMATTER));
            }
        }
        return builder.toString();
    }

    private String cleanReminderContent(String text) {
        return text.replace("提醒我", "")
                .replace("请提醒我", "")
                .replace("记得", "")
                .trim();
    }

    private LocalDateTime resolveReminderTime(String text) {
        LocalDateTime now = LocalDateTime.now();
        if (text.contains("明天")) {
            return now.plusDays(1).with(LocalTime.of(8, 0));
        }
        if (text.contains("今晚")) {
            return now.with(LocalTime.of(20, 0));
        }
        if (text.contains("下午")) {
            return now.with(LocalTime.of(15, 0));
        }
        return null;
    }
}
