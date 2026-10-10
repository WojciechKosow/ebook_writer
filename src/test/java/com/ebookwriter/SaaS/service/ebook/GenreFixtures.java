package com.ebookwriter.SaaS.service.ebook;

import com.ebookwriter.SaaS.entity.ChapterStatus;
import com.ebookwriter.SaaS.entity.Ebook;
import com.ebookwriter.SaaS.entity.EbookChapter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Small books from different genres, used to show that every quality rule
 * works the same whatever the book is: a technical book with code in two
 * languages and terminal output, a cookbook with numbered steps, boxes nested in
 * them and line-based ingredient lists, and a poetry collection whose line
 * breaks and indentation are the content. Nothing in the code under test may
 * know which is which.
 *
 * <p>Each book is complete and clean as built; tests derive broken variants.
 * Every chapter carries an internal brief ({@code description}) written like
 * an instruction to the model — it must never reach the reader.
 */
final class GenreFixtures {

    private GenreFixtures() {
    }

    /** One fixture book. {@code longLine} is a verbatim line wider than the page. */
    record Book(String genre, Ebook ebook, List<EbookChapter> chapters, String longLine, String sharedBox) {
        Book copy() {
            List<EbookChapter> cs = new ArrayList<>();
            for (EbookChapter c : chapters) {
                cs.add(EbookChapter.builder().id(c.getId()).chapterNumber(c.getChapterNumber()).title(c.getTitle())
                        .description(c.getDescription()).readerSubtitle(c.getReaderSubtitle())
                        .approxPages(c.getApproxPages()).content(c.getContent()).summary(c.getSummary())
                        .coveredTopics(c.getCoveredTopics()).status(c.getStatus()).build());
            }
            return new Book(genre, ebook, cs, longLine, sharedBox);
        }

        EbookChapter chapter(int n) {
            return chapters.get(n - 1);
        }

        @Override
        public String toString() {
            return genre;
        }
    }

    static List<Book> all() {
        return List.of(technical(), cookbook(), poetry());
    }

    // ---- Technical book: Python + YAML code, terminal output -----------------------

    static final String TECH_LONG = "    response = session.post(f\"{base_url}/v1/deployments/{deployment_id}/rollbacks\", "
            + "json={\"reason\": reason, \"force\": True}, timeout=30)";

