package com.ebookwriter.SaaS.dto.image;

/**
 * Aspect ratio an {@link ImagePlan} requests, mapped to a concrete pixel size
 * the OpenAI Image API accepts. The planner reasons in ratios ("16:9"); the
 * generator turns the chosen ratio into a supported {@code WxH} size — the AI
 * never picks raw pixel dimensions.
 */
public enum AspectRatio {

    /** Square — good for icons, portraits, self-contained figures. */
    SQUARE("1:1", "1024x1024"),

    /** Landscape — good for wide illustrations, banners, scenes. */
    LANDSCAPE("3:2", "1536x1024"),

    /** Portrait — good for tall figures, covers, full-page visuals. */
    PORTRAIT("2:3", "1024x1536");

    private final String label;
    private final String openAiSize;

    AspectRatio(String label, String openAiSize) {
        this.label = label;
        this.openAiSize = openAiSize;
    }

    /** Human/plan-facing label, e.g. {@code "3:2"}. */
    public String label() {
        return label;
    }

    /** The concrete {@code WxH} size string sent to the OpenAI Image API. */
    public String openAiSize() {
        return openAiSize;
    }

    /**
     * Map a free-form ratio string from the model to the nearest supported ratio.
     * Anything wider than tall is LANDSCAPE, taller than wide is PORTRAIT, and
     * near-square (or unparseable) is SQUARE. Accepts "16:9", "4/3", "1.91:1", …
     */
    public static AspectRatio fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return LANDSCAPE;
        }
        String s = raw.trim().toLowerCase();
        // Direct label matches first.
        for (AspectRatio ar : values()) {
            if (ar.label.equals(s) || ar.name().toLowerCase().equals(s)) {
                return ar;
            }
        }
        Double ratio = parseRatio(s);
        if (ratio == null) {
            return LANDSCAPE;
        }
        if (ratio >= 1.15) {
            return LANDSCAPE;
        }
        if (ratio <= 0.87) {
            return PORTRAIT;
        }
        return SQUARE;
    }

    /** Parse "W:H" or "W/H" or a plain decimal into a width/height ratio. */
    private static Double parseRatio(String s) {
        String sep = s.contains(":") ? ":" : (s.contains("/") ? "/" : null);
        try {
            if (sep != null) {
                String[] parts = s.split(java.util.regex.Pattern.quote(sep));
                if (parts.length == 2) {
                    double w = Double.parseDouble(parts[0].trim());
                    double h = Double.parseDouble(parts[1].trim());
                    return h == 0 ? null : w / h;
                }
                return null;
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
