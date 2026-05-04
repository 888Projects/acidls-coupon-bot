package com.acidcouponbot.repository;

import com.acidcouponbot.model.RandgoVoucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RandgoVoucherRepository extends JpaRepository<RandgoVoucher, Long> {
    List<RandgoVoucher> findByActiveTrueAndIncludeInBundleTrueOrderByDisplayOrderAsc();
    List<RandgoVoucher> findByCategoryAndActiveTrueAndIncludeInBundleTrue(String category);

    Optional<RandgoVoucher> findByVoucherGuid(String voucherGuid);

    long countByActiveTrue();
}