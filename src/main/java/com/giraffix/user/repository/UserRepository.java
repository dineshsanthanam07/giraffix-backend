package com.giraffix.user.repository;

import com.giraffix.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.rmi.server.UID;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, UID> {
    Optional<User> findByGoogleId(String googleId);
}
