package com.flocier.domain.agent.service.armory.node.factory.element;

import com.alibaba.fastjson.JSON;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rag 高级问答顾问：
 * - query 改写（可选）：通过外部传入的 LLM/实现进行改写
 * - 双路召回：向量 topK + BM25(关键词) topK
 * - 粗排：合并去重并做简单融合排序
 * - rerank 精排（可选）：对粗排融合后的候选打分重排；{@code rerankTopK} 表示精排后写入上下文的前 K 条（不在精排前截断粗排）
 *
 * 说明：BM25 检索与 rerank/改写模型都通过接口外置，你可以在装配 Advisor 时注入任意实现（OpenAI/本地模型/HTTP 服务等）。
 */
public class RagAnswerAdvanceAdvisor implements BaseAdvisor {

    private static final Pattern KNOWLEDGE_FILTER_PATTERN =
            Pattern.compile("(knowledge|knowledge_tag)\\s*==\\s*['\"]([^'\"]+)['\"]");

    /**
     * 向量召回
     */
    private final VectorStore vectorStore;
    private final SearchRequest vectorSearchRequest;

    /**
     * BM25 / 关键词召回（外部注入）
     */
    private final KeywordRetriever keywordRetriever;
    private final int keywordTopK;

    /**
     * query 改写（外部注入，可选）
     */
    private final QueryRewriter queryRewriter;

    /**
     * rerank（外部注入，可选）
     */
    private final Reranker reranker;

    /**
     * 精排后保留并注入 prompt 的文档条数（粗排结果先整批交给 {@link Reranker}，排序后再取前 K 条）。
     */
    private final int rerankTopK;

    /**
     * 注入到用户 prompt 的上下文模板
     */
    private final String userTextAdvise;

    public RagAnswerAdvanceAdvisor(VectorStore vectorStore,
                                  SearchRequest vectorSearchRequest,
                                  KeywordRetriever keywordRetriever,
                                  int keywordTopK,
                                  QueryRewriter queryRewriter,
                                  Reranker reranker,
                                  int rerankTopK) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore must not be null");
        this.vectorSearchRequest = Objects.requireNonNull(vectorSearchRequest, "vectorSearchRequest must not be null");
        this.keywordRetriever = keywordRetriever;
        this.keywordTopK = keywordTopK <= 0 ? 0 : keywordTopK;
        this.queryRewriter = queryRewriter;
        this.reranker = reranker;
        this.rerankTopK = rerankTopK <= 0 ? this.vectorSearchRequest.getTopK() : rerankTopK;
        this.userTextAdvise =
                "\nContext information is below, surrounded by ---------------------\n\n---------------------\n{question_answer_context}\n---------------------\n\nGiven the context and provided history information and not prior knowledge,\nreply to the user comment. If the answer is not in the context, inform\nthe user that you can't answer the question.\n";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        HashMap<String, Object> context = new HashMap<>(chatClientRequest.context());

        String userText = chatClientRequest.prompt().getUserMessage().getText();

        String rewrittenQuery = rewriteQueryIfNeeded(userText, context);
        context.put("qa_original_query", userText);
        context.put("qa_rewritten_query", rewrittenQuery);

        // 1) vector recall
        Filter.Expression vectorFilterExpression = this.doGetFilterExpression(context);
        injectKeywordFilterFromVectorExpressionIfNeeded(context, vectorFilterExpression);
        SearchRequest vectorRequest = SearchRequest
                .from(this.vectorSearchRequest)
                .query(rewrittenQuery)
                .filterExpression(vectorFilterExpression)
                .build();
        List<Document> vectorDocs = this.vectorStore.similaritySearch(vectorRequest);
        context.put("qa_vector_retrieved_documents", vectorDocs);

        // 2) keyword/BM25 recall (optional)
        List<Document> keywordDocs = new ArrayList<>();
        if (this.keywordRetriever != null && this.keywordTopK > 0) {
            keywordDocs = Optional.ofNullable(this.keywordRetriever.search(rewrittenQuery, this.keywordTopK, context))
                    .orElseGet(ArrayList::new);
        }
        context.put("qa_keyword_retrieved_documents", keywordDocs);

        // 3) coarse rank merge (vector + keyword)
        List<ScoredDocument> coarse = fuseCoarseRank(vectorDocs, keywordDocs);
        context.put("qa_coarse_rank_documents", coarse.stream().map(sd -> sd.document).collect(Collectors.toList()));

