package dev.finguard.domain.repository;

import dev.finguard.domain.model.FraudPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FraudPatternRepository extends JpaRepository<FraudPattern, Long> {

    List<FraudPattern> findByPatternType(String patternType);
}
