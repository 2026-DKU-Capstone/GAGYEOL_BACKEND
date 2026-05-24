package GAGYELOL.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 파트 C 소유 - 증빙서류 관련 AI 기능
 * Upstage Information Extract API를 사용해 파일에서 직접 구조화된 데이터를 추출합니다.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EvidenceAiService {

    private final UpstageIeClient upstageIeClient;
    private final OpenAiClient openAiClient;

    /**
     * 증빙서류 파일에서 결제 수단을 분류합니다.
     * Upstage IE에 파일을 직접 전송해 CARD / CASH를 반환합니다.
     */
    public String classifyPaymentType(byte[] fileBytes, String mimeType) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "paymentType", Map.of(
                                "type", "string",
                                "description", "결제 수단 분류. 신용카드/체크카드 결제는 CARD, 현금/계좌이체/현금영수증은 CASH"
                        )
                ),
                "required", List.of("paymentType")
        );

        log.info("Upstage IE 결제유형 분류 요청");
        try {
            String result = upstageIeClient.extract(fileBytes, mimeType, schema);
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readTree(result).path("paymentType").asText("CASH");
        } catch (Exception e) {
            throw new RuntimeException("결제 유형 분류 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 증빙서류 파일에서 양식지 필드 값을 추출합니다.
     * Upstage IE에 파일과 필드 스키마를 전송해 구조화된 결과를 반환합니다.
     * 반환 형식: {"filled": {"필드명": "값"}, "missing": ["필드명", ...]}
     */
    public String fillFormFields(byte[] fileBytes, String mimeType, List<String> formFields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : formFields) {
            properties.put(field, Map.of(
                    "type", "string",
                    "description", fieldDescription(field)
            ));
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);

        log.info("Upstage IE 필드 추출 요청 - 필드 수: {}", formFields.size());
        try {
            String result = upstageIeClient.extract(fileBytes, mimeType, schema);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(result);

            Map<String, String> filled = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();

            for (String field : formFields) {
                JsonNode value = node.path(field);
                if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
                    missing.add(field);
                } else {
                    filled.put(field, value.asText());
                }
            }

            return mapper.writeValueAsString(Map.of("filled", filled, "missing", missing));
        } catch (Exception e) {
            throw new RuntimeException("필드 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 수령인 학생증/신분증 이미지에서 인적 정보를 추출합니다. (#3)
     * 반환 형식: {"name":..., "affiliation":..., "studentId":..., "phone":...} (못 찾은 값은 빈 문자열)
     */
    public Map<String, String> extractRecipientInfo(byte[] imageBytes, String mimeType) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "description", "수령인 이름(성명)"));
        properties.put("affiliation", Map.of("type", "string", "description", "수령인 소속 (학과·학부·부서명)"));
        properties.put("studentId", Map.of("type", "string", "description", "수령인 학번 또는 사번"));
        properties.put("phone", Map.of("type", "string", "description", "수령인 전화번호 (연락처)"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);

        log.info("Upstage IE 수령인 정보 추출 요청");
        try {
            String result = upstageIeClient.extract(imageBytes, mimeType, schema);
            JsonNode node = new ObjectMapper().readTree(result);
            Map<String, String> info = new LinkedHashMap<>();
            for (String key : List.of("name", "affiliation", "studentId", "phone")) {
                JsonNode value = node.path(key);
                info.put(key, (value.isMissingNode() || value.isNull()) ? "" : value.asText(""));
            }
            return info;
        } catch (Exception e) {
            throw new RuntimeException("수령인 정보 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 추출 스키마의 필드 설명을 만든다. 금액성 필드에는 음수 기호를 제거한 절댓값으로,
     * 부가세·공급가액 등 세부 금액이 여러 개면 모두 합산한 총합 하나만 반환하도록 지침을 덧붙인다. (#4)
     * 날짜성 필드에는 대표 날짜 하나만, 시간 제외하여 반환하도록 지침을 덧붙인다.
     */
    private static String fieldDescription(String field) {
        String base = field + " 항목의 값";
        if (isAmountField(field)) {
            return base + ". 금액은 음수 기호(-)를 제거하고 절댓값(양수)으로만 반환하고, "
                    + "공급가액·부가세 등 세부 금액 항목이 여러 개이면 모두 합산한 총합 금액 하나만 숫자로 반환할 것";
        }
        if (isDateField(field)) {
            return base + ". 날짜가 여러 개 있으면 가장 대표적인 날짜 하나만 반환할 것. "
                    + "시간(시각, HH:MM 등)은 포함하지 말 것. "
                    + "형식은 YYYY년 MM월 DD일 또는 YY/MM/DD 중 원문 맥락에 맞는 것으로 통일할 것";
        }
        return base;
    }

    private static boolean isAmountField(String field) {
        return field.contains("금액") || field.contains("합계") || field.contains("총액")
                || field.contains("단가") || field.contains("비용") || field.endsWith("액");
    }

    private static boolean isDateField(String field) {
        return field.contains("날짜") || field.contains("일자") || field.contains("일시")
                || field.contains("기간") || field.endsWith("일");
    }

    /**
     * 증빙서류 내용 + 규정 청크 + 양식지 목록을 바탕으로 적절한 양식지를 선택합니다.
     * 반환 형식: {"formId": 숫자, "reason": "선택 이유"}
     */
    public String selectForm(String evidenceText, String policyChunks, String formListDescription, String paymentType) {
        String paymentLabel = "CARD".equals(paymentType) ? "카드 결제" : "현금 결제";
        String prompt = String.format("""
                당신은 회계 처리 전문가입니다. 아래 정보를 단계적으로 검토하여 가장 적합한 양식지 하나를 선택하세요.

                [결제 수단] %s

                [증빙서류 내용]
                %s

                [관련 규정]
                %s

                [사용 가능한 양식지 목록]
                %s

                반드시 다음 JSON 형식으로만 응답하세요:
                {"formId": 숫자, "reason": "선택 이유를 한국어로 설명"}
                """, paymentLabel, evidenceText, policyChunks, formListDescription);

        log.info("양식지 선택 GPT 요청");
        return openAiClient.chatJson(prompt);
    }
}
