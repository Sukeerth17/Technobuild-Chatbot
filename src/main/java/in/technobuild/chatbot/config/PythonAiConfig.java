package in.technobuild.chatbot.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class PythonAiConfig {

    @Value("${chatbot.python.base-url:${app.python-ai.base-url:http://localhost:8000}}")
    private String pythonAiBaseUrl;

    @Bean(name = "pythonAiWebClient")
    public WebClient pythonAiWebClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(30))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000);

        return WebClient.builder()
                .baseUrl(pythonAiBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
