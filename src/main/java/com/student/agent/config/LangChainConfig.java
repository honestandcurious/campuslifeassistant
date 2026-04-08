package com.student.agent.config;

import com.student.agent.agent.StudentAssistantAgent;
import com.student.agent.agent.StudentAssistantFallbackAgent;
import com.student.agent.tools.StudentAssistantTools;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class LangChainConfig {

    @Autowired
    private RedisEmbeddingStore redisEmbeddingStore;

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return memoryId -> MessageWindowChatMemory.withMaxMessages(20);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public ChatModel chatModel(@Value("${app.ai.api-key}") String apiKey,
                               @Value("${app.ai.chat-model}") String modelName) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public EmbeddingModel embeddingModel(@Value("${app.ai.api-key}") String apiKey,
                                         @Value("${app.ai.embedding-model}") String embeddingModelName) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(embeddingModelName)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

//    @Bean
//    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
//    public DocumentSplitter documentSplitter() {
//        return DocumentSplitters.recursive(500, 100);
//    }
    public EmbeddingStore store(EmbeddingModel embeddingModel){
        //1.加载文档进内存
//        List<Document> documents = ClassPathDocumentLoader.loadDocuments("content");
        List<Document> documents = ClassPathDocumentLoader.loadDocuments("content",new ApachePdfBoxDocumentParser());
        //2.构建向量数据库操作对象 内存版本向量数据库
        //InMemoryEmbeddingStore store = new InMemoryEmbeddingStore();
        //构建文档分割器对象
        DocumentSplitter ds = DocumentSplitters.recursive(500,100);
        //3.构建一个EmbeddingStoreIngestor对象,完成文本数据切割,向量化, 存储
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                //.embeddingModel(store)
                .embeddingStore(redisEmbeddingStore)
                .documentSplitter(ds)
                .embeddingModel(embeddingModel)
                .build();
        ingestor.ingest(documents);
        return redisEmbeddingStore;
    }
//    @Bean(name = "redisEmbeddingStore")
//    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
//    public EmbeddingStore<TextSegment> redisEmbeddingStore(
//            @Value("${app.rag.redis.enabled:true}") boolean redisEnabled,
//            @Value("${app.rag.redis.host}") String host,
//            @Value("${app.rag.redis.port}") int port,
//            @Value("${app.rag.redis.index-name}") String indexName,
//            @Value("${app.rag.redis.prefix}") String prefix,
//            @Value("${app.rag.redis.dimension}") int dimension) {
//        if (!redisEnabled) {
//            return new InMemoryEmbeddingStore<>();
//        }
//        return RedisEmbeddingStore.builder()
//                .host(host)
//                .port(port)
//                .indexName(indexName)
//                .prefix(prefix)
//                .dimension(dimension)
//                .build();
//    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public EmbeddingStoreIngestor embeddingStoreIngestor(
                                                         DocumentSplitter documentSplitter,
                                                         EmbeddingModel embeddingModel) {
        return EmbeddingStoreIngestor.builder()
                .embeddingStore(redisEmbeddingStore)
                .documentSplitter(documentSplitter)
                .embeddingModel(embeddingModel)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai", name = "enabled", havingValue = "true")
    public ContentRetriever contentRetriever(
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
                                                       StudentAssistantFallbackAgent fallbackAgent) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return fallbackAgent;
        }
        return AiServices.builder(StudentAssistantAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(tools)
                .contentRetriever(contentRetrieverProvider.getIfAvailable())
                .build();
    }
}
