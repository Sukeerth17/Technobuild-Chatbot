package in.technobuild.chatbot.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RateLimiterService {

    private static final String RATE_KEY_PREFIX = "rate:";
    private static final String BLACKLIST_KEY_PREFIX = "blacklist:";

    private final RedisTemplate<String, String> redisTemplate;
    private final int requestsPerMinute;
    private final ConcurrentHashMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiterService(
            @Qualifier("stringRedisTemplate") RedisTemplate<String, String> redisTemplate,
            @Value("${chatbot.rate-limit.requests-per-minute:20}") int requestsPerMinute) {
        this.redisTemplate = redisTemplate;
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
    }

    public boolean isAllowed(Long userId) {
        if (userId == null || isBlacklisted(userId)) {
            return false;
        }

        Bucket bucket = buckets.computeIfAbsent(userId, this::newBucket);
        boolean consumed = bucket.tryConsume(1);
        if (consumed) {
            redisTemplate.opsForValue().set(RATE_KEY_PREFIX + userId, String.valueOf(getRemainingRequests(userId)));
        }
        return consumed;
    }

    public long getRemainingRequests(Long userId) {
        if (userId == null) {
            return 0L;
        }
        Bucket bucket = buckets.computeIfAbsent(userId, this::newBucket);
        return bucket.getAvailableTokens();
    }

    public boolean isBlacklisted(Long userId) {
        if (userId == null) {
            return true;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + userId));
    }

    public void addToBlacklist(Long userId, Duration duration) {
        if (userId == null) {
            return;
        }
        redisTemplate.opsForValue().set(BLACKLIST_KEY_PREFIX + userId, "1", duration);
        log.warn("User {} blacklisted for {}", userId, duration);
    }

    private Bucket newBucket(Long userId) {
        Refill refill = Refill.greedy(requestsPerMinute, Duration.ofMinutes(1));
        Bandwidth limit = Bandwidth.classic(requestsPerMinute, refill);
        log.debug("Created rate-limit bucket for userId={}", userId);
        return Bucket.builder().addLimit(limit).build();
    }
}
