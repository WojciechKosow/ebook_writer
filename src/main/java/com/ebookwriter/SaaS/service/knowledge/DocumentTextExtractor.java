package com.ebookwriter.SaaS.service.knowledge;

import com.ebookwriter.SaaS.config.properties.KnowledgeProperties;
import com.ebookwriter.SaaS.dto.knowledge.NormalizedDocument;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns a single document's bytes into normalised text: PDF (PDFBox text
 * layer), DOCX (paragraph text from {@code word/document.xml}, headings kept as
 * Markdown {@code #}), TXT / MD / any text file (decoded as-is). Used for direct
 * uploads and for documents found inside an archive.
 *
 * <p>Failures are reported as {@link DocumentExtractionException} with a reason
 * the author can understand; the caller decides whether that fails the source
 * (a direct upload) or just skips one file (inside an archive).
 */
@Component
@RequiredArgsConstructor
public class DocumentTextExtractor {

    private final KnowledgeProperties limits;

    /** Thrown when a document cannot be read; the message is user-facing. */
    public static class DocumentExtractionException extends Exception {
        public DocumentExtractionException(String message) {
            super(message);
        }
    }

    /**
     * Extract one document.
     *
     * @param path  its path/filename (also decides the format by extension)
     * @param kind  its role in the analysis
     */
    public NormalizedDocument extract(String path, byte[] bytes, NormalizedDocument.Kind kind)
            throws DocumentExtractionException {
        String ext = FileClassifier.extension(path);
        String raw = switch (ext) {
            case "pdf" -> pdfText(bytes);
            case "docx" -> docxText(bytes);
            default -> {
                if (!TextNormalizer.looksLikeText(bytes)) {
                    throw new DocumentExtractionException("not a text file");
                }
                yield TextNormalizer.decode(bytes);
            }
        };
        String text = TextNormalizer.normalize(raw);
        if (text.isBlank()) {
            throw new DocumentExtractionException(ext.equals("pdf")
                    ? "no extractable text (a scanned PDF?)" : "empty document");
        }
        return document(path, kind, text);
    }

    /** Build a normalised document from already-normalised text, applying the size limit. */
    NormalizedDocument document(String path, NormalizedDocument.Kind kind, String normalizedText) {
        boolean truncated = normalizedText.length() > limits.getMaxDocumentChars();
        String content = truncated ? TextNormalizer.truncate(normalizedText, limits.getMaxDocumentChars()) : normalizedText;
        String ext = FileClassifier.extension(path);
        String language = (kind == NormalizedDocument.Kind.CODE || kind == NormalizedDocument.Kind.TEST
                || kind == NormalizedDocument.Kind.BUILD || kind == NormalizedDocument.Kind.CONFIG)
                ? FileClassifier.languageOf(path) : null;
        return new NormalizedDocument(path, kind, ext, language, content.length(), truncated,
                TextNormalizer.contentHash(normalizedText), content);
    }

    // ---- PDF -------------------------------------------------------------------

    String pdfText(byte[] bytes) throws DocumentExtractionException {
        try (PDDocument doc = PDDocument.load(bytes, "", null, null,
                MemoryUsageSetting.setupMixed(64L * 1024 * 1024))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int pages = doc.getNumberOfPages();
            int last = Math.min(pages, Math.max(1, limits.getMaxPdfPages()));
            stripper.setStartPage(1);
            stripper.setEndPage(last);
            String text = stripper.getText(doc);
            if (last < pages) {
                text += "\n[… only the first " + last + " of " + pages + " pages were read …]";
            }
            return text;
        } catch (InvalidPasswordException e) {
            throw new DocumentExtractionException("the PDF is password-protected");
        } catch (IOException | RuntimeException e) {
            throw new DocumentExtractionException("could not read the PDF (damaged or unsupported)");
        }
    }

    // ---- DOCX ------------------------------------------------------------------

    String docxText(byte[] bytes) throws DocumentExtractionException {
        byte[] xml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 2_000) break;
                if ("word/document.xml".equals(entry.getName())) {
                    // document.xml is verbose markup: allow a few times the text limit.
                    xml = readBounded(zip, limits.getMaxFileBytes() * 4);
                    break;
                }
            }
        } catch (IOException e) {
            throw new DocumentExtractionException("could not read the DOCX (damaged or not a Word document)");
        }
        if (xml == null) {
            throw new DocumentExtractionException("not a Word document (no word/document.xml)");
        }
        return docxXmlToText(xml);
    }

    static String docxXmlToText(byte[] xml) throws DocumentExtractionException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // No DTDs, no external entities (XXE-safe).
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        StringBuilder out = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        int headingLevel = 0;
        boolean inText = false;
        try {
            XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "t" -> inText = true;
                        case "tab" -> paragraph.append('\t');
                        case "br", "cr" -> paragraph.append('\n');
                        case "pStyle" -> headingLevel = headingLevel(attr(r, "val"));
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                    if (inText) paragraph.append(r.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "t" -> inText = false;
                        case "p" -> {
                            String p = paragraph.toString().strip();
                            if (!p.isEmpty()) {
                                if (headingLevel > 0) out.append("#".repeat(headingLevel)).append(' ');
                                out.append(p);
                            }
                            out.append('\n');
                            paragraph.setLength(0);
                            headingLevel = 0;
                        }
                        default -> {
                        }
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new DocumentExtractionException("could not read the DOCX (malformed document)");
        }
        return out.toString();
    }

    private static String attr(XMLStreamReader r, String localName) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (localName.equals(r.getAttributeLocalName(i))) return r.getAttributeValue(i);
        }
        return null;
    }

    private static int headingLevel(String style) {
        if (style == null) return 0;
        String s = style.toLowerCase(Locale.ROOT);
        if (s.equals("title")) return 1;
        if (s.startsWith("heading")) {
            try {
                return Math.min(6, Math.max(1, Integer.parseInt(s.substring("heading".length()).strip())));
            } catch (NumberFormatException e) {
                return 2;
            }
        }
        return 0;
    }

    /** Read at most {@code max} bytes; throws when the stream holds more. */
    public static byte[] readBounded(InputStream in, long max) throws IOException {
        byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
        if (data.length > max) {
            throw new IOException("entry larger than " + max + " bytes");
        }
        return data;
    }
}
