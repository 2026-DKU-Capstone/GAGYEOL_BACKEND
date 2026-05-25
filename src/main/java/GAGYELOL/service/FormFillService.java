package GAGYELOL.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class FormFillService {

    /** EvidenceService가 그룹 정보로 구성한 단체명(예: "단국대학 소프트웨어학과 학생회"). 양식의 "00대학 …학생회" 자리표시자 교체용 예약 키. */
    public static final String ORG_TITLE_KEY = "__ORG_TITLE__";
    /** 오늘 날짜(예: "2026년 05월 26일"). 양식의 "0000년 00월 00일" 자리표시자 교체용 예약 키. */
    public static final String TODAY_DATE_KEY = "__TODAY_DATE__";
    /** 지출인 서명란("지출인 : (인)")에 채울 이름(그룹 등록 지출인). */
    public static final String PAYER_SIGN_KEY = "__PAYER_SIGN__";
    /** 수령인 서명란("수령인 : (인)")에 채울 이름(학생증에서 추출한 수령인). */
    public static final String RECIPIENT_SIGN_KEY = "__RECIPIENT_SIGN__";

    /** fill()에서 일반 필드 루프 전에 분리해 별도 패스로 처리하는 예약 키 목록. */
    private static final List<String> RESERVED_KEYS =
            List.of(ORG_TITLE_KEY, TODAY_DATE_KEY, PAYER_SIGN_KEY, RECIPIENT_SIGN_KEY);

    /** 양식에 박혀 있는 단체명 자리표시자: "00대학 00학과(부) 00전공 학생회" 류(00/OO/○○ 등 허용)를 통째로 매칭. */
    private static final java.util.regex.Pattern ORG_PLACEHOLDER =
            java.util.regex.Pattern.compile("[0０OoＯｏ○◯]{2,}\\s*대학[\\s\\S]*?학생회");
    /** 날짜 자리표시자: "0000년 00월 00일" 류(0/０/○ 등 허용). */
    private static final java.util.regex.Pattern DATE_PLACEHOLDER =
            java.util.regex.Pattern.compile("[0０○◯]{2,4}\\s*년\\s*[0０○◯]{1,2}\\s*월\\s*[0０○◯]{1,2}\\s*일");
    /** "지출인 : (인)" 서명란. */
    private static final java.util.regex.Pattern SIGN_PAYER =
            java.util.regex.Pattern.compile("지출인\\s*[:：]\\s*\\(\\s*인\\s*\\)");
    /** "수령인 : (인)" 서명란. */
    private static final java.util.regex.Pattern SIGN_RECIPIENT =
            java.util.regex.Pattern.compile("수령인\\s*[:：]\\s*\\(\\s*인\\s*\\)");

    /**
     * 파일 확장자에 따라 DOCX 또는 XLSX 채우기를 호출합니다.
     * (backward compatibility - 이미지 없이 텍스트 필드만 채움)
     */
    public byte[] fill(String filePath, Map<String, String> allFields) throws IOException {
        return fill(filePath, allFields, Collections.emptyMap(), Collections.emptySet());
    }

    /**
     * Backward-compat 오버로드 - generatedFields 정보 없이 호출하는 기존 코드용.
     */
    public byte[] fill(String filePath, Map<String, String> allFields, Map<String, byte[]> imageFieldsBytes) throws IOException {
        return fill(filePath, allFields, imageFieldsBytes, Collections.emptySet());
    }

    /**
     * 핵심 진입점 - 텍스트 필드, 이미지 필드, 그리고 LLM 생성(분할 금지) 필드 정보를 함께 받음.
     * generatedFields에 포함된 필드는 XLSX에서 ", "로 분리하지 않고 단일 값으로 처리 → 자연어 문장 보호.
     */
    public byte[] fill(String filePath, Map<String, String> allFields, Map<String, byte[]> imageFieldsBytes,
                       java.util.Set<String> generatedFields) throws IOException {
        if (imageFieldsBytes == null) {
            imageFieldsBytes = Collections.emptyMap();
        }
        if (generatedFields == null) {
            generatedFields = Collections.emptySet();
        }
        // 자리표시자(단체명/오늘날짜/지출인·수령인 서명) 교체용 값은 예약 키로 전달됨
        // — 일반 필드 루프에서 제외하고 별도 패스로 처리
        Map<String, String> reserved = new java.util.LinkedHashMap<>();
        for (String key : RESERVED_KEYS) {
            String v = allFields.get(key);
            if (v != null) reserved.put(key, v);
        }
        if (!reserved.isEmpty()) {
            allFields = new java.util.LinkedHashMap<>(allFields);
            allFields.keySet().removeAll(reserved.keySet());
        }

        String lower = filePath.toLowerCase();
        if (lower.endsWith(".docx")) {
            return fillDocx(filePath, allFields, imageFieldsBytes, reserved);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return fillXlsx(filePath, allFields, imageFieldsBytes, generatedFields, reserved);
        } else {
            throw new IllegalArgumentException("지원하지 않는 양식 파일 형식: " + filePath);
        }
    }

    /**
     * DOCX 파일의 테이블 셀과 단락에서 필드명을 탐색하여 인접 빈 셀/다음 줄에 값을 채웁니다.
     */
    private byte[] fillDocx(String filePath, Map<String, String> allFields, Map<String, byte[]> imageFieldsBytes,
                            Map<String, String> reserved) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(fis)) {

            log.info("DOCX 채우기 시작 - 전달된 필드: {}", allFields.keySet());

            // 테이블에서 필드명 셀 탐색 → 오른쪽 빈 셀에 값 입력
            // 그룹 레이블(예: "지출인")을 컬럼별로 기억해 "지출인 소속" 형태로 매칭
            for (XWPFTable table : doc.getTables()) {
                Map<Integer, String> columnGroupLabels = new java.util.HashMap<>();
                for (XWPFTableRow row : table.getRows()) {
                    List<XWPFTableCell> cells = row.getTableCells();
                    for (int i = 0; i < cells.size(); i++) {
                        String cellText = cells.get(i).getText().trim();
                        log.info("셀 텍스트: [{}]", cellText);
                        if (cellText.isEmpty()) continue;

                        // 이 셀이 그룹 레이블인지 확인 (예: "지출인" → "지출인 소속" 같은 필드가 존재)
                        final String ct = cellText;
                        boolean isGroupLabel = allFields.keySet().stream()
                                .anyMatch(f -> f.startsWith(ct + " "));
                        if (isGroupLabel) {
                            columnGroupLabels.put(i, cellText);
                            continue;
                        }

                        // 왼쪽 컬럼의 그룹 레이블 조합해서 매칭 시도
                        String groupLabel = columnGroupLabels.getOrDefault(i - 1, "");
                        String compoundKey = groupLabel.isEmpty() ? cellText : groupLabel + " " + cellText;

                        for (Map.Entry<String, String> entry : allFields.entrySet()) {
                            String field = entry.getKey();
                            String value = entry.getValue();
                            String normalizedCell = normalize(cellText);
                            String normalizedField = normalize(field);
                            String normalizedCompound = normalize(compoundKey);
                            boolean matches = normalizedCell.contains(normalizedField)
                                    || normalizedCompound.contains(normalizedField);
                            if (!matches) continue;

                            // 1순위: 오른쪽 인접 빈 셀 채우기 (현금지출증빙서 등)
                            // OO/00/○○ 플레이스홀더 텍스트가 있는 셀도 빈 값 셀로 취급한다.
                            if (i + 1 < cells.size()) {
                                XWPFTableCell nextCell = cells.get(i + 1);
                                String nextText = nextCell.getText().trim();
                                if (nextText.isEmpty() || isOoPlaceholder(nextText)) {
                                    setCellText(nextCell, value);
                                    log.info("DOCX 필드 채우기 완료: {} = {}", field, value);
                                    break;
                                } else if (nextText.length() <= 2) {
                                    setCellText(nextCell, value + nextText);
                                    log.info("DOCX 필드 채우기 완료(단위 포함): {} = {}{}", field, value, nextText);
                                    break;
                                }
                            }

                            // 2순위: 인라인 채우기 - 레이블 셀 자체에 값 삽입 (지출 기록부 병합 셀 구조)
                            if (fillInline(cells.get(i), cellText, normalizedField, value)) {
                                log.info("DOCX 필드 채우기 완료(인라인): {} = {}", field, value);
                                break;
                            }
                        }
                    }
                }
            }

            // 단락에서 {{필드명}} 플레이스홀더 교체
            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                replacePlaceholders(paragraph, allFields);
            }
            // 테이블 셀 내부 단락도 교체
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph para : cell.getParagraphs()) {
                            replacePlaceholders(para, allFields);
                        }
                    }
                }
            }

            // 이미지 필드 삽입 - 텍스트 채우기 완료 후 별도 단계로 실행
            if (!imageFieldsBytes.isEmpty()) {
                insertDocxImages(doc, imageFieldsBytes);
            }

            // 자리표시자 교체: 단체명("00대학 …학생회") · 오늘 날짜("0000년 00월 00일") · 지출인/수령인 서명
            applyTemplateReplacements(doc, reserved);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    /** DOCX 전체(상위 단락·표 셀 단락)에서 예약 키 자리표시자(단체명/날짜/서명)를 교체. */
    private void applyTemplateReplacements(XWPFDocument doc, Map<String, String> reserved) {
        if (reserved == null || reserved.isEmpty()) return;
        for (XWPFParagraph p : doc.getParagraphs()) replaceTemplatesInParagraph(p, reserved);
        for (XWPFTable t : doc.getTables()) {
            for (XWPFTableRow r : t.getRows()) {
                for (XWPFTableCell c : r.getTableCells()) {
                    for (XWPFParagraph p : c.getParagraphs()) replaceTemplatesInParagraph(p, reserved);
                }
            }
        }
    }

    private void replaceTemplatesInParagraph(XWPFParagraph para, Map<String, String> reserved) {
        String text = para.getText();
        if (text == null || text.isEmpty()) return;
        String replaced = applyTemplateText(text, reserved);
        if (replaced.equals(text)) return;
        // 자리표시자가 여러 run에 걸쳐 있을 수 있으므로 첫 run에 결과를 모으고 나머지는 제거
        for (int i = para.getRuns().size() - 1; i > 0; i--) para.removeRun(i);
        if (para.getRuns().isEmpty()) {
            para.createRun().setText(replaced);
        } else {
            para.getRuns().get(0).setText(replaced, 0);
        }
        log.info("DOCX 자리표시자 교체 → {}", replaced);
    }

    /** XLSX/XLS 전체 시트의 문자열 셀에서 예약 키 자리표시자를 교체. */
    private void applyTemplateReplacements(Workbook workbook, Map<String, String> reserved) {
        if (reserved == null || reserved.isEmpty()) return;
        for (int si = 0; si < workbook.getNumberOfSheets(); si++) {
            Sheet sheet = workbook.getSheetAt(si);
            for (Row row : sheet) {
                if (row == null) continue;
                short last = row.getLastCellNum();
                for (int ci = 0; ci < last; ci++) {
                    Cell cell = row.getCell(ci);
                    if (cell == null || cell.getCellType() != CellType.STRING) continue;
                    String v = cell.getStringCellValue();
                    if (v == null || v.isEmpty()) continue;
                    String replaced = applyTemplateText(v, reserved);
                    if (!replaced.equals(v)) {
                        cell.setCellValue(replaced);
                        log.info("XLSX 자리표시자 교체 → {}", replaced);
                    }
                }
            }
        }
    }

    /** 예약 키 값으로 자리표시자(단체명/오늘날짜/지출인·수령인 서명)를 텍스트에서 교체. */
    private String applyTemplateText(String text, Map<String, String> reserved) {
        String orgTitle = reserved.get(ORG_TITLE_KEY);
        if (orgTitle != null && !orgTitle.isBlank()) {
            text = ORG_PLACEHOLDER.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(orgTitle));
        }
        String date = reserved.get(TODAY_DATE_KEY);
        if (date != null && !date.isBlank()) {
            text = DATE_PLACEHOLDER.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(date));
        }
        String payer = reserved.get(PAYER_SIGN_KEY);
        if (payer != null && !payer.isBlank()) {
            text = SIGN_PAYER.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement("지출인 : " + payer + " (인)"));
        }
        String recipient = reserved.get(RECIPIENT_SIGN_KEY);
        if (recipient != null && !recipient.isBlank()) {
            text = SIGN_RECIPIENT.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement("수령인 : " + recipient + " (인)"));
        }
        return text;
    }

    /**
     * DOCX 테이블에서 이미지 플레이스홀더 셀을 찾아 이미지를 삽입합니다.
     * 매칭 우선순위:
     *   1) 셀 텍스트가 필드명과 정확히 일치 → 오른쪽 인접 빈 셀에 삽입
     *   2) 셀 텍스트가 필드명을 포함하거나 그 반대(예: 셀 "학생증 부착(...)" ⊃ 필드 "학생증")
     *   3) 필드명이 이미지성 키워드(사진/이미지/학생증/영수증)이고 셀이 "부착"/"사진"/"이미지" 포함
     * 2)·3)의 경우 인접 빈 셀이 없으면 매칭된 셀 자체에 새 단락으로 이미지 추가(레이블 텍스트는 유지).
     * 같은 셀에 두 번 삽입하지 않도록 추적합니다.
     */
    private void insertDocxImages(XWPFDocument doc, Map<String, byte[]> imageFieldsBytes) {
        java.util.Set<XWPFTableCell> usedCells = new java.util.HashSet<>();

        for (Map.Entry<String, byte[]> imgEntry : imageFieldsBytes.entrySet()) {
            String fieldName = imgEntry.getKey();
            byte[] imageBytes = normalizeToPng(imgEntry.getValue(), fieldName);
            if (imageBytes == null || imageBytes.length == 0) continue;

            int docxPicType = XWPFDocument.PICTURE_TYPE_PNG;

            boolean inserted = insertDocxImageInMatchingCell(doc, fieldName, imageBytes, docxPicType, usedCells);
            if (!inserted) {
                log.warn("DOCX 이미지 플레이스홀더를 찾지 못함: {}", fieldName);
            }
        }
    }

    private boolean insertDocxImageInMatchingCell(XWPFDocument doc, String fieldName, byte[] imageBytes,
                                                   int docxPicType, java.util.Set<XWPFTableCell> usedCells) {
        String normalizedField = normalize(fieldName);

        // Pass 1: 정확 일치 → 오른쪽 빈 셀에 삽입 (전통적 2열 레이블/값 구조)
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                List<XWPFTableCell> cells = row.getTableCells();
                for (int i = 0; i < cells.size(); i++) {
                    XWPFTableCell labelCell = cells.get(i);
                    if (usedCells.contains(labelCell)) continue;
                    String cellText = labelCell.getText().trim();
                    if (cellText.isEmpty()) continue;
                    if (!normalize(cellText).equals(normalizedField)) continue;

                    if (i + 1 < cells.size()) {
                        XWPFTableCell next = cells.get(i + 1);
                        if (next.getText().trim().isEmpty() && !usedCells.contains(next)) {
                            if (addPictureToCell(next, fieldName, imageBytes, docxPicType)) {
                                usedCells.add(next);
                                return true;
                            }
                        }
                    }
                }
            }
        }

        // Pass 2a: 구체적 매칭 — 셀이 필드명을 포함하거나 반대
        // (예: 필드 "영수증" → 셀 "영수증 부착(...)" 우선 매칭; 같은 행의 "학생증 부착(...)"은 키워드만 일치하므로 후순위)
        for (XWPFTable table : doc.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    if (usedCells.contains(cell)) continue;
                    String cellTextRaw = cell.getText().trim();
                    if (cellTextRaw.isEmpty()) continue;
                    String cellText = normalize(cellTextRaw);
                    if (!cellText.contains(normalizedField) && !normalizedField.contains(cellText)) continue;

                    if (addPictureToCell(cell, fieldName, imageBytes, docxPicType)) {
                        usedCells.add(cell);
                        return true;
                    }
                }
            }
        }

        // Pass 2b: 키워드 폴백 — 필드명이 이미지성(사진/이미지/학생증/영수증)이고
        // 셀에 부착/사진/이미지 키워드가 있을 때. "사진" 같은 제너릭 필드명 대응.
        boolean fieldIsImageish = isImageishLabel(normalizedField);
        if (fieldIsImageish) {
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (usedCells.contains(cell)) continue;
                        String cellTextRaw = cell.getText().trim();
                        if (cellTextRaw.isEmpty()) continue;
                        String cellText = normalize(cellTextRaw);
                        if (!cellText.contains("부착") && !cellText.contains("사진") && !cellText.contains("이미지")) continue;

                        if (addPictureToCell(cell, fieldName, imageBytes, docxPicType)) {
                            usedCells.add(cell);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isImageishLabel(String normalizedField) {
        return normalizedField.contains("사진") || normalizedField.contains("이미지")
                || normalizedField.contains("학생증") || normalizedField.contains("영수증");
    }

    /** 양식 칸 이미지 최대 너비(EMU). 셀이 너무 넓어도 이 이상 키우지 않음. */
    private static final int MAX_IMG_WIDTH_EMU = 16 * 360000;   // 16cm
    /** 양식 칸 이미지 최대 높이(EMU). A4 1페이지를 넘기지 않도록 제한(행 높이와 무관). */
    private static final int MAX_IMG_HEIGHT_EMU = 10 * 360000;  // 10cm

    private boolean addPictureToCell(XWPFTableCell cell, String fieldName, byte[] imageBytes,
                                      int docxPicType) {
        try {
            clearCellContent(cell);
            XWPFParagraph para = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
            para.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun run = para.createRun();
            int[] box = getCellDimensionsEmu(cell);
            // 너비는 셀 너비 기준(상한 MAX_IMG_WIDTH). 높이는 행 높이가 불안정하므로(짧은 행이면
            // 0.9cm로 쪼그라들고, 큰 이미지는 행을 늘려 페이지를 넘김) 행 높이 대신 상한(MAX_IMG_HEIGHT)으로
            // 제한한다. 박스 안에서 원본 비율을 유지하며 최대 크기로 맞춤.
            int widthEmu = Math.min(box[0], MAX_IMG_WIDTH_EMU);
            int boxWpx = Math.max(1, (int) Math.round(widthEmu / (double) Units.EMU_PER_PIXEL * 0.95));
            int boxHpx = Math.max(1, (int) Math.round(MAX_IMG_HEIGHT_EMU / (double) Units.EMU_PER_PIXEL));
            int[] dims = fitDimensionsEmu(imageBytes, boxWpx, boxHpx);
            try (ByteArrayInputStream imgStream = new ByteArrayInputStream(imageBytes)) {
                run.addPicture(imgStream, docxPicType, fieldName, dims[0], dims[1]);
            }
            log.info("DOCX 이미지 삽입 완료: {} ({} bytes, {}x{} EMU)", fieldName, imageBytes.length, dims[0], dims[1]);
            return true;
        } catch (InvalidFormatException | IOException e) {
            log.warn("DOCX 이미지 삽입 실패: {} - {}", fieldName, e.getMessage());
            return false;
        }
    }

    private static final Pattern TWIPS_W = Pattern.compile("w:w=\"(\\d+)\"");
    private static final Pattern GRIDSPAN_VAL = Pattern.compile("w:val=\"(\\d+)\"");

    /**
     * 이미지를 넣을 셀의 실제 너비·높이를 계산해 EMU로 반환한다.
     * 너비: 셀에 tcW가 명시돼 있으면 사용하고, 없으면(많은 양식이 tblGrid에만 너비를 둠)
     *       tblGrid 전체 너비를 gridSpan 비율로 안분해 병합 셀 너비를 추정한다.
     * 높이: 행 trHeight(twips). 읽기 실패 시 기본값(260×340px) 사용.
     */
    private int[] getCellDimensionsEmu(XWPFTableCell cell) {
        Integer widthTwips = null;
        Integer heightTwips = null;
        try {
            CTTcPr tcPr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : null;
            if (tcPr != null && tcPr.isSetTcW()) {
                Matcher m = TWIPS_W.matcher(tcPr.getTcW().xmlText());
                if (m.find()) {
                    int t = Integer.parseInt(m.group(1));
                    if (t > 100) widthTwips = t;
                }
            }
            XWPFTableRow row = cell.getTableRow();
            if (widthTwips == null && row != null) {
                widthTwips = estimatedCellWidthTwips(row, cell); // tcW 미설정 양식 대응
            }
            if (row != null) {
                int rh = row.getHeight(); // trHeight (twips), 미설정 시 0
                if (rh > 100) heightTwips = rh;
            }
        } catch (Exception e) {
            log.info("셀 크기 계산 실패, 기본값 사용: {}", e.getMessage());
        }
        int w = (widthTwips != null) ? widthTwips * 635 : Units.toEMU(260);  // 1 twip = 635 EMU
        int h = (heightTwips != null) ? heightTwips * 635 : Units.toEMU(340);
        log.info("DOCX 이미지 셀 크기: twipsW={}, twipsH={} → EMU {}x{}", widthTwips, heightTwips, w, h);
        return new int[]{w, h};
    }

    /** tblGrid 전체 너비를 gridSpan 비율로 안분해 (병합) 셀 너비(twips)를 추정. tcW가 없는 양식 대응. */
    private Integer estimatedCellWidthTwips(XWPFTableRow row, XWPFTableCell cell) {
        try {
            XWPFTable table = row.getTable();
            if (table == null) return null;
            int total = 0, cols = 0;
            Matcher gm = TWIPS_W.matcher(table.getCTTbl().getTblGrid().xmlText());
            while (gm.find()) { total += Integer.parseInt(gm.group(1)); cols++; }
            if (cols == 0 || total <= 100) return null;
            int span = gridSpanOf(cell);
            int est = (int) Math.round((double) total * span / cols);
            return est > 100 ? est : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 셀의 gridSpan(가로 병합 칸 수). 미설정 시 1. */
    private int gridSpanOf(XWPFTableCell cell) {
        try {
            CTTcPr p = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : null;
            if (p != null && p.isSetGridSpan()) {
                Matcher m = GRIDSPAN_VAL.matcher(p.getGridSpan().xmlText());
                if (m.find()) return Math.max(1, Integer.parseInt(m.group(1)));
            }
        } catch (Exception ignored) {}
        return 1;
    }

    /** 셀의 모든 단락에서 run(텍스트)을 제거한다. 단락 구조는 유지하고 첫 단락을 이미지용으로 재사용한다. (#5) */
    private void clearCellContent(XWPFTableCell cell) {
        for (XWPFParagraph para : cell.getParagraphs()) {
            for (int i = para.getRuns().size() - 1; i >= 0; i--) {
                para.removeRun(i);
            }
        }
    }

    /**
     * 박스(maxWpx×maxHpx) 안에 원본 비율을 유지하며 최대 크기로 맞춘 EMU 크기를 반환한다.
     * scale = min(maxW/원본W, maxH/원본H). 디코딩 실패 시 박스 크기를 그대로 사용. (#5)
     */
    int[] fitDimensionsEmu(byte[] imageBytes, int maxWpx, int maxHpx) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
            BufferedImage img = ImageIO.read(bis);
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                double scale = Math.min((double) maxWpx / img.getWidth(), (double) maxHpx / img.getHeight());
                int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
                return new int[]{Units.toEMU(w), Units.toEMU(h)};
            }
        } catch (IOException e) {
            log.warn("이미지 크기 계산 실패, 기본 박스 크기 사용 - {}", e.getMessage());
        }
        return new int[]{Units.toEMU(maxWpx), Units.toEMU(maxHpx)};
    }

    private void replacePlaceholders(XWPFParagraph paragraph, Map<String, String> allFields) {
        String text = paragraph.getText();
        boolean changed = false;
        for (Map.Entry<String, String> entry : allFields.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            if (text.contains(placeholder)) {
                text = text.replace(placeholder, entry.getValue());
                changed = true;
            }
        }
        if (changed) {
            // 모든 run을 제거하고 첫 번째 run에 교체된 텍스트 설정
            for (int i = paragraph.getRuns().size() - 1; i > 0; i--) {
                paragraph.removeRun(i);
            }
            if (!paragraph.getRuns().isEmpty()) {
                paragraph.getRuns().get(0).setText(text, 0);
            }
        }
    }

    private String normalize(String text) {
        return text
                .replace(' ', ' ')  // non-breaking space
                .replace('＆', '&')       // full-width ampersand
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * OO/00/oo/○○ 패턴이 포함된 플레이스홀더 셀인지 판별.
     * 예) "OO대학 OO학과(부) OO전공" → true
     * 실제 채워진 값 셀("SW융합대학 소프트웨어학과") → false
     */
    private static final Pattern OO_PLACEHOLDER = Pattern.compile("OO|oo|○○|00(?=\\D|$)");
    private static boolean isOoPlaceholder(String text) {
        return OO_PLACEHOLDER.matcher(text).find();
    }

    /**
     * 레이블 셀 자체에 값을 삽입한다 (병합 셀 구조에서 별도 값 칸이 없는 경우).
     * 패턴 1: "레이블 :       (인)" → "레이블 : 값  (인)"
     * 패턴 2: "레이블 :"       → "레이블 : 값"
     * 패턴 3: 셀 텍스트 = 필드명  → 값으로 교체
     */
    private boolean fillInline(XWPFTableCell cell, String rawCellText, String normalizedField, String value) {
        String trimmed = rawCellText.trim();

        if (trimmed.contains(":") && trimmed.contains("(인)")) {
            int colonIdx = trimmed.indexOf(":");
            int inIdx = trimmed.indexOf("(인)");
            if (colonIdx < inIdx) {
                String beforeColon = trimmed.substring(0, colonIdx + 1);
                String inPart = trimmed.substring(inIdx);
                setCellText(cell, beforeColon + " " + value + "  " + inPart);
                return true;
            }
        }

        if (trimmed.endsWith(":")) {
            setCellText(cell, trimmed + " " + value);
            return true;
        }

        if (normalize(trimmed).equals(normalizedField)) {
            clearCellText(cell, value);
            return true;
        }

        // 패턴 4: "[회장       (인)]" 형태 — 콜론 없이 (인)만 있는 경우
        // 필드명 뒤 공백에 이름을 삽입 → "[회장 값  (인)]"
        if (trimmed.contains("(인)") && normalize(trimmed).contains(normalizedField)) {
            int inIdx = trimmed.indexOf("(인)");
            String beforeIn = trimmed.substring(0, inIdx).stripTrailing();
            String afterIn = trimmed.substring(inIdx);
            setCellText(cell, beforeIn + " " + value + "  " + afterIn);
            return true;
        }

        return false;
    }

    // 셀의 모든 단락·run을 비우고 첫 run에만 값을 씀 (여러 단락으로 된 레이블 셀 교체용)
    private void clearCellText(XWPFTableCell cell, String value) {
        enableWordWrap(cell);
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER); // 값 수직 가운데 (#6)
        boolean valueSet = false;
        for (XWPFParagraph para : cell.getParagraphs()) {
            para.setAlignment(ParagraphAlignment.CENTER); // 값 수평 가운데 (#6)
            for (int j = 0; j < para.getRuns().size(); j++) {
                if (!valueSet) {
                    para.getRuns().get(j).setText(value, 0);
                    valueSet = true;
                } else {
                    para.getRuns().get(j).setText("", 0);
                }
            }
        }
        if (!valueSet) {
            XWPFParagraph para = cell.getParagraphs().isEmpty()
                    ? cell.addParagraph() : cell.getParagraphs().get(0);
            para.setAlignment(ParagraphAlignment.CENTER);
            para.createRun().setText(value);
        }
    }

    private void setCellText(XWPFTableCell cell, String value) {
        enableWordWrap(cell);
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER); // 값 수직 가운데 (#6)
        XWPFParagraph para;
        if (cell.getParagraphs().isEmpty()) {
            para = cell.addParagraph();
            para.createRun().setText(value);
        } else {
            para = cell.getParagraphs().get(0);
            if (para.getRuns().isEmpty()) {
                para.createRun().setText(value);
            } else {
                para.getRuns().get(0).setText(value, 0);
            }
        }
        para.setAlignment(ParagraphAlignment.CENTER); // 값 수평 가운데 (#6)
    }

    private void enableWordWrap(XWPFTableCell cell) {
        CTTc ctTc = cell.getCTTc();
        CTTcPr tcPr = ctTc.isSetTcPr() ? ctTc.getTcPr() : ctTc.addNewTcPr();
        if (tcPr.isSetNoWrap()) {
            tcPr.unsetNoWrap();
        }
    }

    /**
     * XLSX/XLS 파일에서 필드명이 포함된 셀을 탐색하여 인접 빈 셀에 값을 채웁니다.
     * WorkbookFactory를 사용하여 XLS(구버전 바이너리)도 지원합니다.
     */
    /**
     * IE가 행별 데이터를 한 셀에 ", "(콤마+공백)로 이어 반환하는 형태를 행 단위 리스트로 분리.
     * - 값 내부의 천 단위 콤마(예: "9,000")는 공백이 없어 보존됨.
     * - 자연어 문장(생성된 "내용" 등) 보호: 각 토큰이 짧을 때만 다중행으로 인식.
     * - 빈 토큰("16,000, , 9,000" 형태의 누락된 행)도 보존해서 행 정렬 유지.
     * - 다중행으로 판단되지 않으면 원본을 단일 원소 리스트로 반환.
     */
    private static final int MULTIROW_MIN_PARTS = 3;
    private static final int MULTIROW_MAX_TOKEN_LEN = 30;
    /** 표 데이터 시트 복제(페이지) 상한 — cloneSheet 폭주로 인한 OutOfMemoryError 방지. */
    private static final int MAX_TABLE_PAGES = 30;

    /**
     * startRow부터 아래로 스캔해 '합계/소계/총계/총액' 라벨이 있는 행 번호를 찾는다. 없으면 -1.
     * 다중행 데이터를 이 행 위까지만 채우기 위한 경계로 사용한다.
     */
    private int findSumRowIndex(Sheet sheet, int startRow) {
        int last = sheet.getLastRowNum();
        for (int r = startRow; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                if (cell == null || cell.getCellType() != CellType.STRING) continue;
                String t = cell.getStringCellValue().replace(" ", "");
                if (t.contains("합계") || t.contains("소계") || t.contains("총계") || t.contains("총액")) {
                    return r;
                }
            }
        }
        return -1;
    }

    /** 단일 값 채우기: 오른쪽 빈 셀 우선, 없으면 아래 빈 셀. (#6 가운데 정렬) */
    private void fillSingleValue(Workbook workbook, Sheet sheet, Row row, int ci, String value, Map<Short, CellStyle> cache) {
        Cell rightCell = row.getCell(ci + 1);
        if (rightCell == null || rightCell.getCellType() == CellType.BLANK) {
            if (rightCell == null) rightCell = row.createCell(ci + 1);
            rightCell.setCellValue(value);
            applyCenter(workbook, rightCell, cache);
        } else {
            Row nextRow = sheet.getRow(row.getRowNum() + 1);
            if (nextRow == null) nextRow = sheet.createRow(row.getRowNum() + 1);
            Cell belowCell = nextRow.getCell(ci);
            if (belowCell == null || belowCell.getCellType() == CellType.BLANK) {
                if (belowCell == null) belowCell = nextRow.createCell(ci);
                belowCell.setCellValue(value);
                applyCenter(workbook, belowCell, cache);
            }
        }
    }

    /** 다중행(표) 채우기 1건: 어느 시트의 어느 헤더 행/열에 어떤 값 리스트를 펼칠지. */
    private static class MultiRowFill {
        final int sheetIndex;
        final int headerRow;
        final int col;
        final List<String> values;
        MultiRowFill(int sheetIndex, int headerRow, int col, List<String> values) {
            this.sheetIndex = sheetIndex;
            this.headerRow = headerRow;
            this.col = col;
            this.values = values;
        }
    }

    /**
     * 수집한 표 데이터를 채운다. 헤더 행 아래 '합계' 행 위까지만 채우고, 칸을 초과하면
     * 같은 양식 시트를 복제해 다음 양식지(시트)로 이어 쓴다.
     * 같은 시트·헤더의 여러 컬럼은 동일한 페이지 경계로 함께 분할된다.
     */
    /**
     * 시트의 '구조'(필드 라벨 셀 + 합계 표식 셀)만으로 서명을 만든다.
     * 월별 12개 복제 시트처럼 데이터/월 라벨("3월" 등)만 다르고 양식 골격이 같은 시트를
     * 같은 것으로 인식해 중복 제거(첫 시트에만 채움)하기 위함. 전체 내용 비교가 아니라
     * 라벨 위치+텍스트만 쓰므로 월 표기가 달라도 동일 서명이 된다.
     */
    private String sheetSignature(Sheet sheet, java.util.Set<String> fieldNames) {
        StringBuilder sb = new StringBuilder();
        int last = sheet.getLastRowNum();
        for (int r = 0; r <= last && sb.length() < 4000; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            short lc = row.getLastCellNum();
            for (int c = 0; c < lc; c++) {
                Cell cell = row.getCell(c);
                if (cell == null || cell.getCellType() != CellType.STRING) continue;
                String v = cell.getStringCellValue();
                if (v == null || v.isBlank()) continue;
                String nv = normalize(v);
                boolean structural = nv.contains("합계") || nv.contains("소계") || nv.contains("총계") || nv.contains("총액");
                if (!structural) {
                    for (String f : fieldNames) {
                        if (f != null && !f.isBlank() && nv.contains(normalize(f))) { structural = true; break; }
                    }
                }
                if (structural) sb.append(r).append(':').append(c).append('=').append(nv).append('|');
            }
        }
        return sb.toString();
    }

    private void applyMultiRowFills(Workbook workbook, List<MultiRowFill> fills, Map<Short, CellStyle> cache) {
        if (fills.isEmpty()) return;
        // (시트, 헤더 행)별 그룹 — 한 표의 여러 컬럼을 같은 페이지 경계로 함께 분할
        Map<String, List<MultiRowFill>> groups = new java.util.LinkedHashMap<>();
        for (MultiRowFill f : fills) {
            groups.computeIfAbsent(f.sheetIndex + ":" + f.headerRow, k -> new java.util.ArrayList<>()).add(f);
        }

        for (List<MultiRowFill> group : groups.values()) {
            MultiRowFill any = group.get(0);
            Sheet origSheet = workbook.getSheetAt(any.sheetIndex);
            int startRow = any.headerRow + 1;
            int sumRowIdx = findSumRowIndex(origSheet, startRow);
            int capacity = (sumRowIdx >= 0) ? (sumRowIdx - startRow) : Integer.MAX_VALUE;
            if (capacity <= 0) {
                log.warn("XLSX 표 채우기: 합계 행 바로 위에 빈 칸이 없어 건너뜀 (headerRow={})", any.headerRow);
                continue;
            }

            int maxTokens = 0;
            for (MultiRowFill f : group) maxTokens = Math.max(maxTokens, f.values.size());
            int pages = (capacity == Integer.MAX_VALUE) ? 1 : (int) Math.ceil((double) maxTokens / capacity);

            // 안전장치: 시트 복제 폭주로 인한 OutOfMemoryError 방지.
            // (합계 행이 헤더 바로 아래라 capacity가 작거나 토큰이 비정상적으로 많을 때 수백 장 복제되는 것을 막음)
            if (pages > MAX_TABLE_PAGES) {
                log.warn("XLSX 표 페이지 수가 과도함({}) - {}장으로 제한 (capacity={}, maxTokens={})",
                        pages, MAX_TABLE_PAGES, capacity, maxTokens);
                pages = MAX_TABLE_PAGES;
            }

            // 페이지 시트 준비: page0 = 원본, 추가 페이지는 데이터 쓰기 전의 깨끗한 원본 복제본
            List<Sheet> pageSheets = new java.util.ArrayList<>();
            pageSheets.add(origSheet);
            int origIdx = workbook.getSheetIndex(origSheet);
            for (int p = 1; p < pages; p++) {
                pageSheets.add(workbook.cloneSheet(origIdx));
            }

            for (int p = 0; p < pages; p++) {
                Sheet target = pageSheets.get(p);
                for (MultiRowFill f : group) {
                    for (int k = 0; k < capacity; k++) {
                        int tokenIdx = p * capacity + k;
                        if (tokenIdx >= f.values.size()) break;
                        String v = f.values.get(tokenIdx);
                        if (v.isEmpty()) continue; // 누락 행 보존 → 행 정렬 유지
                        Row tr = target.getRow(startRow + k);
                        if (tr == null) tr = target.createRow(startRow + k);
                        Cell tc = tr.getCell(f.col);
                        if (tc == null) tc = tr.createCell(f.col);
                        else if (tc.getCellType() != CellType.BLANK) continue; // 기존 데이터/수식 보존
                        tc.setCellValue(v);
                        applyCenter(workbook, tc, cache);
                    }
                }
            }
            if (pages > 1) {
                log.info("XLSX 표 데이터가 칸({})을 초과 - 양식 시트를 복제해 {}장으로 이어 작성", capacity, pages);
            }
        }
    }

    private java.util.List<String> splitMultiRowValue(String value) {
        if (value == null) return java.util.Collections.emptyList();
        String[] parts = value.split(",\\s+");
        if (parts.length < MULTIROW_MIN_PARTS) {
            return java.util.Collections.singletonList(value);
        }
        // 자연어 문장 보호: 한 토큰이라도 너무 길면 표 데이터가 아니라고 판단
        for (String p : parts) {
            if (p.trim().length() > MULTIROW_MAX_TOKEN_LEN) {
                return java.util.Collections.singletonList(value);
            }
        }
        java.util.List<String> result = new java.util.ArrayList<>(parts.length);
        for (String p : parts) {
            result.add(p.trim());
        }
        return result;
    }

    private byte[] fillXlsx(String filePath, Map<String, String> allFields, Map<String, byte[]> imageFieldsBytes,
                             java.util.Set<String> generatedFields, Map<String, String> reserved) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(fis)) {

            // 채운 셀에 적용할 가운데 정렬 스타일 캐시 (원본 스타일별로 1개만 생성해 스타일 수 폭증 방지) (#6)
            Map<Short, CellStyle> centerStyleCache = new java.util.HashMap<>();

            // Phase 1: 단일 값은 즉시 채우고, 다중행(표) 데이터는 수집만 한다. (합계 행 처리·시트 분할은 Phase 2)
            // 같은 구조가 반복되는 시트(예: 월별 12개 복제 시트)는 첫 시트에만 채운다.
            // — 같은 데이터를 모든 시트에 중복으로 넣지 않고, 거대한 시트를 12배 cloneSheet 하다 OOM 나는 것을 막는다.
            List<MultiRowFill> multiRowFills = new java.util.ArrayList<>();
            int originalSheetCount = workbook.getNumberOfSheets();
            java.util.Set<String> seenSheetSig = new java.util.HashSet<>();
            for (int si = 0; si < originalSheetCount; si++) {
                Sheet sheet = workbook.getSheetAt(si);
                String sig = sheetSignature(sheet, allFields.keySet());
                if (!sig.isEmpty() && !seenSheetSig.add(sig)) {
                    log.info("XLSX 동일 구조 중복 시트 건너뜀(첫 시트에만 채움): sheetIndex={}", si);
                    continue;
                }
                // 인덱스 기반 순회: 다중행 채우기로 행을 새로 만들어도 원본 행 범위만 처리하고
                // 이터레이터 ConcurrentModificationException을 피한다. (합계 등 헤더 아래 행이 있는 양식 대응)
                int lastRowNum = sheet.getLastRowNum();
                for (int ri = 0; ri <= lastRowNum; ri++) {
                    Row row = sheet.getRow(ri);
                    if (row == null) continue;
                    for (int ci = 0; ci < row.getLastCellNum(); ci++) {
                        Cell cell = row.getCell(ci);
                        if (cell == null || cell.getCellType() != CellType.STRING) continue;
                        String cellText = cell.getStringCellValue().trim();

                        for (Map.Entry<String, String> entry : allFields.entrySet()) {
                            String field = entry.getKey();
                            String value = entry.getValue();
                            if (!cellText.contains(field)) continue;

                            // 값 기반 결정: 표 데이터(다중 짧은 토큰)인지 단일 값인지.
                            // generatedFields에 명시된 필드(LLM 생성 자연어)는 절대 분할 안 함.
                            // 양식 레이아웃(오른쪽 셀 비어있는지)으로 분기하면 "| 번호 |   | 날짜 |" 처럼
                            // 헤더 사이에 빈 데이터 칸이 있는 표 양식에서 통문자열이 한 셀에 들어가는 버그가 있음.
                            List<String> rowValues = generatedFields.contains(field)
                                    ? Collections.singletonList(value)
                                    : splitMultiRowValue(value);

                            if (rowValues.size() >= MULTIROW_MIN_PARTS) {
                                // 표 데이터: 합계 행 처리·시트 분할을 위해 수집만 하고 Phase 2에서 채운다.
                                multiRowFills.add(new MultiRowFill(si, row.getRowNum(), ci, rowValues));
                            } else {
                                fillSingleValue(workbook, sheet, row, ci, value, centerStyleCache);
                                log.debug("XLSX 필드 채우기(단일): {} = {}", field, value);
                            }
                        }
                    }
                }
            }

            // Phase 2: 표 데이터를 합계 행 위까지 채우고, 칸을 초과하면 양식 시트를 복제해 다음 양식지로 이어 쓴다.
            applyMultiRowFills(workbook, multiRowFills, centerStyleCache);

            // 자리표시자 교체: 단체명·오늘 날짜·지출인/수령인 서명 (복제된 페이지의 머리글도 함께 교체됨)
            applyTemplateReplacements(workbook, reserved);

            // 이미지 필드 삽입 - 텍스트 채우기 완료 후 별도 단계로 실행
            if (!imageFieldsBytes.isEmpty()) {
                insertXlsxImages(workbook, imageFieldsBytes);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 채운 셀에 가운데 정렬을 적용한다. 원본 셀 스타일(테두리·폰트·서식)을 복제한 뒤
     * 정렬만 변경해 기존 양식 서식을 보존하고, 원본 스타일별로 1개만 만들어 캐시한다. (#6)
     */
    private void applyCenter(Workbook workbook, Cell cell, Map<Short, CellStyle> cache) {
        CellStyle source = cell.getCellStyle();
        CellStyle centered = cache.computeIfAbsent(source.getIndex(), idx -> {
            CellStyle s = workbook.createCellStyle();
            s.cloneStyleFrom(source);
            s.setAlignment(HorizontalAlignment.CENTER);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            return s;
        });
        cell.setCellStyle(centered);
    }

    /**
     * XLSX/XLS 시트에서 이미지 플레이스홀더 셀을 찾아 인접 영역에 이미지를 삽입합니다.
     * DOCX와 동일한 매칭 정책:
     *   1) 정확 일치 → 오른쪽 인접 영역에 앵커
     *   2) 부분 일치(셀⊃필드 or 필드⊃셀)
     *   3) 필드가 이미지성이고 셀이 "부착/사진/이미지" 키워드 포함 → 같은 셀 위치에 앵커
     */
    private void insertXlsxImages(Workbook workbook, Map<String, byte[]> imageFieldsBytes) {
        CreationHelper helper = workbook.getCreationHelper();
        java.util.Set<String> usedCellKeys = new java.util.HashSet<>();

        for (Map.Entry<String, byte[]> imgEntry : imageFieldsBytes.entrySet()) {
            String fieldName = imgEntry.getKey();
            byte[] imageBytes = normalizeToPng(imgEntry.getValue(), fieldName);
            if (imageBytes == null || imageBytes.length == 0) continue;

            String normalizedField = normalize(fieldName);
            boolean fieldIsImageish = isImageishLabel(normalizedField);
            int pictureIndex = workbook.addPicture(imageBytes, Workbook.PICTURE_TYPE_PNG);

            boolean inserted = false;
            // Pass 1: 정확 일치
            for (int si = 0; si < workbook.getNumberOfSheets() && !inserted; si++) {
                Sheet sheet = workbook.getSheetAt(si);
                for (Row row : sheet) {
                    if (inserted) break;
                    short last = row.getLastCellNum();
                    for (int ci = 0; ci < last; ci++) {
                        Cell cell = row.getCell(ci);
                        if (cell == null || cell.getCellType() != CellType.STRING) continue;
                        String cellText = cell.getStringCellValue().trim();
                        if (cellText.isEmpty()) continue;
                        String key = si + ":" + row.getRowNum() + ":" + ci;
                        if (usedCellKeys.contains(key)) continue;
                        if (!normalize(cellText).equals(normalizedField)) continue;

                        if (anchorPicture(sheet, helper, pictureIndex, row.getRowNum(), ci, fieldName, imageBytes.length, true)) {
                            usedCellKeys.add(key);
                            inserted = true;
                            break;
                        }
                    }
                }
            }
            // Pass 2a: 구체적 매칭 — 셀이 필드명을 포함하거나 반대
            for (int si = 0; si < workbook.getNumberOfSheets() && !inserted; si++) {
                Sheet sheet = workbook.getSheetAt(si);
                for (Row row : sheet) {
                    if (inserted) break;
                    short last = row.getLastCellNum();
                    for (int ci = 0; ci < last; ci++) {
                        Cell cell = row.getCell(ci);
                        if (cell == null || cell.getCellType() != CellType.STRING) continue;
                        String cellTextRaw = cell.getStringCellValue().trim();
                        if (cellTextRaw.isEmpty()) continue;
                        String key = si + ":" + row.getRowNum() + ":" + ci;
                        if (usedCellKeys.contains(key)) continue;
                        String cellText = normalize(cellTextRaw);

                        if (!cellText.contains(normalizedField) && !normalizedField.contains(cellText)) continue;

                        if (anchorPicture(sheet, helper, pictureIndex, row.getRowNum(), ci, fieldName, imageBytes.length, false)) {
                            usedCellKeys.add(key);
                            inserted = true;
                            break;
                        }
                    }
                }
            }
            // Pass 2b: 키워드 폴백 — 필드가 이미지성이고 셀에 부착/사진/이미지 키워드
            for (int si = 0; si < workbook.getNumberOfSheets() && !inserted && fieldIsImageish; si++) {
                Sheet sheet = workbook.getSheetAt(si);
                for (Row row : sheet) {
                    if (inserted) break;
                    short last = row.getLastCellNum();
                    for (int ci = 0; ci < last; ci++) {
                        Cell cell = row.getCell(ci);
                        if (cell == null || cell.getCellType() != CellType.STRING) continue;
                        String cellTextRaw = cell.getStringCellValue().trim();
                        if (cellTextRaw.isEmpty()) continue;
                        String key = si + ":" + row.getRowNum() + ":" + ci;
                        if (usedCellKeys.contains(key)) continue;
                        String cellText = normalize(cellTextRaw);

                        if (!cellText.contains("부착") && !cellText.contains("사진") && !cellText.contains("이미지")) continue;

                        if (anchorPicture(sheet, helper, pictureIndex, row.getRowNum(), ci, fieldName, imageBytes.length, false)) {
                            usedCellKeys.add(key);
                            inserted = true;
                            break;
                        }
                    }
                }
            }

            if (!inserted) {
                log.warn("XLSX 이미지 플레이스홀더를 찾지 못함: {}", fieldName);
            }
        }
    }

    private boolean anchorPicture(Sheet sheet, CreationHelper helper, int pictureIndex,
                                   int labelRow, int labelCol, String fieldName, int byteLen,
                                   boolean anchorToRight) {
        try {
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = helper.createClientAnchor();
            int col1 = anchorToRight ? labelCol + 1 : labelCol;
            clearXlsxCellText(sheet, labelRow, col1); // 이미지가 덮을 셀의 기존 텍스트 제거 (#5)
            anchor.setCol1(col1);
            anchor.setRow1(labelRow);
            anchor.setCol2(col1 + 2);
            anchor.setRow2(labelRow + 4);
            drawing.createPicture(anchor, pictureIndex);
            log.info("XLSX 이미지 삽입 완료: {} ({} bytes)", fieldName, byteLen);
            return true;
        } catch (Exception e) {
            log.warn("XLSX 이미지 삽입 실패: {} - {}", fieldName, e.getMessage());
            return false;
        }
    }

    /** 이미지가 덮을 셀의 문자열 텍스트를 제거한다 (이미지 뒤로 글자가 비치지 않도록). (#5) */
    private void clearXlsxCellText(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return;
        Cell cell = row.getCell(colIdx);
        if (cell != null && cell.getCellType() == CellType.STRING) {
            cell.setBlank();
        }
    }

    /**
     * 입력 이미지 바이트를 ImageIO로 디코딩 후 PNG로 재인코딩합니다.
     * JPEG/PNG/GIF/BMP/WBMP는 JDK 기본 지원, WebP는 TwelveMonkeys SPI로 지원.
     * 알파 채널이 있는 경우 흰 배경에 합성하여 양식지 출력에서 검게 보이지 않도록 합니다.
     * 디코딩 실패 시(미지원 포맷) 원본 바이트를 그대로 반환 — 다운스트림에서 실패하지만 호출자 흐름은 유지.
     */
    private byte[] normalizeToPng(byte[] imageBytes, String fieldName) {
        if (imageBytes == null || imageBytes.length == 0) return imageBytes;
        try {
            BufferedImage src;
            try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
                src = ImageIO.read(bis);
            }
            if (src == null) {
                log.warn("이미지 디코딩 실패(미지원 포맷일 수 있음) - field={}, bytes={}", fieldName, imageBytes.length);
                return imageBytes;
            }
            BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                if (!ImageIO.write(rgb, "png", bos)) {
                    log.warn("PNG writer를 찾지 못함 - field={}", fieldName);
                    return imageBytes;
                }
                return bos.toByteArray();
            }
        } catch (IOException e) {
            log.warn("이미지 정규화 실패, 원본 사용 - field={}: {}", fieldName, e.getMessage());
            return imageBytes;
        }
    }
}
