package dev.finguard.domain.repository;

import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.ExperimentResult;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Stream;

@Repository
public interface ExperimentResultRepository extends JpaRepository<ExperimentResult, Long> {

    List<ExperimentResult> findByExperimentName(String experimentName);

    List<ExperimentResult> findByConfig(DetectionConfig config);

    @Query("SELECT e FROM ExperimentResult e WHERE e.experimentName = :name ORDER BY e.config, e.fold")
    List<ExperimentResult> findByExperimentNameOrdered(@Param("name") String name);

    @Query("SELECT e FROM ExperimentResult e ORDER BY e.createdAt DESC")
    List<ExperimentResult> findAllOrderedByCreatedAtDesc();

    /**
     * Streaming export (audit A-7). Caller must be {@code @Transactional(readOnly=true)}
     * and must close the stream (try-with-resources).
     */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.AvailableHints.HINT_FETCH_SIZE, value = "1000"))
    @Query("SELECT e FROM ExperimentResult e WHERE e.experimentName = :name ORDER BY e.config, e.fold")
    Stream<ExperimentResult> streamByExperimentNameOrdered(@Param("name") String name);

    /** Streaming variant of {@code findAll()} for export — see {@link #streamByExperimentNameOrdered}. */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.AvailableHints.HINT_FETCH_SIZE, value = "1000"))
    @Query("SELECT e FROM ExperimentResult e ORDER BY e.id")
    Stream<ExperimentResult> streamAll();

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("DELETE FROM ExperimentResult e")
    int bulkDeleteAll();

    /** Bulk-delete by ID list — used by the "delete selected rows" UI. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM ExperimentResult e WHERE e.id IN :ids")
    int bulkDeleteByIdIn(@org.springframework.data.repository.query.Param("ids")
                          java.util.Collection<Long> ids);

    /** Bulk-delete every row (fold + summary) belonging to a named experiment. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM ExperimentResult e WHERE e.experimentName = :name")
    int bulkDeleteByExperimentName(@org.springframework.data.repository.query.Param("name") String name);

    /** Distinct experiment names — used to populate the "delete experiment" dropdown. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT e.experimentName FROM ExperimentResult e ORDER BY e.experimentName")
    java.util.List<String> findDistinctExperimentNames();
}
