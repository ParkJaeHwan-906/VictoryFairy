package com.skhynix.domain.record.repository;

import com.skhynix.domain.record.entity.BatterRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatterRecordRepository extends JpaRepository<BatterRecord, Long> {
}
