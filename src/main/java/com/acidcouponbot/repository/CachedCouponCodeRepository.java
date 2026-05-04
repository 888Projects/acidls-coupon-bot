package com.acidcouponbot.repository;

import com.acidcouponbot.model.CachedCouponCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CachedCouponCodeRepository extends JpaRepository<CachedCouponCode, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""

            SELECT c FROM CachedCouponCode c
        WHERE c.voucherGuid = :voucherGuid
        AND c.status = 'AVAILABLE'
        AND (c.endDate IS NULL OR c.endDate > CURRENT_TIMESTAMP)
        ORDER BY c.id ASC
        LIMIT 1
        """)
    Optional<CachedCouponCode> findNextAvailableForUpdate(@Param("voucherGuid") String voucherGuid);

    long countByVoucherGuidAndStatus(String voucherGuid, CachedCouponCode.CodeStatus status);
    List<CachedCouponCode> findByUsedAsFallbackTrueAndRandgoNotifiedFalse();
    boolean existsByCodeGuid(String codeGuid);

    @Query("""
        SELECT c.voucherGuid, COUNT(c)
        FROM CachedCouponCode c
        WHERE c.status = 'AVAILABLE'
        AND (c.endDate IS NULL OR c.endDate > CURRENT_TIMESTAMP)
        GROUP BY c.voucherGuid
        """)
    List<Object[]> countAvailableByVoucher();
}
