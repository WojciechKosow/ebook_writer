package com.ebookwriter.SaaS.service;

import java.util.UUID;

public interface MailService {
    void sendVerificationEmail(String to, UUID tokenId, String token);
    void sendPasswordResetEmail(String to, UUID tokenId, String token);
}
