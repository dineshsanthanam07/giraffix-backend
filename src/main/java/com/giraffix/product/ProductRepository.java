package com.giraffix.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository
        extends JpaRepository<Product, UUID> {

    List<Product> findAllByBrand_IdOrderByCreatedAtDesc(
            UUID brandId
    );

    Optional<Product> findByIdAndBrand_CreatedBy_Id(
            UUID productId,
            UUID userId
    );
}