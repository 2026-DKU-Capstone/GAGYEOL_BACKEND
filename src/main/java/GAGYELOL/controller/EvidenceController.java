package GAGYELOL.controller;

import GAGYELOL.config.JwtUtil;
import GAGYELOL.dto.CompleteFormRequest;
import GAGYELOL.dto.EvidenceAnalysisResponse;
import GAGYELOL.dto.EvidenceListResponse;
import GAGYELOL.dto.EvidenceResponse;
import GAGYELOL.dto.FillFieldsRequest;
import GAGYELOL.dto.FillFieldsResponse;
import GAGYELOL.dto.RecipientInfoResponse;
import GAGYELOL.service.EvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/evidence")
@RequiredArgsConstructor
public class EvidenceController {

    private final EvidenceService evidenceService;
    private final JwtUtil jwtUtil;

    @GetMapping
    public ResponseEntity<List<EvidenceResponse>> listByGroup(@RequestParam(required = false) Long groupId) {
        return ResponseEntity.ok(evidenceService.getByGroup(groupId));
    }

    @GetMapping("/list")
    public ResponseEntity<List<EvidenceListResponse>> list(
            @RequestParam Long groupId,
            @RequestParam(required = false) String status
    ) {
        return ResponseEntity.ok(evidenceService.listByGroup(groupId, status));
    }

    @GetMapping("/{evidenceId}")
    public ResponseEntity<EvidenceResponse> getById(@PathVariable Long evidenceId) {
        return ResponseEntity.ok(evidenceService.getById(evidenceId));
    }

    @DeleteMapping("/{evidenceId}")
    public ResponseEntity<Void> delete(@PathVariable Long evidenceId) {
        evidenceService.delete(evidenceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/analyze")
    public ResponseEntity<EvidenceAnalysisResponse> analyze(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "groupId", required = false) Long groupId,
            @RequestParam(value = "businessName", required = false) String businessName,
            @RequestParam(value = "recipientImage", required = false) MultipartFile recipientImage
    ) {
        Long userId = jwtUtil.extractUserId(token.replace("Bearer ", ""));
        return ResponseEntity.ok(evidenceService.analyze(file, userId, groupId, businessName, recipientImage));
    }

    @PostMapping("/extract-recipient")
    public ResponseEntity<RecipientInfoResponse> extractRecipient(
            @RequestParam("recipientImage") MultipartFile recipientImage
    ) {
        return ResponseEntity.ok(evidenceService.extractRecipient(recipientImage));
    }

    @PostMapping("/{evidenceId}/fill")
    public ResponseEntity<FillFieldsResponse> fillFields(
            @PathVariable Long evidenceId,
            @RequestBody FillFieldsRequest request
    ) {
        return ResponseEntity.ok(evidenceService.fillFields(evidenceId, request));
    }

    @PostMapping("/{evidenceId}/complete")
    public ResponseEntity<byte[]> completeForm(
            @PathVariable Long evidenceId,
            @RequestBody CompleteFormRequest request
    ) {
        byte[] fileBytes = evidenceService.completeForm(evidenceId, request);
        boolean isZip = request.getForms().size() > 1;

        HttpHeaders headers = new HttpHeaders();
        String fileName = evidenceService.resolveOutputFilename(request);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build());
        headers.setContentType(isZip
                ? MediaType.parseMediaType("application/zip")
                : MediaType.APPLICATION_OCTET_STREAM);

        return ResponseEntity.ok().headers(headers).body(fileBytes);
    }
}
