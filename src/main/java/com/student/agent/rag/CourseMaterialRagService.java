package com.student.agent.rag;

import com.student.agent.config.RagProperties;
import com.student.agent.entity.CourseMaterial;
import com.student.agent.repository.CourseMaterialMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
public class CourseMaterialRagService {

    private static final Logger log = LoggerFactory.getLogger(CourseMaterialRagService.class);
    private static final double RRF_K = 60.0d;

    // API 频率限制保护：每个 Batch 后的休眠时间（毫秒）
    // 如果仍然报 403，请将此值调大（例如 2000 或 3000）
    private static final long API_SLEEP_MS = 1500;

    private final CourseMaterialMapper courseMaterialMapper;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider;
    private final QueryRewriteService queryRewriteService;
    private final RerankService rerankService;
    private final RagProperties ragProperties;

    private volatile List<CourseMaterial> cachedMaterials = new ArrayList<>();
    private volatile List<MaterialChunk> cachedChunks = new ArrayList<>();
    private volatile Map<String, Double> inverseDocumentFrequencies = Map.of();
    private volatile double averageDocumentLength = 1.0d;

    public CourseMaterialRagService(CourseMaterialMapper courseMaterialMapper,
                                    ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                    ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider,
                                    QueryRewriteService queryRewriteService,
                                    RerankService rerankService,
                                    RagProperties ragProperties) {
        this.courseMaterialMapper = courseMaterialMapper;
        this.embeddingModelProvider = embeddingModelProvider;
        this.embeddingStoreProvider = embeddingStoreProvider;
        this.queryRewriteService = queryRewriteService;
        this.rerankService = rerankService;
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    public synchronized void reload() {
        cachedMaterials = courseMaterialMapper.selectList(null);
        cachedChunks = buildChunks(cachedMaterials);

        // 1. 计算向量（内部已实现增量检查和延迟控制）
        computeEmbeddings(cachedChunks);

        // 2. 同步到向量库（不再 removeAll，仅添加新内容）
        syncToEmbeddingStore(cachedChunks);

        rebuildBm25Stats(cachedChunks);
        log.info("RAG initialized with {} materials and {} chunks", cachedMaterials.size(), cachedChunks.size());
    }

    private void computeEmbeddings(List<MaterialChunk> chunks) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        EmbeddingStore<TextSegment> embeddingStore = embeddingStoreProvider.getIfAvailable();

        if (embeddingModel == null || chunks.isEmpty()) {
            return;
        }

        log.info("Starting embedding computation for {} chunks...", chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            MaterialChunk chunk = chunks.get(i);

            // --- [优化] 检查向量库是否已存在该 ID 的向量 ---
            float[] existingVector = null;
            if (embeddingStore != null) {
                existingVector = findVectorInStore(embeddingStore, chunk.chunkId());
            }

            if (existingVector != null) {
                // 如果已存在，直接复用，不调用 API
                chunks.set(i, updateChunkWithVector(chunk, existingVector));
                continue;
            }

            // --- [优化] 增量调用 API ---
            try {
                log.debug("Embedding chunk {}/{} via API: {}", i + 1, chunks.size(), chunk.chunkId());
                List<TextSegment> segments = List.of(TextSegment.from(chunk.text()));
                List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

                if (!embeddings.isEmpty()) {
                    float[] vector = embeddings.get(0).vector();
                    normalizeVector(vector);
                    chunks.set(i, updateChunkWithVector(chunk, vector));
                }

                // --- [优化] 延迟控制，防止 403 ---
                Thread.sleep(API_SLEEP_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Embedding interrupted", e);
                break;
            } catch (Exception e) {
                log.error("Failed to embed chunk {}: {}", chunk.chunkId(), e.getMessage());
            }
        }
    }

    /**
     * 在向量库中查找已有的向量
     */
    private float[] findVectorInStore(EmbeddingStore<TextSegment> store, String chunkId) {
        try {
            // 使用元数据过滤器按 chunkId 精确查找
            Filter idFilter = new IsEqualTo("chunkId", chunkId);
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .filter(idFilter)
                    .maxResults(1)
                    .build();

            EmbeddingSearchResult<TextSegment> result = store.search(request);
            if (!result.matches().isEmpty()) {
                log.debug("Found existing embedding for chunkId: {}", chunkId);
                // 注意：RedisSearch 等存储通常在 match 对象中直接返回 embedding
                return result.matches().get(0).embedding().vector();
            }
        } catch (Exception e) {
            log.warn("Error checking existence of chunk {}: {}", chunkId, e.getMessage());
        }
        return null;
    }

    private MaterialChunk updateChunkWithVector(MaterialChunk chunk, float[] vector) {
        return new MaterialChunk(
                chunk.chunkId(),
                chunk.materialId(),
                chunk.courseName(),
                chunk.title(),
                chunk.source(),
                chunk.text(),
                chunk.metadata(),
                chunk.terms(),
                chunk.tokenCount(),
                vector
        );
    }

    private void syncToEmbeddingStore(List<MaterialChunk> chunks) {
        EmbeddingStore<TextSegment> embeddingStore = embeddingStoreProvider.getIfAvailable();
        if (embeddingStore == null || chunks.isEmpty()) {
            return;
        }

        try {
            // --- [优化] 移除 embeddingStore.removeAll()，实现持久化增量同步 ---
            // embeddingStore.removeAll();

            List<String> idsToUpdate = new ArrayList<>();
            List<Embedding> embeddingsToUpdate = new ArrayList<>();
            List<TextSegment> segmentsToUpdate = new ArrayList<>();

            for (MaterialChunk chunk : chunks) {
                if (chunk.embedding() == null) continue;

                // 我们通过 ID 覆盖更新。如果向量库支持 put(id, ...)，
                // 那么即便之前存在，也会被更新为最新的 text 内容。
                Metadata metadata = new Metadata()
                        .put("chunkId", chunk.chunkId())
                        .put("courseName", safe(chunk.courseName()))
                        .put("title", safe(chunk.title()))
                        .put("source", safe(chunk.source()));

                String sourceType = chunk.metadata().getOrDefault("sourceType", "");
                if (!sourceType.isBlank()) {
                    metadata.put("sourceType", sourceType);
                }

                idsToUpdate.add(chunk.chunkId());
                embeddingsToUpdate.add(Embedding.from(chunk.embedding()));
                segmentsToUpdate.add(TextSegment.from(chunk.text(), metadata));
            }

            if (!idsToUpdate.isEmpty()) {
                // addAll 在很多实现中（如 Redis, Chroma）具备 "Upsert" 语义，
                // 即 ID 存在则更新，不存在则添加。
                embeddingStore.addAll(idsToUpdate, embeddingsToUpdate, segmentsToUpdate);
                log.info("Synced {} chunks (new or updated) to embedding store", idsToUpdate.size());
            }
        } catch (Exception ex) {
            log.error("Failed to sync chunks to embedding store: {}", ex.getMessage());
        }
    }

    // ... 保持 search, buildChunks, splitBySemanticBoundaries 等其余代码不变 ...

    public String search(String question) {
        if (cachedChunks.isEmpty()) {
            return "No indexed course materials are available.";
        }

        RagQueryContext queryContext = queryRewriteService.rewrite(question, cachedMaterials);
        List<MaterialChunk> filteredChunks = applyMetadataFilter(queryContext, cachedChunks);
        if (filteredChunks.isEmpty()) {
            filteredChunks = cachedChunks;
        }

        Map<String, Double> bm25Scores = scoreWithBm25(queryContext, filteredChunks);
        Map<String, Double> vectorScores = scoreWithVector(queryContext, filteredChunks);
        List<ScoredChunk> fusedCandidates = fuseByRrf(filteredChunks, bm25Scores, vectorScores);
        List<MaterialChunk> rerankCandidates = fusedCandidates.stream()
                .limit(ragProperties.getFusionTopK())
                .map(ScoredChunk::chunk)
                .toList();
        Map<String, Double> rerankScores = rerankService.rerank(queryContext.normalizedQuery(), rerankCandidates);

        List<ScoredChunk> topResults = fusedCandidates.stream()
                .map(candidate -> candidate.withRerankScore(rerankScores.getOrDefault(candidate.chunk().chunkId(), 0.0d)))
                .map(candidate -> candidate.withFinalScore(resolveFinalScore(candidate)))
                .filter(candidate -> candidate.finalScore() >= ragProperties.getMinScore())
                .sorted(Comparator.comparingDouble(ScoredChunk::finalScore).reversed())
                .limit(ragProperties.getFinalTopK())
                .toList();

        if (topResults.isEmpty()) {
            return "No relevant course materials were found. Try a more specific query.";
        }

        StringBuilder builder = new StringBuilder("Retrieved course materials:");
        for (ScoredChunk result : topResults) {
            builder.append("\n- Course: ").append(result.chunk().courseName())
                    .append("; Title: ").append(result.chunk().title());
            if (result.chunk().source() != null && !result.chunk().source().isBlank()) {
                builder.append("; Source: ").append(result.chunk().source());
            }
            builder.append("; Summary: ").append(summarizeChunk(result.chunk().text()));
        }
        return builder.toString();
    }

    private List<MaterialChunk> buildChunks(Collection<CourseMaterial> materials) {
        List<MaterialChunk> chunks = new ArrayList<>();
        AtomicInteger chunkCounter = new AtomicInteger(1);
        for (CourseMaterial material : materials) {
            chunks.addAll(buildMaterialChunks(
                    chunkCounter,
                    material.getId(),
                    material.getCourseName(),
                    material.getTitle(),
                    material.getSource(),
                    "db",
                    material.getContent()
            ));
        }
        chunks.addAll(loadPdfChunks(chunkCounter));
        return chunks;
    }

    private List<MaterialChunk> loadPdfChunks(AtomicInteger chunkCounter) {
        List<MaterialChunk> chunks = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();
        try {
            Resource[] resources = resolver.getResources("classpath*:content/*.pdf");
            for (Resource resource : resources) {
                if (!resource.exists()) {
                    continue;
                }
                try (var inputStream = resource.getInputStream()) {
                    Document document = parser.parse(inputStream);
                    String filename = resource.getFilename() == null ? "unknown.pdf" : resource.getFilename();
                    String title = filename.replaceFirst("(?i)\\.pdf$", "");
                    chunks.addAll(buildMaterialChunks(
                            chunkCounter,
                            null,
                            title,
                            title,
                            filename,
                            "pdf",
                            document.text()
                    ));
                }
            }
        } catch (IOException ex) {
            log.warn("Failed to load PDF knowledge base from classpath content/: {}", ex.getMessage());
        }
        return chunks;
    }

    private List<MaterialChunk> buildMaterialChunks(AtomicInteger chunkCounter,
                                                    Long materialId,
                                                    String courseName,
                                                    String title,
                                                    String source,
                                                    String sourceType,
                                                    String content) {
        List<MaterialChunk> chunks = new ArrayList<>();
        List<String> semanticUnits = splitBySemanticBoundaries(content);
        List<String> chunkTexts = mergeWithOverlap(
                semanticUnits,
                ragProperties.getMaxCharsPerChunk(),
                ragProperties.getOverlapChars()
        );
        for (String chunkText : chunkTexts) {
            String text = buildChunkText(courseName, title, source, sourceType, chunkText);
            Set<String> terms = tokenizeForRetrieval(text);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("courseName", safe(courseName));
            metadata.put("title", safe(title));
            metadata.put("source", safe(source));
            metadata.put("sourceType", safe(sourceType));
            chunks.add(new MaterialChunk(
                    "chunk-" + chunkCounter.getAndIncrement(),
                    materialId,
                    courseName,
                    title,
                    source,
                    text,
                    metadata,
                    terms,
                    Math.max(1, terms.size()),
                    null
            ));
        }
        return chunks;
    }

    private void rebuildBm25Stats(List<MaterialChunk> chunks) {
        Map<String, Integer> documentFrequency = new HashMap<>();
        int totalLength = 0;
        for (MaterialChunk chunk : chunks) {
            totalLength += Math.max(1, chunk.tokenCount());
            for (String term : chunk.terms()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        int totalDocuments = Math.max(1, chunks.size());
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
            double value = Math.log(1 + (totalDocuments - entry.getValue() + 0.5d) / (entry.getValue() + 0.5d));
            idf.put(entry.getKey(), value);
        }
        inverseDocumentFrequencies = idf;
        averageDocumentLength = Math.max(1.0d, totalLength / (double) totalDocuments);
    }

    private List<String> splitBySemanticBoundaries(String content) {
        String normalized = safe(content)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        List<String> units = new ArrayList<>();
        for (String paragraph : normalized.split("\\n\\s*\\n+")) {
            String trimmed = paragraph.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.length() <= ragProperties.getMaxCharsPerChunk()) {
                units.add(trimmed);
            } else {
                units.addAll(splitLongParagraph(trimmed));
            }
        }
        return units;
    }

    private List<String> splitLongParagraph(String paragraph) {
        List<String> units = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[。！？；.!?;])");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (current.length() > 0 && current.length() + trimmed.length() > ragProperties.getMaxCharsPerChunk()) {
                units.add(current.toString().trim());
                current = new StringBuilder();
            }
            if (trimmed.length() > ragProperties.getMaxCharsPerChunk()) {
                units.addAll(forceSplit(trimmed, ragProperties.getMaxCharsPerChunk(), ragProperties.getOverlapChars()));
                continue;
            }
            current.append(trimmed).append(' ');
        }
        if (!current.isEmpty()) {
            units.add(current.toString().trim());
        }
        return units;
    }

    private List<String> mergeWithOverlap(List<String> units, int maxChars, int overlapChars) {
        List<String> merged = new ArrayList<>();
        if (units.isEmpty()) {
            return merged;
        }

        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() == 0) {
                current.append(unit);
                continue;
            }
            if (current.length() + 1 + unit.length() <= maxChars) {
                current.append('\n').append(unit);
                continue;
            }
            merged.add(current.toString().trim());
            String overlap = tail(current.toString(), overlapChars);
            current = new StringBuilder();
            if (!overlap.isBlank()) {
                current.append(overlap).append('\n');
            }
            current.append(unit);
        }
        if (!current.isEmpty()) {
            merged.add(current.toString().trim());
        }
        return merged;
    }

    private List<String> forceSplit(String text, int maxChars, int overlapChars) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            parts.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlapChars);
        }
        return parts;
    }

    private List<MaterialChunk> applyMetadataFilter(RagQueryContext context, List<MaterialChunk> chunks) {
        return chunks.stream()
                .filter(chunk -> matchesFilter(context.courseNameFilter(), chunk.courseName()))
                .filter(chunk -> matchesFilter(context.titleKeyword(), chunk.title()))
                .filter(chunk -> matchesFilter(context.sourceKeyword(), chunk.source()) || matchesFilter(context.sourceKeyword(), chunk.title()))
                .toList();
    }

    private boolean matchesFilter(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return safe(actual).toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private Map<String, Double> scoreWithBm25(RagQueryContext context, List<MaterialChunk> chunks) {
        Map<String, Double> scores = new HashMap<>();
        for (MaterialChunk chunk : chunks) {
            double bestScore = 0.0d;
            for (String variant : context.rewrittenQueries()) {
                Map<String, Integer> queryTerms = termFrequency(tokenizeForRetrieval(variant));
                bestScore = Math.max(bestScore, bm25Score(queryTerms, chunk));
            }
            if (bestScore > 0.0d) {
                scores.put(chunk.chunkId(), bestScore);
            }
        }
        return scores;
    }

    private double bm25Score(Map<String, Integer> queryTerms, MaterialChunk chunk) {
        Map<String, Integer> documentTerms = termFrequency(chunk.terms());
        double score = 0.0d;
        double k1 = 1.5d;
        double b = 0.75d;
        double documentLength = Math.max(1.0d, chunk.tokenCount());

        for (Map.Entry<String, Integer> entry : queryTerms.entrySet()) {
            int tf = documentTerms.getOrDefault(entry.getKey(), 0);
            if (tf <= 0) {
                continue;
            }
            double idf = inverseDocumentFrequencies.getOrDefault(entry.getKey(), 0.0d);
            double numerator = tf * (k1 + 1.0d);
            double denominator = tf + k1 * (1.0d - b + b * documentLength / averageDocumentLength);
            score += idf * numerator / denominator;
        }
        return score;
    }

    private Map<String, Double> scoreWithVector(RagQueryContext context, List<MaterialChunk> chunks) {
        EmbeddingStore<TextSegment> embeddingStore = embeddingStoreProvider.getIfAvailable();
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingStore != null && embeddingModel != null && !context.rewrittenQueries().isEmpty()) {
            Map<String, Double> redisScores = scoreWithRedisVectorSearch(context, chunks, embeddingStore, embeddingModel);
            if (!redisScores.isEmpty()) {
                return redisScores;
            }
        }

        if (embeddingModel == null || context.rewrittenQueries().isEmpty()) {
            return Map.of();
        }

        List<TextSegment> querySegments = context.rewrittenQueries().stream().map(TextSegment::from).toList();
        List<Embedding> queryEmbeddings = embeddingModel.embedAll(querySegments).content();
        for (Embedding embedding : queryEmbeddings) {
            normalizeVector(embedding.vector());
        }

        Map<String, Double> scores = new HashMap<>();
        for (MaterialChunk chunk : chunks) {
            if (chunk.embedding() == null) {
                continue;
            }
            double bestScore = 0.0d;
            for (Embedding queryEmbedding : queryEmbeddings) {
                bestScore = Math.max(bestScore, cosineSimilarity(queryEmbedding.vector(), chunk.embedding()));
            }
            if (bestScore > 0.0d) {
                scores.put(chunk.chunkId(), bestScore);
            }
        }
        return scores;
    }

    private Map<String, Double> scoreWithRedisVectorSearch(RagQueryContext context,
                                                           List<MaterialChunk> chunks,
                                                           EmbeddingStore<TextSegment> embeddingStore,
                                                           EmbeddingModel embeddingModel) {
        Set<String> allowedChunkIds = new HashSet<>();
        for (MaterialChunk chunk : chunks) {
            allowedChunkIds.add(chunk.chunkId());
        }

        Map<String, Double> scores = new HashMap<>();
        List<TextSegment> querySegments = context.rewrittenQueries().stream().map(TextSegment::from).toList();
        List<Embedding> queryEmbeddings = embeddingModel.embedAll(querySegments).content();
        for (Embedding queryEmbedding : queryEmbeddings) {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(ragProperties.getVectorTopK())
                    .minScore(0.0d)
                    .build();
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
            result.matches().forEach(match -> {
                TextSegment segment = match.embedded();
                if (segment == null || segment.metadata() == null) {
                    return;
                }
                String chunkId = segment.metadata().getString("chunkId");
                if (chunkId == null || !allowedChunkIds.contains(chunkId)) {
                    return;
                }
                scores.merge(chunkId, match.score(), Math::max);
            });
        }
        return scores;
    }

    private List<ScoredChunk> fuseByRrf(List<MaterialChunk> chunks,
                                        Map<String, Double> bm25Scores,
                                        Map<String, Double> vectorScores) {
        Map<String, Integer> bm25Ranks = rankDescending(bm25Scores, ragProperties.getBm25TopK());
        Map<String, Integer> vectorRanks = rankDescending(vectorScores, ragProperties.getVectorTopK());
        List<ScoredChunk> fused = new ArrayList<>();

        for (MaterialChunk chunk : chunks) {
            Integer bm25Rank = bm25Ranks.get(chunk.chunkId());
            Integer vectorRank = vectorRanks.get(chunk.chunkId());
            if (bm25Rank == null && vectorRank == null) {
                continue;
            }
            double fusedScore = 0.0d;
            if (bm25Rank != null) {
                fusedScore += 1.0d / (RRF_K + bm25Rank);
            }
            if (vectorRank != null) {
                fusedScore += 1.0d / (RRF_K + vectorRank);
            }
            fused.add(new ScoredChunk(
                    chunk,
                    fusedScore,
                    bm25Scores.getOrDefault(chunk.chunkId(), 0.0d),
                    vectorScores.getOrDefault(chunk.chunkId(), 0.0d),
                    0.0d,
                    0.0d
            ));
        }

        return fused.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed())
                .limit(ragProperties.getFusionTopK())
                .toList();
    }

    private Map<String, Integer> rankDescending(Map<String, Double> scores, int limit) {
        Map<String, Integer> ranked = new HashMap<>();
        AtomicInteger rank = new AtomicInteger(1);
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> ranked.put(entry.getKey(), rank.getAndIncrement()));
        return ranked;
    }

    private double resolveFinalScore(ScoredChunk candidate) {
        if (candidate.rerankScore() > 0.0d) {
            return 0.65d * candidate.rerankScore()
                    + 0.20d * candidate.vectorScore()
                    + 0.15d * normalizeFusedScore(candidate.fusedScore());
        }
        return 0.55d * normalizeFusedScore(candidate.fusedScore())
                + 0.30d * candidate.vectorScore()
                + 0.15d * candidate.bm25Score();
    }

    private double normalizeFusedScore(double score) {
        return Math.min(1.0d, score * 80.0d);
    }

    private Map<String, Integer> termFrequency(Collection<String> terms) {
        Map<String, Integer> frequency = new HashMap<>();
        for (String term : terms) {
            frequency.merge(term, 1, Integer::sum);
        }
        return frequency;
    }

    private Set<String> tokenizeForRetrieval(String text) {
        String normalized = safe(text).toLowerCase(Locale.ROOT)
                .replace('，', ' ')
                .replace('。', ' ')
                .replace('？', ' ')
                .replace('：', ' ')
                .replace('；', ' ')
                .replace('、', ' ')
                .replace('\n', ' ');
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

    private String summarizeChunk(String content) {
        return content.length() <= 110 ? content : content.substring(0, 110) + "...";
    }

    private String buildChunkText(String courseName, String title, String source, String sourceType, String chunkText) {
        return "Course: " + safe(courseName)
                + "\nTitle: " + safe(title)
                + "\nSource: " + safe(source)
                + "\nSourceType: " + safe(sourceType)
                + "\nContent: " + chunkText.trim();
    }

    private String tail(String text, int overlapChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int start = Math.max(0, text.length() - overlapChars);
        return text.substring(start).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void normalizeVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        double norm = 0.0d;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0.0d) {
            return;
        }
        double divisor = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / divisor);
        }
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) {
            return 0.0d;
        }
        double dot = 0.0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }

    private record ScoredChunk(
            MaterialChunk chunk,
            double fusedScore,
            double bm25Score,
            double vectorScore,
            double rerankScore,
            double finalScore
    ) {
        private ScoredChunk withRerankScore(double newRerankScore) {
            return new ScoredChunk(chunk, fusedScore, bm25Score, vectorScore, newRerankScore, finalScore);
        }

        private ScoredChunk withFinalScore(double newFinalScore) {
            return new ScoredChunk(chunk, fusedScore, bm25Score, vectorScore, rerankScore, newFinalScore);
        }
    }
}

