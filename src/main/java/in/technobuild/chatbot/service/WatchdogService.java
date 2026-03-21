package in.technobuild.chatbot.service;

import in.technobuild.chatbot.kafka.producer.ModelHealthEventProducer;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchdogService {

    private final OllamaService ollamaService;
    private final in.technobuild.chatbot.client.PythonAiClient pythonAiClient;
    private final ModelHealthEventProducer modelHealthEventProducer;

    @Value("${chatbot.ollama.chat-model:llama3.1:8b-instruct-q4_K_M}")
    private String ollamaChatModel;

    private final AtomicBoolean ollamaHealthy = new AtomicBoolean(true);
    private final AtomicBoolean pythonHealthy = new AtomicBoolean(true);
    private final AtomicBoolean ollamaModelLoaded = new AtomicBoolean(false);

    @Scheduled(fixedDelay = 30000)
    public void pingOllama() {
        boolean healthy = ollamaService.isOllamaHealthy();
        ollamaHealthy.set(healthy);

        if (healthy) {
            ollamaModelLoaded.set(ollamaService.isModelAvailable(ollamaChatModel));
            log.debug("Ollama healthy");
            modelHealthEventProducer.publish("ollama", "{\"service\":\"ollama\",\"status\":\"UP\"}");
            return;
        }

        log.error("Ollama is DOWN");
        restartOllama();

        sleep(10_000);
        boolean afterRestart = ollamaService.isOllamaHealthy();
        ollamaHealthy.set(afterRestart);
        ollamaModelLoaded.set(afterRestart && ollamaService.isModelAvailable(ollamaChatModel));

        if (!afterRestart) {
            log.error("Ollama still DOWN after restart attempt");
        }
        modelHealthEventProducer.publish("ollama",
                String.format("{\"service\":\"ollama\",\"status\":\"%s\"}", afterRestart ? "UP" : "DOWN"));
    }

    @Scheduled(fixedDelay = 30000)
    public void pingPythonService() {
        boolean healthy = pythonAiClient.isPythonServiceHealthy();
        pythonHealthy.set(healthy);

        if (!healthy) {
            log.error("Python AI service DOWN");
        }

        modelHealthEventProducer.publish("python",
                String.format("{\"service\":\"python\",\"status\":\"%s\"}", healthy ? "UP" : "DOWN"));
    }

    public boolean getOllamaStatus() {
        return ollamaHealthy.get();
    }

    public boolean getPythonStatus() {
        return pythonHealthy.get();
    }

    public boolean isOllamaModelLoaded() {
        return ollamaModelLoaded.get();
    }

    private void restartOllama() {
        try {
            Runtime.getRuntime().exec("ollama serve");
        } catch (Exception ex) {
            log.error("Failed to execute 'ollama serve' restart command", ex);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Watchdog sleep interrupted", ex);
        }
    }
}
