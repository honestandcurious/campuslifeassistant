package com.student.agent.service;

import com.student.agent.agent.StudentAssistantAgent;
import com.student.agent.agent.StudentAssistantStreamingAgent;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int CHUNK_SIZE = 24;

    private final StudentAssistantAgent studentAssistantAgent;
    private final ObjectProvider<StudentAssistantStreamingAgent> streamingAgentProvider;

    public ChatService(StudentAssistantAgent studentAssistantAgent,
                       ObjectProvider<StudentAssistantStreamingAgent> streamingAgentProvider) {
        this.studentAssistantAgent = studentAssistantAgent;
        this.streamingAgentProvider = streamingAgentProvider;
    }

    public Flux<String> chat(String memoryId, String message) {
        StudentAssistantStreamingAgent streamingAgent = streamingAgentProvider.getIfAvailable();
        if (streamingAgent != null) {
            log.info("Streaming chat request memoryId={}, message={}", memoryId, message);
            return streamingAgent.chat(memoryId, message)
                    .doOnNext(chunk -> log.debug("Streaming chunk memoryId={}, chunk={}", memoryId, chunk))
                    .doOnComplete(() -> log.info("Streaming chat completed memoryId={}", memoryId))
                    .doOnError(error -> log.error("Streaming chat failed memoryId={}", memoryId, error));
        }

        return Flux.defer(() -> {
                    log.info("Fallback chat request memoryId={}, message={}", memoryId, message);
                    String reply = studentAssistantAgent.chat(memoryId, message);
                    log.info("Fallback chat response memoryId={}, reply={}", memoryId, reply);
                    return Flux.fromIterable(splitReply(reply));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<String> splitReply(String reply) {
        List<String> chunks = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            chunks.add("未收到有效回复。");
            return chunks;
        }

        for (int start = 0; start < reply.length(); start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE, reply.length());
            chunks.add(reply.substring(start, end));
        }
        return chunks;
    }
}