        // 4) rerank (optional)
        List<Document> finalDocs;
        if (this.reranker != null && !coarse.isEmpty()) {
            List<Document> candidates = coarse.stream()
                    .map(sd -> sd.document)
                    .collect(Collectors.toList());
            List<ScoredDocument> reranked = Optional.ofNullable(this.reranker.rerank(rewrittenQuery, candidates, context))
                    .orElseGet(() -> candidates.stream().map(d -> new ScoredDocument(d, 0d)).collect(Collectors.toList()));
            reranked.sort(Comparator.comparingDouble((ScoredDocument sd) -> sd.score).reversed());
            List<ScoredDocument> rerankedTop = reranked.stream()
                    .limit(this.rerankTopK)
                    .collect(Collectors.toList());
            finalDocs = rerankedTop.stream().map(sd -> sd.document).collect(Collectors.toList());
            context.put("qa_rerank_documents", finalDocs);
            context.put("qa_rerank_scores", rerankedTop.stream()
                    .collect(Collectors.toMap(sd -> fingerprint(sd.document), sd -> sd.score, (a, b) -> a, LinkedHashMap::new)));
        } else {
            finalDocs = coarse.stream().map(sd -> sd.document).collect(Collectors.toList());
        }

        context.put("qa_retrieved_documents", finalDocs);

