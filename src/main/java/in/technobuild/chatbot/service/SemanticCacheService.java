package in.technobuild.chatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.technobuild.chatbot.client.PythonAiClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticCacheService {

    private static final Duration CACHE_TTL = Duration.ofHours(24);

    @Qualifier("stringRedisTemplate")
    private final RedisTemplate<String, String> redisTemplate;

    private final PythonAiClient pythonAiClient;
    private final ObjectMapper objectMapper;

    @Value("${chatbot.cache.enabled:true}")
    private boolean cacheEnabled;

    public Optional<String> findCachedAnswer(Long userId, String question) {
        if (!isCacheEnabled() || userId == null || question == null || question.isBlank()) {
            return Optional.empty();
        }

        try {
            List<Float> queryEmbedding = pythonAiClient.embed(question);
            if (queryEmbedding.isEmpty()) {
                return Optional.empty();
            }

            Set<String> keys = redisTemplate.keys(keyPrefix(userId) + "*");
            if (keys == null || keys.isEmpty()) {
                return Optional.empty();
            }

            String bestAnswer = null;
            double bestScore = 0.0;
            for (String key : keys) {
                String raw = redisTemplate.opsForValue().get(key);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                CacheEntry entry = objectMapper.readValue(raw, CacheEntry.class);
                double similarity = cosineSimilarity(queryEmbedding, entry.embedding());
                if (similarity > 0.95 && similarity > bestScore) {
                    bestScore = similarity;
                    bestAnswer = entry.answer();
                }
            }
            return Optional.ofNullable(bestAnswer);
        } catch (Exception ex) {
            log.warn("Semantic cache lookup failed for userId={}", userId, ex);
            return Optional.empty();
        }
    }

    public void cacheAnswer(Long userId, String question, String answer) {
        if (!isCacheEnabled() || userId == null || question == null || question.isBlank()) {
            return;
        }

        try {
            List<Float> embedding = pythonAiClient.embed(question);
            if (embedding.isEmpty()) {
                return;
            }

            CacheEntry entry = new CacheEntry(
                    question,
                    answer == null ? "" : answer,
                    embedding,
                    Instant.now().toEpochMilli()
            );
            String key = keyPrefix(userId) + sha256(question);
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(entry), CACHE_TTL);
        } catch (Exception ex) {
            log.warn("Failed to cache semantic answer for userId={}", userId, ex);
        }
    }

    public void clearUserCache(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            Set<String> keys = redisTemplate.keys(keyPrefix(userId) + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ex) {
            log.warn("Failed to clear semantic cache for userId={}", userId, ex);
        }
    }

    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    private String keyPrefix(Long userId) {
        return "cache:semantic:" + userId + ":";
    }

    private double cosineSimilarity(List<Float> vec1, List<Float> vec2) {
        if (vec1 == null || vec2 == null || vec1.isEmpty() || vec2.isEmpty() || vec1.size() != vec2.size()) {
            return 0.0;
        }

        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < vec1.size(); i++) {
            double a = vec1.get(i);
            double b = vec2.get(i);
            dot += a * b;
            norm1 += a * a;
            norm2 += b * b;
        }
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private record CacheEntry(String question, String answer, List<Float> embedding, long timestamp) {
        private CacheEntry {
            if (embedding == null) {
                embedding = new ArrayList<>();
            }
        }
    }
}
