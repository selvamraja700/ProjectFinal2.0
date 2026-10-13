package com.barberease.repositories;

import com.barberease.models.Queue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueueRepository extends JpaRepository<Queue, Long> {
    List<Queue> findByStatusInOrderByPositionAsc(Collection<String> statuses);
    List<Queue> findByChairIdAndStatusInOrderByPositionAsc(Long chairId, Collection<String> statuses);
    List<Queue> findByStatusInAndCreatedAtAfterOrderByPositionAsc(Collection<String> statuses, LocalDateTime cutoff);
    List<Queue> findByChairIdAndStatusInAndCreatedAtAfterOrderByPositionAsc(Long chairId, Collection<String> statuses, LocalDateTime cutoff);
    Optional<Queue> findByCustomerIdAndStatusIn(Long customerId, Collection<String> statuses);
    List<Queue> findAllByCustomerIdAndStatusIn(Long customerId, Collection<String> statuses);
    List<Queue> findByTokenNumber(String tokenNumber);
    Optional<Queue> findFirstByStatusOrderByPositionAsc(String status);
    Optional<Queue> findFirstByChairIdAndStatusOrderByPositionAsc(Long chairId, String status);
    Optional<Queue> findFirstByStatusInOrderByPositionDesc(Collection<String> statuses);
    Optional<Queue> findFirstByChairIdAndStatusInOrderByPositionDesc(Long chairId, Collection<String> statuses);
    List<Queue> findByStatusOrderByCreatedAtAsc(String status);
    List<Queue> findByChairIdAndStatusOrderByCreatedAtAsc(Long chairId, String status);
    List<Queue> findByStatusAndCreatedAtAfterOrderByCreatedAtAsc(String status, LocalDateTime cutoff);
    List<Queue> findByStatusInAndCreatedAtAfterOrderByCreatedAtAsc(Collection<String> statuses, LocalDateTime cutoff);
    List<Queue> findByChairIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(Long chairId, String status, LocalDateTime cutoff);
    
    List<Queue> findByStatusAndChairIsNullAndCreatedAtAfterOrderByCreatedAtAsc(String status, LocalDateTime cutoff);
    long countByStatusAndChairIsNull(String status);
    long countByStatus(String status);
    long countByStatusIn(Collection<String> statuses);
    long countByChairIdAndStatus(Long chairId, String status);
    long countByStatusAndCompletedAtAfter(String status, LocalDateTime cutoff);
}
