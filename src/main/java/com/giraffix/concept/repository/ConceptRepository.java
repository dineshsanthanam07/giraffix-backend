package com.giraffix.concept.repository;

import com.giraffix.concept.entity.Concept;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ConceptRepository extends JpaRepository<Concept, UUID> {}
