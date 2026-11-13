package com.ebookwriter.SaaS.dto;

import java.util.List;

/**
 * The writing assistant's suggestion: a piece of text (a rewritten paragraph, a
 * caption, alt text, a takeaway) or a short list (examples, section titles).
 */
public record AssistResponse(String text, List<String> items, int creditsCharged) {
}
