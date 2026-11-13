package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.config.properties.AnthropicProperties;
import com.ebookwriter.SaaS.dto.AssistResponse;
import com.ebookwriter.SaaS.entity.CreditTransactionType;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.prompt.EditorAssistPrompts;
import com.ebookwriter.SaaS.repository.EbookRepository;
import com.ebookwriter.SaaS.request.AssistRequest;
import com.ebookwriter.SaaS.service.ai.AnthropicService;
import com.ebookwriter.SaaS.service.credit.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The editor's writing assistant: one suggestion per request (a rewritten
 * paragraph, a caption, alt text, examples, missing sections, a takeaway). Each
 * costs {@link #CREDITS} credit, taken before the model call and returned if it
 * fails. Nothing is written to the book here — the editor shows the suggestion
 * and the author decides.
 *
 * <p>Deliberately not {@code @Transactional}: the spend and a refund each commit
 * on their own and the wallet lock is not held through the model call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditorAssistService {

    /** Credits per suggestion. */
    public static final int CREDITS = 1;
    private static final long MAX_TOKENS = 1200;

    private final EbookRepository ebookRepository;
    private final CreditService creditService;
    private final AnthropicService anthropicService;
    private final AnthropicProperties anthropicProperties;

    public AssistResponse assist(UUID ebookId, UUID userId, AssistRequest request) {
        Ebook ebook = ebookRepository.findByIdAndUserId(ebookId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Ebook not found"));
        boolean needsText = "block".equals(request.getTarget());
        if (needsText && (request.getText() == null || request.getText().isBlank())) {
            throw new IllegalStateException("Write a few words first — the assistant works on what is on the page.");
        }

        String system = EditorAssistPrompts.system(ebook.getLanguage());
        String user = EditorAssistPrompts.user(request.getAction(), request.getTarget(), request.getText(),
                request.getChapterTitle(), request.getChapterText(), request.getHeadings(),
                request.getAssetDescription());

        CreditService.Debit debit = creditService.spend(userId, CREDITS, CreditTransactionType.EDITOR_ASSIST,
                ebookId, "Writing assistant: " + request.getAction());
        String out;
        try {
            out = anthropicService.complete(system, user, MAX_TOKENS, anthropicProperties.assistant());
        } catch (RuntimeException e) {
            log.warn("Writing assistant failed for ebook {} ({}): {} — returning {} credit(s)",
                    ebookId, request.getAction(), e.getMessage(), debit.credits());
            creditService.refundGeneration(userId, debit.credits(), debit.fromSubscription(), ebookId);
            throw new IllegalStateException("The assistant is unavailable right now. Your credit was returned — try again in a moment.");
        }

        String text = clean(out);
        if (EditorAssistPrompts.isList(request.getAction())) {
            return new AssistResponse(null, items(text), CREDITS);
        }
        return new AssistResponse(text, null, CREDITS);
    }

    /** Strip wrapping quotes and stray labels a model sometimes adds. */
    static String clean(String s) {
        String t = s == null ? "" : s.strip();
        t = t.replaceFirst("(?i)^(here is|here's)[^:\\n]*:\\s*", "");
        if (t.length() > 1 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("“") && t.endsWith("”"))) {
            t = t.substring(1, t.length() - 1).strip();
        }
        return t;
    }

    /** One item per line, without list markers or numbering. */
    static List<String> items(String s) {
        List<String> out = new ArrayList<>();
        for (String line : s.split("\\R")) {
            String t = line.strip().replaceFirst("^([-*•]|\\d+[.)])\\s*", "").replaceAll("^\\*\\*(.*)\\*\\*$", "$1").strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.size() > 6 ? out.subList(0, 6) : out;
    }
}