    static Book technical() {
        String box = """
                :::warning
                Never deploy on a Friday afternoon without a tested rollback path; a failed release with no way back costs the whole weekend.
                :::""";
        String ch1 = """
                Deployments fail in predictable ways, and almost every failure starts with a release that cannot be undone. This chapter builds the smallest pipeline that can always go back.

                ## The rollback client

                The client below asks the platform to roll a deployment back. The request is the same whatever triggered it:

                ```python
                import requests

                def rollback(session, base_url, deployment_id, reason):
                %s
                    response.raise_for_status()
                    return response.json()["status"]
                ```

                Running it against a staging deployment prints the new state:

                ```console
                $ python rollback.py --deployment 4411 --reason "bad config"
                rollback requested: 4411
                status: ROLLING_BACK
                ```

                ## Releasing safely

                A release goes out in three moves, and each one has a guard:

                1. Build the artifact once and tag it with the commit.
                2. Deploy to staging and run the smoke tests.

                   :::warning
                   Smoke tests that only check the home page prove nothing; hit one real endpoint per service.
                   :::

                3. Promote the same artifact to production.

                %s

                :::key-idea
                A deployment you cannot undo is a bet, not a release.
                :::

                :::tip
                Keep the previous artifact warm for an hour after every release.
                :::

                :::note
                The platform keeps ten releases per service by default.
                :::

                :::example
                A team that rolled back in ninety seconds instead of rebuilding saved an entire on-call night.
                :::

                :::takeaway
                Build once, promote the same artifact, and keep a way back.
                :::

                :::done-when
                You can roll staging back with one command.
                :::

                :::pullquote
                The fastest fix is the release you already had.
                :::

                ## Exercises

                :::exercise Roll back staging
                Deploy a broken build to staging and roll it back with the client above.
                :::

                :::exercise Time the rollback
                Measure how long the rollback takes from command to healthy service.
                :::

                :::exercise Break the smoke test
                Make a smoke test fail on purpose and confirm the pipeline stops.
                :::

                :::exercise Keep ten releases
                List the releases the platform keeps and delete the oldest by hand.
                :::
                """.formatted(TECH_LONG, box);
        String ch2 = """
                Configuration decides whether a rollback is even possible. The pipeline reads one file per service, and every value in it has a reason to exist.

                ## The service file

                ```yaml
                service: checkout
                replicas: 3
                rollback:
                  keep_releases: 10
                  health_check: /healthz
                ```

                Each key maps to a guard from the previous chapter: the number of releases kept is what makes a rollback possible at all.

                %s

                ## Exercises

                :::exercise Add a health check
                Add a health check path to your own service file and deploy it to staging.
                :::

                :::exercise Lower the release count
                Set keep_releases to two and observe which rollback stops working.
                :::
                """.formatted(box);
        String ch3 = """
                Everything in this book comes down to one habit: never ship what you cannot take back. The client, the configuration and the guards are three views of that habit.

                ## Start tomorrow

                Pick one service, give it a service file, and roll its staging deployment back once by hand. Then automate the same command.

                ## Exercises

                :::exercise Automate one rollback
                Turn the manual rollback you did into a pipeline step and run it on staging.
                :::

                The next release you ship will be one you can take back, and that is the whole point.
                """;
        Ebook ebook = ebook("Releases You Can Undo", "[{\"heading\":\"Exercises\",\"purpose\":\"hands-on tasks\"}]", 9);
        return new Book("technical", ebook, List.of(
                chapter(ebook, 1, "Rollbacks First", "Orient the reader to why deployments fail and walk through the "
                        + "rollback client, teaching readers how to call the platform API and read its output",
                        "Why every release needs a way back", 4, ch1,
                        "- Rollback client — calls the platform API to roll a deployment back\n- Smoke tests — one real endpoint per service"),
                chapter(ebook, 2, "Configuration", "Walk through the service file and teach readers how each key maps "
                        + "to a deployment guard from chapter one", "One file per service, every key with a reason", 2,
                        ch2, "- Service file — per-service YAML with rollback settings\n- Rollback client — reused"),
                chapter(ebook, 3, "The Habit", "Close the book: synthesise the guards and give the reader a first step",
                        "One habit behind every safe release", 1, ch3, "- The habit — never ship what you cannot undo")),
                TECH_LONG, box);
    }

    // ---- Cookbook: numbered steps, boxes inside steps, line-based ingredients -------------

    static final String COOK_LONG = "2 tablespoons of cold-pressed extra virgin olive oil from the first harvest, "
            + "plus a little more for brushing the tray before the dough goes in";

    static Book cookbook() {
        String box = """
                :::tip
                Weigh flour instead of measuring it by the cup; a cup can hold anything from 120 to 160 grams.
                :::""";
        String ch1 = """
                A good flatbread needs only four ingredients and one hot oven. The method below works in any home kitchen.

                ## Ingredients

                :::verbatim For two flatbreads
                250 g   flour
                160 ml  warm water
                  5 g   salt
                %s
                :::

                ## Method

                1. Mix the flour and the salt in a wide bowl.
                2. Pour in the water and the oil, then stir until no dry flour is left.

                   :::tip
                   If the dough sticks to your fingers, wet your hands rather than adding flour.
                   :::

                3. Knead for five minutes, cover, and rest for thirty.
                4. Shape two rounds and bake them on the hottest shelf for eight minutes.

                %s

                :::note
                The dough keeps for a day in the fridge, wrapped tightly.
                :::

                ## Variations

                Brush the baked bread with garlic butter, or scatter seeds over it before baking.
                """.formatted(COOK_LONG, box);
        String ch2 = """
                Soup turns leftovers into a meal. This one starts from whatever vegetables are in the drawer.

                ## Ingredients

                :::verbatim For four bowls
                1      onion
                2      carrots
                1 l    stock
                :::

                ## Method

                1. Soften the onion in a little oil.
                2. Add the carrots and the stock and simmer for twenty minutes.

                   :::warning
                   Never blend boiling soup in a closed blender; the steam can blow the lid off.
                   :::

                3. Blend until smooth and season to taste.

                %s

                ## Variations

                Stir in a spoon of yogurt, or add lentils for a heartier bowl.
                """.formatted(box);
        String ch3 = """
                Bread and soup together make the simplest complete meal there is, and both forgive almost every mistake.

                ## Ingredients

                :::verbatim For one supper
                1      flatbread
                2      bowls of soup
                :::

                ## Method

                1. Warm the flatbread in the oven while the soup heats.
                2. Tear the bread and serve it beside the bowls.

                ## Variations

                Serve the soup in a hollowed loaf for a feast that costs almost nothing.

                Cooking well is mostly cooking often, and now you have two dishes worth making every week.
                """;
        Ebook ebook = ebook("Two Dishes Every Week", "[{\"heading\":\"Variations\",\"purpose\":\"ways to change the dish\"}]", 7);
        return new Book("cookbook", ebook, List.of(
                chapter(ebook, 1, "Flatbread", "Teach readers how to make a basic flatbread and orient the reader to "
                        + "kneading and resting times", "Four ingredients and one hot oven", 2, ch1,
                        "- Weighing flour — grams instead of cups\n- Kneading — five minutes, then rest"),
                chapter(ebook, 2, "Soup", "Walk through a leftover vegetable soup and teach readers safe blending",
                        "Turn the vegetable drawer into dinner", 2, ch2,
                        "- Safe blending — never blend boiling soup closed\n- Weighing flour — grams instead of cups"),
                chapter(ebook, 3, "A Week of Cooking", "Close the book with a one-week plan combining both dishes",
                        "Two dishes, one week, no recipe", 1, ch3, "- Weekly plan — two bakes, one pot")),
                COOK_LONG, box);
    }

