package GAGYELOL.service;

import GAGYELOL.dto.PolicyResponse;
import GAGYELOL.dto.PolicyUploadResponse;
import GAGYELOL.entity.Policy;
import GAGYELOL.entity.User;
import GAGYELOL.repository.PolicyChunkVectorStore;
import GAGYELOL.repository.PolicyRepository;
import GAGYELOL.repository.UserRepository;
import GAGYELOL.service.PolicyAiService;
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
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final UserRepository userRepository;
    private final PolicyChunkVectorStore vectorStore;
    private final PdfParserService pdfParserService;
    private final FormParserService formParserService;
    private final EmbeddingService embeddingService;
    private final PolicyAiService policyAiService;
    private final GAGYELOL.repository.UserGroupRepository groupRepository;
    private final GroupService groupService;

    @Value("${file.upload.policy-dir:./uploads/policies}")
    private String uploadDir;

    public PolicyUploadResponse upload(MultipartFile file, String policyName, Long userId, Long groupId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        GAGYELOL.entity.UserGroup group = groupId != null
                ? groupRepository.findById(groupId).orElseThrow(() -> new IllegalArgumentException("그룹을 찾을 수 없습니다.")) : null;

        if (group != null) groupService.assertMaxRole(user, group);

        // 1. 파일 저장
        String filePath = saveFile(file);
        log.info("파일 저장 완료: {}", filePath);

        // 2. Policy 메타데이터 저장
        Policy policy = policyRepository.save(Policy.builder()
                .user(user)
                .group(group)
                .policyName(policyName)
                .filePath(filePath)
                .build());
        log.info("Policy 저장 완료 - id: {}", policy.getId());

        // 3. 파일 형식별 텍스트 추출
        // PDF: 텍스트 기반 우선, 실패 시 Vision API
        // XLSX/XLS: POI로 시트 텍스트 추출
        String text;
        String lowerName = filePath.toLowerCase();
        boolean isPdf = lowerName.endsWith(".pdf");
        boolean isExcel = lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls");

        if (isExcel) {
            try {
                text = formParserService.extractText(new File(filePath));
            } catch (IOException e) {
                throw new RuntimeException("Excel 텍스트 추출 실패", e);
            }
        } else if (isPdf) {
            try {
                text = pdfParserService.extractText(new File(filePath));
            } catch (IOException e) {
                throw new RuntimeException("PDF 텍스트 추출 실패", e);
            }

            // PDF에 한해 텍스트가 너무 짧으면 이미지 기반으로 보고 Vision API 사용
            if (pdfParserService.isImageBasedPdf(text)) {
                byte[] pdfBytes;
                try {
                    pdfBytes = Files.readAllBytes(Paths.get(filePath));
                } catch (IOException e) {
                    throw new RuntimeException("PDF 파일 읽기 실패", e);
                }
                log.info("이미지 기반 PDF 감지 - Vision API로 처리합니다. policyId={}", policy.getId());
                try {
                    List<String> pageImages = pdfParserService.extractPageImagesAsBase64(pdfBytes);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < pageImages.size(); i++) {
                        log.info("페이지 {}/{} Vision 처리 중...", i + 1, pageImages.size());
                        sb.append(policyAiService.extractTextFromImage(pageImages.get(i)));
                        sb.append("\n");
                    }
                    text = sb.toString();
                } catch (IOException e) {
                    throw new RuntimeException("PDF 이미지 변환 실패", e);
                }
            }
        } else {
            throw new IllegalArgumentException("지원하지 않는 규정 문서 형식입니다 (PDF, XLSX, XLS만 가능): " + filePath);
        }

        // 5. 청킹 + 임베딩 + 벡터 저장
        List<String> chunks = pdfParserService.chunkText(text);
        for (int i = 0; i < chunks.size(); i++) {
            log.info("청크 {}/{} 임베딩 중...", i + 1, chunks.size());
            float[] embedding = embeddingService.embed(chunks.get(i));
            vectorStore.save(policy.getId(), i, chunks.get(i), embedding);
        }

        log.info("RAG 인덱싱 완료 - policyId={}, 청크 수={}", policy.getId(), chunks.size());

        return PolicyUploadResponse.builder()
                .policyId(policy.getId())
                .policyName(policyName)
                .chunkCount(chunks.size())
                .message("규정책 RAG 인덱싱이 완료되었습니다.")
                .build();
    }

    @Transactional(readOnly = true)
    public List<PolicyResponse> getByGroup(Long groupId) {
        GAGYELOL.entity.UserGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("그룹을 찾을 수 없습니다."));
        return policyRepository.findByGroup(group).stream()
                .map(PolicyResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyResponse getById(Long policyId) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("규정책을 찾을 수 없습니다."));
        return PolicyResponse.from(policy);
    }

    public void delete(Long policyId, Long userId) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new IllegalArgumentException("규정책을 찾을 수 없습니다."));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        if (policy.getGroup() != null) groupService.assertMaxRole(user, policy.getGroup());
        vectorStore.deleteByPolicyId(policyId);
        policyRepository.delete(policy);
        log.info("규정책 삭제 완료 - policyId={}", policyId);
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
