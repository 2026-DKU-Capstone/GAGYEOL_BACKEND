package GAGYELOL.controller;

import GAGYELOL.config.JwtUtil;
import GAGYELOL.dto.PolicyResponse;
import GAGYELOL.dto.PolicyUploadResponse;
import GAGYELOL.service.PolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService policyService;
    private final JwtUtil jwtUtil;

    @PostMapping("/upload")
    public ResponseEntity<PolicyUploadResponse> upload(
            @RequestHeader("Authorization") String token,
            @RequestParam("file") MultipartFile file,
            @RequestParam("policyName") String policyName,
            @RequestParam(value = "groupId", required = false) Long groupId
    ) {
        Long userId = jwtUtil.extractUserId(token.replace("Bearer ", ""));
        return ResponseEntity.ok(policyService.upload(file, policyName, userId, groupId));
    }

    @GetMapping
    public ResponseEntity<List<PolicyResponse>> getByGroup(@RequestParam Long groupId) {
        return ResponseEntity.ok(policyService.getByGroup(groupId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PolicyResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(policyService.getById(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("Authorization") String token,
            @PathVariable Long id) {
        Long userId = jwtUtil.extractUserId(token.replace("Bearer ", ""));
        policyService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }
}
