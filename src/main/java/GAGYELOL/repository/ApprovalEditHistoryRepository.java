package GAGYELOL.repository;

import GAGYELOL.entity.ApprovalEditHistory;
import GAGYELOL.entity.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalEditHistoryRepository extends JpaRepository<ApprovalEditHistory, Long> {
    List<ApprovalEditHistory> findByRequestOrderByEditedAtAsc(ApprovalRequest request);
    void deleteByRequest(ApprovalRequest request);
}