        String documentContext = finalDocs.stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(System.lineSeparator()));

        String advisedUserText = userText + System.lineSeparator() + this.userTextAdvise;
        Map<String, Object> advisedUserParams = new HashMap<>(context);
        advisedUserParams.put("question_answer_context", documentContext);

        return ChatClientRequest.builder()
                .prompt(Prompt.builder()
                        .messages(
                                new UserMessage(advisedUserText),
                                new AssistantMessage(JSON.toJSONString(advisedUserParams))
                        )
                        .build())
                .context(advisedUserParams)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        ChatResponse.Builder chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());

        chatResponseBuilder.metadata("qa_original_query", chatClientResponse.context().get("qa_original_query"));
        chatResponseBuilder.metadata("qa_rewritten_query", chatClientResponse.context().get("qa_rewritten_query"));

        chatResponseBuilder.metadata("qa_vector_retrieved_documents", chatClientResponse.context().get("qa_vector_retrieved_documents"));
        chatResponseBuilder.metadata("qa_keyword_retrieved_documents", chatClientResponse.context().get("qa_keyword_retrieved_documents"));
        chatResponseBuilder.metadata("qa_coarse_rank_documents", chatClientResponse.context().get("qa_coarse_rank_documents"));
        chatResponseBuilder.metadata("qa_rerank_documents", chatClientResponse.context().get("qa_rerank_documents"));
        chatResponseBuilder.metadata("qa_rerank_scores", chatClientResponse.context().get("qa_rerank_scores"));

        chatResponseBuilder.metadata("qa_retrieved_documents", chatClientResponse.context().get("qa_retrieved_documents"));

        ChatResponse chatResponse = chatResponseBuilder.build();
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(chatClientResponse.context())
                .build();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(this.before(chatClientRequest, callAdvisorChain));
        return this.after(chatClientResponse, callAdvisorChain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return BaseAdvisor.super.adviseStream(chatClientRequest, streamAdvisorChain);
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    protected Filter.Expression doGetFilterExpression(Map<String, Object> context) {
        return context.containsKey("qa_filter_expression") && StringUtils.hasText(context.get("qa_filter_expression").toString())
                ? (new FilterExpressionTextParser()).parse(context.get("qa_filter_expression").toString())
                : this.vectorSearchRequest.getFilterExpression();
    }

    private String rewriteQueryIfNeeded(String userText, Map<String, Object> context) {
        if (this.queryRewriter == null) {
            return userText;
        }
        String rewritten = this.queryRewriter.rewrite(userText, context);
        return StringUtils.hasText(rewritten) ? rewritten : userText;
    }

    /**
     * 让 BM25 路能复用向量路的知识库过滤条件：
     * - 若上游已显式放入 qa_keyword_filter / qa_knowledge_tag，则不覆盖；
     * - 否则尝试从 qa_filter_expression 或实际生效的 vector filter expression 中提取 knowledge 过滤值。
     */
    private void injectKeywordFilterFromVectorExpressionIfNeeded(Map<String, Object> context, Filter.Expression vectorFilterExpression) {
        if (context == null) {
            return;
        }
        if (hasTextObject(context.get("qa_keyword_filter")) || hasTextObject(context.get("qa_knowledge_tag"))) {
            return;
        }

        String rawFilterExpression = null;
        if (hasTextObject(context.get("qa_filter_expression"))) {
            rawFilterExpression = String.valueOf(context.get("qa_filter_expression"));
        } else if (vectorFilterExpression != null && StringUtils.hasText(vectorFilterExpression.toString())) {
            rawFilterExpression = vectorFilterExpression.toString();
        }

        String knowledgeTag = extractKnowledgeTag(rawFilterExpression);
        if (StringUtils.hasText(knowledgeTag)) {
            context.put("qa_knowledge_tag", knowledgeTag);
        }
    }

    private static boolean hasTextObject(Object value) {
        return value != null && StringUtils.hasText(value.toString());
    }

    private static String extractKnowledgeTag(String rawFilterExpression) {
        if (!StringUtils.hasText(rawFilterExpression)) {
            return null;
        }
        Matcher matcher = KNOWLEDGE_FILTER_PATTERN.matcher(rawFilterExpression);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return null;
    }

    /**
     * 粗排融合：将两个召回列表去重后做一个简单的 rank-fusion（越靠前得分越高）。
     */
    private List<ScoredDocument> fuseCoarseRank(List<Document> vectorDocs, List<Document> keywordDocs) {
        Map<String, ScoredDocument> merged = new LinkedHashMap<>();

        // 采用 Reciprocal Rank Fusion (RRF) 的简化版：score += 1/(k + rank)
        final int k = 60;
        addRrfScores(merged, vectorDocs, k, 1.0d);
        addRrfScores(merged, keywordDocs, k, 1.0d);

        List<ScoredDocument> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparingDouble((ScoredDocument sd) -> sd.score).reversed());
        return result;
    }

    private void addRrfScores(Map<String, ScoredDocument> merged, List<Document> docs, int k, double weight) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        for (int i = 0; i < docs.size(); i++) {
            Document d = docs.get(i);
            String key = fingerprint(d);
            double add = weight * (1.0d / (k + (i + 1)));
            merged.compute(key, (kk, existing) -> {
                if (existing == null) {
                    return new ScoredDocument(d, add);
                }
                existing.score += add;
                return existing;
            });
        }
    }

    private static String fingerprint(Document document) {
        if (document == null) {
            return "null";
        }

        Map<String, Object> metadata = document.getMetadata() == null
                ? Map.of()
                : document.getMetadata();

        // 1) 当前阶段优先使用 parent_document_id + chunk_index（向量检索侧稳定可用）
        String parentDocumentId = textOf(metadata.get("parent_document_id"));
        String parentChunkIndex = textOf(metadata.get("chunk_index"));
        if (StringUtils.hasText(parentDocumentId) && StringUtils.hasText(parentChunkIndex)) {
            return "parentChunk:" + parentDocumentId + "#" + parentChunkIndex;
        }

        // 2) 兼容 ES 映射命名：doc_id + chunk_index
        String docId = textOf(metadata.get("doc_id"));
        String docChunkIndex = textOf(metadata.get("chunk_index"));
        if (StringUtils.hasText(docId) && StringUtils.hasText(docChunkIndex)) {
            return "docChunk:" + docId + "#" + docChunkIndex;
        }

        // 3) chunk_id 暂时后置兜底（当前向量检索 metadata 尚未稳定带 chunk_id）
        String chunkId = textOf(metadata.get("chunk_id"));
        if (StringUtils.hasText(chunkId)) {
            return "chunk:" + chunkId;
        }

        // 4) 最终兜底：仅使用文本计算 hash，避免 metadata 差异导致同文档无法合并
        String text = textOf(document.getText());
        return "text:" + sha256(text);
    }

    private static String sha256(String input) {
        String base = input == null ? "" : input;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(base.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(base.hashCode());
        }
    }

    private static String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 粗排/精排用的带分文档；对外公开以便 {@link Reranker} 的实现类可以构造返回值。
     */
    public static final class ScoredDocument {
        private final Document document;
        private double score;

        public ScoredDocument(Document document, double score) {
            this.document = document;
            this.score = score;
        }

        public Document getDocument() {
            return document;
        }

        public double getScore() {
            return score;
        }
    }

    /**
     * query 改写接口（通常用 LLM 实现）
     */
    public interface QueryRewriter {
        /**
         * @param userQuery 用户原始 query
         * @param context   advisor 上下文，可读写
         */
        String rewrite(String userQuery, Map<String, Object> context);
    }

    /**
     * 关键词 / BM25 召回接口（通常通过数据库全文检索、ES、OpenSearch 等实现）
     */
    public interface KeywordRetriever {
        /**
         * @param query   改写后的 query
         * @param topK    返回数量
         * @param context advisor 上下文，可读写
         */
        List<Document> search(String query, int topK, Map<String, Object> context);
    }

    /**
     * rerank 接口（通常用 rerank 模型或 LLM 实现）
     */
    public interface Reranker {
        /**
         * @param query      改写后的 query
         * @param candidates 粗排候选
         * @param context    advisor 上下文，可读写
         * @return 带分数的候选列表（分数越大越相关）
         */
        List<ScoredDocument> rerank(String query, List<Document> candidates, Map<String, Object> context);
    }
}

