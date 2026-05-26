package GAGYELOL.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #3 검증: 금액성 필드의 추출 스키마 description에 "음수 제거·절댓값·영수증 최종 합계 금액 하나만" 지침이
 * 포함되고, 비금액 필드에는 포함되지 않아야 한다.
 */
@ExtendWith(MockitoExtension.class)
class EvidenceAiServiceTest {

    @Mock UpstageIeClient upstageIeClient;
    @Mock OpenAiClient openAiClient;
    @InjectMocks EvidenceAiService service;

    @Captor ArgumentCaptor<Map<String, Object>> schemaCaptor;

    @Test
    void 금액성_필드는_절댓값_최종합계_지침이_스키마에_포함되고_비금액_필드는_제외된다() {
        when(upstageIeClient.extract(any(), eq("image/png"), any()))
                .thenReturn("{\"금액\":\"1000\",\"비고\":\"메모\"}");

        service.fillFormFields(new byte[]{1, 2, 3}, "image/png", List.of("금액", "비고"));

        verify(upstageIeClient).extract(any(), eq("image/png"), schemaCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schemaCaptor.getValue().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) props.get("금액");
        @SuppressWarnings("unchecked")
        Map<String, Object> note = (Map<String, Object>) props.get("비고");

        assertThat((String) amount.get("description"))
                .contains("음수").contains("절댓값").contains("최종 합계").contains("하나만");
        assertThat((String) note.get("description"))
                .doesNotContain("절댓값").doesNotContain("최종 합계");
    }

    @Test
    void 원장_양식이면_각_열은_행별_다중값_매핑_지침이_적용된다() {
        // 수입금액+지출금액+잔액이 모두 있는 원장(수입지출관리대장) 양식
        when(upstageIeClient.extract(any(), eq("image/png"), any())).thenReturn("{}");

        service.fillFormFields(new byte[]{1}, "image/png",
                List.of("번호", "날짜", "내용", "수입금액", "지출금액", "잔액"));

        verify(upstageIeClient).extract(any(), eq("image/png"), schemaCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schemaCaptor.getValue().get("properties");

        String income = (String) ((Map<?, ?>) props.get("수입금액")).get("description");
        String expense = (String) ((Map<?, ?>) props.get("지출금액")).get("description");
        // 열↔열 매핑 + 행별 다중값(콤마 나열, 합산 금지)
        assertThat(income).contains("찾으신").contains("콤마").contains("합산하지");
        assertThat(expense).contains("맡기신");
        assertThat((String) ((Map<?, ?>) props.get("날짜")).get("description")).contains("콤마");
    }

    @Test
    void 비원장_양식의_지출금액은_단일_최종합계로_추출된다() {
        // "지출 금액"만 있고 수입금액/잔액이 없는 양식(지출기록부)은 원장이 아니므로 단일 금액
        when(upstageIeClient.extract(any(), eq("image/png"), any())).thenReturn("{}");

        service.fillFormFields(new byte[]{1}, "image/png", List.of("지출 금액", "내용"));

        verify(upstageIeClient).extract(any(), eq("image/png"), schemaCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schemaCaptor.getValue().get("properties");
        String expense = (String) ((Map<?, ?>) props.get("지출 금액")).get("description");
        assertThat(expense).contains("최종 합계").contains("하나만").doesNotContain("콤마");
    }

    @Test
    void 수령인_정보는_이름소속학번전화_4개_필드로_파싱되고_누락값은_빈문자열() {
        when(upstageIeClient.extract(any(), eq("image/jpeg"), any()))
                .thenReturn("{\"name\":\"홍길동\",\"affiliation\":\"소프트웨어학과\",\"studentId\":\"32000000\"}");

        Map<String, String> info = service.extractRecipientInfo(new byte[]{1}, "image/jpeg");

        assertThat(info)
                .containsEntry("name", "홍길동")
                .containsEntry("affiliation", "소프트웨어학과")
                .containsEntry("studentId", "32000000")
                .containsEntry("phone", ""); // 응답에 없는 값은 빈 문자열
    }
}
