package GAGYELOL.service;

import GAGYELOL.dto.FillFieldsRequest;
import GAGYELOL.dto.FillFieldsResponse;
import GAGYELOL.entity.*;
import GAGYELOL.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvidenceFillTest {

    @Mock EvidenceRepository evidenceRepository;
    @Mock EvidenceFormRepository evidenceFormRepository;
    @Mock ApprovalRequestRepository approvalRequestRepository;
    @Mock FormRepository formRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupRepository groupRepository;
    @Mock PolicyChunkVectorStore vectorStore;
    @Mock EmbeddingService embeddingService;
    @Mock EvidenceAiService evidenceAiService;
    @Mock FormAiService formAiService;
    @Mock FormFillService formFillService;

    @InjectMocks EvidenceService evidenceService;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(evidenceService, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(evidenceService, "uploadDir", tempDir.toString());
    }

    @Test
    void fillFields_경로1_generatedField에_사업명_있으면_LLM_호출() throws Exception {
        // given
        Path evidenceFile = tempDir.resolve("evidence.pdf");
        Files.write(evidenceFile, "dummy".getBytes());

        UserGroup group = UserGroup.builder().payerName("홍길동").build();

        Evidence evidence = Evidence.builder()
                .filePath(evidenceFile.toString())
                .fileName("evidence.pdf")
                .businessName("단국대 캡스톤 프로젝트")
                .group(group)
                .build();

        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"지출인 성명\",\"내용\",\"금액\"]")
                .generatedFields("[\"내용\"]")
                .build();

        when(evidenceRepository.findById(1L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(10L)).thenReturn(Optional.of(form));
        when(formAiService.generateFieldContent(eq("단국대 캡스톤 프로젝트"), isNull(), eq("내용")))
                .thenReturn("단국대학교 캡스톤디자인 과제 수행을 위한 물품 구매 비용입니다.");
        // 지출인 성명은 그룹 등록 정보로 채워지므로 영수증 IE에는 "금액"만 전달됨
        when(evidenceAiService.fillFormFields(any(), any(), eq(List.of("금액"))))
                .thenReturn("{\"filled\":{\"금액\":\"50000\"},\"missing\":[]}");

        FillFieldsRequest request = new FillFieldsRequest();
        ReflectionTestUtils.setField(request, "formIds", List.of(10L));

        // when
        FillFieldsResponse response = evidenceService.fillFields(1L, request);

        // then
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getFilledFields()).containsEntry("내용", "단국대학교 캡스톤디자인 과제 수행을 위한 물품 구매 비용입니다.");
        assertThat(result.getFilledFields()).containsEntry("지출인 성명", "홍길동");
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        assertThat(result.getMissingFields()).isEmpty();
        verify(formAiService).generateFieldContent(eq("단국대 캡스톤 프로젝트"), isNull(), eq("내용"));
    }

    @Test
    void fillFields_경로1_사업명_없으면_generatedField가_missing으로() throws Exception {
        // given
        Path evidenceFile = tempDir.resolve("evidence2.pdf");
        Files.write(evidenceFile, "dummy".getBytes());

        Evidence evidence = Evidence.builder()
                .filePath(evidenceFile.toString())
                .fileName("evidence2.pdf")
                .businessName(null)
                .build();

        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"내용\",\"금액\"]")
                .generatedFields("[\"내용\"]")
                .build();

        when(evidenceRepository.findById(2L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(20L)).thenReturn(Optional.of(form));
        when(evidenceAiService.fillFormFields(any(), any(), eq(List.of("금액"))))
                .thenReturn("{\"filled\":{\"금액\":\"30000\"},\"missing\":[]}");

        FillFieldsRequest request = new FillFieldsRequest();
        ReflectionTestUtils.setField(request, "formIds", List.of(20L));

        // when
        FillFieldsResponse response = evidenceService.fillFields(2L, request);

        // then
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getMissingFields()).contains("내용");
        assertThat(result.getFilledFields()).containsEntry("금액", "30000");
        verify(formAiService, never()).generateFieldContent(any(), any(), any());
    }

    @Test
    void fillFields_경로3_증빙에서_못_찾으면_수령인_사진에서_추출() throws Exception {
        // given — 영수증에서 추출 가능한 비-인적 필드로 수령인 사진 폴백 경로를 검증
        Path evidenceFile = tempDir.resolve("evidence3.pdf");
        Path recipientFile = tempDir.resolve("recipient.jpg");
        Files.write(evidenceFile, "dummy".getBytes());
        Files.write(recipientFile, "dummy".getBytes());

        Evidence evidence = Evidence.builder()
                .filePath(evidenceFile.toString())
                .fileName("evidence3.pdf")
                .businessName(null)
                .recipientImagePath(recipientFile.toString())
                .build();

        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"금액\",\"거래처\"]")
                .generatedFields("[]")
                .build();

        when(evidenceRepository.findById(3L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(30L)).thenReturn(Optional.of(form));
        // 증빙서류에서는 거래처를 못 찾음
        when(evidenceAiService.fillFormFields(any(), eq("application/pdf"), any()))
                .thenReturn("{\"filled\":{\"금액\":\"50000\"},\"missing\":[\"거래처\"]}");
        // 수령인 사진에서 거래처를 찾음
        when(evidenceAiService.fillFormFields(any(), eq("image/jpeg"), eq(List.of("거래처"))))
                .thenReturn("{\"filled\":{\"거래처\":\"단국문구\"},\"missing\":[]}");

        FillFieldsRequest request = new FillFieldsRequest();
        ReflectionTestUtils.setField(request, "formIds", List.of(30L));

        // when
        FillFieldsResponse response = evidenceService.fillFields(3L, request);

        // then
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        assertThat(result.getFilledFields()).containsEntry("거래처", "단국문구");
        assertThat(result.getMissingFields()).isEmpty();
    }

    @Test
    void fillFields_지출자_지출인_필드는_그룹등록정보로_채우고_영수증추출_제외() throws Exception {
        // given — 그룹에 지출인 정보가 등록되어 있음
        Path evidenceFile = tempDir.resolve("evidence4.pdf");
        Files.write(evidenceFile, "dummy".getBytes());

        UserGroup group = UserGroup.builder()
                .payerName("홍길동")
                .payerAffiliation("소프트웨어학과")
                .payerStudentId("32200000")
                .build();

        Evidence evidence = Evidence.builder()
                .filePath(evidenceFile.toString())
                .fileName("evidence4.pdf")
                .group(group)
                .build();

        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"지출인 성명\",\"지출자 소속\",\"금액\"]")
                .generatedFields("[]")
                .build();

        when(evidenceRepository.findById(4L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(40L)).thenReturn(Optional.of(form));
        // 영수증 IE에는 "금액"만 전달되어야 함 (지출자/지출인은 그룹 정보로 이미 채워짐)
        when(evidenceAiService.fillFormFields(any(), any(), eq(List.of("금액"))))
                .thenReturn("{\"filled\":{\"금액\":\"50000\"},\"missing\":[]}");

        FillFieldsRequest request = new FillFieldsRequest();
        ReflectionTestUtils.setField(request, "formIds", List.of(40L));

        // when
        FillFieldsResponse response = evidenceService.fillFields(4L, request);

        // then
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getFilledFields()).containsEntry("지출인 성명", "홍길동");
        assertThat(result.getFilledFields()).containsEntry("지출자 소속", "소프트웨어학과");
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        assertThat(result.getMissingFields()).isEmpty();
        // 핵심: 지출자/지출인은 영수증 IE 호출 대상에서 빠지고 "금액"만 전달됨
        verify(evidenceAiService).fillFormFields(any(), any(), eq(List.of("금액")));
    }

    @Test
    void fillFields_검토자_필드는_항상_미입력이고_영수증추출_제외() throws Exception {
        // given
        Path evidenceFile = tempDir.resolve("evidence5.pdf");
        Files.write(evidenceFile, "dummy".getBytes());

        UserGroup group = UserGroup.builder().payerName("홍길동").build();

        Evidence evidence = Evidence.builder()
                .filePath(evidenceFile.toString())
                .fileName("evidence5.pdf")
                .group(group)
                .build();

        // "검토자 소속"은 "소속"을 포함하지만 검토자 판정이 우선이라 지출인 소속으로 채워지면 안 됨
        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"검토자\",\"검토자 소속\",\"금액\"]")
                .generatedFields("[]")
                .build();

        when(evidenceRepository.findById(5L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(50L)).thenReturn(Optional.of(form));
        when(evidenceAiService.fillFormFields(any(), any(), eq(List.of("금액"))))
                .thenReturn("{\"filled\":{\"금액\":\"50000\"},\"missing\":[]}");

        FillFieldsRequest request = new FillFieldsRequest();
        ReflectionTestUtils.setField(request, "formIds", List.of(50L));

        // when
        FillFieldsResponse response = evidenceService.fillFields(5L, request);

        // then
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getMissingFields()).contains("검토자", "검토자 소속");
        assertThat(result.getFilledFields()).doesNotContainKey("검토자 소속");
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        // 검토자 필드는 영수증 IE 호출 대상에서 빠지고 "금액"만 전달됨
        verify(evidenceAiService).fillFormFields(any(), any(), eq(List.of("금액")));
    }

    @Test
    void fillFields_그룹에_지출인정보_없으면_미입력이고_영수증추출_제외() throws Exception {
        // given — 그룹은 있으나 지출인 정보 미등록
        Path evidenceFile = tempDir.resolve("evidence6.pdf");
        Files.write(evidenceFile, "dummy".getBytes());

        UserGroup group = UserGroup.builder().build();

        Evidence evidence = Evidence.builder()
                .filePath(evidenceFile.toString())
                .fileName("evidence6.pdf")
                .group(group)
                .build();

        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"지출인 성명\",\"금액\"]")
                .generatedFields("[]")
                .build();

        when(evidenceRepository.findById(6L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(60L)).thenReturn(Optional.of(form));
        when(evidenceAiService.fillFormFields(any(), any(), eq(List.of("금액"))))
                .thenReturn("{\"filled\":{\"금액\":\"50000\"},\"missing\":[]}");

        FillFieldsRequest request = new FillFieldsRequest();
        ReflectionTestUtils.setField(request, "formIds", List.of(60L));

        // when
        FillFieldsResponse response = evidenceService.fillFields(6L, request);

        // then — 등록 정보가 없으므로 영수증에서 찾지 않고 미입력으로 표시
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getMissingFields()).contains("지출인 성명");
        assertThat(result.getFilledFields()).doesNotContainKey("지출인 성명");
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        verify(evidenceAiService).fillFormFields(any(), any(), eq(List.of("금액")));
    }
}
