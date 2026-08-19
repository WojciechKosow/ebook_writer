package com.ebookwriter.SaaS.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Rendered PDF bytes, kept in their own table and keyed by the ebook id so the
 * blob is loaded only on download — never during status polling or listing.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ebook_pdfs")
public class EbookPdf {

    @Id
    private UUID ebookId;

    @Lob
    @Column(nullable = false)
    private byte[] data;
}
