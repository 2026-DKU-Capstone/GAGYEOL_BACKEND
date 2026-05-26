package GAGYELOL.service;

import GAGYELOL.entity.Evidence;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (#2) 통장 거래내역(.xls)을 Upstage IE 대신 POI로 직접 파싱해 원장 열을 '행별 다중값'으로 매핑하는지 검증.
 * IE가 바이너리 .xls를 못 읽어 한 행만 채워지던 문제의 핵심 수정 지점.
 */
class EvidenceLedgerParseTest {

    @TempDir Path tempDir;

    @Test
    void 통장xls를_POI로_파싱해_원장열을_행별다중값으로_매핑한다() throws Exception {
        Path bank = tempDir.resolve("거래내역조회.xls");
        try (HSSFWorkbook wb = new HSSFWorkbook()) {
            Sheet s = wb.createSheet("sheet");
            s.createRow(0).createCell(0).setCellValue("거래내역조회");
            Row h = s.createRow(3);
            String[] heads = {"No.", "거래일시", "적요", "기재내용", "찾으신금액", "맡기신금액", "거래후 잔액"};
            for (int c = 0; c < heads.length; c++) h.createCell(c).setCellValue(heads[c]);

            Row r1 = s.createRow(4);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("2025.09.20 14:26");
            r1.createCell(2).setCellValue("모바일");
            r1.createCell(3).setCellValue("카카A");
            r1.createCell(4).setCellValue(5000);
            r1.createCell(5).setCellValue(0);
            r1.createCell(6).setCellValue(100);

            Row r2 = s.createRow(5);
            r2.createCell(0).setCellValue(2);
            r2.createCell(1).setCellValue("2025.08.30 09:00");
            r2.createCell(2).setCellValue("타행");
            r2.createCell(3).setCellValue("카카B");
            r2.createCell(4).setCellValue(0);
            r2.createCell(5).setCellValue(45000);
            r2.createCell(6).setCellValue(200);

            try (FileOutputStream fos = new FileOutputStream(bank.toFile())) { wb.write(fos); }
        }

        Evidence ev = Evidence.builder().filePath(bank.toString()).fileName("거래내역조회.xls").build();
        Map<String, String> cols = EvidenceService.extractLedgerFromBankSheet(ev,
                List.of("번호", "날짜", "내용", "수입금액", "지출금액", "잔액"));

        assertThat(cols.get("수입금액")).isEqualTo("5000, 0");        // 찾으신금액 → 수입금액
        assertThat(cols.get("지출금액")).isEqualTo("0, 45000");       // 맡기신금액 → 지출금액
        assertThat(cols.get("잔액")).isEqualTo("100, 200");           // 거래후 잔액 → 잔액
        assertThat(cols.get("날짜")).isEqualTo("2025.09.20, 2025.08.30"); // 거래일시(시간 제거)
        assertThat(cols.get("내용")).isEqualTo("카카A, 카카B");        // 기재내용
        assertThat(cols.get("번호")).isEqualTo("1, 2");               // 순번
    }
}
