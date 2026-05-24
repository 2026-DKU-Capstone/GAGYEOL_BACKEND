package GAGYELOL.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FormFillServiceXlsxTest {

    private final FormFillService service = new FormFillService();

    @TempDir Path tempDir;

    /**
     * Result/수입지출 (1).docx (실제로는 XLSX)에서 재현된 버그.
     * 헤더 사이에 빈 데이터 칸이 있는 표 양식: | 번호 |   | 날짜 |   | ...
     * 기존 코드는 rightCell이 BLANK라는 이유로 "레이블-값 분기"로 빠져 통문자열을 한 셀에 넣음.
     * 수정 후엔 값 자체가 표 데이터라고 판단되면(짧은 토큰 다수) 헤더 아래로 분할되어야 함.
     */
    @Test
    void 헤더_사이에_빈셀이_있는_표양식에서도_행으로_분할된다() throws IOException {
        Path tempFile = createXlsxWithGappedHeaders();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("번호", "1, 2, 3, 4, 5");
        fields.put("날짜", "2025.01.01, 2025.01.02, 2025.01.03, 2025.01.04, 2025.01.05");
        fields.put("내용", "사업명 'Test'와 관련하여, 본 사업은 효율적이고 체계적인 실행을 목표로 하며, 관련 절차와 규정을 준수하여 진행될 예정입니다.");

        // "내용"은 LLM이 생성한 자연어 → generatedFields로 명시해 분할 금지
        byte[] result = service.fill(tempFile.toString(), fields, Collections.emptyMap(), Set.of("내용"));

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet sheet = out.getSheetAt(0);

            // 번호 컬럼(col 0): 헤더 아래 행에 "1".."5" 들어가야 함
            for (int i = 0; i < 5; i++) {
                Cell c = sheet.getRow(i + 1).getCell(0);
                assertThat(c).as("번호 row %d", i + 1).isNotNull();
                assertThat(c.getStringCellValue()).isEqualTo(String.valueOf(i + 1));
            }

            // 날짜 컬럼(col 2): 헤더 아래 행에 날짜 5개
            for (int i = 0; i < 5; i++) {
                Cell c = sheet.getRow(i + 1).getCell(2);
                assertThat(c).as("날짜 row %d", i + 1).isNotNull();
                assertThat(c.getStringCellValue()).startsWith("2025.01.0");
            }

            // 내용 컬럼(col 4): 장문 자연어는 분할되지 않고 단일 셀(헤더 오른쪽 col 5)에 통째로 들어가야 함
            Cell contentCell = sheet.getRow(0).getCell(5);
            assertThat(contentCell).isNotNull();
            assertThat(contentCell.getStringCellValue())
                    .startsWith("사업명 'Test'")
                    .endsWith("진행될 예정입니다.");

            // 내용 컬럼 아래 행(col 4)은 분할되지 않아야 함
            Row row1 = sheet.getRow(1);
            Cell row1Col4 = row1 != null ? row1.getCell(4) : null;
            if (row1Col4 != null) {
                assertThat(row1Col4.getCellType()).isEqualTo(CellType.BLANK);
            }
        }
    }

    /**
     * 50행짜리 표 데이터(사용자 실제 양식 시나리오).
     * 천 단위 콤마("9,000")는 공백이 없어 보존되어야 함.
     */
    @Test
    void 천단위_콤마는_보존되고_각_값은_별도_행에_분할된다() throws IOException {
        Path tempFile = createXlsxWithGappedHeaders();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("번호", "1, 2, 3");
        fields.put("날짜", "9,000, 16,000, 9,000"); // 헤더 명이 "날짜"지만 값은 통화 형태

        byte[] result = service.fill(tempFile.toString(), fields);

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet sheet = out.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("9,000");
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("16,000");
            assertThat(sheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("9,000");
        }
    }

    /**
     * 중간에 누락된 행("a, , c") 보존 — 빈 토큰 위치의 셀은 그대로 두고 행 정렬 유지.
     */
    @Test
    void 중간_누락된_행은_건너뛰고_행_정렬_유지() throws IOException {
        Path tempFile = createXlsxWithGappedHeaders();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("번호", "100, , 300, , 500");

        byte[] result = service.fill(tempFile.toString(), fields);

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet sheet = out.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("100");
            // row 2 col 0: should be blank (skipped)
            Row row2 = sheet.getRow(2);
            if (row2 != null && row2.getCell(0) != null) {
                assertThat(row2.getCell(0).getCellType()).isEqualTo(CellType.BLANK);
            }
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("300");
            Row row4 = sheet.getRow(4);
            if (row4 != null && row4.getCell(0) != null) {
                assertThat(row4.getCell(0).getCellType()).isEqualTo(CellType.BLANK);
            }
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("500");
        }
    }

    /**
     * 단일 값(레이블-값 구조)은 오른쪽 빈 셀에 그대로 들어가야 함 — 기존 동작 보존.
     */
    @Test
    void 단일값은_오른쪽_빈셀에_들어간다() throws IOException {
        Path tempFile = tempDir.resolve("simple.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("지출인 성명");
            // col 1 blank
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                wb.write(fos);
            }
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("지출인 성명", "홍길동");

        byte[] result = service.fill(tempFile.toString(), fields);

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet sheet = out.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("홍길동");
        }
    }

    /**
     * 다중행 데이터가 표 칸 수를 초과하면 합계 행을 넘지 않고, 초과분은 양식 시트를 복제한
     * 다음 시트(다음 양식지)로 이어 기록된다.
     * 헤더(row0) 아래 데이터 칸은 row1~3 (합계는 row4) = 칸 3개. 값 5개 → 3개는 현재 시트, 2개는 복제 시트.
     */
    @Test
    void 다중행은_합계행_위까지_채우고_초과분은_다음_시트로_복제된다() throws IOException {
        Path tempFile = tempDir.resolve("form-with-sum.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("양식");
            sheet.createRow(0).createCell(0).setCellValue("번호"); // 헤더
            // row 1~3 = 데이터 칸 (빈 행)
            sheet.createRow(4).createCell(0).setCellValue("합계"); // 합계 행
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                wb.write(fos);
            }
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("번호", "1, 2, 3, 4, 5"); // 5개지만 데이터 칸은 3개(row1~3)

        byte[] result = service.fill(tempFile.toString(), fields);

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            // 칸을 초과해 두 번째 양식지(복제 시트)가 생겼다
            assertThat(out.getNumberOfSheets()).isEqualTo(2);

            Sheet page1 = out.getSheetAt(0);
            assertThat(page1.getRow(1).getCell(0).getStringCellValue()).isEqualTo("1");
            assertThat(page1.getRow(2).getCell(0).getStringCellValue()).isEqualTo("2");
            assertThat(page1.getRow(3).getCell(0).getStringCellValue()).isEqualTo("3");
            // 합계 행(row4)은 덮어쓰지 않고 라벨 유지, 그 아래로 새 행도 만들지 않음
            assertThat(page1.getRow(4).getCell(0).getStringCellValue()).isEqualTo("합계");
            assertThat(page1.getRow(5)).isNull();

            // 두 번째 양식지: 헤더·합계 라벨이 복제되고 초과분 4,5가 들어간다
            Sheet page2 = out.getSheetAt(1);
            assertThat(page2.getRow(0).getCell(0).getStringCellValue()).isEqualTo("번호");
            assertThat(page2.getRow(1).getCell(0).getStringCellValue()).isEqualTo("4");
            assertThat(page2.getRow(2).getCell(0).getStringCellValue()).isEqualTo("5");
            assertThat(page2.getRow(4).getCell(0).getStringCellValue()).isEqualTo("합계");
        }
    }

    private Path createXlsxWithGappedHeaders() throws IOException {
        Path tempFile = tempDir.resolve("form-gapped.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            // 사용자 양식 패턴: 헤더 사이에 빈 데이터 칸
            header.createCell(0).setCellValue("번호");
            // col 1 blank
            header.createCell(2).setCellValue("날짜");
            // col 3 blank
            header.createCell(4).setCellValue("내용");
            // col 5 blank
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                wb.write(fos);
            }
        }
        return tempFile;
    }
}
