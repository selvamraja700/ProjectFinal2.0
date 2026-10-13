package com.barberease.repositories;

import com.barberease.models.Customer;
import com.barberease.models.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {
    List<WalletTransaction> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<WalletTransaction> findByCustomerOrderByCreatedAtDesc(Customer customer);
}
