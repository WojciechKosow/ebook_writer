package com.ebookwriter.SaaS.service;

import com.ebookwriter.SaaS.request.LoginRequest;
import com.ebookwriter.SaaS.request.PasswordResetRequest;
import com.ebookwriter.SaaS.request.RegisterRequest;
import com.ebookwriter.SaaS.response.AuthResponse;

import java.util.UUID;

public interface UserService {

    AuthResponse register(RegisterRequest request);
    AuthResponse login(LoginRequest request);
    void verifyAccount(UUID tokenId, String rawToken);
    void resendVerificationEmail(String email);
    void forgotPassword(String email);
    void resetPassword(UUID tokenId, String token, PasswordResetRequest request);
}
