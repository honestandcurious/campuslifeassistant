package com.student.agent.config;

import com.student.agent.agent.StudentAssistantAgent;
import com.student.agent.agent.StudentAssistantFallbackAgent;
import com.student.agent.tools.StudentAssistantTools;
import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.util.List;

@Configuration
public class LangChainConfig {

    @Autowired
    private ChatMemoryStore redisChatMemoryStore;

//    @Bean
//    public ChatMemoryProvider chatMemoryProvider() {
//        return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
//    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public ChatModel chatModel(@Value("${app.ai.api-key}") String apiKey,
                               @Value("${app.ai.chat-model}") String modelName,
                               @Value("${app.ai.base-url:}") String baseUrl) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(true)
                .logResponses(true);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    //构建ChatMemoryProvider对象
    @Bean
    public ChatMemoryProvider  chatMemoryProvider() {
        ChatMemoryProvider chatMemoryProvider = new ChatMemoryProvider() {
            @Override
            public ChatMemory get(Object memoryId) {
                return MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build();
            }
        };
        return chatMemoryProvider;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public dev.langchain4j.model.chat.StreamingChatModel streamingChatModel(@Value("${app.ai.api-key}") String apiKey,
                                                                            @Value("${app.ai.chat-model}") String modelName,
                                                                            @Value("${app.ai.base-url:}") String baseUrl) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(true)
                .logResponses(true);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public EmbeddingModel embeddingModel(@Value("${app.ai.embedding-model.api-key}") String apiKey,
                                         @Value("${app.ai.embedding-model.model}") String embeddingModelName,
                                         @Value("${app.ai.embedding-model.base-url:}") String baseUrl) {
        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingModelName)
                .logRequests(true)
                .logResponses(true);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public DocumentSplitter documentSplitter() {
        return DocumentSplitters.recursive(500, 100);
    }

   //@Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public EmbeddingStoreIngestor embeddingStoreIngestor(EmbeddingStore<TextSegment> redisEmbeddingStore,
                                                         DocumentSplitter documentSplitter,
                                                         EmbeddingModel embeddingModel) {
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("content",new ApachePdfBoxDocumentParser());
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .embeddingStore(redisEmbeddingStore)
                .documentSplitter(documentSplitter)
                .embeddingModel(embeddingModel)
                .build();
        ingestor.ingest(documents);
        return ingestor;
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> redisEmbeddingStore,
                                             EmbeddingModel embeddingModel,
                                             @Value("${app.rag.min-score}") double minScore,
                                             @Value("${app.rag.max-results}") int maxResults) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(redisEmbeddingStore)
                .minScore(minScore)
                .maxResults(maxResults)
                .embeddingModel(embeddingModel)
                .build();
    }

    @Bean
    @Primary
    public StudentAssistantAgent studentAssistantAgent(ObjectProvider<ChatModel> chatModelProvider,
                                                       StudentAssistantTools tools,
                                                       ChatMemoryProvider chatMemoryProvider,
                                                       ObjectProvider<ContentRetriever> contentRetrieverProvider,
                                                       ObjectProvider<ToolProvider> toolProviderProvider,
                                                       StudentAssistantFallbackAgent fallbackAgent) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallbackAgent;
        }

        var builder = AiServices.builder(StudentAssistantAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(tools)
                .contentRetriever(contentRetrieverProvider.getIfAvailable());

        ToolProvider toolProvider = toolProviderProvider.getIfAvailable();
        if (toolProvider != null) {
            builder.toolProvider(toolProvider);
        }

        return builder.build();
    }
}
