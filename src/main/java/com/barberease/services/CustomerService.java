package com.barberease.services;

import com.barberease.models.Customer;
import com.barberease.repositories.CustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WalletService walletService;

    public Customer getProfile(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
        BigDecimal walletBal = walletService.getBalance(customerId);
        if (walletBal != null && (customer.getWalletBalance() == null || !customer.getWalletBalance().equals(walletBal))) {
            customer.setWalletBalance(walletBal);
            customer = customerRepository.save(customer);
        }
        return customer;
    }

    public Customer updateProfile(Long customerId, Customer data) {
        Customer customer = getProfile(customerId);

        if (data.getName() != null) customer.setName(data.getName());
        if (data.getPhone() != null) customer.setPhone(data.getPhone());
        if (data.getAddress() != null) customer.setAddress(data.getAddress());
        if (data.getGender() != null) customer.setGender(data.getGender());

        return customerRepository.save(customer);
    }

    public void changePassword(Long customerId, String currentPassword, String newPassword) {
        Customer customer = getProfile(customerId);

        if (!passwordEncoder.matches(currentPassword, customer.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        customer.setPassword(passwordEncoder.encode(newPassword));
        customerRepository.save(customer);
    }

    public List<Customer> getAllCustomers() {
        return customerRepository.findAll();
    }

    public Customer toggleCustomerStatus(Long customerId) {
        Customer customer = getProfile(customerId);
        customer.setActive(!customer.isActive());
        return customerRepository.save(customer);
    }

    public Customer topUpWallet(Long customerId, BigDecimal amount) {
        walletService.rechargeWallet(customerId, amount);
        return getProfile(customerId);
    }
}
