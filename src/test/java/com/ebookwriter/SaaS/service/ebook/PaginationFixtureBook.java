package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A deterministic, technical-book-shaped manuscript for pagination tests: the
 * shape a knowledge book takes (h2 sections, h3 "Entity: what it owns"
 * subsections, h4 labels, long prose, lists, code, tables, callouts, bold
 * labels and lead-ins, exercises — some over several pages — in varied order),
 * so titles land at every possible height on a page.
 */
final class PaginationFixtureBook {

    private static final String[] ENTITIES = {
            "User: the account, the credentials and the role",
            "Offer: the listing, and the soft delete",
            "Order: the snapshot of a purchase",
            "Cart: what the buyer is about to commit",
            "Payment: the money, held at arm's length",
            "Category: the tree the catalogue hangs on",
            "Review: the buyer's word, moderated",
            "Address: where the parcel goes",
    };

    private static final String SENTENCE =
            "The repository returns the entity and the service decides what the caller may see of it. ";
    private static final String SENTENCE_2 =
            "Keeping that split explicit is what lets the controller stay thin when the rules change later. ";

    private PaginationFixtureBook() {
    }

    static Ebook ebook() {
        return Ebook.builder()
                .topic("Building an Online Shop with Spring Boot")
                .title("Building an Online Shop with Spring Boot")
                .subtitle("A project, explained from its own code")
                .language("English")
                .build();
    }

    static List<EbookChapter> chapters(long seed, int count) {
        Random r = new Random(seed);
        List<EbookChapter> chapters = new ArrayList<>();
        for (int c = 1; c <= count; c++) {
            chapters.add(EbookChapter.builder()
                    .chapterNumber(c)
                    .title("Chapter part " + c)
                    .description("How the shop's " + c + "th layer is built, and why.")
                    .content(chapter(r))
                    .build());
        }
        return chapters;
    }

    private static String chapter(Random r) {
        StringBuilder sb = new StringBuilder();
        sb.append(prose(r, 2 + r.nextInt(5))).append("\n\n");
        int sections = 3 + r.nextInt(3);
        for (int s = 0; s < sections; s++) {
            sb.append("## Section ").append(s + 1).append(": how the layer fits together\n\n");
            if (r.nextInt(3) > 0) {
                sb.append(prose(r, 1 + r.nextInt(4))).append("\n\n"); // else the h3 follows the h2 directly
            }
            int subs = 1 + r.nextInt(3);
            for (int k = 0; k < subs; k++) {
                sb.append("### ").append(ENTITIES[r.nextInt(ENTITIES.length)]).append("\n\n");
                int blocks = 2 + r.nextInt(4);
                for (int b = 0; b < blocks; b++) {
                    sb.append(block(r)).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    private static String block(Random r) {
        return switch (r.nextInt(20)) {
            case 12 -> "**Request**\n\n```http\nPOST /api/auth/login\nContent-Type: application/json\n\n{\"email\": \"a@b.c\", \"password\": \"secret\"}\n```";
            case 13 -> "**Important**\n\n" + prose(r, 2 + r.nextInt(8));
            case 14 -> ":::exercise Implement the login endpoint\nYour task: " + prose(r, 3 + r.nextInt(6))
                    + ("\n\n" + prose(r, 4 + r.nextInt(8))).repeat(r.nextInt(5)) + "\n:::";
            case 15 -> ":::warning\n" + prose(r, 2 + r.nextInt(6)) + "\n:::";
            case 16 -> ":::tip Use a dedicated bean\n" + prose(r, 1 + r.nextInt(5)) + "\n:::";
            case 17 -> code(r, 30 + r.nextInt(45)); // long examples, some taller than a page
            case 18 -> "#### Exercise — Implement login\n\nYour task:\n\n" + prose(r, 4 + r.nextInt(10));
            case 19 -> "Response:\n\n```http\nHTTP/1.1 200 OK\n\n{\"token\": \"eyJ...\"}\n```";
            case 0, 1 -> prose(r, 6 + r.nextInt(10)); // long paragraph (often > 700 chars)
            case 2 -> prose(r, 1 + r.nextInt(3));
            case 3 -> list(r);
            case 4 -> code(r, 4 + r.nextInt(22));
            case 5 -> "Here is the relevant part of `Offer.java`:\n\n" + code(r, 4 + r.nextInt(26));
            case 6 -> table(r, 3 + r.nextInt(14));
            case 7 -> "The columns map one to one:\n\n" + table(r, 3 + r.nextInt(14));
            case 8 -> "#### What to watch\n\n" + prose(r, 2 + r.nextInt(6));
            case 9 -> "The rules are:\n\n" + list(r);
            case 10 -> ":::example\n" + prose(r, 3 + r.nextInt(12)) + "\n:::";
            default -> ":::note\n" + prose(r, 1 + r.nextInt(3)) + "\n:::";
        };
    }

    private static String prose(Random r, int sentences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences; i++) {
            sb.append(r.nextBoolean() ? SENTENCE : SENTENCE_2);
        }
        return sb.toString().strip();
    }

    private static String list(Random r) {
        StringBuilder sb = new StringBuilder();
        int items = 3 + r.nextInt(6);
        for (int i = 0; i < items; i++) {
            sb.append("- **Rule ").append(i + 1).append("** — ").append(prose(r, 1 + r.nextInt(3))).append('\n');
        }
        return sb.toString();
    }

    private static String code(Random r, int lines) {
        StringBuilder sb = new StringBuilder("```java\n@Entity\npublic class Offer {\n");
        for (int i = 0; i < lines; i++) {
            sb.append("    private String field").append(i).append("; // column ").append(i).append('\n');
        }
        return sb.append("}\n```").toString();
    }

    private static String table(Random r, int rows) {
        StringBuilder sb = new StringBuilder("| Column | Type | Why |\n|---|---|---|\n");
        for (int i = 0; i < rows; i++) {
            sb.append("| field").append(i).append(" | VARCHAR(255) | ").append(r.nextBoolean() ? "lookup" : "display").append(" |\n");
        }
        return sb.toString();
    }
}
