package com.giraffix.user.service;

import com.giraffix.user.entity.User;
import com.giraffix.user.repository.UserRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User saveOrUpdateGoogleUser(OidcUser googleUser) {

        String googleId = googleUser.getSubject();
        String email = googleUser.getEmail();

        if (googleId == null || email == null ||
                !Boolean.TRUE.equals(
                        googleUser.getEmailVerified())) {
            throw new IllegalArgumentException(
                    "A verified Google email is required");
        }

        User user = userRepository.findByGoogleId(googleId)
                .orElseGet(User::new);

        user.setGoogleId(googleId);
        user.setEmail(email);
        user.setFullName(googleUser.getFullName());

        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User getUserByGoogleId(String googleId) {
        return userRepository.findByGoogleId(googleId)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Giraffix user account not found"));
    }
}

