package com.ebookwriter.SaaS.security.oauth;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Looks up an {@link OAuthProvider} bean by its URL id. */
@Component
public class OAuthProviderRegistry {

    private final Map<String, OAuthProvider> providers;

    public OAuthProviderRegistry(List<OAuthProvider> providers) {
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(OAuthProvider::id, Function.identity()));
    }

    /** @throws OAuthException INVALID_REQUEST for an unknown provider, NOT_CONFIGURED when credentials are missing */
    public OAuthProvider require(String id) {
        OAuthProvider provider = id == null ? null : providers.get(id);
        if (provider == null) {
            throw new OAuthException(OAuthErrorCode.INVALID_REQUEST, "Unknown OAuth provider");
        }
        if (!provider.isConfigured()) {
            throw new OAuthException(OAuthErrorCode.NOT_CONFIGURED,
                    "OAuth provider '" + id + "' is not configured");
        }
        return provider;
    }
}
