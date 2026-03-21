package in.technobuild.chatbot.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
public class PythonAiClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient pythonAiWebClient;

    public PythonAiClient(@Qualifier("pythonAiWebClient") WebClient pythonAiWebClient) {
        this.pythonAiWebClient = pythonAiWebClient;
    }

    public List<Float> embed(String text) {
        try {
            EmbedResponse response = pythonAiWebClient.post()
                    .uri("/embed")
                    .bodyValue(new EmbedRequest(text))
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    .block(REQUEST_TIMEOUT);
            return response != null && response.getEmbedding() != null ? response.getEmbedding() : List.of();
        } catch (Exception ex) {
            log.error("Python embed call failed", ex);
            return List.of();
        }
    }

    public List<RerankResult> rerank(String query, List<String> documents, int topK) {
        try {
            RerankResponse response = pythonAiWebClient.post()
                    .uri("/rerank")
                    .bodyValue(new RerankRequest(query, documents, topK))
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .block(REQUEST_TIMEOUT);

            if (response == null || response.getResults() == null) {
                return fallbackRerank(documents, topK);
            }

            return response.getResults().stream()
                    .sorted(Comparator.comparing(RerankResult::score).reversed())
                    .limit(topK)
                    .toList();
        } catch (Exception ex) {
            log.error("Python rerank call failed", ex);
            return fallbackRerank(documents, topK);
        }
    }

    public List<String> chunkDocument(String text) {
        try {
            ChunkResponse response = pythonAiWebClient.post()
                    .uri("/chunk")
                    .bodyValue(new ChunkRequest(text, 512, 50))
                    .retrieve()
                    .bodyToMono(ChunkResponse.class)
                    .block(REQUEST_TIMEOUT);
            if (response == null || response.getChunks() == null || response.getChunks().isEmpty()) {
                return List.of(text);
            }
            return response.getChunks();
        } catch (Exception ex) {
            log.error("Python chunk call failed", ex);
            return List.of(text);
        }
    }

    public boolean checkConfidence(String answer, List<String> chunks) {
        try {
            ConfidenceResponse response = pythonAiWebClient.post()
                    .uri("/confidence")
                    .bodyValue(new ConfidenceRequest(answer, chunks))
                    .retrieve()
                    .bodyToMono(ConfidenceResponse.class)
                    .block(REQUEST_TIMEOUT);
            return response == null || response.isGrounded();
        } catch (Exception ex) {
            log.error("Python confidence call failed", ex);
            return true;
        }
    }

    public boolean isPythonServiceHealthy() {
        try {
            HealthResponse response = pythonAiWebClient.get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(HealthResponse.class)
                    .block(Duration.ofSeconds(3));
            if (response == null || response.getStatus() == null) {
                return false;
            }
            return response.getStatus().toUpperCase().contains("UP")
                    || response.getStatus().toUpperCase().contains("200");
        } catch (Exception ex) {
            log.error("Python health call failed", ex);
            return false;
        }
    }

    public ParseDocumentResult parseDocument(byte[] fileBytes, String fileType, String fileName) {
        try {
            MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
            multipartBody.add("file", new NamedByteArrayResource(fileBytes, fileName));
            multipartBody.add("file_type", fileType);

            ParseDocumentResponse response = pythonAiWebClient.post()
                    .uri("/parse-document")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(multipartBody)
                    .retrieve()
                    .bodyToMono(ParseDocumentResponse.class)
                    .block(Duration.ofSeconds(60));

            if (response == null || response.getText() == null) {
                return new ParseDocumentResult("", 0, 0);
            }
            return new ParseDocumentResult(
                    response.getText(),
                    response.getPages() == null ? 0 : response.getPages(),
                    response.getWordCount() == null ? 0 : response.getWordCount()
            );
        } catch (Exception ex) {
            log.error("Python parse-document call failed", ex);
            return new ParseDocumentResult("", 0, 0);
        }
    }

    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            EmbedBatchResponse response = pythonAiWebClient.post()
                    .uri("/embed-batch")
                    .bodyValue(new EmbedBatchRequest(texts))
                    .retrieve()
                    .bodyToMono(EmbedBatchResponse.class)
                    .block(Duration.ofSeconds(60));
            return response != null && response.getEmbeddings() != null ? response.getEmbeddings() : List.of();
        } catch (Exception ex) {
            log.error("Python embed-batch call failed", ex);
            return List.of();
        }
    }

    public PythonSqlResponse callGetResponse(String prompt, String uuid, boolean firstRequest, Duration timeout) {
        try {
            GetResponseRequest request = new GetResponseRequest(prompt, firstRequest, uuid);
            PythonSqlResponse response = pythonAiWebClient.post()
                    .uri("/getResponse")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(PythonSqlResponse.class)
                    .block(timeout);
            return response != null ? response : new PythonSqlResponse(List.of(), uuid);
        } catch (Exception ex) {
            log.error("Python /getResponse call failed", ex);
            return new PythonSqlResponse(List.of(), uuid);
        }
    }

    private List<RerankResult> fallbackRerank(List<String> documents, int topK) {
        List<RerankResult> fallback = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, documents.size()); i++) {
            fallback.add(new RerankResult(i, 0.0f, documents.get(i)));
        }
        return fallback;
    }

    public record RerankResult(int index, float score, String text) {
    }

    public record PythonSqlResponse(List<Map<String, Object>> response, String uuid) {
    }

    public record ParseDocumentResult(String text, int pages, int wordCount) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class EmbedRequest {
        private String text;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class EmbedResponse {
        private List<Float> embedding;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class EmbedBatchRequest {
        private List<String> texts;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class EmbedBatchResponse {
        private List<List<Float>> embeddings;
        private Integer count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RerankRequest {
        private String query;
        private List<String> documents;
        @JsonProperty("top_k")
        private int topK;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class RerankResponse {
        private List<RerankResult> results;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ChunkRequest {
        private String text;
        @JsonProperty("chunk_size")
        private int chunkSize;
        @JsonProperty("chunk_overlap")
        private int chunkOverlap;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ChunkResponse {
        private List<String> chunks;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ConfidenceRequest {
        private String answer;
        @JsonProperty("retrieved_chunks")
        private List<String> retrievedChunks;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ConfidenceResponse {
        @JsonProperty("is_grounded")
        private boolean grounded;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class GetResponseRequest {
        private String prompt;
        @JsonProperty("first_request")
        private boolean firstRequest;
        private String uuid;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class HealthResponse {
        private String status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ParseDocumentResponse {
        private String text;
        private Integer pages;
        @JsonProperty("word_count")
        private Integer wordCount;
        private String error;
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray == null ? new byte[0] : byteArray);
            this.filename = filename == null || filename.isBlank() ? "upload.bin" : filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
