package com.ebookwriter.SaaS.dto.blueprint;

import com.ebookwriter.SaaS.entity.QuestionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/** One question to the author, with its answer (if any) and the chapter it serves. */
public record BlueprintQuestionDTO(UUID id, String gapId, String chapterId, String chapterTitle, String question,
                                   String reason, int priority, QuestionStatus status, String answer,
                                   LocalDateTime answeredAt) {
}
