package com.ebookwriter.SaaS.config.properties;

import com.ebookwriter.SaaS.entity.WritingQuality;
import com.ebookwriter.SaaS.service.ai.ModelChoice;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "anthropic")
public class AnthropicProperties {

    /** API key. Set ANTHROPIC_API_KEY in the environment. */
    private String apiKey;

    /**
     * The premium model: all analysis and planning, and the writing of
     * {@link WritingQuality#PRO} books. Must be a Claude 4.6+ model (adaptive
     * thinking is used). Override with ANTHROPIC_MODEL if needed.
     */
    private String model = "claude-opus-5";

    /** Client request timeout in minutes — generation calls can be long. */
    private int timeoutMinutes = 15;

    /** How many times to retry a failed generation call before giving up. */
    private int maxRetries = 2;

    /**
     * Whether to run the Step 3 editorial pass. It roughly doubles the cost of
     * a book (a second full pass over every chapter), so it can be turned off
     * for cheap validation runs. Default on.
     */
    private boolean editingEnabled = true;

    /**
     * Model to use for the editorial pass. Blank = same as {@link #model}. Set
     * this to a cheaper model (e.g. claude-sonnet-5) to keep the consistency
     * pass at a fraction of the cost.
     */
    private String editingModel;

    /** Resolve the model used for editing (falls back to the main model). */
    public String resolveEditingModel() {
        return (editingModel == null || editingModel.isBlank()) ? model : editingModel.trim();
    }

    /**
     * Model that writes and edits chapters of a {@link WritingQuality#BASIC}
     * book. The main {@link #model} still does all the analysis and planning;
     * this lighter model writes from its plan.
     */
    private String basicWritingModel = "claude-sonnet-5-5";

    /** Effort for Basic writing and editing ({@code low|medium|high|xhigh|max}); blank = the model's default. */
    private String basicWritingEffort = "medium";

    /** Effort for Pro writing and editing; blank = the model's default. */
    private String proWritingEffort;

    /** Who writes a chapter at this quality. */
    public ModelChoice writerFor(WritingQuality quality) {
        return WritingQuality.orStored(quality) == WritingQuality.BASIC
                ? new ModelChoice(basicModel(), basicWritingEffort)
                : new ModelChoice(model, proWritingEffort);
    }

    /** Who runs the editorial pass at this quality. */
    public ModelChoice editorFor(WritingQuality quality) {
        return WritingQuality.orStored(quality) == WritingQuality.BASIC
                ? new ModelChoice(basicModel(), basicWritingEffort)
                : new ModelChoice(resolveEditingModel(), proWritingEffort);
    }

    private String basicModel() {
        return (basicWritingModel == null || basicWritingModel.isBlank()) ? model : basicWritingModel.trim();
    }
}
