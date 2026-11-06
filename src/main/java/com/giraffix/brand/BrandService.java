package com.giraffix.brand;



import com.giraffix.User.User;
import com.giraffix.User.UserService;
import com.giraffix.brand.dto.*;



import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class BrandService {

    private final BrandRepository brandRepository;
    private final UserService userService;

    public BrandService(
            BrandRepository brandRepository,
            UserService userService) {
        this.brandRepository = brandRepository;
        this.userService = userService;
    }

    @Transactional
    public BrandResponse createBrand(
            String googleId,
            BrandRequest request) {

        User user = userService.getUserByGoogleId(googleId);

        Brand brand = new Brand();
        brand.setCreatedBy(user);
        brand.setName(request.name().trim());
        brand.setDescription(request.description());
        brand.setIndustry(request.industry());
        brand.setTargetAudience(request.targetAudience());
        brand.setPrimaryColor(request.primaryColor());
        brand.setSecondaryColor(request.secondaryColor());

        return BrandResponse.from(brandRepository.save(brand));
    }

    @Transactional(readOnly = true)
    public List<BrandResponse> getMyBrands(String googleId) {

        User user = userService.getUserByGoogleId(googleId);

        return brandRepository
                .findAllByOwner_IdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(BrandResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public BrandResponse getBrand(
            String googleId,
            UUID brandId) {

        User user = userService.getUserByGoogleId(googleId);

        return BrandResponse.from(
                findOwnedBrand(brandId, user.getId())
        );
    }

    @Transactional
    public BrandResponse updateBrand(
            String googleId,
            UUID brandId,
            BrandRequest request) {

        User user = userService.getUserByGoogleId(googleId);
        Brand brand = findOwnedBrand(brandId, user.getId());

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Brand name cannot be blank"
                );
            }
            brand.setName(request.name().trim());
        }

        if (request.description() != null)
            brand.setDescription(request.description());

        if (request.industry() != null)
            brand.setIndustry(request.industry());

        if (request.targetAudience() != null)
            brand.setTargetAudience(request.targetAudience());

        if (request.primaryColor() != null)
            brand.setPrimaryColor(request.primaryColor());

        if (request.secondaryColor() != null)
            brand.setSecondaryColor(request.secondaryColor());

        return BrandResponse.from(brandRepository.save(brand));
    }

    private Brand findOwnedBrand(UUID brandId, UUID userId) {
        return brandRepository
                .findByIdAndOwner_Id(brandId, userId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Brand not found"
                        )
                );
    }
}
