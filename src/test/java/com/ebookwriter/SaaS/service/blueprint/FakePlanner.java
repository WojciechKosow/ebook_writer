package com.ebookwriter.SaaS.service.blueprint;

import com.ebookwriter.SaaS.support.FakeOpenAiServer;
import com.ebookwriter.SaaS.support.FakeOpenAiServer.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers like the blueprint planner would — only from the BOOK KNOWLEDGE that
 * was actually sent in the prompt: one chapter per knowledge topic (so a project
 * without orders gets no Orders chapter), the author's JWT problem as a gap with
 * a question, an inferable gap (must be dropped), slightly-off citations (must
 * be resolved) and an invented file (must be dropped).
 */
public final class FakePlanner {

    public enum Mode { NORMAL, NO_GAPS, MANY_GAPS }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ANSWERED_ID = Pattern.compile("questionId: ([0-9a-f-]{36})");

    private FakePlanner() {
    }

    public static Reply answer(FakeOpenAiServer.Request r, Mode mode) {
        String marker = "BOOK KNOWLEDGE (JSON)\n";
        JsonNode knowledge;
        try {
            knowledge = MAPPER.readTree(r.user().substring(r.user().indexOf(marker) + marker.length()).strip());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        boolean answeredJwt = r.user().contains("AUTHOR'S ANSWERS");

        ObjectNode out = MAPPER.createObjectNode();
        out.put("concept", "A hands-on guide to building " + knowledge.path("project").path("name").asText() + " from scratch.");
        out.put("workingTitle", "Building an Online Shop with Spring Boot");
        out.put("audience", "Beginner Java developers");
        out.put("readerGoal", "Build a working online shop backend from scratch");
        out.put("promise", "You will have a running, secured shop API.");
        out.put("structureRationale", "Follows the author's build order from the notes.");
        ArrayNode chapters = out.putArray("chapters");
        ObjectNode intro = chapters.addObject();
        intro.put("key", "c0").put("title", "What We're Building").put("purpose", "Show the final project.");
        intro.putArray("topics").add("application overview").add("architecture");
        intro.putArray("sourceReferences").add("README.md").add("my-shop.zip (structure)");
        intro.putArray("knowledgeReferences");

        String jwtKey = null;
        int i = 1;
        for (JsonNode topic : knowledge.path("topics")) {
            String name = topic.path("name").asText();
            String key = "c" + i++;
            ObjectNode ch = chapters.addObject();
            ch.put("key", key).put("title", name).put("purpose", "Implement " + name.toLowerCase() + ".");
            ch.putArray("topics").add(name);
            // Lower-cased name: the pipeline must still match it to the knowledge item.
            ArrayNode refs = ch.putArray("knowledgeReferences");
            refs.addObject().put("type", "topic").put("name", name.toLowerCase());
            refs.addObject().put("type", "topic").put("name", "Kubernetes deployment"); // not in knowledge
            ArrayNode src = ch.putArray("sourceReferences");
            src.add("src/main/java/com/shop/Invented.java");
            if (name.contains("JWT")) {
                jwtKey = key;
                Matcher same = Pattern.compile("\\[id: ([0-9a-f-]{36})\\] [^\\n]*JWT").matcher(r.user());
                if (same.find()) ch.put("sameAsChapterId", same.group(1));
                refs.addObject().put("type", "userInsight").put("name", "had problems with JWT");
                ObjectNode kp = ch.putArray("keyPoints").addObject();
                kp.put("point", "Explain why the project uses JWT");
                kp.putArray("sources").add("user notes");
                src.add("SecurityConfig.java");
                ch.putArray("gapKeys").add("g1");
            }
        }

        ArrayNode gaps = out.putArray("knowledgeGaps");
        ArrayNode questions = out.putArray("questions");
        if (mode == Mode.NORMAL) {
            if (!answeredJwt) {
                gap(gaps, "g1", "We know the author had problems with JWT, but not what went wrong, how it was solved, or the lesson for the reader.",
                        "critical", jwtKey, false);
                question(questions, "g1", jwtKey, "You mentioned JWT caused problems. What went wrong, and how did you fix it?", 1);
            }
            gap(gaps, "g2", "The reason for choosing JWT over server sessions is not explained.", "important", jwtKey, false);
            question(questions, "g2", jwtKey, "Why did you choose JWT instead of server-side sessions?", 2);
            gap(gaps, "g3", "Which Java version is used?", "minor", "c1", true);
            question(questions, "g3", "c1", "Which Java version do you use?", 1);
        } else if (mode == Mode.MANY_GAPS) {
            for (int g = 0; g < 15; g++) {
                gap(gaps, "m" + g, "Missing detail number " + g, g % 3 == 0 ? "critical" : "minor", "c" + (1 + g % 6), false);
                question(questions, "m" + g, "c" + (1 + g % 6), "Question about detail " + g + "?", 5 - (g % 5));
            }
        }

        ArrayNode links = out.putArray("answerLinks");
        Matcher m = ANSWERED_ID.matcher(r.user());
        while (m.find()) links.addObject().put("questionId", m.group(1)).put("chapterKey", jwtKey);
        return Reply.json(out.toString());
    }

    private static void gap(ArrayNode gaps, String key, String description, String severity, String chapterKey, boolean inferable) {
        ObjectNode g = gaps.addObject();
        g.put("key", key).put("description", description).put("whyItMatters", "The book would be vague without it")
                .put("severity", severity).put("canBeInferred", inferable);
        ArrayNode ck = g.putArray("chapterKeys");
        if (chapterKey != null) ck.add(chapterKey);
    }

    private static void question(ArrayNode questions, String gapKey, String chapterKey, String text, int priority) {
        questions.addObject().put("gapKey", gapKey).put("chapterKey", chapterKey).put("question", text)
                .put("reason", "Needed for the chapter").put("priority", priority);
    }

    static List<String> titles(JsonNode chapters) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : chapters) out.add(c.path("title").asText());
        return out;
    }
}
