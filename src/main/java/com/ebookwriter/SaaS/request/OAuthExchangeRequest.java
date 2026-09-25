package com.ebookwriter.SaaS.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of POST /api/auth/oauth2/exchange. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OAuthExchangeRequest {

    /** The one-time code from the callback redirect. */
    @NotBlank
    @Size(max = 200)
    private String code;

    /** The random value the frontend generated before starting the login. */
    @NotBlank
    @Size(max = 128)
    private String clientState;

    /** Optional; absent means a normal (1-day) session, like an unticked "Remember me". */
    private Boolean rememberMe;

    public boolean rememberMeOrDefault() {
        return Boolean.TRUE.equals(rememberMe);
    }
}
