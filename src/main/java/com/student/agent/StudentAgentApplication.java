package com.student.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.student.agent.repository")
public class StudentAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(StudentAgentApplication.class, args);
    }
}
