package com.giraffix.product.service;


import com.giraffix.brand.entity.Brand;
import com.giraffix.brand.repository.BrandRepository;
import com.giraffix.product.dto.ProductResponse;
import com.giraffix.product.entity.Product;
import com.giraffix.product.repository.ProductRepository;
import com.giraffix.user.entity.User;
import com.giraffix.user.service.UserService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final UserService userService;

    public ProductService(
            ProductRepository productRepository,
            BrandRepository brandRepository,
            UserService userService) {

        this.productRepository = productRepository;
        this.brandRepository = brandRepository;
        this.userService = userService;
    }

    @Transactional
    public ProductResponse createProduct(
            String googleId,
            UUID brandId,
            Product.ProductRequest request) {

        User user = userService.getUserByGoogleId(googleId);

        Brand brand = brandRepository
                .findByIdAndCreatedBy_Id(brandId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Brand not found"
                ));

        Product product = new Product();
        product.setBrand(brand);
        product.setName(request.name().trim());
        product.setCategory(request.category().trim());
        product.setDescription(request.description());
        product.setKeyFeatures(request.keyFeatures());
        product.setTargetAudience(request.targetAudience());
        product.setImageUrl(request.imageUrl());

        return ProductResponse.from(
                productRepository.saveAndFlush(product)
        );
    }


    @Transactional(readOnly = true)
    public List<ProductResponse> getBrandProducts(
            String googleId,
            UUID brandId) {

        return productRepository
                .findAllByBrand_IdOrderByCreatedAtDesc(brandId)
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(
            String googleId,
            UUID productId) {

        User user = userService.getUserByGoogleId(googleId);

        return ProductResponse.from(
                findOwnedProduct(productId, user.getId())
        );
    }

    @Transactional
    public ProductResponse updateProduct(
            String googleId,
            UUID productId,
            Product.ProductRequest request) {

        User user = userService.getUserByGoogleId(googleId);
        Product product = findOwnedProduct(
                productId, user.getId()
        );

        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Product name cannot be blank"
                );
            }
            product.setName(request.name().trim());
        }

        if (request.category() != null) {
            if (request.category().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Product category cannot be blank"
                );
            }
            product.setCategory(request.category().trim());
        }

        if (request.description() != null)
            product.setDescription(request.description());

        if (request.keyFeatures() != null)
            product.setKeyFeatures(request.keyFeatures());

        if (request.targetAudience() != null)
            product.setTargetAudience(request.targetAudience());

        if (request.imageUrl() != null) {
            product.setImageUrl(request.imageUrl());
        }
        return ProductResponse.from(
                productRepository.saveAndFlush(product)
        );
    }


    @Transactional
    public void deleteProduct(
            String googleId,
            UUID productId) {

        User user = userService.getUserByGoogleId(googleId);

        Product product = findOwnedProduct(
                productId, user.getId()
        );

        productRepository.delete(product);
    }


    private Product findOwnedProduct(
            UUID productId,
            UUID userId) {

        return productRepository
                .findByIdAndBrand_CreatedBy_Id(productId, userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Product not found"
                ));
    }
}

