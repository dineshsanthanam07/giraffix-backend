package com.giraffix.brand.repository;




import com.giraffix.brand.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrandRepository
        extends JpaRepository<Brand, UUID> {

    List<Brand> findAllByCreatedBy_IdOrderByCreatedAtDesc(
            UUID userId
    );

    Optional<Brand> findByIdAndCreatedBy_Id(
            UUID brandId,
            UUID userId
    );

    boolean existsByIdAndCreatedBy_Id(UUID id, UUID owner);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b from Brand b where b.id = :id and b.createdBy.id = :owner")
    Optional<Brand> findOwnedForUpdate(@org.springframework.data.repository.query.Param("id") UUID id,
                                     @org.springframework.data.repository.query.Param("owner") UUID owner);
}
