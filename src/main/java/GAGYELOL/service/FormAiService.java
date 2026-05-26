package GAGYELOL.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 파트 B 소유 - 양식지 관련 AI 기능
 * 양식지 필드 분석
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FormAiService {

    private final OpenAiClient openAiClient;

    /**
     * 양식지 텍스트에서 필드 목록, 설명, LLM 생성 필드를 추출합니다.
     * 반환 형식: {"description": "...", "fields": [...], "generatedFields": [...]}
     */
    public String analyzeForm(String formText, String policyChunks) {
        String policySection = (policyChunks != null && !policyChunks.isBlank())
                ? "\n[관련 규정 (규정책에서 검색된 내용)]\n" + policyChunks + "\n"
                : "";

        String prompt = String.format("""
                다음은 양식지의 텍스트입니다.%s
                아래 네 가지를 JSON으로 반환하세요.
                1. description: 이 양식지를 **언제, 어떤 상황에서** 사용하는지 구체적으로 설명하세요.
                   - 관련 규정이 제공된 경우, 규정에서 명시한 사용 조건(금액 기준, 결제 수단, 첨부 의무 등)을 반영하세요.
                   - "카드/현금 중 어떤 결제 수단에 쓰는지", "누가 작성하는지", "어떤 거래에서 발생하는지"를 포함하세요.
                2. paymentType: 이 양식지가 어떤 결제 수단에 사용되는지 판단하세요.
                   - "CARD": 카드 결제 전용 양식 (카드, 신용카드, 체크카드 관련 필드나 설명이 있는 경우)
                   - "CASH": 현금 결제 전용 양식 (현금, 현금영수증, 현금지출증빙 등 명시적으로 현금 거래를 나타내는 키워드가 있는 경우)
                   - "BOTH": 카드/현금 구분 없이 범용으로 사용되는 양식. "영수증 부착" 처럼 결제 수단을 특정하지 않는 표현만 있는 경우도 BOTH로 판단하세요.
                   - 판단 근거: 양식 제목, 필드명, 결제 수단 관련 키워드를 종합하세요. "영수증"은 카드영수증도 포함하므로 CASH의 근거로 쓰지 마세요.
                3. fields: 이 양식지에서 실제로 값을 입력해야 하는 빈 칸(입력 필드)의 이름 목록
                4. generatedFields: fields 중에서 사업명을 바탕으로 LLM이 서술형 내용을 생성해야 하는 필드 목록
                   - "내용", "목적", "설명", "사유", "개요", "세부내용" 등 서술형 작성이 필요한 필드를 포함하세요.
                   - 성명, 금액, 날짜, 소속 등 단순 기재 필드는 제외하세요.

                [필드 추출 규칙]
                - 테이블에서 행/열의 그룹 레이블(예: "지출인", "수령인")은 필드가 아닙니다.
                - 실제 빈 칸(입력 칸)만 필드로 추출하세요.
                - 빈 칸의 이름이 그룹 레이블 하위에 있을 경우, "그룹 레이블 + 칸 이름" 형태로 합쳐서 표현하세요.
                  예) "지출인" 그룹 아래 "성명" 칸 → "지출인 성명"
                - 이미 값이 고정된 셀(제목, 안내문, 합계 레이블 등)은 제외하세요.
                - "OO", "oo", "○○", "00" 처럼 플레이스홀더로 쓰인 부분은 입력이 필요한 빈 칸으로 간주하세요.
                  예) "OO대학 OO학과(부) OO전공 학생회" → "학생회" 필드로 추출
                  예) "[회장       (인)]" → "회장" 필드로 추출

                반드시 다음 JSON 형식으로만 응답하세요:
                {"description": "...", "paymentType": "CARD|CASH|BOTH", "fields": ["항목1", "항목2", ...], "generatedFields": ["항목1", ...]}

                양식지 텍스트:
                %s""", policySection, formText);

        log.info("양식지 분석 GPT 요청");
        return openAiClient.chatJson(prompt);
    }

    /**
     * 사업명·증빙 OCR 결과를 바탕으로 특정 필드의 서술형 내용을 생성합니다.
     */
    public String generateFieldContent(String businessName, String itemName, String description, String fieldName,
                                       java.util.Map<String, String> filledFields) {
        String itemLine = (itemName != null && !itemName.isBlank())
                ? "지출 항목: " + itemName + "\n"
                : "";
        String descLine = (description != null && !description.isBlank())
                ? "사업 설명: " + description + "\n"
                : "";

        String contextLine = "";
        if (filledFields != null && !filledFields.isEmpty()) {
            String items = filledFields.entrySet().stream()
                    .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
            if (!items.isBlank()) {
                contextLine = "이미 파악된 항목: " + items + "\n";
            }
        }

        String prompt = String.format("""
                사업명: %s
                %s%s%s위 사업의 공문서 양식에서 '%s' 항목에 들어갈 내용을 작성해주세요.
                이미 파악된 항목(품목, 금액, 날짜 등)을 반영해 구체적으로 작성하세요.
                반드시 '-습니다' 체로, 간결하고 공식적인 문체로 2문장 이내, 공백 포함 %d자 이내로 작성하세요.
                내용만 반환하고, 다른 설명은 붙이지 마세요.

                [예시]
                입력: 사업명=2024년 봄 MT, 지출 항목=버스 대여비, 금액=150,000원
                출력: 2024년 봄 MT 행사 참석을 위한 버스 대여 비용으로 지출하였습니다.
                """, businessName, itemLine, descLine, contextLine, fieldName, MAX_CONTENT_LEN);

        log.info("LLM 필드 생성 요청 - businessName={}, itemName={}, field={}, contextFields={}",
                businessName, itemName, fieldName, filledFields != null ? filledFields.keySet() : "없음");
        return limitLength(openAiClient.chat(prompt, false, 0.7), MAX_CONTENT_LEN);
    }

    /** 생성된 '내용' 등 서술형 필드가 양식 칸을 넘지 않도록 하는 최대 글자 수. */
    private static final int MAX_CONTENT_LEN = 150;

    /**
     * 생성 텍스트가 max자를 넘으면 max 이내의 마지막 문장 종결("다." 또는 ".")에서 자른다.
     * 적당한 종결부가 없으면 max자에서 하드 컷한다. (양식 칸 넘침 방지)
     */
    private String limitLength(String text, int max) {
        if (text == null) return null;
        String t = text.strip();
        if (t.length() <= max) return t;
        String head = t.substring(0, max);
        int da = head.lastIndexOf("다.");
        if (da >= max / 2) return head.substring(0, da + 2).strip();
        int dot = head.lastIndexOf('.');
        if (dot >= max / 2) return head.substring(0, dot + 1).strip();
        return head.strip();
    }
}
