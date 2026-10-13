package com.barberease.repositories;

import com.barberease.models.Customer;
import com.barberease.models.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByCustomerId(Long customerId);
    Optional<Wallet> findByCustomer(Customer customer);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.customer.id = :customerId")
    Optional<Wallet> findByCustomerIdForUpdate(@Param("customerId") Long customerId);
}
