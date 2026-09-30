package dev.infrai.examples.mediaerase.infrai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.infrai.examples.mediaerase.config.InfraiProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class InfraiClient implements InfraiControlPlane {
    private static final int MAX_ATTEMPTS = 4;
    private final InfraiProperties properties;
    private final HttpClient http;
    private final ObjectMapper json;

    public InfraiClient(InfraiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    @Override
    public List<String> listSessionIds(String userId) {
        JsonNode data = call("GET", "/v1/auth/session/list_for_user/" + segment(userId));
        JsonNode sessions = data.isArray() ? data : data.path("sessions");
        if (!sessions.isArray()) {
            throw new IllegalStateException("Expected session list data");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode session : sessions) {
            String id = session.isTextual() ? session.textValue() : session.path("id").asText("");
            if (id.isBlank()) id = session.path("session_id").asText("");
            if (id.isBlank()) throw new IllegalStateException("Session record is missing its identifier");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    @Override
    public void revokeSession(String sessionId) {
        String encodedSessionId = segment(sessionId);
        call("POST", "/v1/auth/session/revoke/" + encodedSessionId + "?session_id=" + encodedSessionId);
    }

    @Override
    public void revokeCredential(String credentialId) {
        call("DELETE", "/v1/account/keys/revoke/" + segment(credentialId));
    }

    private JsonNode call(String method, String path) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response = send(method, path);
            JsonNode envelope = decodeEnvelope(response.body(), response.statusCode());
            if (response.statusCode() == 429 && attempt + 1 < MAX_ATTEMPTS) {
                pause(retryDelay(response, attempt));
                continue;
            }
            if (!envelope.path("ok").asBoolean(false)) {
                JsonNode errorNode = envelope.path("error");
                Map<String, Object> detail = json.convertValue(errorNode, Map.class);
                throw new InfraiException(errorNode.path("code").asText("REQUEST_REJECTED"),
                        response.statusCode(), detail == null ? Map.of() : detail);
            }
            if (response.statusCode() >= 500) {
                throw new IllegalStateException("Infrai transport status " + response.statusCode());
            }
            return envelope.path("data");
        }
        throw new IllegalStateException("Rate-limit retry budget exhausted");
    }

    private HttpResponse<String> send(String method, String path) {
        HttpRequest request = HttpRequest.newBuilder(properties.baseUrl().resolve(path))
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Accept", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException("Infrai transport request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Infrai transport request interrupted", exception);
        }
    }

    private JsonNode decodeEnvelope(String body, int statusCode) {
        try {
            return json.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unreadable response envelope at HTTP " + statusCode, exception);
        }
    }

    private static Duration retryDelay(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After")
                .map(InfraiClient::parseRetryAfter)
                .orElse(Duration.ofMillis(250L * (1L << attempt)));
    }

    private static Duration parseRetryAfter(String value) {
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            try {
                Duration delay = Duration.between(ZonedDateTime.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (RuntimeException invalidDate) {
                return Duration.ofMillis(250);
            }
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(Math.min(duration.toMillis(), 30_000));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry wait interrupted", exception);
        }
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Identifier is required");
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
