package com.ebookwriter.SaaS.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.HtmlUtils;

import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class MailServiceImpl implements MailService {

    // Scrivetta "Ink & Paper" palette (mirrors the frontend's --ip-* tokens).
    private static final String BG = "#f3efe6";
    private static final String CARD = "#fffdf8";
    private static final String INK = "#1b1814";
    private static final String INK_2 = "#4b453c";
    private static final String MUTED = "#6b6357";
    private static final String HAIR = "#e4dccd";
    private static final String ACCENT = "#3a2f95";
    private static final String BRASS = "#8a5c1f";
    private static final String DETAILS_BG = "#fbf9f4";

    private static final String SERIF = "Georgia, 'Times New Roman', Times, serif";
    private static final String SANS = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

    private final WebClient postmarkWebClient;

    @Value("${app.mail.from}")
    private String mailFrom;

    @Value("${app.mail.app-name:Scrivetta}")
    private String appName;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public MailServiceImpl(@Qualifier("postmarkWebClient") WebClient postmarkWebClient) {
        this.postmarkWebClient = postmarkWebClient;
    }

    /** One labelled row in an email's summary box (e.g. "Ebook" → title). Values are plain text. */
    private record Detail(String label, String value) {
    }

    /** Everything one transactional email shows; body and note may contain trusted inline HTML. */
    private record Email(String preheader, String heading, String bodyHtml, List<Detail> details,
                         String buttonLabel, String url, String noteHtml) {
    }

    private void send(String to, String subject, Email email) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("From", appName + " <" + mailFrom + ">");
        payload.put("To", to);
        payload.put("Subject", subject);
        payload.put("HtmlBody", renderHtml(email));
        // A plain-text part improves deliverability and serves text-only clients.
        payload.put("TextBody", renderText(email));
        payload.put("MessageStream", "outbound");
        // Transactional links must reach the user unchanged — no click-tracking redirects.
        payload.put("TrackLinks", "None");
        try {
            postmarkWebClient.post()
                    .uri("/email")
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("[Postmark] Accepted send '{}' to {} (from={})", subject, to, mailFrom);
        } catch (Exception e) {
            // WebClientConfig's error-logging filter already logs Postmark's
            // response body — the precise rejection reason (bad token, unverified
            // sender, account pending approval, …) shows up there rather than in
            // this generic message.
            log.error("[Postmark] Could not send email to {} (from={}): {}",
                    to, mailFrom, e.getMessage(), e);
            throw new RuntimeException("error: cannot send an email", e);
        }
    }

    @Override
    public void sendVerificationEmail(String to, UUID tokenId, String token) {
        String subject = "Confirm your email for " + appName;
        String confirmationUrl = baseUrl() + "/verify?tokenId=" + tokenId + "&token=" + token;
        send(to, subject, new Email(
                "One click to activate your " + appName + " account.",
                "Confirm your email",
                "Thanks for signing up for <strong>" + esc(appName) + "</strong>. "
                        + "Before you get started, please confirm that this is your email address.",
                List.of(),
                "Confirm email address",
                confirmationUrl,
                "If you didn't create an account, you can safely ignore this email."
        ));
    }

    @Override
    public void sendPasswordResetEmail(String to, UUID tokenId, String token) {
        String subject = "Reset your " + appName + " password";
        String passwordResetUrl = baseUrl() + "/reset-password?tokenId=" + tokenId + "&token=" + token;
        send(to, subject, new Email(
                "Use this link to choose a new password.",
                "Reset your password",
                "We received a request to reset the password for your <strong>" + esc(appName) + "</strong> account. "
                        + "Click the button below to choose a new one.",
                List.of(),
                "Choose a new password",
                passwordResetUrl,
                "If you didn't request a password reset, you can safely ignore this email — your password won't change."
        ));
    }

    @Override
    public void sendStorePurchaseEmail(String to, String productTitle, String storeName, String downloadUrl,
                                       boolean linkedToAccount) {
        String title = productTitle != null && !productTitle.isBlank() ? productTitle : "your purchase";
        boolean hasSeller = storeName != null && !storeName.isBlank();
        String subject = "Your download is ready: " + title;

        List<Detail> details = new ArrayList<>();
        details.add(new Detail("Product", title));
        if (hasSeller) {
            details.add(new Detail("Sold by", storeName));
        }
        details.add(new Detail("Link valid for", "30 days"));
        details.add(new Detail("Downloads", "Up to 5 per file"));

        String account = linkedToAccount
                ? " It's also saved in <strong>My Purchases</strong> in your Scrivetta account, where you can "
                        + "download it again any time."
                : " Want it kept for good? Create a free Scrivetta account with this email address — your purchase "
                        + "appears in <strong>My Purchases</strong> and you can download it any time.";
        send(to, subject, new Email(
                "Your payment is confirmed — download " + title + " now.",
                "Thank you for your purchase",
                "Your payment is confirmed and your copy of <strong>" + esc(title) + "</strong> is ready. "
                        + "Download it now and keep the files somewhere safe." + account,
                details,
                "Download your files",
                downloadUrl,
                "This link is personal — anyone who has it can download your copy, so please don't share it."
        ));
    }

    private String baseUrl() {
        return frontendUrl.replaceAll("/+$", "");
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s);
    }

    private String renderHtml(Email email) {
        String url = esc(email.url());
        StringBuilder details = new StringBuilder();
        if (!email.details().isEmpty()) {
            details.append("""
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"
                           style="margin:28px 0 4px;background:%s;border:1px solid %s;border-radius:10px;">
                    """.formatted(DETAILS_BG, HAIR));
            for (int i = 0; i < email.details().size(); i++) {
                Detail d = email.details().get(i);
                String border = i == 0 ? "" : "border-top:1px solid " + HAIR + ";";
                details.append("""
                        <tr>
                          <td style="padding:12px 18px;%sfont-family:%s;font-size:13px;color:%s;white-space:nowrap;vertical-align:top;">%s</td>
                          <td style="padding:12px 18px;%sfont-family:%s;font-size:14px;color:%s;font-weight:600;text-align:right;">%s</td>
                        </tr>
                        """.formatted(border, SANS, MUTED, esc(d.label()), border, SANS, INK, esc(d.value())));
            }
            details.append("</table>");
        }

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="x-apple-disable-message-reformatting">
                  <meta name="color-scheme" content="light">
                  <meta name="supported-color-schemes" content="light">
                  <title>%1$s</title>
                  <style>
                    @media only screen and (max-width: 620px) {
                      .sv-card { padding: 32px 24px !important; }
                      .sv-outer { padding: 24px 12px !important; }
                    }
                  </style>
                </head>
                <body style="margin:0;padding:0;background:%2$s;-webkit-text-size-adjust:100%%;">
                  <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;mso-hide:all;">%3$s&#8199;&#847;&#8199;&#847;&#8199;&#847;&#8199;&#847;&#8199;&#847;&#8199;&#847;</div>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background:%2$s;">
                    <tr>
                      <td align="center" class="sv-outer" style="padding:40px 16px;">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="max-width:560px;">
                          <tr>
                            <td align="center" style="padding:0 0 24px;font-family:%4$s;font-size:26px;letter-spacing:0.5px;color:%5$s;">
                              <a href="%6$s" style="color:%5$s;text-decoration:none;">%7$s</a>
                            </td>
                          </tr>
                          <tr>
                            <td class="sv-card" style="background:%8$s;border:1px solid %9$s;border-radius:14px;padding:44px 48px;">
                              <div style="height:3px;width:40px;background:%10$s;border-radius:2px;margin:0 0 24px;"></div>
                              <h1 style="margin:0 0 16px;font-family:%4$s;font-size:26px;line-height:1.25;font-weight:normal;color:%11$s;">%1$s</h1>
                              <p style="margin:0;font-family:%12$s;font-size:16px;line-height:1.6;color:%13$s;">%14$s</p>
                              %15$s
                              <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:32px 0 0;">
                                <tr>
                                  <td align="center" bgcolor="%5$s" style="border-radius:8px;">
                                    <a href="%16$s" target="_blank"
                                       style="display:inline-block;padding:14px 30px;font-family:%12$s;font-size:16px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;">%17$s</a>
                                  </td>
                                </tr>
                              </table>
                              <p style="margin:32px 0 0;padding-top:24px;border-top:1px solid %9$s;font-family:%12$s;font-size:13px;line-height:1.6;color:%18$s;">
                                Button not working? Copy this link into your browser:<br>
                                <a href="%16$s" style="color:%5$s;word-break:break-all;">%16$s</a>
                              </p>
                              <p style="margin:16px 0 0;font-family:%12$s;font-size:13px;line-height:1.6;color:%18$s;">%19$s</p>
                            </td>
                          </tr>
                          <tr>
                            <td align="center" style="padding:24px 16px 0;font-family:%12$s;font-size:12px;line-height:1.6;color:%18$s;">
                              Sent by <a href="%6$s" style="color:%18$s;text-decoration:underline;">%7$s</a> &middot; &copy; %20$s %7$s
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(
                esc(email.heading()),       // 1
                BG,                          // 2
                esc(email.preheader()),     // 3
                SERIF,                       // 4
                ACCENT,                      // 5
                esc(baseUrl()),             // 6
                esc(appName),               // 7
                CARD,                        // 8
                HAIR,                        // 9
                BRASS,                       // 10
                INK,                         // 11
                SANS,                        // 12
                INK_2,                       // 13
                email.bodyHtml(),            // 14
                details,                     // 15
                url,                         // 16
                esc(email.buttonLabel()),   // 17
                MUTED,                       // 18
                email.noteHtml(),            // 19
                Year.now().getValue()        // 20
        );
    }

    private String renderText(Email email) {
        StringBuilder sb = new StringBuilder();
        sb.append(email.heading()).append("\n\n");
        sb.append(HtmlUtils.htmlUnescape(email.bodyHtml().replaceAll("<[^>]+>", ""))).append("\n\n");
        for (Detail d : email.details()) {
            sb.append(d.label()).append(": ").append(d.value()).append("\n");
        }
        if (!email.details().isEmpty()) {
            sb.append("\n");
        }
        sb.append(email.buttonLabel()).append(":\n").append(email.url()).append("\n\n");
        sb.append(HtmlUtils.htmlUnescape(email.noteHtml().replaceAll("<[^>]+>", ""))).append("\n\n");
        sb.append("— ").append(appName).append("\n").append(baseUrl()).append("\n");
        return sb.toString();
    }
}
