package com.ebookwriter.SaaS.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The author's answer to one question, or {@code skip=true} to decline it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlueprintAnswerRequest {
    private String answer;
    private Boolean skip;

    public boolean isSkip() {
        return Boolean.TRUE.equals(skip);
    }
}
