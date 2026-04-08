package com.student.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Data;

@Data
@TableName("schedule")
public class Schedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String courseName;

    private String teacher;

    private String classroom;

    private Integer dayOfWeek;

    private Integer sectionIndex;

    private LocalTime startTime;

    private LocalTime endTime;

    private String weekRange;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
