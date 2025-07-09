package com.gra.paradise.botattendance.repository;

import com.gra.paradise.botattendance.model.CrewActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CrewActivityRepository extends JpaRepository<CrewActivity, Long> {
    List<CrewActivity> findAllByScheduleIdOrderByTimestamp(Long scheduleId);
}