    // ---- Poetry: line breaks and indentation are the content ---------------------------

    static final String POEM_LONG = "and the river, which had carried every name we ever gave it, carried this one too, "
            + "past the mill and the bridge and the last lit window of the town";

    static Book poetry() {
        String box = """
                :::note
                These poems were written to be read aloud; the indented lines are where the breath turns.
                :::""";
        String ch1 = """
                The first poems follow the river from its source to the town, one season at a time.

                :::verbatim Spring
                The ice lets go at night,
                    quietly,
                        the way a held breath ends.
                %s
                :::

                %s

                :::verbatim Summer
                Low water. Warm stones.
                    A heron that will not hurry.
                :::

                Each season leaves something on the bank for the next one.
                """.formatted(POEM_LONG, box);
        String ch2 = """
                The second sequence turns from the river to the people who live along it.

                :::verbatim The Ferryman
                He knows the depth by the colour,
                    the weather by the gulls,
                        the passengers by their hands.
                :::

                %s

                :::verbatim Closing Time
                Chairs up on the tables.
                    One light left on
                        for whoever comes in from the water.
                :::

                Every face in these poems belongs to someone who stayed.
                """.formatted(box);
        String ch3 = """
                The last poem returns to the source, where the book began.

                :::verbatim Source
                Here it is again:
                    a thread of water
                        nobody has named yet.
                :::

                The river ends where it starts, and so does this book.
                """;
        Ebook ebook = ebook("River Year", "[]", 5);
        return new Book("poetry", ebook, List.of(
                chapter(ebook, 1, "Seasons", "Orient the reader to the river through four seasonal poems and teach "
                        + "readers to hear the indented lines as pauses", "The river, one season at a time", 1, ch1,
                        "- Indented lines — where the breath turns"),
                chapter(ebook, 2, "Along the Banks", "Walk through portraits of the people living by the river",
                        "The people who stayed by the water", 1, ch2, "- Portraits — the ferryman, the bar"),
                chapter(ebook, 3, "Source", "Close the collection by returning to the source",
                        "Back to where the river begins", 1, ch3, "- Return — the river ends where it starts")),
                POEM_LONG, box);
    }

    // ---- builders ------------------------------------------------------------------

    private static Ebook ebook(String title, String templateJson, int plannedPages) {
        return Ebook.builder().id(UUID.randomUUID()).title(title).topic(title).language("English")
                .chapterTemplateJson(templateJson).plannedPages(plannedPages).build();
    }

    private static EbookChapter chapter(Ebook ebook, int n, String title, String brief, String subtitle, int pages,
                                        String content, String topics) {
        return EbookChapter.builder().id(UUID.randomUUID()).ebook(ebook).chapterNumber(n).title(title)
                .description(brief).readerSubtitle(subtitle).approxPages(pages).content(content.strip())
                .summary("Chapter " + n + " established " + title.toLowerCase() + " for the reader.")
                .coveredTopics(topics).status(ChapterStatus.EDITED).build();
    }
}
