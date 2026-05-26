package GAGYELOL.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Upstage Information Extract API 클라이언트.
 * 파일을 직접 전송해 구조화된 JSON을 반환합니다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UpstageIeClient {

    private static final String URL = "https://api.upstage.ai/v1/information-extraction";

    @Value("${upstage.api-key}")
    private String apiKey;

    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    /**
     * 파일 바이트와 JSON 스키마를 전송해 구조화된 JSON 문자열을 반환합니다.
     *
     * @param fileBytes 파일 바이트 배열
     * @param mimeType  파일 MIME 타입 (예: "application/pdf", "image/jpeg")
     * @param schema    추출할 필드 정의 (JSON Schema 형식)
     * @return Upstage IE가 추출한 JSON 문자열
     */
    public String extract(byte[] fileBytes, String mimeType, Map<String, Object> schema) {
        String base64 = Base64.getEncoder().encodeToString(fileBytes);
        try {
            Map<String, Object> body = Map.of(
                    "model", "information-extract",
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(Map.of(
                                    "type", "image_url",
                                    "image_url", Map.of(
                                            "url", "data:" + mimeType + ";base64," + base64
                                    )
                            ))
                    )),
                    "response_format", Map.of(
                            "type", "json_schema",
                            "json_schema", Map.of(
                                    "name", "document_schema",
                                    "schema", schema
                            )
                    )
            );

            String requestJson = objectMapper.writeValueAsString(body);
            log.info("Upstage IE 요청 - mimeType={}", mimeType);

            String responseBody = restClient.post()
                    .uri(URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("error")) {
                throw new RuntimeException("Upstage IE 오류: " + root.path("error").path("message").asText());
            }
            String content = root.path("choices").get(0).path("message").path("content").asText();
            log.info("Upstage IE 응답 수신 완료");
            return content;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Upstage IE 호출 실패: " + e.getMessage(), e);
        }
    }
}
