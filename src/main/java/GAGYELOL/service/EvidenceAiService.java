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
     * Upstage IE에 파일을 전송해 LLM이 맥락을 파악해 분류합니다.
     */
    public String classifyPaymentType(byte[] fileBytes, String mimeType) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "paymentType", Map.of(
                                "type", "string",
                                "enum", List.of("CARD", "CASH", "BOTH"),
                                "description", """
                                        이 문서의 실제 결제 수단을 분류하세요.
                                        - CARD: 신용카드·체크카드로 결제한 영수증 (예: 신용카드 매출전표, 카드승인 영수증)
                                        - CASH: 현금·계좌이체로 결제한 영수증 (예: 현금영수증, 현금(지출증빙), 현금(소득공제), 계좌이체 확인서)
                                        - BOTH: 결제 수단을 특정할 수 없는 문서
                                        주의: KT멤버십·포인트 등 적립 카드 번호는 결제 수단이 아닙니다. 실제 결제 방식만 보세요.
                                        [예시]
                                        - "신용카드 매출전표 / 승인번호: 12345" → CARD
                                        - "현금(지출증빙) / 승인번호: 67890" → CASH
                                        - "현금영수증 / 계좌이체" → CASH
                                        - "KT멤버십 카드번호: 1234 / 신용카드 결제" → CARD
                                        """
                        )
                ),
                "required", List.of("paymentType")
        );

        log.info("Upstage IE 결제유형 분류 요청");
        try {
            String result = upstageIeClient.extract(fileBytes, mimeType, schema);
            return new ObjectMapper().readTree(result).path("paymentType").asText("BOTH");
        } catch (Exception e) {
            log.warn("결제유형 분류 실패, BOTH 반환: {}", e.getMessage());
            return "BOTH";
        }
    }

    /**
     * 증빙서류 파일에서 양식지 필드 값을 추출합니다.
     * Upstage IE에 파일과 필드 스키마를 전송해 구조화된 결과를 반환합니다.
     * 반환 형식: {"filled": {"필드명": "값"}, "missing": ["필드명", ...]}
     */
    public String fillFormFields(byte[] fileBytes, String mimeType, List<String> formFields) {
        // 수입지출관리대장(원장)일 때만 통장 거래내역 열↔열(행별 다중값) 매핑을 적용한다.
        boolean ledgerForm = isLedgerFieldSet(formFields);
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : formFields) {
            properties.put(field, Map.of(
                    "type", "string",
                    "description", fieldDescription(field, ledgerForm)
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
     * 양식 필드 집합이 수입지출관리대장(원장)인지 판별한다. 수입금액·지출금액·잔액 열을 모두 가져야 원장으로 본다.
     * (지출기록부처럼 "지출 금액" 하나만 있는 양식은 원장이 아니므로 단일 금액으로 추출한다.) (#3)
     */
    private static boolean isLedgerFieldSet(List<String> fields) {
        boolean income = false, expense = false, balance = false;
        for (String f : fields) {
            if (f.contains("수입") && f.contains("금액")) income = true;
            else if (f.contains("지출") && f.contains("금액")) expense = true;
            if (f.contains("잔액")) balance = true;
        }
        return income && expense && balance;
    }

    /**
     * 추출 스키마의 필드 설명을 만든다. 수입지출관리대장(원장)이면 통장 거래내역 열↔열(행별 다중값)로,
     * 그 외 양식의 금액성 필드에는 음수 제거·절댓값·영수증 최종 합계 금액 하나만 반환하도록 지침을 덧붙인다. (#3)
     * 날짜성 필드에는 대표 날짜 하나만, 시간 제외하여 반환하도록 지침을 덧붙인다.
     */
    private static String fieldDescription(String field, boolean ledgerForm) {
        String base = field + " 항목의 값";

        // 원장 양식일 때만 통장 거래내역 열↔열 매핑(행별 다중값). 지출기록부의 "지출 금액" 등에는 적용 안 함.
        if (ledgerForm) return ledgerColumnDescription(field);

        if (isAmountField(field)) {
            return base + ". 금액은 음수 기호(-)를 제거하고 절댓값(양수)으로만 반환할 것. "
                    + "영수증/증빙에 '승인금액', '총금액', '합계', '결제금액'처럼 최종 합계 금액이 표시되어 있으면 "
                    + "그 최종 합계 금액 하나만 반환할 것(공급가액·부가세 등 중간 항목 금액이나 개별 품목 금액은 반환하지 말 것). "
                    + "여러 금액을 나열하지 말고 숫자 하나만 반환할 것";
        }
        if (isDateField(field)) {
            return base + ". 날짜가 여러 개 있으면 가장 대표적인 날짜 하나만 반환할 것. "
                    + "시간(시각, HH:MM 등)은 포함하지 말 것. "
                    + "형식은 YYYY년 MM월 DD일 또는 YY/MM/DD 중 원문 맥락에 맞는 것으로 통일할 것";
        }
        return base;
    }

    /**
     * 수입지출관리대장(원장)의 각 열을 은행 거래내역(통장)의 대응 열에 행별로 매핑한다. 모든 열이 거래(행)
     * 단위로 정렬돼야 하므로 각 열의 값을 위에서부터 순서대로 콤마로 나열하고 절대 합산하지 않는다.
     * - 수입금액 ← '찾으신 금액'(출금) 열, 지출금액 ← '맡기신 금액'(입금) 열, 잔액 ← '거래후 잔액' 열
     * - 날짜 ← 거래일시(날짜), 번호 ← 순번, 내용 ← 적요/기재내용
     */
    private static String ledgerColumnDescription(String field) {
        String hint;
        if (field.contains("수입") && field.contains("금액"))      hint = "통장 거래내역의 '찾으신 금액'(출금) 열";
        else if (field.contains("지출") && field.contains("금액")) hint = "통장 거래내역의 '맡기신 금액'(입금) 열";
        else if (field.contains("잔액"))                          hint = "통장 거래내역의 '거래후 잔액' 열";
        else if (field.contains("날짜") || field.contains("일자")) hint = "통장 거래내역의 거래일시(날짜, 시간 제외)";
        else if (field.contains("번호"))                          hint = "각 거래의 순번(1, 2, 3, …)";
        else if (field.contains("내용") || field.contains("적요")) hint = "통장 거래내역의 적요/기재내용";
        else hint = null;

        String src = (hint != null) ? (" 이 열은 " + hint + "에 해당한다.") : "";
        boolean numeric = field.contains("금액") || field.contains("잔액");
        return field + " 항목." + src
                + " 입력은 은행 거래내역(통장 거래내역조회)이다. 표의 각 거래(행) 값을 위에서부터 순서대로"
                + " 모두 콤마(,)로 구분해 나열할 것. 절대 합산하지 말 것. "
                + "특정 행에 값이 없으면 그 자리를 빈칸으로 두어 행 순서를 유지할 것."
                + (numeric ? " 숫자만(원화기호·천단위 콤마 없이) 반환할 것." : "");
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
