package com.skhynix.domain.record.repository;

import com.skhynix.domain.record.entity.PitcherRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PitcherRecordRepository extends JpaRepository<PitcherRecord, Long> {
}