//package com.student.agent.rag;
//
//import com.student.agent.config.RagProperties;
//import com.student.agent.entity.CourseMaterial;
//import com.student.agent.repository.CourseMaterialMapper;
//import dev.langchain4j.data.document.Document;
//import dev.langchain4j.data.document.Metadata;
//import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
//import dev.langchain4j.data.embedding.Embedding;
//import dev.langchain4j.data.segment.TextSegment;
//import dev.langchain4j.model.embedding.EmbeddingModel;
//import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
//import dev.langchain4j.store.embedding.EmbeddingSearchResult;
//import dev.langchain4j.store.embedding.EmbeddingStore;
//import jakarta.annotation.PostConstruct;
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.Collection;
//import java.util.Comparator;
//import java.util.HashMap;
//import java.util.HashSet;
//import java.util.LinkedHashMap;
//import java.util.List;
//import java.util.Locale;
//import java.util.Map;
//import java.util.Set;
//import java.util.concurrent.atomic.AtomicInteger;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.ObjectProvider;
//import org.springframework.core.io.Resource;
//import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
//import org.springframework.stereotype.Service;
//
//@Service
//public class CourseMaterialRagService {
//
//    private static final Logger log = LoggerFactory.getLogger(CourseMaterialRagService.class);
//    private static final double RRF_K = 60.0d;
//
//    private final CourseMaterialMapper courseMaterialMapper;
//    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
//    private final ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider;
//    private final QueryRewriteService queryRewriteService;
//    private final RerankService rerankService;
//    private final RagProperties ragProperties;
//
//    private volatile List<CourseMaterial> cachedMaterials = new ArrayList<>();
//    private volatile List<MaterialChunk> cachedChunks = new ArrayList<>();
//    private volatile Map<String, Double> inverseDocumentFrequencies = Map.of();
//    private volatile double averageDocumentLength = 1.0d;
//
//    public CourseMaterialRagService(CourseMaterialMapper courseMaterialMapper,
//                                    ObjectProvider<EmbeddingModel> embeddingModelProvider,
//                                    ObjectProvider<EmbeddingStore<TextSegment>> embeddingStoreProvider,
//                                    QueryRewriteService queryRewriteService,
//                                    RerankService rerankService,
//                                    RagProperties ragProperties) {
//        this.courseMaterialMapper = courseMaterialMapper;
//        this.embeddingModelProvider = embeddingModelProvider;
//        this.embeddingStoreProvider = embeddingStoreProvider;
//        this.queryRewriteService = queryRewriteService;
//        this.rerankService = rerankService;
//        this.ragProperties = ragProperties;
//    }
//
//    @PostConstruct
//    public void init() {
//        reload();
//    }
//
//    public synchronized void reload() {
//        cachedMaterials = courseMaterialMapper.selectList(null);
//        cachedChunks = buildChunks(cachedMaterials);
//        computeEmbeddings(cachedChunks);
//        syncToEmbeddingStore(cachedChunks);
//        rebuildBm25Stats(cachedChunks);
//        log.info("RAG initialized with {} materials and {} chunks", cachedMaterials.size(), cachedChunks.size());
//    }
//
//    public String search(String question) {
//        if (cachedChunks.isEmpty()) {
//            return "No indexed course materials are available.";
//        }
//
//        RagQueryContext queryContext = queryRewriteService.rewrite(question, cachedMaterials);
//        List<MaterialChunk> filteredChunks = applyMetadataFilter(queryContext, cachedChunks);
//        if (filteredChunks.isEmpty()) {
//            filteredChunks = cachedChunks;
//        }
//
//        Map<String, Double> bm25Scores = scoreWithBm25(queryContext, filteredChunks);
//        Map<String, Double> vectorScores = scoreWithVector(queryContext, filteredChunks);
//        List<ScoredChunk> fusedCandidates = fuseByRrf(filteredChunks, bm25Scores, vectorScores);
//        List<MaterialChunk> rerankCandidates = fusedCandidates.stream()
//                .limit(ragProperties.getFusionTopK())
//                .map(ScoredChunk::chunk)
//                .toList();
//        Map<String, Double> rerankScores = rerankService.rerank(queryContext.normalizedQuery(), rerankCandidates);
//
//        List<ScoredChunk> topResults = fusedCandidates.stream()
//                .map(candidate -> candidate.withRerankScore(rerankScores.getOrDefault(candidate.chunk().chunkId(), 0.0d)))
//                .map(candidate -> candidate.withFinalScore(resolveFinalScore(candidate)))
//                .filter(candidate -> candidate.finalScore() >= ragProperties.getMinScore())
//                .sorted(Comparator.comparingDouble(ScoredChunk::finalScore).reversed())
//                .limit(ragProperties.getFinalTopK())
//                .toList();
//
//        if (topResults.isEmpty()) {
//            return "No relevant course materials were found. Try a more specific query.";
//        }
//
//        StringBuilder builder = new StringBuilder("Retrieved course materials:");
//        for (ScoredChunk result : topResults) {
//            builder.append("\n- Course: ").append(result.chunk().courseName())
//                    .append("; Title: ").append(result.chunk().title());
//            if (result.chunk().source() != null && !result.chunk().source().isBlank()) {
//                builder.append("; Source: ").append(result.chunk().source());
//            }
//            builder.append("; Summary: ").append(summarizeChunk(result.chunk().text()));
//        }
//        return builder.toString();
//    }
//
//    private List<MaterialChunk> buildChunks(Collection<CourseMaterial> materials) {
//        List<MaterialChunk> chunks = new ArrayList<>();
//        AtomicInteger chunkCounter = new AtomicInteger(1);
//        for (CourseMaterial material : materials) {
//            chunks.addAll(buildMaterialChunks(
//                    chunkCounter,
//                    material.getId(),
//                    material.getCourseName(),
//                    material.getTitle(),
//                    material.getSource(),
//                    "db",
//                    material.getContent()
//            ));
//        }
//        chunks.addAll(loadPdfChunks(chunkCounter));
//        return chunks;
//    }
//
//    private List<MaterialChunk> loadPdfChunks(AtomicInteger chunkCounter) {
//        List<MaterialChunk> chunks = new ArrayList<>();
//        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
//        ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();
//        try {
//            Resource[] resources = resolver.getResources("classpath*:content/*.pdf");
//            for (Resource resource : resources) {
//                if (!resource.exists()) {
//                    continue;
//                }
//                try (var inputStream = resource.getInputStream()) {
//                    Document document = parser.parse(inputStream);
//                    String filename = resource.getFilename() == null ? "unknown.pdf" : resource.getFilename();
//                    String title = filename.replaceFirst("(?i)\\.pdf$", "");
//                    chunks.addAll(buildMaterialChunks(
//                            chunkCounter,
//                            null,
//                            title,
//                            title,
//                            filename,
//                            "pdf",
//                            document.text()
//                    ));
//                }
//            }
//        } catch (IOException ex) {
//            log.warn("Failed to load PDF knowledge base from classpath content/: {}", ex.getMessage());
//        }
//        return chunks;
//    }
//
//    private List<MaterialChunk> buildMaterialChunks(AtomicInteger chunkCounter,
//                                                    Long materialId,
//                                                    String courseName,
//                                                    String title,
//                                                    String source,
//                                                    String sourceType,
//                                                    String content) {
//        List<MaterialChunk> chunks = new ArrayList<>();
//        List<String> semanticUnits = splitBySemanticBoundaries(content);
//        List<String> chunkTexts = mergeWithOverlap(
//                semanticUnits,
//                ragProperties.getMaxCharsPerChunk(),
//                ragProperties.getOverlapChars()
//        );
//        for (String chunkText : chunkTexts) {
//            String text = buildChunkText(courseName, title, source, sourceType, chunkText);
//            Set<String> terms = tokenizeForRetrieval(text);
//            Map<String, String> metadata = new LinkedHashMap<>();
//            metadata.put("courseName", safe(courseName));
//            metadata.put("title", safe(title));
//            metadata.put("source", safe(source));
//            metadata.put("sourceType", safe(sourceType));
//            chunks.add(new MaterialChunk(
//                    "chunk-" + chunkCounter.getAndIncrement(),
//                    materialId,
//                    courseName,
//                    title,
//                    source,
//                    text,
//                    metadata,
//                    terms,
//                    Math.max(1, terms.size()),
//                    null
//            ));
//        }
//        return chunks;
//    }
//
//    private void computeEmbeddings(List<MaterialChunk> chunks) {
//        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
//        if (embeddingModel == null || chunks.isEmpty()) {
//            return;
//        }
//
//        int batchSize = Math.max(1, ragProperties.getEmbeddingBatchSize());
//        for (int index = 0; index < chunks.size(); index += batchSize) {
//            int end = Math.min(index + batchSize, chunks.size());
//            List<MaterialChunk> batch = chunks.subList(index, end);
//            List<TextSegment> segments = batch.stream().map(chunk -> TextSegment.from(chunk.text())).toList();
//            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
//            for (int i = 0; i < batch.size() && i < embeddings.size(); i++) {
//                float[] vector = embeddings.get(i).vector();
//                normalizeVector(vector);
//                MaterialChunk chunk = batch.get(i);
//                batch.set(i, new MaterialChunk(
//                        chunk.chunkId(),
//                        chunk.materialId(),
//                        chunk.courseName(),
//                        chunk.title(),
//                        chunk.source(),
//                        chunk.text(),
//                        chunk.metadata(),
//                        chunk.terms(),
//                        chunk.tokenCount(),
//                        vector
//                ));
//            }
//        }
//    }
//
//    private void syncToEmbeddingStore(List<MaterialChunk> chunks) {
//        EmbeddingStore<TextSegment> embeddingStore = embeddingStoreProvider.getIfAvailable();
//        if (embeddingStore == null || chunks.isEmpty()) {
//            return;
//        }
//
//        try {
//            embeddingStore.removeAll();
//            List<String> ids = new ArrayList<>(chunks.size());
//            List<Embedding> embeddings = new ArrayList<>(chunks.size());
//            List<TextSegment> segments = new ArrayList<>(chunks.size());
//
//            for (MaterialChunk chunk : chunks) {
//                if (chunk.embedding() == null) {
//                    continue;
//                }
//                Metadata metadata = new Metadata()
//                        .put("chunkId", chunk.chunkId())
//                        .put("courseName", safe(chunk.courseName()))
//                        .put("title", safe(chunk.title()))
//                        .put("source", safe(chunk.source()));
//                String sourceType = chunk.metadata().getOrDefault("sourceType", "");
//                if (!sourceType.isBlank()) {
//                    metadata.put("sourceType", sourceType);
//                }
//                ids.add(chunk.chunkId());
//                embeddings.add(Embedding.from(chunk.embedding()));
//                segments.add(TextSegment.from(chunk.text(), metadata));
//            }
//
//            if (!ids.isEmpty()) {
//                embeddingStore.addAll(ids, embeddings, segments);
//                log.info("Synced {} chunks into RedisSearch embedding store", ids.size());
//            }
//        } catch (Exception ex) {
//            log.warn("Failed to sync chunks to RedisSearch embedding store: {}", ex.getMessage());
//        }
//    }
//
//    private void rebuildBm25Stats(List<MaterialChunk> chunks) {
//        Map<String, Integer> documentFrequency = new HashMap<>();
//        int totalLength = 0;
//        for (MaterialChunk chunk : chunks) {
//            totalLength += Math.max(1, chunk.tokenCount());
//            for (String term : chunk.terms()) {
//                documentFrequency.merge(term, 1, Integer::sum);
//            }
//        }
//
//        int totalDocuments = Math.max(1, chunks.size());
//        Map<String, Double> idf = new HashMap<>();
//        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
//            double value = Math.log(1 + (totalDocuments - entry.getValue() + 0.5d) / (entry.getValue() + 0.5d));
//            idf.put(entry.getKey(), value);
//        }
//        inverseDocumentFrequencies = idf;
//        averageDocumentLength = Math.max(1.0d, totalLength / (double) totalDocuments);
//    }
//
//    private List<String> splitBySemanticBoundaries(String content) {
//        String normalized = safe(content)
//                .replace("\r\n", "\n")
//                .replace("\r", "\n")
//                .replaceAll("[\\t\\x0B\\f]+", " ")
//                .trim();
//        if (normalized.isBlank()) {
//            return List.of();
//        }
//
//        List<String> units = new ArrayList<>();
//        for (String paragraph : normalized.split("\\n\\s*\\n+")) {
//            String trimmed = paragraph.trim();
//            if (trimmed.isBlank()) {
//                continue;
//            }
//            if (trimmed.length() <= ragProperties.getMaxCharsPerChunk()) {
//                units.add(trimmed);
//            } else {
//                units.addAll(splitLongParagraph(trimmed));
//            }
//        }
//        return units;
//    }
//
//    private List<String> splitLongParagraph(String paragraph) {
//        List<String> units = new ArrayList<>();
//        String[] sentences = paragraph.split("(?<=[。！？；.!?;])");
//        StringBuilder current = new StringBuilder();
//        for (String sentence : sentences) {
//            String trimmed = sentence.trim();
//            if (trimmed.isBlank()) {
//                continue;
//            }
//            if (current.length() > 0 && current.length() + trimmed.length() > ragProperties.getMaxCharsPerChunk()) {
//                units.add(current.toString().trim());
//                current = new StringBuilder();
//            }
//            if (trimmed.length() > ragProperties.getMaxCharsPerChunk()) {
//                units.addAll(forceSplit(trimmed, ragProperties.getMaxCharsPerChunk(), ragProperties.getOverlapChars()));
//                continue;
//            }
//            current.append(trimmed).append(' ');
//        }
//        if (!current.isEmpty()) {
//            units.add(current.toString().trim());
//        }
//        return units;
//    }
//
//    private List<String> mergeWithOverlap(List<String> units, int maxChars, int overlapChars) {
//        List<String> merged = new ArrayList<>();
//        if (units.isEmpty()) {
//            return merged;
//        }
//
//        StringBuilder current = new StringBuilder();
//        for (String unit : units) {
//            if (current.length() == 0) {
//                current.append(unit);
//                continue;
//            }
//            if (current.length() + 1 + unit.length() <= maxChars) {
//                current.append('\n').append(unit);
//                continue;
//            }
//            merged.add(current.toString().trim());
//            String overlap = tail(current.toString(), overlapChars);
//            current = new StringBuilder();
//            if (!overlap.isBlank()) {
//                current.append(overlap).append('\n');
//            }
//            current.append(unit);
//        }
//        if (!current.isEmpty()) {
//            merged.add(current.toString().trim());
//        }
//        return merged;
//    }
//
//    private List<String> forceSplit(String text, int maxChars, int overlapChars) {
//        List<String> parts = new ArrayList<>();
//        int start = 0;
//        while (start < text.length()) {
//            int end = Math.min(start + maxChars, text.length());
//            parts.add(text.substring(start, end));
//            if (end >= text.length()) {
//                break;
//            }
//            start = Math.max(start + 1, end - overlapChars);
//        }
//        return parts;
//    }
//
//    private List<MaterialChunk> applyMetadataFilter(RagQueryContext context, List<MaterialChunk> chunks) {
//        return chunks.stream()
//                .filter(chunk -> matchesFilter(context.courseNameFilter(), chunk.courseName()))
//                .filter(chunk -> matchesFilter(context.titleKeyword(), chunk.title()))
//                .filter(chunk -> matchesFilter(context.sourceKeyword(), chunk.source()) || matchesFilter(context.sourceKeyword(), chunk.title()))
//                .toList();
//    }
//
//    private boolean matchesFilter(String expected, String actual) {
//        if (expected == null || expected.isBlank()) {
//            return true;
//        }
//        return safe(actual).toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
//    }
//
//    private Map<String, Double> scoreWithBm25(RagQueryContext context, List<MaterialChunk> chunks) {
//        Map<String, Double> scores = new HashMap<>();
//        for (MaterialChunk chunk : chunks) {
//            double bestScore = 0.0d;
//            for (String variant : context.rewrittenQueries()) {
//                Map<String, Integer> queryTerms = termFrequency(tokenizeForRetrieval(variant));
//                bestScore = Math.max(bestScore, bm25Score(queryTerms, chunk));
//            }
//            if (bestScore > 0.0d) {
//                scores.put(chunk.chunkId(), bestScore);
//            }
//        }
//        return scores;
//    }
//
//    private double bm25Score(Map<String, Integer> queryTerms, MaterialChunk chunk) {
//        Map<String, Integer> documentTerms = termFrequency(chunk.terms());
//        double score = 0.0d;
//        double k1 = 1.5d;
//        double b = 0.75d;
//        double documentLength = Math.max(1.0d, chunk.tokenCount());
//
//        for (Map.Entry<String, Integer> entry : queryTerms.entrySet()) {
//            int tf = documentTerms.getOrDefault(entry.getKey(), 0);
//            if (tf <= 0) {
//                continue;
//            }
//            double idf = inverseDocumentFrequencies.getOrDefault(entry.getKey(), 0.0d);
//            double numerator = tf * (k1 + 1.0d);
//            double denominator = tf + k1 * (1.0d - b + b * documentLength / averageDocumentLength);
//            score += idf * numerator / denominator;
//        }
//        return score;
//    }
//
//    private Map<String, Double> scoreWithVector(RagQueryContext context, List<MaterialChunk> chunks) {
//        EmbeddingStore<TextSegment> embeddingStore = embeddingStoreProvider.getIfAvailable();
//        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
//        if (embeddingStore != null && embeddingModel != null && !context.rewrittenQueries().isEmpty()) {
//            Map<String, Double> redisScores = scoreWithRedisVectorSearch(context, chunks, embeddingStore, embeddingModel);
//            if (!redisScores.isEmpty()) {
//                return redisScores;
//            }
//        }
//
//        if (embeddingModel == null || context.rewrittenQueries().isEmpty()) {
//            return Map.of();
//        }
//
//        List<TextSegment> querySegments = context.rewrittenQueries().stream().map(TextSegment::from).toList();
//        List<Embedding> queryEmbeddings = embeddingModel.embedAll(querySegments).content();
//        for (Embedding embedding : queryEmbeddings) {
//            normalizeVector(embedding.vector());
//        }
//
//        Map<String, Double> scores = new HashMap<>();
//        for (MaterialChunk chunk : chunks) {
//            if (chunk.embedding() == null) {
//                continue;
//            }
//            double bestScore = 0.0d;
//            for (Embedding queryEmbedding : queryEmbeddings) {
//                bestScore = Math.max(bestScore, cosineSimilarity(queryEmbedding.vector(), chunk.embedding()));
//            }
//            if (bestScore > 0.0d) {
//                scores.put(chunk.chunkId(), bestScore);
//            }
//        }
//        return scores;
//    }
//
//    private Map<String, Double> scoreWithRedisVectorSearch(RagQueryContext context,
//                                                           List<MaterialChunk> chunks,
//                                                           EmbeddingStore<TextSegment> embeddingStore,
//                                                           EmbeddingModel embeddingModel) {
//        Set<String> allowedChunkIds = new HashSet<>();
//        for (MaterialChunk chunk : chunks) {
//            allowedChunkIds.add(chunk.chunkId());
//        }
//
//        Map<String, Double> scores = new HashMap<>();
//        List<TextSegment> querySegments = context.rewrittenQueries().stream().map(TextSegment::from).toList();
//        List<Embedding> queryEmbeddings = embeddingModel.embedAll(querySegments).content();
//        for (Embedding queryEmbedding : queryEmbeddings) {
//            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
//                    .queryEmbedding(queryEmbedding)
//                    .maxResults(ragProperties.getVectorTopK())
//                    .minScore(0.0d)
//                    .build();
//            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
//            result.matches().forEach(match -> {
//                TextSegment segment = match.embedded();
//                if (segment == null || segment.metadata() == null) {
//                    return;
//                }
//                String chunkId = segment.metadata().getString("chunkId");
//                if (chunkId == null || !allowedChunkIds.contains(chunkId)) {
//                    return;
//                }
//                scores.merge(chunkId, match.score(), Math::max);
//            });
//        }
//        return scores;
//    }
//
//    private List<ScoredChunk> fuseByRrf(List<MaterialChunk> chunks,
//                                        Map<String, Double> bm25Scores,
//                                        Map<String, Double> vectorScores) {
//        Map<String, Integer> bm25Ranks = rankDescending(bm25Scores, ragProperties.getBm25TopK());
//        Map<String, Integer> vectorRanks = rankDescending(vectorScores, ragProperties.getVectorTopK());
//        List<ScoredChunk> fused = new ArrayList<>();
//
//        for (MaterialChunk chunk : chunks) {
//            Integer bm25Rank = bm25Ranks.get(chunk.chunkId());
//            Integer vectorRank = vectorRanks.get(chunk.chunkId());
//            if (bm25Rank == null && vectorRank == null) {
//                continue;
//            }
//            double fusedScore = 0.0d;
//            if (bm25Rank != null) {
//                fusedScore += 1.0d / (RRF_K + bm25Rank);
//            }
//            if (vectorRank != null) {
//                fusedScore += 1.0d / (RRF_K + vectorRank);
//            }
//            fused.add(new ScoredChunk(
//                    chunk,
//                    fusedScore,
//                    bm25Scores.getOrDefault(chunk.chunkId(), 0.0d),
//                    vectorScores.getOrDefault(chunk.chunkId(), 0.0d),
//                    0.0d,
//                    0.0d
//            ));
//        }
//
//        return fused.stream()
//                .sorted(Comparator.comparingDouble(ScoredChunk::fusedScore).reversed())
//                .limit(ragProperties.getFusionTopK())
//                .toList();
//    }
//
//    private Map<String, Integer> rankDescending(Map<String, Double> scores, int limit) {
//        Map<String, Integer> ranked = new HashMap<>();
//        AtomicInteger rank = new AtomicInteger(1);
//        scores.entrySet().stream()
//                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
//                .limit(limit)
//                .forEach(entry -> ranked.put(entry.getKey(), rank.getAndIncrement()));
//        return ranked;
//    }
//
//    private double resolveFinalScore(ScoredChunk candidate) {
//        if (candidate.rerankScore() > 0.0d) {
//            return 0.65d * candidate.rerankScore()
//                    + 0.20d * candidate.vectorScore()
//                    + 0.15d * normalizeFusedScore(candidate.fusedScore());
//        }
//        return 0.55d * normalizeFusedScore(candidate.fusedScore())
//                + 0.30d * candidate.vectorScore()
//                + 0.15d * candidate.bm25Score();
//    }
//
//    private double normalizeFusedScore(double score) {
//        return Math.min(1.0d, score * 80.0d);
//    }
//
//    private Map<String, Integer> termFrequency(Collection<String> terms) {
//        Map<String, Integer> frequency = new HashMap<>();
//        for (String term : terms) {
//            frequency.merge(term, 1, Integer::sum);
//        }
//        return frequency;
//    }
//
//    private Set<String> tokenizeForRetrieval(String text) {
//        String normalized = safe(text).toLowerCase(Locale.ROOT)
//                .replace('，', ' ')
//                .replace('。', ' ')
//                .replace('？', ' ')
//                .replace('：', ' ')
//                .replace('；', ' ')
//                .replace('、', ' ')
//                .replace('\n', ' ');
//        String[] parts = normalized.split("\\s+");
//        Set<String> tokens = new HashSet<>();
//        for (String part : parts) {
//            if (!part.isBlank()) {
//                tokens.add(part);
//            }
//        }
//        tokens.addAll(splitChineseBiGram(normalized));
//        return tokens;
//    }
//
//    private List<String> splitChineseBiGram(String text) {
//        List<String> grams = new ArrayList<>();
//        String compact = text.replace(" ", "");
//        for (int i = 0; i < compact.length() - 1; i++) {
//            grams.add(compact.substring(i, i + 2));
//        }
//        return grams;
//    }
//
//    private String summarizeChunk(String content) {
//        return content.length() <= 110 ? content : content.substring(0, 110) + "...";
//    }
//
//    private String buildChunkText(String courseName, String title, String source, String sourceType, String chunkText) {
//        return "Course: " + safe(courseName)
//                + "\nTitle: " + safe(title)
//                + "\nSource: " + safe(source)
//                + "\nSourceType: " + safe(sourceType)
//                + "\nContent: " + chunkText.trim();
//    }
//
//    private String tail(String text, int overlapChars) {
//        if (text == null || text.isBlank()) {
//            return "";
//        }
//        int start = Math.max(0, text.length() - overlapChars);
//        return text.substring(start).trim();
//    }
//
//    private String safe(String value) {
//        return value == null ? "" : value;
//    }
//
//    private void normalizeVector(float[] vector) {
//        if (vector == null || vector.length == 0) {
//            return;
//        }
//        double norm = 0.0d;
//        for (float value : vector) {
//            norm += value * value;
//        }
//        if (norm == 0.0d) {
//            return;
//        }
//        double divisor = Math.sqrt(norm);
//        for (int i = 0; i < vector.length; i++) {
//            vector[i] = (float) (vector[i] / divisor);
//        }
//    }
//
//    private double cosineSimilarity(float[] left, float[] right) {
//        if (left == null || right == null || left.length != right.length) {
//            return 0.0d;
//        }
//        double dot = 0.0d;
//        for (int i = 0; i < left.length; i++) {
//            dot += left[i] * right[i];
//        }
//        return dot;
//    }
//
//    private record ScoredChunk(
//            MaterialChunk chunk,
//            double fusedScore,
//            double bm25Score,
//            double vectorScore,
//            double rerankScore,
//            double finalScore
//    ) {
//        private ScoredChunk withRerankScore(double newRerankScore) {
//            return new ScoredChunk(chunk, fusedScore, bm25Score, vectorScore, newRerankScore, finalScore);
//        }
//
//        private ScoredChunk withFinalScore(double newFinalScore) {
//            return new ScoredChunk(chunk, fusedScore, bm25Score, vectorScore, rerankScore, newFinalScore);
//        }
//    }
//}
