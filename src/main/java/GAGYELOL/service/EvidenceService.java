package GAGYELOL.service;

import GAGYELOL.dto.CompleteFormRequest;
import GAGYELOL.dto.EvidenceAnalysisResponse;
import GAGYELOL.dto.EvidenceResponse;
import GAGYELOL.dto.FillFieldsRequest;
import GAGYELOL.dto.FillFieldsResponse;
import GAGYELOL.entity.Evidence;
import GAGYELOL.entity.Form;
import GAGYELOL.entity.User;
import GAGYELOL.entity.UserGroup;
import GAGYELOL.entity.EvidenceForm;
import GAGYELOL.repository.ApprovalRequestRepository;
import GAGYELOL.repository.EvidenceFormRepository;
import GAGYELOL.repository.EvidenceRepository;
import GAGYELOL.repository.FormRepository;
import GAGYELOL.repository.PolicyChunkVectorStore;
import GAGYELOL.repository.UserGroupRepository;
import GAGYELOL.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class EvidenceService {

    private final EvidenceRepository evidenceRepository;
    private final EvidenceFormRepository evidenceFormRepository;
    private final ApprovalRequestRepository approvalRequestRepository;
    private final FormRepository formRepository;
    private final UserRepository userRepository;
    private final UserGroupRepository groupRepository;
    private final PolicyChunkVectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final EvidenceAiService evidenceAiService;
    private final FormAiService formAiService;
    private final FormFillService formFillService;
    private final ObjectMapper objectMapper;

    @Value("${file.upload.evidence-dir:./uploads/evidence}")
    private String uploadDir;

    private static final int TOP_K = 5;

    @Transactional(readOnly = true)
    public List<GAGYELOL.dto.EvidenceListResponse> listByGroup(Long groupId, String statusFilter) {
        if (groupId == null) return List.of();
        UserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("그룹을 찾을 수 없습니다: " + groupId));
        return evidenceRepository.findByGroupOrderByCreatedAtDesc(group).stream()
                .map(e -> {
                    var approval = approvalRequestRepository.findFirstByEvidenceOrderByCreatedAtDesc(e);
                    String status = approval.map(a -> a.getStatus().toLowerCase()).orElse("draft");
                    java.time.LocalDateTime updatedAt = approval
                            .map(a -> a.getUpdatedAt() != null ? a.getUpdatedAt() : e.getCreatedAt())
                            .orElse(e.getCreatedAt());
                    return GAGYELOL.dto.EvidenceListResponse.builder()
                            .evidenceId(e.getId())
                            .businessName(e.getBusinessName())
                            .title(e.getBusinessName())
                            .status(status)
                            .totalAmount(null)
                            .itemCount(null)
                            .fileType(resolveFileType(e.getFileName()))
                            .createdAt(e.getCreatedAt())
                            .updatedAt(updatedAt)
                            .build();
                })
                .filter(r -> statusFilter == null || statusFilter.equalsIgnoreCase(r.getStatus()))
                .toList();
    }

    private String resolveFileType(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))  return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "XLSX";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "JPG";
        if (lower.endsWith(".png"))  return "PNG";
        return null;
    }

    @Transactional(readOnly = true)
    public List<EvidenceResponse> getByGroup(Long groupId) {
        if (groupId == null) return List.of();
        UserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("그룹을 찾을 수 없습니다: " + groupId));
        return evidenceRepository.findByGroupOrderByCreatedAtDesc(group).stream()
                .map(e -> EvidenceResponse.from(e, evidenceFormRepository.findFormIdsByEvidenceId(e.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public EvidenceResponse getById(Long evidenceId) {
        Evidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new IllegalArgumentException("증빙서류를 찾을 수 없습니다: " + evidenceId));
        return EvidenceResponse.from(evidence, evidenceFormRepository.findFormIdsByEvidenceId(evidenceId));
    }

    public void delete(Long evidenceId) {
        Evidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new IllegalArgumentException("증빙서류를 찾을 수 없습니다: " + evidenceId));

        // 결재 요청에서 참조 중이면 삭제 거부 (FK 무결성 + 감사 보존)
        long approvalCount = approvalRequestRepository.countByEvidence(evidence);
        if (approvalCount > 0) {
            throw new IllegalArgumentException(
                    "해당 증빙서류는 결재 요청에서 사용 중이므로 삭제할 수 없습니다. (결재 요청 " + approvalCount + "건)");
        }

        evidenceFormRepository.deleteByEvidenceId(evidenceId);
        evidenceRepository.delete(evidence);

        // 업로드 파일 제거 (DB 삭제 성공 후에만 시도)
        try {
            Files.deleteIfExists(Paths.get(evidence.getFilePath()));
        } catch (IOException e) {
            log.warn("증빙서류 파일 삭제 실패 (evidenceId={}, path={}): {}",
                    evidenceId, evidence.getFilePath(), e.getMessage());
        }
        log.info("증빙서류 삭제 완료 - evidenceId={}", evidenceId);
    }

    public EvidenceAnalysisResponse analyze(MultipartFile file, Long userId, Long groupId,
                                            String businessName, MultipartFile recipientImage) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        UserGroup group = groupId != null
                ? groupRepository.findById(groupId).orElseThrow(() -> new IllegalArgumentException("그룹을 찾을 수 없습니다: " + groupId))
                : null;

        Long policyId = (group != null && group.getActivePolicy() != null)
                ? group.getActivePolicy().getId()
                : null;
        if (policyId == null) {
            log.warn("그룹에 active_policy_id가 설정되지 않았습니다. RAG 검색 없이 진행합니다.");
        }

        // 1. 증빙 파일 저장
        String filePath = saveFile(file);
        String fileName = file.getOriginalFilename();
        log.info("증빙서류 저장 완료: {}", filePath);

        // 2. 수령인 사진 저장 (선택)
        String recipientImagePath = null;
        if (recipientImage != null && !recipientImage.isEmpty()) {
            recipientImagePath = saveFile(recipientImage);
            log.info("수령인 사진 저장 완료: {}", recipientImagePath);
        }

        // 3. Upstage IE로 결제 유형 분류
        String mimeType = resolveMimeType(fileName);
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("파일 읽기 실패", e);
        }
        String paymentType = evidenceAiService.classifyPaymentType(fileBytes, mimeType);
        log.info("결제 유형 분류 결과: {}", paymentType);

        // 4. 증빙서류 저장 (사업명 + 수령인 사진 경로 포함)
        Evidence evidence = evidenceRepository.save(Evidence.builder()
                .user(user)
                .group(group)
                .policyId(policyId)
                .filePath(filePath)
                .fileName(fileName)
                .extractedText("")
                .businessName(businessName)
                .recipientImagePath(recipientImagePath)
                .build());

        // 5. 결제 유형에 맞는 양식지 목록 반환
        List<Form> forms = group != null
                ? formRepository.findByGroupAndPaymentTypeIn(group, List.of(paymentType, "BOTH"))
                : formRepository.findByPaymentTypeIn(List.of(paymentType, "BOTH"));
        log.info("양식지 {}개 로드 완료 (결제유형: {})", forms.size(), paymentType);

        List<EvidenceAnalysisResponse.FormSummary> formSummaries = forms.stream()
                .map(f -> {
                    List<String> fields = List.of();
                    try { fields = objectMapper.readValue(f.getFormFields(), new TypeReference<>() {}); }
                    catch (Exception ignored) {}
                    double score = f.getPaymentType().equalsIgnoreCase(paymentType) ? 1.0 : 0.8;
                    return EvidenceAnalysisResponse.FormSummary.builder()
                            .formId(f.getId())
                            .formName(f.getFormName())
                            .description(f.getDescription())
                            .paymentType(f.getPaymentType())
                            .fields(fields)
                            .matchScore(score)
                            .build();
                })
                .sorted(Comparator.comparingDouble(EvidenceAnalysisResponse.FormSummary::getMatchScore).reversed())
                .toList();

        return EvidenceAnalysisResponse.builder()
                .evidenceId(evidence.getId())
                .paymentType(paymentType)
                .extractedText("")
                .availableForms(formSummaries)
                .build();
    }

    private String resolveMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".bmp"))  return "image/bmp";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (lower.endsWith(".hwp"))  return "application/x-hwp";
        if (lower.endsWith(".hwpx")) return "application/hwpx";
        throw new IllegalArgumentException("지원하지 않는 파일 형식입니다: " + fileName);
    }

    /**
     * 사용자가 선택한 양식지들의 필드를 증빙서류 내용으로 자동 채웁니다.
     */
    public FillFieldsResponse fillFields(Long evidenceId, FillFieldsRequest request) {
        Evidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new IllegalArgumentException("증빙서류를 찾을 수 없습니다: " + evidenceId));

        // 선택된 양식지 저장 (evidence_forms 테이블)
        evidenceFormRepository.deleteByEvidenceId(evidenceId);
        for (Long formId : request.getFormIds()) {
            Form selectedForm = formRepository.findById(formId)
                    .orElseThrow(() -> new IllegalArgumentException("양식지를 찾을 수 없습니다: " + formId));
            evidenceFormRepository.save(EvidenceForm.builder()
                    .id(new EvidenceForm.EvidenceFormId(evidenceId, formId))
                    .evidence(evidence)
                    .form(selectedForm)
                    .build());
        }

        List<FillFieldsResponse.FormFillResult> results = new ArrayList<>();

        for (Long formId : request.getFormIds()) {
            Form form = formRepository.findById(formId)
                    .orElseThrow(() -> new IllegalArgumentException("양식지를 찾을 수 없습니다: " + formId));

            // 양식지가 증빙서류와 같은 그룹 소속인지 검증
            if (evidence.getGroup() != null && form.getGroup() != null
                    && !form.getGroup().getId().equals(evidence.getGroup().getId())) {
                throw new IllegalArgumentException(
                        "양식지(id=" + formId + ")가 해당 그룹 소속이 아닙니다.");
            }

            List<String> formFields;
            Set<String> generatedFieldSet;
            try {
                formFields = objectMapper.readValue(form.getFormFields(), new TypeReference<>() {});
                List<String> gfList = objectMapper.readValue(
                        form.getGeneratedFields() != null ? form.getGeneratedFields() : "[]",
                        new TypeReference<>() {});
                generatedFieldSet = new HashSet<>(gfList);
            } catch (Exception e) {
                log.warn("양식지 {} 필드 파싱 실패, 건너뜀", formId);
                continue;
            }

            Map<String, String> filledFields = new LinkedHashMap<>();
            List<String> missingFields = new ArrayList<>();

            // [경로 0] 인적 필드(지출자/지출인/검토자)는 영수증에서 추출하지 않고
            //          로그인/그룹 등록 정보로만 채운다. 못 채우면 미입력으로 표시한다.
            //   - 지출자/지출인: 그룹 등록 지출인 정보(UserGroup.payer*)로 채우고, 없으면 missing
            //   - 검토자: 등록된 사람 정보가 없으므로 항상 missing (검토자가 소속/학번을 포함해도 지출인 값으로 채우지 않도록 먼저 검사)
            List<String> remainingAfterPayer = new ArrayList<>();
            UserGroup group = evidence.getGroup();
            for (String field : formFields) {
                if (isReviewerField(field)) {
                    missingFields.add(field);
                    log.info("검토자 필드 - 등록 정보 없음, 미입력 처리: {}", field);
                } else if (isPayerField(field)) {
                    String payerValue = resolvePayerField(field, group);
                    if (payerValue != null && !payerValue.isBlank()) {
                        filledFields.put(field, payerValue);
                        log.info("지출자/지출인 정보 채우기: {} = {}", field, payerValue);
                    } else {
                        missingFields.add(field);
                        log.info("지출자/지출인 필드 - 등록 정보 없음, 미입력 처리: {}", field);
                    }
                } else {
                    remainingAfterPayer.add(field);
                }
            }

            // [경로 1] generatedFields → 사업명 기반 LLM 생성
            String businessName = evidence.getBusinessName();
            List<String> directFields = new ArrayList<>();
            for (String field : remainingAfterPayer) {
                if (generatedFieldSet.contains(field)) {
                    if (businessName != null && !businessName.isBlank()) {
                        String groupDescription = (group != null) ? group.getDescription() : null;
                        String content = formAiService.generateFieldContent(businessName, groupDescription, field);
                        filledFields.put(field, content);
                        log.info("LLM 생성 필드: {} = {}", field, content);
                    } else {
                        missingFields.add(field);
                    }
                } else {
                    directFields.add(field);
                }
            }

            // [경로 2] 증빙 파일에서 Upstage IE 추출
            if (!directFields.isEmpty()) {
                byte[] evidenceBytes;
                try {
                    evidenceBytes = Files.readAllBytes(Paths.get(evidence.getFilePath()));
                } catch (IOException e) {
                    throw new RuntimeException("증빙서류 파일 읽기 실패: " + e.getMessage(), e);
                }
                String evidenceMimeType = resolveMimeType(evidence.getFileName());

                String ieResult = evidenceAiService.fillFormFields(evidenceBytes, evidenceMimeType, directFields);
                log.info("증빙서류 IE 결과 (formId={}): {}", formId, ieResult);

                List<String> stillMissing = new ArrayList<>();
                try {
                    JsonNode node = objectMapper.readTree(ieResult);
                    node.path("filled").fields().forEachRemaining(e -> filledFields.put(e.getKey(), e.getValue().asText()));
                    node.path("missing").forEach(n -> stillMissing.add(n.asText()));
                } catch (Exception e) {
                    log.warn("증빙서류 IE 결과 파싱 실패 (formId={}): {}", formId, e.getMessage());
                    stillMissing.addAll(directFields);
                }

                // [경로 3] 증빙에서 못 찾은 필드 → 수령인 사진에서 추출 시도
                String recipientImagePath = evidence.getRecipientImagePath();
                if (!stillMissing.isEmpty() && recipientImagePath != null) {
                    try {
                        byte[] recipientBytes = Files.readAllBytes(Paths.get(recipientImagePath));
                        String recipientFileName = Paths.get(recipientImagePath).getFileName().toString();
                        String recipientMimeType = resolveMimeType(recipientFileName);
                        String recipientResult = evidenceAiService.fillFormFields(recipientBytes, recipientMimeType, stillMissing);
                        log.info("수령인 사진 IE 결과 (formId={}): {}", formId, recipientResult);

                        List<String> finalMissing = new ArrayList<>();
                        JsonNode rNode = objectMapper.readTree(recipientResult);
                        rNode.path("filled").fields().forEachRemaining(e -> filledFields.put(e.getKey(), e.getValue().asText()));
                        rNode.path("missing").forEach(n -> finalMissing.add(n.asText()));
                        missingFields.addAll(finalMissing);
                    } catch (Exception e) {
                        log.warn("수령인 사진 IE 실패 (formId={}): {}", formId, e.getMessage());
                        missingFields.addAll(stillMissing);
                    }
                } else {
                    missingFields.addAll(stillMissing);
                }
            }

            results.add(FillFieldsResponse.FormFillResult.builder()
                    .formId(formId)
                    .formName(form.getFormName())
                    .filledFields(filledFields)
                    .missingFields(missingFields)
                    .build());
        }

        return FillFieldsResponse.builder()
                .evidenceId(evidenceId)
                .results(results)
                .build();
    }

    /**
     * 자동 채운 값 + 사용자 입력을 합쳐 최종 양식지 파일을 생성합니다.
     * 양식지가 1개면 단일 파일, 여러 개면 ZIP으로 반환합니다.
     */
    public byte[] completeForm(Long evidenceId, CompleteFormRequest request) {
        Evidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new IllegalArgumentException("증빙서류를 찾을 수 없습니다: " + evidenceId));

        // form이 evidence와 같은 그룹 소속인지 검증
        for (CompleteFormRequest.FormInput input : request.getForms()) {
            Form form = formRepository.findById(input.getFormId())
                    .orElseThrow(() -> new IllegalArgumentException("양식지를 찾을 수 없습니다: " + input.getFormId()));
            if (evidence.getGroup() != null && form.getGroup() != null
                    && !form.getGroup().getId().equals(evidence.getGroup().getId())) {
                throw new IllegalArgumentException("양식지(id=" + input.getFormId() + ")가 해당 그룹 소속이 아닙니다.");
            }
        }

        List<CompleteFormRequest.FormInput> formInputs = request.getForms();

        if (formInputs.size() == 1) {
            return generateSingleFile(evidence, formInputs.get(0));
        }
        return generateZip(evidence, formInputs);
    }

    /**
     * 단일 파일 생성 시 사용할 출력 파일명을 반환합니다. (Controller에서 Content-Disposition 헤더에 사용)
     */
    public String resolveOutputFilename(CompleteFormRequest request) {
        if (request.getForms().size() > 1) return "completed_forms.zip";
        Long formId = request.getForms().get(0).getFormId();
        Form form = formRepository.findById(formId).orElse(null);
        if (form == null) return "completed_form.docx";
        String lower = form.getFilePath().toLowerCase();
        if (lower.endsWith(".xlsx")) return "completed_form.xlsx";
        if (lower.endsWith(".xls"))  return "completed_form.xls";
        return "completed_form.docx";
    }

    private byte[] generateSingleFile(Evidence evidence, CompleteFormRequest.FormInput input) {
        Form form = formRepository.findById(input.getFormId())
                .orElseThrow(() -> new IllegalArgumentException("양식지를 찾을 수 없습니다: " + input.getFormId()));
        Map<String, String> allFields = mergeFields(input);
        Map<String, byte[]> imageBytesMap = resolveImageBytes(evidence, input.getImageFields());
        Set<String> generatedFields = parseGeneratedFields(form);
        log.info("양식지 최종 완성 - formId={}, 필드 수={}, 이미지 수={}, generatedFields={}",
                form.getId(), allFields.size(), imageBytesMap.size(), generatedFields);
        try {
            return formFillService.fill(form.getFilePath(), allFields, imageBytesMap, generatedFields);
        } catch (IOException e) {
            throw new RuntimeException("양식지 파일 생성 실패: " + e.getMessage(), e);
        }
    }

    private byte[] generateZip(Evidence evidence, List<CompleteFormRequest.FormInput> formInputs) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(baos)) {

            for (CompleteFormRequest.FormInput input : formInputs) {
                Form form = formRepository.findById(input.getFormId())
                        .orElseThrow(() -> new IllegalArgumentException("양식지를 찾을 수 없습니다: " + input.getFormId()));
                Map<String, String> allFields = mergeFields(input);
                Map<String, byte[]> imageBytesMap = resolveImageBytes(evidence, input.getImageFields());
                Set<String> generatedFields = parseGeneratedFields(form);
                byte[] fileBytes = formFillService.fill(form.getFilePath(), allFields, imageBytesMap, generatedFields);

                String ext = form.getFilePath().substring(form.getFilePath().lastIndexOf('.'));
                zip.putNextEntry(new ZipEntry(form.getFormName() + ext));
                zip.write(fileBytes);
                zip.closeEntry();
                log.info("ZIP 항목 추가: {}", form.getFormName());
            }
            zip.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("ZIP 생성 실패: " + e.getMessage(), e);
        }
    }

    /**
     * imageFields 맵의 값("evidence", "recipientImage", 또는 파일 경로)을 실제 이미지 바이트로 변환합니다.
     * 이미지 타입이 아닌 증빙서류(DOCX, XLSX 등)는 "evidence" 소스에서 제외됩니다.
     */
    private Map<String, byte[]> resolveImageBytes(Evidence evidence, Map<String, String> imageFields) {
        if (imageFields == null || imageFields.isEmpty()) return Map.of();
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : imageFields.entrySet()) {
            String fieldName = entry.getKey();
            String source = entry.getValue();
            if (source == null || source.isBlank()) continue;
            try {
                byte[] bytes = null;
                if ("evidence".equalsIgnoreCase(source)) {
                    String mimeType = resolveMimeType(evidence.getFileName());
                    if (mimeType.startsWith("image/")) {
                        bytes = Files.readAllBytes(Paths.get(evidence.getFilePath()));
                    } else {
                        log.warn("증빙서류가 이미지 형식이 아니므로 '{}' 필드 이미지로 사용 불가: {}", fieldName, evidence.getFileName());
                    }
                } else if ("recipientImage".equalsIgnoreCase(source)) {
                    if (evidence.getRecipientImagePath() != null) {
                        bytes = Files.readAllBytes(Paths.get(evidence.getRecipientImagePath()));
                    }
                } else {
                    bytes = Files.readAllBytes(Paths.get(source));
                }
                if (bytes != null) result.put(fieldName, bytes);
            } catch (IOException e) {
                log.warn("이미지 파일 읽기 실패 - field={}, source={}: {}", fieldName, source, e.getMessage());
            }
        }
        return result;
    }

    private Map<String, String> mergeFields(CompleteFormRequest.FormInput input) {
        Map<String, String> allFields = new LinkedHashMap<>();
        if (input.getFilledFields() != null) allFields.putAll(input.getFilledFields());
        if (input.getUserInputFields() != null) allFields.putAll(input.getUserInputFields());
        allFields.replaceAll((key, value) -> key.contains("날짜") ? normalizeDateValue(value) : value);
        return allFields;
    }

    private String normalizeDateValue(String value) {
        if (value == null || value.isBlank()) return value;
        Pattern full = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
        Matcher m = full.matcher(value);
        if (m.find()) {
            String yy = m.group(1).substring(2);
            String mm = String.format("%02d", Integer.parseInt(m.group(2)));
            String dd = String.format("%02d", Integer.parseInt(m.group(3)));
            return yy + "/" + mm + "/" + dd;
        }
        Pattern korean = Pattern.compile("(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일");
        m = korean.matcher(value);
        if (m.find()) {
            String yy = m.group(1).substring(2);
            String mm = String.format("%02d", Integer.parseInt(m.group(2)));
            String dd = String.format("%02d", Integer.parseInt(m.group(3)));
            return yy + "/" + mm + "/" + dd;
        }
        return value;
    }

    private Set<String> parseGeneratedFields(Form form) {
        String raw = form.getGeneratedFields();
        if (raw == null || raw.isBlank()) return Collections.emptySet();
        try {
            List<String> list = objectMapper.readValue(raw, new TypeReference<>() {});
            return new HashSet<>(list);
        } catch (Exception e) {
            log.warn("Form {}의 generatedFields 파싱 실패, 빈 set으로 진행: {}", form.getId(), e.getMessage());
            return Collections.emptySet();
        }
    }

    /** 지출자/지출인 인적 필드인지 판별. 이 필드들은 영수증이 아니라 그룹 등록 정보로만 채운다. */
    private static boolean isPayerField(String field) {
        return field.contains("지출인") || field.contains("지출자");
    }

    /** 검토자 인적 필드인지 판별. 등록된 사람 정보가 없어 항상 미입력으로 표시한다. */
    private static boolean isReviewerField(String field) {
        return field.contains("검토자");
    }

    private String resolvePayerField(String field, UserGroup group) {
        if (group == null || !isPayerField(field)) return null;
        if (field.contains("이름") || field.contains("성명")) return group.getPayerName();
        if (field.contains("소속")) return group.getPayerAffiliation();
        if (field.contains("학번") || field.contains("사번")) return group.getPayerStudentId();
        if (field.contains("전화") || field.contains("연락")) return group.getPayerPhone();
        return null;
    }

    private String buildFormListDescription(List<Form> forms) {
        StringBuilder sb = new StringBuilder();
        for (Form form : forms) {
            sb.append(String.format("formId=%d, 양식명: %s, 용도: %s, 필드: %s\n",
                    form.getId(), form.getFormName(),
                    form.getDescription(), form.getFormFields()));
        }
        return sb.toString();
    }

    private String saveFile(MultipartFile file) {
        try {
            Path dirPath = Paths.get(uploadDir);
            Files.createDirectories(dirPath);
            String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = dirPath.resolve(filename);
            Files.write(filePath, file.getBytes());
            return filePath.toString();
        } catch (IOException e) {
            throw new RuntimeException("파일 저장 실패", e);
        }
    }
}
