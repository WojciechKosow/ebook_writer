package com.ebookwriter.SaaS.controller;

import com.ebookwriter.SaaS.dto.CheckoutResponse;
import com.ebookwriter.SaaS.dto.CreditBalanceResponse;
import com.ebookwriter.SaaS.dto.CreditPackDTO;
import com.ebookwriter.SaaS.dto.CreditTransactionDTO;
import com.ebookwriter.SaaS.entity.CreditPack;
import com.ebookwriter.SaaS.entity.User;
import com.ebookwriter.SaaS.security.CurrentUserService;
import com.ebookwriter.SaaS.service.credit.CreditService;
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
        return new CreditBalanceResponse(creditService.getBalance(user.getId()), tx);
    }

    /** Available one-time credit packs (for the pricing UI). */
    @GetMapping("/packs")
    public List<CreditPackDTO> packs() {
        return Arrays.stream(CreditPack.values()).map(CreditPackDTO::from).toList();
    }

    /** Start a Checkout session to buy a credit pack. Returns the redirect URL. */
    @PostMapping("/purchase")
    public ResponseEntity<CheckoutResponse> purchase(@RequestBody PurchaseRequest request,
                                                     Authentication authentication) {
        if (request.pack() == null) {
            throw new IllegalArgumentException("pack is required");
        }
        User user = currentUserService.require(authentication);
        StripeCheckoutService.CheckoutResult result = checkoutService.createCreditPackCheckout(user, request.pack());
        return ResponseEntity.ok(new CheckoutResponse(result.orderId(), result.checkoutUrl()));
    }
}
