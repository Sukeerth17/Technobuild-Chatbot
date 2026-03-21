package in.technobuild.chatbot.controller;

import in.technobuild.chatbot.dto.response.HealthResponseDto;
import in.technobuild.chatbot.repository.ConversationRepository;
import in.technobuild.chatbot.service.HealthCheckService;
import in.technobuild.chatbot.service.WatchdogService;
import in.technobuild.chatbot.sse.SseEmitterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final HealthCheckService healthCheckService;
    private final ConversationRepository conversationRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final KafkaAdmin kafkaAdmin;
    private final WatchdogService watchdogService;
    private final SseEmitterRegistry sseEmitterRegistry;

    @Value("${kafka.topics.chat-requests}")
    private String chatRequestsTopic;

    @Value("${kafka.topics.sql-requests}")
    private String sqlRequestsTopic;

    @Value("${kafka.topics.chat-responses}")
    private String chatResponsesTopic;

    @Value("${kafka.topics.doc-ingestion}")
    private String docIngestionTopic;

    @Value("${kafka.topics.model-health}")
    private String modelHealthTopic;

    @GetMapping("/health")
    public ResponseEntity<HealthResponseDto> health() {
        log.info("Received /health request");
        HealthResponseDto response = healthCheckService.checkAll();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        log.info("Received /ready request");
        Map<String, Object> response = new LinkedHashMap<>();

        boolean mysqlUp = checkMysql();
        boolean redisUp = checkRedis();
        boolean kafkaUp = checkKafka();
        boolean ollamaUp = watchdogService.getOllamaStatus();
        boolean pythonUp = watchdogService.getPythonStatus();
        boolean modelLoaded = watchdogService.isOllamaModelLoaded();

        response.put("mysql", mysqlUp ? "UP" : "DOWN");
        response.put("redis", redisUp ? "UP" : "DOWN");
        response.put("kafka", kafkaUp ? "UP" : "DOWN");
        response.put("ollama", ollamaUp ? "UP" : "DOWN");
        response.put("python", pythonUp ? "UP" : "DOWN");
        response.put("sseEmitterQueueSize", sseEmitterRegistry.size());
        response.put("ollamaModelLoaded", modelLoaded);

        boolean allUp = mysqlUp && redisUp && kafkaUp && ollamaUp && pythonUp;
        response.put("status", allUp ? "UP" : "DOWN");

        HttpStatus status = allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }

    private boolean checkMysql() {
        try {
            conversationRepository.count();
            return true;
        } catch (Exception ex) {
            log.error("MySQL /ready check failed", ex);
            return false;
        }
    }

    private boolean checkRedis() {
        try {
            stringRedisTemplate.opsForValue().set("ping", "pong");
            return "pong".equalsIgnoreCase(stringRedisTemplate.opsForValue().get("ping"));
        } catch (Exception ex) {
            log.error("Redis /ready check failed", ex);
            return false;
        }
    }

    private boolean checkKafka() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            adminClient.describeTopics(List.of(
                    chatRequestsTopic,
                    sqlRequestsTopic,
                    chatResponsesTopic,
                    docIngestionTopic,
                    modelHealthTopic
            )).allTopicNames().get();
            return true;
        } catch (Exception ex) {
            log.error("Kafka /ready check failed", ex);
            return false;
        }
    }
}
