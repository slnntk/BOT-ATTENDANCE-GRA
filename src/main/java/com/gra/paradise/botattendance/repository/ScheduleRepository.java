package com.gra.paradise.botattendance.repository;

import com.gra.paradise.botattendance.model.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findByActiveTrue();
    Optional<Schedule> findByMessageIdAndChannelId(String messageId, String channelId);
}