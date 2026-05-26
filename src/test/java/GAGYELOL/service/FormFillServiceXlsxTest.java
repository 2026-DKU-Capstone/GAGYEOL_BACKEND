package GAGYELOL.service;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
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
     * 다중행 데이터가 표 칸 수를 초과하면 시트를 복제하지 않고, 합계 행 앞에 행을 삽입(성장)해
     * 한 시트에 모두 채운다. 합계 행은 데이터 끝으로 밀려 내려간다.
     * 헤더(row0) 아래 데이터 칸 row1~3 (합계 row4) = 칸 3개. 값 5개 → 2행 삽입, row1~5에 채우고 합계는 row6.
     */
    @Test
    void 다중행이_칸을_초과하면_같은_시트에_행을_늘려_채우고_합계는_끝으로_내려간다() throws IOException {
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
            assertThat(out.getNumberOfSheets()).as("새 시트를 만들지 않는다").isEqualTo(1);

            Sheet sheet = out.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("1");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("2");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("3");
            // 합계 행 앞에 2행이 삽입되어 4,5도 같은 시트에 들어간다
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("4");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("5");
            // 합계 행은 데이터 끝(row6)으로 밀려 내려간다
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("합계");
        }
    }

    /**
     * 구버전 바이너리 .xls(HSSF) 양식에서도 행 삽입(성장) 경로가 깨지지 않고 파일이 유효해야 한다.
     * 지출 기록부처럼 표+합계 행이 있는 양식은 항상 이 경로를 타므로 .xls 다운로드 실패의 핵심 재현.
     */
    @Test
    void xls_HSSF_칸_초과시_같은_시트에_행을_늘려_채우고_파일이_유효하다() throws IOException {
        Path tempFile = tempDir.resolve("form-with-sum.xls");
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            Sheet sheet = wb.createSheet("지출 기록부");
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

        // 결과 바이트가 정상 HSSF 파일로 다시 열려야 한다 (다운로드 가능 == 손상 없음)
        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(result))) {
            assertThat(out.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = out.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("1");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("5");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("합계");
        }
    }

    /**
     * 양식에 박힌 단체명 자리표시자("00대학 00학과(부) 00전공 학생회")가
     * 예약 키로 전달된 그룹 단체명("단국대학 {그룹 이름}")으로 통째로 교체된다.
     */
    @Test
    void 단체명_자리표시자가_그룹단체명으로_교체된다() throws IOException {
        Path tempFile = tempDir.resolve("org-title.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("00대학 00학과(부) 00전공 학생회");
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                wb.write(fos);
            }
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FormFillService.ORG_TITLE_KEY, "단국대학 소프트웨어학과 학생회");

        byte[] result = service.fill(tempFile.toString(), fields);

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            assertThat(out.getSheetAt(0).getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("단국대학 소프트웨어학과 학생회");
        }
    }

    /**
     * (#1) 필드명을 부분 포함하는 비-머리글 칸("이전 잔액", "(최종 잔액)")이 표 머리글로 오인되어
     * 제목/머리글 영역에 행이 삽입·오염되던 버그 재현. 다중행 데이터는 정확한 머리글("잔액") 아래에만
     * 채워져야 하고, "이전 잔액"·"(최종 잔액)" 칸과 합계는 그대로 보존되어야 한다.
     */
    @Test
    void 다중행_데이터는_부분일치_칸이_아니라_정확한_머리글_아래에만_채워진다() throws IOException {
        Path tempFile = tempDir.resolve("ledger.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("1월");
            sheet.createRow(0).createCell(5).setCellValue("이전 잔액"); // 머리글 아님(부분 일치)
            sheet.createRow(2).createCell(0).setCellValue("잔액");      // 진짜 열 머리글
            // row 3~5 = 데이터 칸 (빈 행)
            sheet.createRow(6).createCell(0).setCellValue("합계");
            sheet.createRow(8).createCell(0).setCellValue("(최종 잔액)"); // 머리글 아님(부분 일치)
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                wb.write(fos);
            }
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("잔액", "1000, 2000, 3000");

        byte[] result = service.fill(tempFile.toString(), fields);

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            assertThat(out.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = out.getSheetAt(0);
            // "잔액" 머리글(row2 col0) 아래 row3~5에만 값이 들어간다
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("1000");
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("2000");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("3000");
            // "이전 잔액"/"(최종 잔액)" 칸과 합계는 보존(오염 없음)
            assertThat(sheet.getRow(0).getCell(5).getStringCellValue()).isEqualTo("이전 잔액");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("합계");
            assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).isEqualTo("(최종 잔액)");
        }
    }

    /**
     * (#2) 수입지출관리대장(원장): 통장 거래를 거래일 '월'에 맞는 시트로 분산하고, 번호는 시트 간 연속 부여,
     * 금액·잔액은 숫자로 채우며, 합계 행에 수입·지출 합계와 최종 잔액을 적는다.
     */
    @Test
    void 원장은_거래월별_시트분산_번호연속_수입지출합계_최종잔액이_채워진다() throws IOException {
        Path tempFile = tempDir.resolve("ledger-monthly.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            for (String month : new String[]{"8월", "9월"}) {
                Sheet s = wb.createSheet(month);
                Row h = s.createRow(0);
                String[] heads = {"번호", "날짜", "내용", "수입금액", "지출금액", "잔액"};
                for (int c = 0; c < heads.length; c++) h.createCell(c).setCellValue(heads[c]);
                // row 1~3 = 데이터 칸, row4 = 합계
                s.createRow(4).createCell(0).setCellValue("합계");
            }
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) { wb.write(fos); }
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("번호", "1, 2, 3, 4");
        fields.put("날짜", "25/08/30, 25/09/01, 25/09/02, 25/09/03"); // 8월 1건, 9월 3건
        fields.put("내용", "고현주, 이마트, 다이소, 쿠팡");
        fields.put("수입금액", "0, 1000, 2000, 3000");
        fields.put("지출금액", "100, 0, 0, 0");
        fields.put("잔액", "900, 1900, 3900, 6900");

        byte[] result = service.fill(tempFile.toString(), fields);

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet aug = out.getSheet("8월");
            Sheet sep = out.getSheet("9월");

            // 8월: 거래 1건(고현주) → 번호 1
            assertThat(aug.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(aug.getRow(1).getCell(2).getStringCellValue()).isEqualTo("고현주");
            assertThat(aug.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(100.0); // 지출금액 숫자
            assertThat(aug.getRow(4).getCell(0).getStringCellValue()).isEqualTo("합계");
            assertThat(aug.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(0.0);    // 수입 합계
            assertThat(aug.getRow(4).getCell(4).getNumericCellValue()).isEqualTo(100.0);  // 지출 합계
            assertThat(aug.getRow(4).getCell(5).getNumericCellValue()).isEqualTo(900.0);  // 최종 잔액

            // 9월: 거래 3건, 번호는 8월(1)에 이어 2·3·4로 연속 부여
            assertThat(sep.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(2.0);
            assertThat(sep.getRow(2).getCell(0).getNumericCellValue()).isEqualTo(3.0);
            assertThat(sep.getRow(3).getCell(0).getNumericCellValue()).isEqualTo(4.0);
            assertThat(sep.getRow(1).getCell(3).getNumericCellValue()).isEqualTo(1000.0); // 수입금액 숫자
            assertThat(sep.getRow(3).getCell(5).getNumericCellValue()).isEqualTo(6900.0); // 마지막 거래 잔액
            assertThat(sep.getRow(4).getCell(0).getStringCellValue()).isEqualTo("합계");
            assertThat(sep.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(6000.0); // 수입 합계(1000+2000+3000)
            assertThat(sep.getRow(4).getCell(4).getNumericCellValue()).isEqualTo(0.0);    // 지출 합계
            assertThat(sep.getRow(4).getCell(5).getNumericCellValue()).isEqualTo(6900.0); // 최종 잔액
        }
    }

    /**
     * (#원장) '담당'·'회장' 라벨 아래의 "OOO (인)" 서명 칸에 지출인 이름(PAYER_SIGN_KEY)과
     * 회장 직책 멤버 이름(REVIEWER_SIGN_KEY)을 "{이름} (인)" 형태로 채운다.
     */
    @Test
    void 담당회장_라벨아래_OOO서명칸에_지출인과_회장_이름을_채운다() throws IOException {
        Path tempFile = tempDir.resolve("sign-labels.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet();
            Row labels = s.createRow(0);
            labels.createCell(3).setCellValue("담당");
            labels.createCell(5).setCellValue("회장");
            Row vals = s.createRow(1);
            vals.createCell(3).setCellValue("OOO (인)");
            vals.createCell(5).setCellValue("OOO (인)");
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) { wb.write(fos); }
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FormFillService.PAYER_SIGN_KEY, "홍길동");
        fields.put(FormFillService.REVIEWER_SIGN_KEY, "안균승");

        byte[] result = service.fill(tempFile.toString(), fields);

        try (XSSFWorkbook out = new XSSFWorkbook(new ByteArrayInputStream(result))) {
            Sheet s = out.getSheetAt(0);
            assertThat(s.getRow(1).getCell(3).getStringCellValue()).isEqualTo("홍길동 (인)"); // 담당 = 지출인
            assertThat(s.getRow(1).getCell(5).getStringCellValue()).isEqualTo("안균승 (인)"); // 회장 = 회장 직책
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
