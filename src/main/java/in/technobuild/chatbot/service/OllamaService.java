package in.technobuild.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService {

    private final ObjectMapper objectMapper;

    @Qualifier("ollamaWebClient")
    private final WebClient ollamaWebClient;

    @Value("${chatbot.ollama.chat-model:llama3.1:8b-instruct-q4_K_M}")
    private String chatModel;

    @CircuitBreaker(name = "ollama", fallbackMethod = "fallbackResponse")
    public void streamChatResponse(String prompt, Consumer<String> tokenConsumer) {
        Map<String, Object> request = baseRequest(prompt, true);

        ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(line -> handleStreamLine(line, tokenConsumer))
                .blockLast(Duration.ofSeconds(120));
    }

    public String generateChatResponse(String prompt) {
        try {
            Map<String, Object> request = baseRequest(prompt, false);
            String response = ollamaWebClient.post()
                    .uri("/api/chat")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(120));

            if (response == null || response.isBlank()) {
                return "";
            }

            JsonNode jsonNode = objectMapper.readTree(response);
            return jsonNode.path("message").path("content").asText("");
        } catch (Exception ex) {
            log.error("Failed to generate non-stream chat response", ex);
            return "";
        }
    }

    public void fallbackResponse(String prompt, Consumer<String> tokenConsumer, Exception ex) {
        log.warn("Ollama circuit breaker OPEN", ex);
        tokenConsumer.accept("I am temporarily unavailable. Please try again in a moment.");
        tokenConsumer.accept("[DONE]");
    }

    public boolean isOllamaHealthy() {
        try {
            ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(3));
            return true;
        } catch (Exception ex) {
            log.error("Ollama health check failed", ex);
            return false;
        }
    }

    public boolean isModelAvailable(String modelName) {
        try {
            String response = ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            if (response == null || response.isBlank()) {
                return false;
            }
            JsonNode root = objectMapper.readTree(response);
            JsonNode models = root.path("models");
            if (!models.isArray()) {
                return false;
            }
            for (JsonNode model : models) {
                String name = model.path("name").asText("");
                if (name.equals(modelName) || name.startsWith(modelName + ":")) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            log.warn("Failed to verify model availability for {}", modelName, ex);
            return false;
        }
    }

    private Map<String, Object> baseRequest(String prompt, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", chatModel);
        body.put("stream", stream);
        body.put("keep_alive", "300s");

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0.1);
        options.put("num_predict", 400);
        body.put("options", options);

        body.put("messages", new Object[]{Map.of("role", "user", "content", prompt)});
        return body;
    }

    private void handleStreamLine(String line, Consumer<String> tokenConsumer) {
        try {
            if (line == null || line.isBlank()) {
                return;
            }

            JsonNode node = objectMapper.readTree(line);
            String token = node.path("message").path("content").asText("");
            if (!token.isBlank()) {
                tokenConsumer.accept(token);
            }

            if (node.path("done").asBoolean(false)) {
                tokenConsumer.accept("[DONE]");
            }
        } catch (Exception ex) {
            log.error("Failed to parse Ollama stream line", ex);
        }
    }
}
