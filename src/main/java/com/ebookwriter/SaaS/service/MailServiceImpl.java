package com.ebookwriter.SaaS.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class MailServiceImpl implements MailService {

    private final RestClient postmarkRestClient;

    @Value("${app.mail.from}")
    private String mailFrom;

    @Value("${app.mail.app-name:Scrivetta}")
    private String appName;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public MailServiceImpl(@Qualifier("postmarkRestClient") RestClient postmarkRestClient) {
        this.postmarkRestClient = postmarkRestClient;
    }

    private void send(String to, String subject, String content) {
        try {
            String response = postmarkRestClient.post()
                    .uri("/email")
                    .body(Map.of(
                            "From", appName + " <" + mailFrom + ">",
                            "To", to,
                            "Subject", subject,
                            "HtmlBody", content,
                            "MessageStream", "outbound"
                    ))
                    .retrieve()
                    .body(String.class);
            log.info("[Postmark] Accepted send '{}' to {} (from={}): {}", subject, to, mailFrom, response);
        } catch (RestClientResponseException e) {
            // Postmark answers failures with a JSON body carrying ErrorCode +
            // Message — that's the real reason a send was rejected (bad token,
            // unverified sender, account pending approval, …). Log it verbatim.
            log.error("[Postmark] Rejected send to {} (from={}): HTTP {} — {}",
                    to, mailFrom, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new RuntimeException("error: cannot send an email", e);
        } catch (Exception e) {
            log.error("[Postmark] Could not reach Postmark to send to {} (from={}): {}",
                    to, mailFrom, e.getMessage(), e);
            throw new RuntimeException("error: cannot send an email", e);
        }
    }

    @Override
    public void sendVerificationEmail(String to, UUID tokenId, String token) {
        String subject = "Verify your email to activate your account";
        String confirmationUrl = frontendUrl + "/verify?tokenId=" + tokenId + "&token=" + token;
        String content = emailTemplate(
                "Confirm your email",
                "Thanks for signing up for <strong>" + appName + "</strong>.<br>"
                        + "Before you get started, we just need to confirm your email address.",
                "Verify Email",
                confirmationUrl,
                "If you didn't create an account, you can safely ignore this email."
        );
        send(to, subject, content);
    }

    @Override
    public void sendPasswordResetEmail(String to, UUID tokenId, String token) {
        String subject = "Reset your password";
        String passwordResetUrl = frontendUrl + "/reset-password?tokenId=" + tokenId + "&token=" + token;
        String content = emailTemplate(
                "Reset your password",
                "We received a request to reset the password for your <strong>" + appName + "</strong> account.<br>"
                        + "If you made this request, click the button below to set a new password.",
                "Reset Password",
                passwordResetUrl,
                "If you didn't request a password reset, you can safely ignore this email."
        );
        send(to, subject, content);
    }

    private String emailTemplate(String heading, String body, String buttonLabel, String url, String footerNote) {
        return """
                <div style="font-family: Arial, sans-serif; background-color: #f5f6fa; padding: 40px;">
                    <table align="center" width="600" style="background: #ffffff; border-radius: 8px; padding: 40px;">
                        <tr>
                            <td style="font-size: 20px; font-weight: bold; color: #333333; text-align: center; padding-bottom: 16px;">
                                %s
                            </td>
                        </tr>
                        <tr>
                            <td style="font-size: 15px; color: #555555; text-align: center;">
                                %s
                            </td>
                        </tr>
                        <tr>
                            <td style="text-align: center; padding: 30px 0;">
                                <a href="%s"
                                   style="background-color: #4CAF50; color: white; padding: 14px 28px;
                                          text-decoration: none; border-radius: 6px; font-size: 16px;">
                                    %s
                                </a>
                            </td>
                        </tr>
                        <tr>
                            <td style="font-size: 13px; color: #777777; text-align: center; padding-top: 20px;">
                                If the button doesn't work, copy and paste this link into your browser:<br>
                                <a href="%s" style="color: #4CAF50;">%s</a>
                            </td>
                        </tr>
                        <tr>
                            <td style="font-size: 12px; color: #aaaaaa; text-align: center; padding-top: 40px;">
                                %s
                            </td>
                        </tr>
                    </table>
                </div>
                """.formatted(heading, body, url, buttonLabel, url, url, footerNote);
    }
}
