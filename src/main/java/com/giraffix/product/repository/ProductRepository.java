package com.giraffix.product.repository;

import com.giraffix.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository
        extends JpaRepository<Product, UUID> {

    List<Product> findAllByBrand_IdOrderByCreatedAtDescIdDesc(
            UUID brandId,org.springframework.data.domain.Pageable pageable
    );

    Optional<Product> findByIdAndBrand_CreatedBy_GoogleId(UUID productId,String subject);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Product p join p.brand b join b.createdBy u where p.id=:id and u.googleId=:subject")
    Optional<Product> findOwnedForUpdate(@org.springframework.data.repository.query.Param("id") UUID id,
                                        @org.springframework.data.repository.query.Param("subject") String subject);
}
