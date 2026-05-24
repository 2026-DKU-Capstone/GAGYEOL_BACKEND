package GAGYELOL.service;

import GAGYELOL.dto.FormUploadResponse;
import GAGYELOL.entity.Form;
import GAGYELOL.entity.User;
import GAGYELOL.entity.UserGroup;
import GAGYELOL.repository.FormRepository;
import GAGYELOL.repository.GroupMemberRepository;
import GAGYELOL.repository.UserRepository;
import GAGYELOL.repository.PolicyChunkVectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class FormService {

    private final FormRepository formRepository;
    private final UserRepository userRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final FormParserService formParserService;
    private final FormAiService formAiService;
    private final EmbeddingService embeddingService;
    private final PolicyChunkVectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final GAGYELOL.repository.UserGroupRepository groupRepository;
    private final GroupService groupService;
    private final GAGYELOL.repository.EvidenceFormRepository evidenceFormRepository;
    private final GAGYELOL.repository.ApprovalRequestRepository approvalRequestRepository;

    @Value("${file.upload.form-dir:./uploads/forms}")
    private String uploadDir;

    public FormUploadResponse upload(MultipartFile file, String formName, Long userId, String paymentType, Long policyId, Long groupId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        GAGYELOL.entity.UserGroup group = groupId != null
                ? groupRepository.findById(groupId).orElseThrow(() -> new IllegalArgumentException("그룹을 찾을 수 없습니다.")) : null;

        if (group != null) groupService.assertMaxRole(user, group);

        // 1. 파일 저장
        String filePath = saveFile(file);
        log.info("양식지 파일 저장 완료: {}", filePath);

        // 2. 양식지 텍스트 추출
        String text;
        try {
            text = formParserService.extractText(new File(filePath));
        } catch (IOException e) {
            throw new RuntimeException("양식지 텍스트 추출 실패", e);
        }
        log.info("텍스트 추출 완료 - {}자", text.length());

        // 3. 규정책 RAG 검색 (policyId가 있는 경우)
        String policyChunks = searchPolicyChunks(text, policyId);

        // 4. GPT로 양식지 분석 (규정 내용 포함)
        String analysisJson = formAiService.analyzeForm(text, policyChunks);
        log.info("양식지 분석 완료: {}", analysisJson);

        String description = "";
        String formFields = "[]";
        String generatedFields = "[]";
        String aiPaymentType = null;
        try {
            JsonNode node = objectMapper.readTree(analysisJson);
            description = node.path("description").asText();
            aiPaymentType = node.has("paymentType") ? node.path("paymentType").asText() : null;
            formFields = node.path("fields").toString();
            generatedFields = node.has("generatedFields") ? node.path("generatedFields").toString() : "[]";
        } catch (Exception e) {
            log.warn("양식지 분석 결과 파싱 실패, 원본 저장: {}", e.getMessage());
        }

        // 4. Form 저장 (AI 분류 우선, 없으면 요청 파라미터 사용)
        String normalizedType = normalizePaymentType(aiPaymentType != null ? aiPaymentType : paymentType);
        log.info("결제유형 분류 - AI={}, 최종={}", aiPaymentType, normalizedType);
        Form form = formRepository.save(Form.builder()
                .user(user)
                .group(group)
                .formName(formName)
                .filePath(filePath)
                .description(description)
                .formFields(formFields)
                .generatedFields(generatedFields)
                .paymentType(normalizedType)
                .build());
        log.info("양식지 저장 완료 - id: {}", form.getId());

        // fields JSON 파싱하여 리스트로 반환
        List<String> fieldList = List.of();
        List<String> generatedFieldList = List.of();
        try {
            fieldList = objectMapper.readValue(formFields, new TypeReference<>() {});
            generatedFieldList = objectMapper.readValue(generatedFields, new TypeReference<>() {});
        } catch (Exception ignored) {}

        return FormUploadResponse.builder()
                .formId(form.getId())
                .formName(formName)
                .description(description)
                .paymentType(normalizedType)
                .fields(fieldList)
                .generatedFields(generatedFieldList)
                .build();
    }

    public FormUploadResponse reanalyze(Long formId, Long policyId) {
        Form form = formRepository.findById(formId)
                .orElseThrow(() -> new IllegalArgumentException("양식지를 찾을 수 없습니다: " + formId));

        // 기존 파일에서 텍스트 재추출
        String text;
        try {
            text = formParserService.extractText(new File(form.getFilePath()));
        } catch (IOException e) {
            throw new RuntimeException("파일 텍스트 추출 실패: " + e.getMessage(), e);
        }
        log.info("재분석 텍스트 추출 완료 - {}자", text.length());

        // 규정책 RAG 검색
        String policyChunks = searchPolicyChunks(text, policyId);

        // 규정 내용 포함해서 재분석
        String analysisJson = formAiService.analyzeForm(text, policyChunks);
        log.info("재분석 결과: {}", analysisJson);

        String description = form.getDescription();
        String formFields = "[]";
        String generatedFields = "[]";
        String aiPaymentType = null;
        try {
            JsonNode node = objectMapper.readTree(analysisJson);
            description = node.path("description").asText();
            aiPaymentType = node.has("paymentType") ? node.path("paymentType").asText() : null;
            formFields = node.path("fields").toString();
            generatedFields = node.has("generatedFields") ? node.path("generatedFields").toString() : "[]";
        } catch (Exception e) {
            log.warn("재분석 결과 파싱 실패: {}", e.getMessage());
        }

        String normalizedType = normalizePaymentType(aiPaymentType != null ? aiPaymentType : form.getPaymentType());
        log.info("재분석 결제유형 분류 - AI={}, 최종={}", aiPaymentType, normalizedType);
        form.updateAnalysis(description, normalizedType, formFields, generatedFields);

        List<String> fieldList = List.of();
        List<String> generatedFieldList = List.of();
        try {
            fieldList = objectMapper.readValue(formFields, new TypeReference<>() {});
            generatedFieldList = objectMapper.readValue(generatedFields, new TypeReference<>() {});
        } catch (Exception ignored) {}

        log.info("재분석 완료 - formId={}, 필드 수={}", formId, fieldList.size());

        return FormUploadResponse.builder()
                .formId(form.getId())
                .formName(form.getFormName())
                .description(description)
                .paymentType(form.getPaymentType())
                .fields(fieldList)
                .generatedFields(generatedFieldList)
                .build();
    }

    // ── 1. 양식지 목록 조회 ──────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public List<FormUploadResponse> getFormsByGroup(Long groupId, Long userId) {
        UserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그룹을 찾을 수 없습니다."));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if (!groupMemberRepository.existsByGroupAndUser(group, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 그룹의 멤버가 아닙니다.");
        }

        return formRepository.findByGroup(group).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // ── 2. 양식지 단건 조회 ──────────────────────────────────────────────────
    @Transactional(readOnly = true)
    public FormUploadResponse getForm(Long formId, Long userId) {
        Form form = formRepository.findById(formId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "양식지를 찾을 수 없습니다."));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if (form.getGroup() == null) {
            if (!form.getUser().getId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");
            }
        } else {
            if (!groupMemberRepository.existsByGroupAndUser(form.getGroup(), user)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 그룹의 멤버가 아닙니다.");
            }
        }

        return toResponse(form);
    }

    // ── 3. 양식지 이름 수정 ──────────────────────────────────────────────────
    public FormUploadResponse updateFormName(Long formId, Long userId, String formName) {
        Form form = formRepository.findById(formId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "양식지를 찾을 수 없습니다."));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if (form.getGroup() == null) {
            if (!form.getUser().getId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");
            }
        } else {
            if (!groupMemberRepository.existsByGroupAndUser(form.getGroup(), user)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 그룹의 멤버가 아닙니다.");
            }
        }

        form.updateFormName(formName);
        return toResponse(form);
    }

    // ── 4. 양식지 삭제 ───────────────────────────────────────────────────────
    public void deleteForm(Long formId, Long userId) {
        Form form = formRepository.findById(formId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "양식지를 찾을 수 없습니다."));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if (form.getGroup() == null) {
            if (!form.getUser().getId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");
            }
        } else {
            groupService.assertMaxRole(user, form.getGroup());
        }

        // FK 참조 정리: evidence_forms 매핑 삭제, approval_requests form 참조 null 처리
        evidenceFormRepository.deleteByFormId(form.getId());
        approvalRequestRepository.detachForm(form.getId());

        try {
            Files.deleteIfExists(Paths.get(form.getFilePath()));
            log.info("양식지 파일 삭제 완료: {}", form.getFilePath());
        } catch (IOException e) {
            log.warn("양식지 파일 삭제 실패 (DB는 계속 삭제): {}", e.getMessage());
        }

        formRepository.delete(form);
        log.info("양식지 삭제 완료 - formId={}", formId);
    }

    // ── 공통: Form → FormUploadResponse 변환 ────────────────────────────────
    private FormUploadResponse toResponse(Form form) {
        List<String> fieldList = List.of();
        List<String> generatedFieldList = List.of();
        try {
            fieldList = objectMapper.readValue(
                    form.getFormFields() != null ? form.getFormFields() : "[]",
                    new TypeReference<>() {});
            generatedFieldList = objectMapper.readValue(
                    form.getGeneratedFields() != null ? form.getGeneratedFields() : "[]",
                    new TypeReference<>() {});
        } catch (Exception ignored) {}

        return FormUploadResponse.builder()
                .formId(form.getId())
                .formName(form.getFormName())
                .description(form.getDescription())
                .paymentType(form.getPaymentType())
                .fields(fieldList)
                .generatedFields(generatedFieldList)
                .createdAt(form.getCreatedAt())
                .build();
    }

    private String searchPolicyChunks(String text, Long policyId) {
        if (policyId == null) return "";
        try {
            float[] embedding = embeddingService.embed(text);
            List<Map<String, Object>> chunks = vectorStore.findSimilar(policyId, embedding, 5);
            if (chunks.isEmpty()) return "";
            String result = chunks.stream()
                    .map(c -> (String) c.get("content"))
                    .collect(Collectors.joining("\n---\n"));
            log.info("양식지 분석용 규정 청크 {}개 검색 완료", chunks.size());
            return result;
        } catch (Exception e) {
            log.warn("규정책 RAG 검색 실패 (무시하고 계속): {}", e.getMessage());
            return "";
        }
    }

    private String normalizePaymentType(String paymentType) {
        if (paymentType == null) return "BOTH";
        return switch (paymentType.toUpperCase()) {
            case "CARD" -> "CARD";
            case "CASH" -> "CASH";
            default -> "BOTH";
        };
    }

    private String saveFile(MultipartFile file) {
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        if (!originalFilename.endsWith(".docx") && !originalFilename.endsWith(".xlsx") && !originalFilename.endsWith(".xls")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 파일 형식입니다. (.docx, .xlsx, .xls만 허용)");
        }
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
