package in.technobuild.chatbot.kafka.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelHealthEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topics.model-health}")
    private String modelHealthTopic;

    public void publish(String key, String event) {
        try {
            kafkaTemplate.send(modelHealthTopic, key, event);
            log.info("Published model-health event to topic {}", modelHealthTopic);
        } catch (Exception ex) {
            log.error("Failed to publish model-health event to topic {}", modelHealthTopic, ex);
        }
    }
}
