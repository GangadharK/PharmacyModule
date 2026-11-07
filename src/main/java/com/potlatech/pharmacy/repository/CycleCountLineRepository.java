package com.potlatech.pharmacy.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.potlatech.pharmacy.entity.CycleCountLine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CycleCountLineRepository extends JpaRepository<CycleCountLine, UUID> {

    List<CycleCountLine> findByCycleCountIdOrderById(UUID cycleCountId);

    Optional<CycleCountLine> findByIdAndCycleCountId(UUID id, UUID cycleCountId);
}
