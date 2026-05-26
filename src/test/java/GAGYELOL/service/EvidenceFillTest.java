package GAGYELOL.service;

import GAGYELOL.dto.CompleteFormRequest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock GroupMemberRepository groupMemberRepository;

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
        when(formAiService.generateFieldContent(eq("단국대 캡스톤 프로젝트"), isNull(), eq("내용"), any()))
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
        verify(formAiService).generateFieldContent(eq("단국대 캡스톤 프로젝트"), isNull(), eq("내용"), any());
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
        verify(formAiService, never()).generateFieldContent(any(), any(), any(), any());
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

        // "지출자"는 단독이면 이름으로, "지출자 소속"은 소속으로 채워진다
        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"지출인 성명\",\"지출자 소속\",\"지출자\",\"금액\"]")
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
        assertThat(result.getFilledFields()).containsEntry("지출자", "홍길동");
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        assertThat(result.getMissingFields()).isEmpty();
        // 핵심: 지출자/지출인은 영수증 IE 호출 대상에서 빠지고 "금액"만 전달됨
        verify(evidenceAiService).fillFormFields(any(), any(), eq(List.of("금액")));
    }

    @Test
    void fillFields_검토자는_회장직급_멤버이름으로_채우고_영수증추출_제외() throws Exception {
        // given — 그룹에 회장 직급 멤버가 등록되어 있음 (#4)
        Path evidenceFile = tempDir.resolve("evidence5.pdf");
        Files.write(evidenceFile, "dummy".getBytes());

        UserGroup group = UserGroup.builder()
                .payerName("홍길동")
                .payerAffiliation("소프트웨어학과")
                .build();

        Evidence evidence = Evidence.builder()
                .filePath(evidenceFile.toString())
                .fileName("evidence5.pdf")
                .group(group)
                .build();

        // 회장 직급 멤버("김회장")가 검토자로 채워진다
        GroupMember president = GroupMember.builder()
                .user(User.builder().name("김회장").build())
                .role(GroupRole.builder().roleName("회장").approvalOrder(2).build())
                .build();

        // "검토자"는 회장 직급 멤버 이름, "검토자 소속"은 (지출인) 소속으로 채워진다
        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"검토자\",\"검토자 소속\",\"금액\"]")
                .generatedFields("[]")
                .build();

        when(evidenceRepository.findById(5L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(50L)).thenReturn(Optional.of(form));
        when(groupMemberRepository.findByGroup(group)).thenReturn(List.of(president));
        when(evidenceAiService.fillFormFields(any(), any(), eq(List.of("금액"))))
                .thenReturn("{\"filled\":{\"금액\":\"50000\"},\"missing\":[]}");

        FillFieldsRequest request = new FillFieldsRequest();
        ReflectionTestUtils.setField(request, "formIds", List.of(50L));

        // when
        FillFieldsResponse response = evidenceService.fillFields(5L, request);

        // then — 검토자는 회장 직급 멤버 이름으로 채워진다
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getFilledFields()).containsEntry("검토자", "김회장");
        assertThat(result.getFilledFields()).containsEntry("검토자 소속", "소프트웨어학과");
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        assertThat(result.getMissingFields()).isEmpty();
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
                .formFields("[\"지출인 성명\",\"검토자\",\"금액\"]")
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

        // then — 등록 정보가 없으므로 영수증에서 찾지 않고 미입력으로 표시 (지출인/검토자 모두)
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getMissingFields()).contains("지출인 성명", "검토자");
        assertThat(result.getFilledFields()).doesNotContainKey("지출인 성명");
        assertThat(result.getFilledFields()).doesNotContainKey("검토자");
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        verify(evidenceAiService).fillFormFields(any(), any(), eq(List.of("금액")));
    }

    @Test
    void fillFields_사업명필드는_입력받은_사업명으로_채우고_영수증추출_제외() throws Exception {
        // given — 사용자가 입력한 사업명이 있음
        Path evidenceFile = tempDir.resolve("evidence7.pdf");
        Files.write(evidenceFile, "dummy".getBytes());

        Evidence evidence = Evidence.builder()
                .filePath(evidenceFile.toString())
                .fileName("evidence7.pdf")
                .businessName("단국대 캡스톤 프로젝트")
                .build();

        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"사업명\",\"금액\"]")
                .generatedFields("[]")
                .build();

        when(evidenceRepository.findById(7L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(70L)).thenReturn(Optional.of(form));
        // 영수증 IE에는 "금액"만 전달되어야 함 (사업명은 입력값으로 이미 채워짐)
        when(evidenceAiService.fillFormFields(any(), any(), eq(List.of("금액"))))
                .thenReturn("{\"filled\":{\"금액\":\"50000\"},\"missing\":[]}");

        FillFieldsRequest request = new FillFieldsRequest();
        ReflectionTestUtils.setField(request, "formIds", List.of(70L));

        // when
        FillFieldsResponse response = evidenceService.fillFields(7L, request);

        // then — 사업명은 영수증 OCR이 아니라 입력값으로 채워진다
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getFilledFields()).containsEntry("사업명", "단국대 캡스톤 프로젝트");
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        assertThat(result.getMissingFields()).isEmpty();
        // 핵심: 사업명 필드는 영수증 IE 호출 대상에서 빠지고 "금액"만 전달됨
        verify(evidenceAiService).fillFormFields(any(), any(), eq(List.of("금액")));
    }

    @Test
    void fillFields_사업명_입력없으면_사업명필드_미입력() throws Exception {
        // given — 입력 사업명이 없음
        Path evidenceFile = tempDir.resolve("evidence8.pdf");
        Files.write(evidenceFile, "dummy".getBytes());

        Evidence evidence = Evidence.builder()
                .filePath(evidenceFile.toString())
                .fileName("evidence8.pdf")
                .businessName(null)
                .build();

        Form form = Form.builder()
                .formName("지출결의서")
                .formFields("[\"사업명\",\"금액\"]")
                .generatedFields("[]")
                .build();

        when(evidenceRepository.findById(8L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(80L)).thenReturn(Optional.of(form));
        when(evidenceAiService.fillFormFields(any(), any(), eq(List.of("금액"))))
                .thenReturn("{\"filled\":{\"금액\":\"50000\"},\"missing\":[]}");

        FillFieldsRequest request = new FillFieldsRequest();
        ReflectionTestUtils.setField(request, "formIds", List.of(80L));

        // when
        FillFieldsResponse response = evidenceService.fillFields(8L, request);

        // then — 사업명은 미입력으로 표시되고 영수증 OCR로 추출하지 않음
        FillFieldsResponse.FormFillResult result = response.getResults().get(0);
        assertThat(result.getMissingFields()).contains("사업명");
        assertThat(result.getFilledFields()).doesNotContainKey("사업명");
        assertThat(result.getFilledFields()).containsEntry("금액", "50000");
        verify(evidenceAiService).fillFormFields(any(), any(), eq(List.of("금액")));
    }

    @Test
    void completeForm_양식파일이_없으면_재업로드_안내_에러() {
        // given — form.filePath가 존재하지 않는 파일을 가리킴 (재배포로 ephemeral 스토리지가 초기화된 상황)
        Form form = Form.builder()
                .formName("지출 기록부")
                .formFields("[\"금액\"]")
                .generatedFields("[]")
                .filePath(tempDir.resolve("사라진양식.docx").toString())
                .build();

        Evidence evidence = Evidence.builder()
                .fileName("evidence9.pdf")
                .build();

        when(evidenceRepository.findById(9L)).thenReturn(Optional.of(evidence));
        when(formRepository.findById(90L)).thenReturn(Optional.of(form));

        CompleteFormRequest.FormInput input = new CompleteFormRequest.FormInput();
        ReflectionTestUtils.setField(input, "formId", 90L);
        CompleteFormRequest request = new CompleteFormRequest();
        ReflectionTestUtils.setField(request, "forms", List.of(input));

        // when / then — 원본 경로를 노출하는 대신 재업로드를 안내하는 명확한 메시지
        assertThatThrownBy(() -> evidenceService.completeForm(9L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("다시 업로드");
    }
}
