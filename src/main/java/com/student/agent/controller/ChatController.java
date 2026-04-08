package com.student.agent.controller;

import com.student.agent.service.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request,
                             @RequestHeader(value = "X-Memory-Id", defaultValue = "default-user") String memoryId) {
        return new ChatResponse(chatService.chat(memoryId, request.getMessage()));
    }

    public static class ChatRequest {
        @NotBlank(message = "message 不能为空")
        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public static class ChatResponse {
        private final String reply;

        public ChatResponse(String reply) {
            this.reply = reply;
        }

        public String getReply() {
            return reply;
        }
    }
}
