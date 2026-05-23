package GAGYELOL.repository;

import GAGYELOL.entity.ApprovalRequest;
import GAGYELOL.entity.Evidence;
import GAGYELOL.entity.Form;
import GAGYELOL.entity.User;
import GAGYELOL.entity.UserGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {
    List<ApprovalRequest> findByGroupOrderByCreatedAtDesc(UserGroup group);
    List<ApprovalRequest> findByRequesterOrderByCreatedAtDesc(User requester);
    List<ApprovalRequest> findByGroupAndStatus(UserGroup group, String status);
    long countByEvidence(Evidence evidence);
    Optional<ApprovalRequest> findFirstByEvidenceOrderByCreatedAtDesc(Evidence evidence);
    boolean existsByEvidenceAndFormAndStatus(Evidence evidence, Form form, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ApprovalRequest r WHERE r.id = :id")
    Optional<ApprovalRequest> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT r FROM ApprovalRequest r WHERE r.requester.id = :userId AND r.group.id = :groupId ORDER BY r.createdAt DESC")
    List<ApprovalRequest> findByRequesterIdAndGroupIdOrderByCreatedAtDesc(@Param("userId") Long userId, @Param("groupId") Long groupId);

    @Query("SELECT r FROM ApprovalRequest r WHERE r.requester.id = :userId AND r.group.id = :groupId AND r.createdAt BETWEEN :start AND :end")
    List<ApprovalRequest> findByRequesterIdAndGroupIdAndCreatedAtBetween(@Param("userId") Long userId, @Param("groupId") Long groupId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Query("SELECT r FROM ApprovalRequest r WHERE r.requester.id = :userId AND r.group.id = :groupId AND r.status = :status")
    List<ApprovalRequest> findByRequesterIdAndGroupIdAndStatus(@Param("userId") Long userId, @Param("groupId") Long groupId, @Param("status") String status);
}
