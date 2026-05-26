package GAGYELOL.repository;

import GAGYELOL.entity.EvidenceForm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EvidenceFormRepository extends JpaRepository<EvidenceForm, EvidenceForm.EvidenceFormId> {

    @Query("SELECT ef.form.id FROM EvidenceForm ef WHERE ef.evidence.id = :evidenceId")
    List<Long> findFormIdsByEvidenceId(@Param("evidenceId") Long evidenceId);

    @Query("SELECT ef.form.formName FROM EvidenceForm ef WHERE ef.evidence.id = :evidenceId ORDER BY ef.form.id ASC")
    List<String> findFormNamesByEvidenceId(@Param("evidenceId") Long evidenceId);

    void deleteByEvidenceId(Long evidenceId);

    void deleteByFormId(Long formId);
}
