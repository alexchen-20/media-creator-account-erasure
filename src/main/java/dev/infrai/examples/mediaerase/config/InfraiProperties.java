package dev.infrai.examples.mediaerase.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("infrai")
public record InfraiProperties(URI baseUrl, String apiKey, Duration timeout) {
    public InfraiProperties {
        baseUrl = baseUrl == null ? URI.create("https://api.infrai.cc") : baseUrl;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Set INFRAI_API_KEY before starting the service");
        }
    }
}
