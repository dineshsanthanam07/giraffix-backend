package com.giraffix.brand.repository;




import com.giraffix.brand.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrandRepository
        extends JpaRepository<Brand, UUID> {

    List<Brand> findAllByOwner_IdOrderByCreatedAtDesc(
            UUID userId
    );

    Optional<Brand> findByIdAndOwner_Id(
            UUID brandId,
            UUID userId
    );
}
