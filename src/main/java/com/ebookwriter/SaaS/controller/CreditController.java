package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.CheckoutResponse;
import com.ebookwriter.SaaS.dto.CreditBalanceResponse;
import com.ebookwriter.SaaS.dto.CreditPackDTO;
import com.ebookwriter.SaaS.dto.CreditTransactionDTO;
import com.ebookwriter.SaaS.entity.CreditPack;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.security.CurrentUserService;
import com.ebookwriter.SaaS.service.credit.CreditPricing;
import com.ebookwriter.SaaS.service.credit.CreditService;
import com.ebookwriter.SaaS.config.properties.CreditProperties;
import com.ebookwriter.SaaS.dto.CreditPricingResponse;
import com.ebookwriter.SaaS.service.payment.StripeCheckoutService;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/credits")
@RequiredArgsConstructor
public class CreditController {

    private final CreditService creditService;
    private final CreditPricing creditPricing;
    private final CreditProperties creditProperties;
    private final StripeCheckoutService checkoutService;
    private final CurrentUserService currentUserService;

    public record PurchaseRequest(@NotNull CreditPack pack) {}

    /** Current balance + recent ledger. */
    @GetMapping
    public CreditBalanceResponse balance(Authentication authentication) {
        User user = currentUserService.require(authentication);
        List<CreditTransactionDTO> tx = creditService.recentTransactions(user.getId()).stream()
                .map(CreditTransactionDTO::from)
                .toList();
        CreditService.Wallet wallet = creditService.getWallet(user.getId());
        return new CreditBalanceResponse(wallet.balance(), wallet.subscriptionCredits(),
                wallet.purchasedCredits(), tx);
    }

    /** One-time credit packs on sale (for the pricing UI). Retired packs are not listed. */
    @GetMapping("/packs")
    public List<CreditPackDTO> packs() {
        return Arrays.stream(CreditPack.values()).filter(CreditPack::isAvailable).map(CreditPackDTO::from).toList();
    }

    /** What each paid AI operation costs, and the PRO allowance — to explain credits in the UI. */
    @GetMapping("/pricing")
    public CreditPricingResponse pricing() {
        return new CreditPricingResponse(creditPricing.creditsPerPage(), creditPricing.creditsPerImage(),
                creditPricing.creditsPerCover(), creditPricing.maxImagesPerBook(),
                creditProperties.getSubscriptionMonthlyGrant());
    }

    /** Start a Checkout session to buy a credit pack. Returns the redirect URL. */
    @PostMapping("/purchase")
    public ResponseEntity<CheckoutResponse> purchase(@RequestBody PurchaseRequest request,
                                                     Authentication authentication) {
        if (request.pack() == null) {
            throw new IllegalArgumentException("pack is required");
        }
        if (!request.pack().isAvailable()) {
            throw new IllegalArgumentException("This credit pack is no longer available");
        }
        User user = currentUserService.require(authentication);
        StripeCheckoutService.CheckoutResult result = checkoutService.createCreditPackCheckout(user, request.pack());
        return ResponseEntity.ok(new CheckoutResponse(result.orderId(), result.checkoutUrl()));
    }
}
