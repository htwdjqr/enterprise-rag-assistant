package com.example.ragassistant.rag;

import com.example.ragassistant.TestFixtures;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.*;

class DocumentTextExtractorTest {
    @TempDir Path temp;

    @Test void readsUtf8Markdown() throws Exception {
        Path file = temp.resolve("政策.md");
        Files.writeString(file, "# 出差制度\n住宿上限 500 元。");
        assertThat(new DocumentTextExtractor(TestFixtures.properties(temp)).extract(file, "政策.md"))
                .contains("住宿上限 500 元");
    }

    @Test void extractsRealDocx() throws Exception {
        Path file = temp.resolve("policy.docx");
        try (XWPFDocument doc = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            doc.createParagraph().createRun().setText("企业差旅住宿上限为 500 元。");
            doc.write(out);
        }
        assertThat(new DocumentTextExtractor(TestFixtures.properties(temp)).extract(file, "policy.docx"))
                .contains("企业差旅住宿上限为 500 元");
    }

    @Test void extractsRealPdfTextLayer() throws Exception {
        Path file = temp.resolve("policy.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Travel allowance: 500 CNY.");
                content.endText();
            }
            pdf.save(file.toFile());
        }
        assertThat(new DocumentTextExtractor(TestFixtures.properties(temp)).extract(file, "policy.pdf"))
                .contains("Travel allowance: 500 CNY");
    }

    @Test void rejectsEmptyAndOversizedText() throws Exception {
        Path file = temp.resolve("empty.txt");
        Files.writeString(file, " \n ");
        var extractor = new DocumentTextExtractor(TestFixtures.properties(temp));
        assertThatThrownBy(() -> extractor.extract(file, "empty.txt")).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(file, "中".repeat(10001));
        assertThatThrownBy(() -> extractor.extract(file, "large.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void imageOnlyPdfExplainsThatOcrIsRequired() throws Exception {
        Path file = temp.resolve("scan.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            var image = LosslessFactory.createFromImage(pdf, new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB));
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.drawImage(image, 0, 0, 100, 100);
            }
            pdf.save(file.toFile());
        }
        assertThatThrownBy(() -> new DocumentTextExtractor(TestFixtures.properties(temp)).extract(file, "scan.pdf"))
                .isInstanceOf(DocumentExtractionException.class).hasMessageContaining("OCR").hasMessageContaining("扫描件");
    }
}
