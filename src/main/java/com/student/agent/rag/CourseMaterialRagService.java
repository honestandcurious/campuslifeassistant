package com.student.agent.rag;

import com.student.agent.entity.CourseMaterial;
import com.student.agent.repository.CourseMaterialMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class CourseMaterialRagService {

    private static final Logger log = LoggerFactory.getLogger(CourseMaterialRagService.class);

    private final CourseMaterialMapper courseMaterialMapper;
    private final ObjectProvider<EmbeddingStoreIngestor> embeddingStoreIngestorProvider;
    private volatile List<CourseMaterial> cachedMaterials = new ArrayList<>();

    public CourseMaterialRagService(CourseMaterialMapper courseMaterialMapper,
                                    ObjectProvider<EmbeddingStoreIngestor> embeddingStoreIngestorProvider) {
        this.courseMaterialMapper = courseMaterialMapper;
        this.embeddingStoreIngestorProvider = embeddingStoreIngestorProvider;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    public synchronized void reload() {
        cachedMaterials = courseMaterialMapper.selectList(null);
        EmbeddingStoreIngestor ingestor = embeddingStoreIngestorProvider.getIfAvailable();
        if (ingestor != null && !cachedMaterials.isEmpty()) {
            List<Document> documents = cachedMaterials.stream()
                    .map(material -> Document.from(material.getTitle() + "\n" + material.getContent()))
                    .toList();
            ingestor.ingest(documents);
            log.info("RAG ingested {} course materials", documents.size());
        } else {
            log.info("RAG initialized in fallback mode, materials={}", cachedMaterials.size());
        }
    }

    public String search(String question) {
        List<ScoredMaterial> topResults = cachedMaterials.stream()
                .map(material -> new ScoredMaterial(material, cosineLikeScore(question, material)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredMaterial::score).reversed())
                .limit(3)
                .toList();

        if (topResults.isEmpty()) {
            return "没有检索到相关课程资料，请换个关键词再试。";
        }

        StringBuilder builder = new StringBuilder("为你检索到以下课程资料：");
        for (ScoredMaterial result : topResults) {
            builder.append("\n- 课程：").append(result.material().getCourseName())
                    .append("；标题：").append(result.material().getTitle())
                    .append("；摘要：").append(summarize(result.material().getContent()));
        }
        return builder.toString();
    }

    private double cosineLikeScore(String question, CourseMaterial material) {
        Set<String> queryTokens = tokenize(question);
        Set<String> documentTokens = tokenize(material.getTitle() + " " + material.getContent());
        if (queryTokens.isEmpty() || documentTokens.isEmpty()) {
            return 0;
        }
        long intersection = queryTokens.stream().filter(documentTokens::contains).count();
        return intersection / (Math.sqrt(queryTokens.size()) * Math.sqrt(documentTokens.size()));
    }

    private Set<String> tokenize(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replace("？", " ")
                .replace("，", " ")
                .replace("。", " ")
                .replace("、", " ");
        String[] parts = normalized.split("\\s+");
        Set<String> tokens = new HashSet<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        tokens.addAll(splitChineseBiGram(normalized));
        return tokens;
    }

    private List<String> splitChineseBiGram(String text) {
        List<String> grams = new ArrayList<>();
        String compact = text.replace(" ", "");
        for (int i = 0; i < compact.length() - 1; i++) {
            grams.add(compact.substring(i, i + 2));
        }
        return grams;
    }

    private String summarize(String content) {
        return content.length() <= 70 ? content : content.substring(0, 70) + "...";
    }

    private record ScoredMaterial(CourseMaterial material, double score) {
    }
}
