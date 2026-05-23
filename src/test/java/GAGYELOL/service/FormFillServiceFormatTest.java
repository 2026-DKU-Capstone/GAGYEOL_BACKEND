package GAGYELOL.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * #5 (이미지 삽입 시 기존 텍스트 제거 + 비율 맞춤) / #6 (채운 값 가운데 정렬) 검증.
 */
class FormFillServiceFormatTest {

    private final FormFillService service = new FormFillService();

    @TempDir Path tempDir;

    // ---- #6 가운데 정렬 ----

    @Test
    void DOCX_채운_값은_수평수직_가운데_정렬된다() throws IOException {
        Path tempFile = tempDir.resolve("label-value.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("지출인 성명");
            // cell(1)은 비어 있음 → 여기에 값이 채워져야 함
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                doc.write(fos);
            }
        }

        byte[] result = service.fill(tempFile.toString(), Map.of("지출인 성명", "홍길동"));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            XWPFTableCell valueCell = doc.getTables().get(0).getRow(0).getCell(1);
            assertThat(valueCell.getText().trim()).isEqualTo("홍길동");
            assertThat(valueCell.getParagraphs().get(0).getAlignment())
                    .as("수평 가운데").isEqualTo(ParagraphAlignment.CENTER);
            assertThat(valueCell.getVerticalAlignment())
                    .as("수직 가운데").isEqualTo(XWPFTableCell.XWPFVertAlign.CENTER);
        }
    }

    @Test
    void XLSX_채운_값은_수평수직_가운데_정렬된다() throws IOException {
        Path tempFile = tempDir.resolve("simple-center.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("지출인 성명");
            // col 1 비어 있음
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                wb.write(fos);
            }
        }

        byte[] result = service.fill(tempFile.toString(), Map.of("지출인 성명", "홍길동"));

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Cell valueCell = out.getSheetAt(0).getRow(0).getCell(1);
            assertThat(valueCell.getStringCellValue()).isEqualTo("홍길동");
            assertThat(valueCell.getCellStyle().getAlignment())
                    .as("수평 가운데").isEqualTo(HorizontalAlignment.CENTER);
            assertThat(valueCell.getCellStyle().getVerticalAlignment())
                    .as("수직 가운데").isEqualTo(VerticalAlignment.CENTER);
        }
    }

    // ---- #5 이미지 삽입 시 텍스트 제거 ----

    @Test
    void DOCX_이미지_삽입시_매칭셀의_레이블_텍스트가_제거된다() throws IOException {
        Path tempFile = tempDir.resolve("img-clear.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(1, 1);
            table.getRow(0).getCell(0).setText("영수증 부착(여기에 영수증을 붙이세요)");
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                doc.write(fos);
            }
        }

        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("영수증", pngBytes(10, 10));

        byte[] result = service.fill(tempFile.toString(), Map.of(), images);

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(result))) {
            XWPFTableCell cell = doc.getTables().get(0).getRow(0).getCell(0);
            assertThat(cell.getText().trim()).as("레이블 텍스트 제거됨").isEmpty();
            assertThat(embeddedPictureCount(cell)).as("이미지 1개 삽입").isEqualTo(1);
        }
    }

    @Test
    void XLSX_이미지_삽입시_덮는셀의_텍스트가_제거된다() throws IOException {
        Path tempFile = tempDir.resolve("img-clear.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("영수증 부착(영수증 첨부)");
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                wb.write(fos);
            }
        }

        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("영수증", pngBytes(10, 10));

        byte[] result = service.fill(tempFile.toString(), Map.of(), images);

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Cell cell = out.getSheetAt(0).getRow(0).getCell(0);
            // Pass 2a는 라벨 셀(col 0) 위치에 앵커 → 그 셀 텍스트가 제거되어야 함
            assertThat(cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK)
                    .as("덮는 셀 텍스트 제거됨").isTrue();
        }
    }

    // ---- #5 비율 유지 ----

    @Test
    void 이미지_원본_비율을_유지하며_박스에_맞춘다() throws IOException {
        // 가로형 2:1 → min(150/100, 190/50)=1.5 → 150x75 (비율 2:1 유지)
        int[] wide = service.fitDimensionsEmu(pngBytes(100, 50), 150, 190);
        assertThat((double) wide[0] / wide[1]).as("가로형 비율 유지").isCloseTo(2.0, within(0.02));

        // 세로형 1:2 → min(150/50, 190/100)=1.9 → 95x190 (비율 1:2 유지)
        int[] tall = service.fitDimensionsEmu(pngBytes(50, 100), 150, 190);
        assertThat((double) tall[0] / tall[1]).as("세로형 비율 유지").isCloseTo(0.5, within(0.02));
    }

    // ---- helpers ----

    private byte[] pngBytes(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private int embeddedPictureCount(XWPFTableCell cell) {
        int count = 0;
        for (XWPFParagraph para : cell.getParagraphs()) {
            for (XWPFRun run : para.getRuns()) {
                count += run.getEmbeddedPictures().size();
            }
        }
        return count;
    }
}
