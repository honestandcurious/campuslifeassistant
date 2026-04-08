package com.student.agent.service;

import com.student.agent.agent.StudentAssistantAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final StudentAssistantAgent studentAssistantAgent;

    public ChatService(StudentAssistantAgent studentAssistantAgent) {
        this.studentAssistantAgent = studentAssistantAgent;
    }

    public String chat(String memoryId, String message) {
        log.info("Chat request memoryId={}, message={}", memoryId, message);
        String reply = studentAssistantAgent.chat(memoryId, message);
        log.info("Chat response memoryId={}, reply={}", memoryId, reply);
        return reply;
    }
}
