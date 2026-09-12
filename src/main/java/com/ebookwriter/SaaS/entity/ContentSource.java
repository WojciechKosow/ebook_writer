package com.ebookwriter.SaaS.entity;

/**
 * Who last authored a piece of content or a placement decision.
 *
 * <p>Recorded so a future regeneration can preserve the user's manual work:
 * anything marked {@link #USER} was created or changed intentionally in the
 * editor and must not be silently overwritten by re-running the AI pipeline.
 */
public enum ContentSource {

    /** Produced by the generation pipeline. */
    AI,

    /** Created or modified by the user in the editor. */
    USER
}
