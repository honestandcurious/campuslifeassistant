package com.student.agent.config;

import com.student.agent.repository.CourseMaterialMapper;
import com.student.agent.repository.ReminderMapper;
import com.student.agent.repository.ScheduleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class StartupLogger implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final ScheduleMapper scheduleMapper;
    private final ReminderMapper reminderMapper;
    private final CourseMaterialMapper courseMaterialMapper;

    public StartupLogger(ScheduleMapper scheduleMapper,
                         ReminderMapper reminderMapper,
                         CourseMaterialMapper courseMaterialMapper) {
        this.scheduleMapper = scheduleMapper;
        this.reminderMapper = reminderMapper;
        this.courseMaterialMapper = courseMaterialMapper;
    }

    @Override
    public void run(String... args) {
        log.info("Schedule rows: {}", scheduleMapper.selectCount(null));
        log.info("Reminder rows: {}", reminderMapper.selectCount(null));
        log.info("Course material rows: {}", courseMaterialMapper.selectCount(null));
    }
}
