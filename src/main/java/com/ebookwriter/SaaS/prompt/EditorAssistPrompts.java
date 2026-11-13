package com.ebookwriter.SaaS.prompt;

import java.util.List;

/**
 * The editor's writing assistant. The author selects a paragraph, a photo or the
 * whole chapter and asks for one specific thing; the model answers with exactly
 * that piece — never a preamble — so the editor can drop it onto the page.
 */
public final class EditorAssistPrompts {

    private EditorAssistPrompts() {
    }

    /** Actions answered with a list of short items (one per line) rather than prose. */
    public static boolean isList(String action) {
        return "examples".equals(action) || "structure".equals(action);
    }

    public static String system(String language) {
        String lang = language == null || language.isBlank() ? "the language of the book" : language;
        return """
                You are the writing assistant inside a book editor. The author is revising a
                non-fiction book and asks you for one specific change. Answer with exactly the
                requested text and nothing else: no preamble, no explanation, no quotation marks
                around it, no headings, no Markdown except **bold** and *italic* where the
                original uses them. Keep the author's facts, terms, examples and voice; never
                invent statistics, studies, quotes or personal stories. Write in %s.
                """.formatted(lang);
    }

    public static String user(String action, String target, String text, String chapterTitle,
                              String chapterText, List<String> headings, String assetDescription) {
        String task = switch (action) {
            case "improve" -> "Improve the paragraph below: smoother rhythm and clearer sentences, the same meaning and about the same length. Return the paragraph.";
            case "pro" -> "Rewrite the paragraph below to read more professional: tighter, more precise, no filler. Return the paragraph.";
            case "simple" -> "Rewrite the paragraph below for a reader who is just starting out: plain words, short sentences, the same meaning. Return the paragraph.";
            case "expand" -> "Expand the paragraph below with one concrete image, analogy or example that makes the idea easier to grasp. Keep everything it already says. Return the expanded paragraph.";
            case "explain" -> "Write a short plain-language aside (two to four sentences) that explains the concept in the paragraph below to a newcomer. Return only the aside.";
            case "examples" -> "Suggest three concrete situations the author could add to illustrate the paragraph below. One short sentence each. Return them one per line, each starting with \"- \".";
            case "caption" -> "Write a caption for the photo described below: one or two sentences that tell the reader what to notice and why it matters for this chapter. Return only the caption.";
            case "alt" -> "Write alt text for the photo described below, for readers using a screen reader: one sentence describing what is in the picture, no \"image of\". Return only the alt text.";
            case "structure" -> "List up to four section headings this chapter is missing — the sections a reader would expect next, given the ones it already has. Short headings, no numbering. Return them one per line, each starting with \"- \".";
            case "takeaway" -> "Write a one-paragraph takeaway for the end of this chapter: the single most useful thing the reader should remember, in three or four sentences. Return only the paragraph.";
            default -> throw new IllegalArgumentException("Unknown assistant action: " + action);
        };
        StringBuilder sb = new StringBuilder();
        sb.append("TASK: ").append(task).append("\n\n");
        sb.append("CHAPTER: ").append(orDash(chapterTitle)).append("\n");
        if (headings != null && !headings.isEmpty()) {
            sb.append("SECTIONS IT HAS: ").append(String.join(" | ", headings)).append("\n");
        }
        if ("fig".equals(target)) {
            sb.append("\nPHOTO: ").append(orDash(assetDescription)).append("\n");
            if (text != null && !text.isBlank()) {
                sb.append("CURRENT CAPTION: ").append(text.strip()).append("\n");
            }
        } else if (!"ch".equals(target)) {
            sb.append("\nPARAGRAPH:\n").append(orDash(text)).append("\n");
        }
        if (chapterText != null && !chapterText.isBlank()) {
            sb.append("\nCHAPTER TEXT (context only, do not repeat it):\n").append(chapterText.strip()).append("\n");
        }
        return sb.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s.strip();
    }
}
