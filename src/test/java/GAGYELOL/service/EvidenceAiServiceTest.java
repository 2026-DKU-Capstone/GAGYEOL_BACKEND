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
