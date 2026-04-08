DELETE FROM schedule;
DELETE FROM reminder;
DELETE FROM course_material;

INSERT INTO schedule (course_name, teacher, classroom, day_of_week, section_index, start_time, end_time, week_range) VALUES
('高等数学', '李老师', '教学楼A101', 1, 1, '08:00:00', '09:35:00', '1-16'),
('Java程序设计', '王老师', '实验楼B201', 1, 3, '10:00:00', '11:35:00', '1-16'),
('数据库原理', '陈老师', '教学楼C305', 2, 1, '08:00:00', '09:35:00', '1-16'),
('操作系统', '赵老师', '教学楼A203', 2, 3, '10:00:00', '11:35:00', '1-16'),
('人工智能导论', '周老师', '教学楼D501', 3, 1, '08:00:00', '09:35:00', '1-16'),
('英语写作', '林老师', '教学楼E202', 4, 2, '08:55:00', '10:30:00', '1-16'),
('软件工程', '吴老师', '实验楼B301', 5, 1, '08:00:00', '09:35:00', '1-16');

INSERT INTO reminder (content, remind_time, status) VALUES
('今晚20点提交数据库作业', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 8 HOUR), 'PENDING'),
('明天上课前带校园卡', DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY), 'PENDING');

INSERT INTO course_material (course_name, title, content, source) VALUES
('数据库原理', '数据库期末试卷汇总', '2022年数据库期末试卷重点覆盖ER模型设计、关系代数、SQL优化、事务隔离级别。2023年试卷增加了索引设计、B+树、范式分解和并发控制案例分析。', '数据库原理资料包'),
('数据库原理', '数据库复习提纲', '数据库系统概论重点章节包括数据模型、关系规范化、SQL语言、事务管理、恢复技术与数据库安全。复习时要关注典型查询语句和索引命中分析。', '教师复习提纲'),
('Java程序设计', 'Spring Boot实训指导', '实训要求完成REST接口开发、分层架构设计、异常处理、日志管理以及单元测试。建议重点掌握Controller、Service、Repository的职责划分。', 'Java课程实验文档'),
('操作系统', '操作系统实验报告模板', '实验报告包括进程调度算法对比、页面置换模拟、死锁场景分析和结果截图。建议使用表格总结FIFO与LRU的缺页率。', '实验模板');
