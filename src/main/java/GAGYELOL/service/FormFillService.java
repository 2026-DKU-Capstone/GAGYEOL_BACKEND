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
    /** 검토자/회장 서명란("회장 (인)", "검토자 : (인)")에 채울 이름(그룹의 회장 직급 멤버). (#4) */
    public static final String REVIEWER_SIGN_KEY = "__REVIEWER_SIGN__";

    /** fill()에서 일반 필드 루프 전에 분리해 별도 패스로 처리하는 예약 키 목록. */
    private static final List<String> RESERVED_KEYS =
            List.of(ORG_TITLE_KEY, TODAY_DATE_KEY, PAYER_SIGN_KEY, RECIPIENT_SIGN_KEY, REVIEWER_SIGN_KEY);

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
    /** 본문 "회장 (인)" 서명란(부회장 제외). */
    private static final java.util.regex.Pattern SIGN_PRESIDENT =
            java.util.regex.Pattern.compile("(?<!부)회장\\s*\\(\\s*인\\s*\\)");
    /** "검토자 : (인)" 서명란. */
    private static final java.util.regex.Pattern SIGN_REVIEWER =
            java.util.regex.Pattern.compile("검토자\\s*[:：]\\s*\\(\\s*인\\s*\\)");

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

            // 양식에 박혀 있던 불필요한 빈 줄(연속/말미 빈 단락)을 정리해 큰 빈 공간을 줄인다. (#2)
            collapseBlankParagraphs(doc);

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
        String reviewer = reserved.get(REVIEWER_SIGN_KEY);
        if (reviewer != null && !reviewer.isBlank()) {
            // 본문 "회장 (인)" → "회장 {이름} (인)", 표 "검토자 : (인)" → "검토자 : {이름} (인)" (#4)
            text = SIGN_PRESIDENT.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement("회장 " + reviewer + " (인)"));
            text = SIGN_REVIEWER.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement("검토자 : " + reviewer + " (인)"));
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
    private static final int MAX_IMG_HEIGHT_EMU = 8 * 360000;   // 8cm (1페이지 유지: 학생증+영수증 나란히 들어가도 넘침 방지)

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

    /**
     * 이미지 삽입을 위해 셀을 비운다: 첫 단락만 남기고 나머지 단락을 제거한 뒤 첫 단락의 run(텍스트)을 지운다.
     * 양식 셀에 넣어둔 여러 빈 단락(줄바꿈)이 사진 아래 큰 빈 공간으로 남는 문제를 막는다. (#2)
     */
    private void clearCellContent(XWPFTableCell cell) {
        // 첫 단락만 남기고 나머지(빈 줄 포함) 단락 제거 — 셀은 최소 1개 단락이 필요하므로 index 0은 유지
        for (int i = cell.getParagraphs().size() - 1; i >= 1; i--) {
            cell.removeParagraph(i);
        }
        for (XWPFParagraph para : cell.getParagraphs()) {
            for (int i = para.getRuns().size() - 1; i >= 0; i--) {
                para.removeRun(i);
            }
        }
    }

    /**
     * DOCX 표 셀의 과도한 빈 단락을 정리한다: 연속된 빈 단락은 1개로 줄이고, 셀 끝(마지막 내용 이후)의
     * 빈 단락은 모두 제거한다. 양식에 박혀 있던 줄바꿈 때문에 사진/문구 아래 큰 빈 공간이 생기는 것을 막는다. (#2)
     */
    private void collapseBlankParagraphs(XWPFDocument doc) {
        for (XWPFTable t : doc.getTables()) {
            for (XWPFTableRow r : t.getRows()) {
                for (XWPFTableCell c : r.getTableCells()) {
                    collapseCellBlanks(c);
                }
            }
        }
    }

    private void collapseCellBlanks(XWPFTableCell cell) {
        List<XWPFParagraph> paras = cell.getParagraphs();
        java.util.List<Integer> toRemove = new java.util.ArrayList<>();
        boolean prevBlank = false;
        int lastNonBlank = -1;
        for (int i = 0; i < paras.size(); i++) {
            boolean blank = paras.get(i).getText().trim().isEmpty();
            if (blank && prevBlank) toRemove.add(i);   // 연속 빈 단락 → 1개로
            if (!blank) lastNonBlank = i;
            prevBlank = blank;
        }
        // 마지막 내용 단락 이후의 빈 단락(말미 여백)은 모두 제거 (셀에는 단락 1개를 남긴다)
        for (int i = paras.size() - 1; i > lastNonBlank && i >= 1; i--) {
            if (!toRemove.contains(i)) toRemove.add(i);
        }
        toRemove.sort(java.util.Collections.reverseOrder());
        for (int idx : toRemove) {
            if (cell.getParagraphs().size() <= 1) break;
            cell.removeParagraph(idx);
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

        // 패턴 1: "레이블 :   (인)" — 콜론 바로 앞 라벨이 이 필드일 때만.
        // (맺음말 "위의 내용과 같이…지출인 : (인)"처럼 문장 속에 필드명이 부분 포함된 셀은 채우지 않음)
        if (trimmed.contains(":") && trimmed.contains("(인)")) {
            int colonIdx = trimmed.indexOf(":");
            int inIdx = trimmed.indexOf("(인)");
            if (colonIdx < inIdx && labelMatchesField(trimmed.substring(0, colonIdx), normalizedField)) {
                String beforeColon = trimmed.substring(0, colonIdx + 1);
                String inPart = trimmed.substring(inIdx);
                setCellText(cell, beforeColon + " " + value + "  " + inPart);
                return true;
            }
        }

        // 패턴 2: "레이블 :" — 콜론 앞 라벨이 이 필드일 때만
        if (trimmed.endsWith(":") && labelMatchesField(trimmed.substring(0, trimmed.length() - 1), normalizedField)) {
            setCellText(cell, trimmed + " " + value);
            return true;
        }

        // 패턴 3: 셀 텍스트 = 필드명 → 값으로 교체
        if (normalize(trimmed).equals(normalizedField)) {
            clearCellText(cell, value);
            return true;
        }

        // 패턴 4: "[회장       (인)]" 형태 — 콜론 없이 (인)만 있는 경우.
        // (인) 바로 앞 라벨이 이 필드일 때만 채운다.
        if (trimmed.contains("(인)")) {
            int inIdx = trimmed.indexOf("(인)");
            if (labelMatchesField(trimmed.substring(0, inIdx), normalizedField)) {
                String beforeIn = trimmed.substring(0, inIdx).stripTrailing();
                String afterIn = trimmed.substring(inIdx);
                setCellText(cell, beforeIn + " " + value + "  " + afterIn);
                return true;
            }
        }

        return false;
    }

    /** 콜론/(인) 바로 앞 텍스트가 이 필드를 '라벨'로 갖는지 — 끝부분이 필드명과 일치할 때만 true.
     *  문장 속에 필드명이 부분 포함된 경우(예: "…위의 내용과 같이…지출인" vs 필드 "내용")를 걸러낸다. */
    private boolean labelMatchesField(String beforeMarker, String normalizedField) {
        return normalize(beforeMarker).endsWith(normalizedField);
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
                // 첫 run 외 나머지 run은 비운다 — 여러 run으로 쪼개진 기존 텍스트가 잔류해 중복되는 것 방지
                for (int j = para.getRuns().size() - 1; j >= 1; j--) para.removeRun(j);
            }
        }
        // 같은 셀의 나머지 단락에 남은 텍스트도 제거 (중복 방지)
        for (int p = 1; p < cell.getParagraphs().size(); p++) {
            XWPFParagraph extra = cell.getParagraphs().get(p);
            for (int j = extra.getRuns().size() - 1; j >= 0; j--) extra.removeRun(j);
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
        // 위치는 무시하고 '라벨 텍스트 집합'만 사용 → 합계 행 위치나 월 표기가 조금씩 달라도
        // 동일 양식이면 같은 서명이 된다. (헤더/합계는 상단부에 있으므로 앞쪽 행만 스캔)
        java.util.TreeSet<String> labels = new java.util.TreeSet<>();
        int last = Math.min(sheet.getLastRowNum(), 200);
        for (int r = 0; r <= last; r++) {
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
                if (structural) labels.add(nv);
            }
        }
        return String.join("|", labels);
    }

    private void applyMultiRowFills(Workbook workbook, List<MultiRowFill> fills, Map<Short, CellStyle> cache) {
        if (fills.isEmpty()) return;
        // (시트, 헤더 행)별 그룹 — 한 표의 여러 컬럼을 함께 처리
        Map<String, List<MultiRowFill>> groups = new java.util.LinkedHashMap<>();
        for (MultiRowFill f : fills) {
            groups.computeIfAbsent(f.sheetIndex + ":" + f.headerRow, k -> new java.util.ArrayList<>()).add(f);
        }

        for (List<MultiRowFill> group : groups.values()) {
            MultiRowFill any = group.get(0);
            Sheet sheet = workbook.getSheetAt(any.sheetIndex);
            int startRow = any.headerRow + 1;
            int maxTokens = 0;
            for (MultiRowFill f : group) maxTokens = Math.max(maxTokens, f.values.size());
            if (maxTokens == 0) continue;

            int sumRowIdx = findSumRowIndex(sheet, startRow);

            // 합계 행이 있고 데이터가 그 위 칸을 초과하면, 시트를 복제하지 않고
            // 합계 행 바로 앞에 부족분만큼 행을 삽입(성장)해 한 시트에 모두 채운다.
            // (합계 행은 데이터 끝으로 밀려 내려가고, 합계 SUM 범위도 새 마지막 데이터 행까지 확장)
            if (sumRowIdx >= 0) {
                int capacity = sumRowIdx - startRow;
                if (capacity <= 0) {
                    log.warn("XLSX 표 채우기: 합계 행 바로 위에 빈 칸이 없어 건너뜀 (headerRow={})", any.headerRow);
                    continue;
                }
                if (maxTokens > capacity) {
                    int extra = maxTokens - capacity;
                    try {
                        int templateRow = sumRowIdx - 1; // 마지막 데이터 행 = 스타일·병합 템플릿
                        sheet.shiftRows(sumRowIdx, sheet.getLastRowNum(), extra, true, true);
                        for (int r = 0; r < extra; r++) {
                            copyRowLayout(sheet, templateRow, sumRowIdx + r);
                        }
                        int newSumRow = sumRowIdx + extra;
                        extendSumFormulas(sheet, newSumRow, sumRowIdx - 1, newSumRow - 1);
                        log.info("XLSX 표가 칸({})을 초과 - 합계 행 앞에 {}행 삽입해 한 시트에 이어 작성", capacity, extra);
                    } catch (Exception e) {
                        log.warn("XLSX 행 확장 실패, 합계 칸까지만 채움: {}", e.getMessage());
                        maxTokens = capacity; // 폴백: 넘침분은 버린다
                    }
                }
            }

            // 데이터 채우기 (startRow 부터 순서대로). 기존 데이터/수식 셀은 보존.
            for (MultiRowFill f : group) {
                for (int k = 0; k < maxTokens && k < f.values.size(); k++) {
                    String v = f.values.get(k);
                    if (v.isEmpty()) continue; // 누락 행 보존 → 행 정렬 유지
                    Row tr = sheet.getRow(startRow + k);
                    if (tr == null) tr = sheet.createRow(startRow + k);
                    Cell tc = tr.getCell(f.col);
                    if (tc == null) tc = tr.createCell(f.col);
                    else if (tc.getCellType() != CellType.BLANK) continue; // 기존 데이터/수식 보존
                    tc.setCellValue(v);
                    applyCenter(workbook, tc, cache);
                }
            }
        }
    }

    /** 템플릿 데이터 행의 셀 스타일·행 높이·가로 병합을 대상 행에 복제한다. (삽입한 새 행을 기존 표와 같게) */
    private void copyRowLayout(Sheet sheet, int srcIdx, int dstIdx) {
        Row src = sheet.getRow(srcIdx);
        Row dst = sheet.getRow(dstIdx);
        if (dst == null) dst = sheet.createRow(dstIdx);
        if (src == null) return;
        if (src.getHeight() > 0) dst.setHeight(src.getHeight());
        short last = src.getLastCellNum();
        for (int c = 0; c < last; c++) {
            Cell sc = src.getCell(c);
            if (sc == null) continue;
            Cell dc = dst.getCell(c);
            if (dc == null) dc = dst.createCell(c);
            try { dc.setCellStyle(sc.getCellStyle()); } catch (Exception ignored) {}
        }
        java.util.List<org.apache.poi.ss.util.CellRangeAddress> toAdd = new java.util.ArrayList<>();
        for (org.apache.poi.ss.util.CellRangeAddress m : sheet.getMergedRegions()) {
            if (m.getFirstRow() == srcIdx && m.getLastRow() == srcIdx) {
                toAdd.add(new org.apache.poi.ss.util.CellRangeAddress(dstIdx, dstIdx, m.getFirstColumn(), m.getLastColumn()));
            }
        }
        for (org.apache.poi.ss.util.CellRangeAddress m : toAdd) {
            try { sheet.addMergedRegion(m); } catch (Exception ignored) {}
        }
    }

    /** 합계 행의 SUM 수식 범위 끝 행을, 행 삽입 후의 새 마지막 데이터 행까지 확장한다.
     *  예) 데이터가 34행까지였고 N행 삽입했다면 SUM(M10:R34) → SUM(M10:R(34+N)). (행 인덱스는 0-based) */
    private void extendSumFormulas(Sheet sheet, int sumRowIdx, int oldLastDataRow0, int newLastDataRow0) {
        Row sumRow = sheet.getRow(sumRowIdx);
        if (sumRow == null) return;
        String oldR = String.valueOf(oldLastDataRow0 + 1); // 엑셀은 1-based
        String newR = String.valueOf(newLastDataRow0 + 1);
        if (oldR.equals(newR)) return;
        Pattern endRef = Pattern.compile("(:\\$?[A-Z]{1,3}\\$?)" + oldR + "(?![0-9])");
        for (Cell c : sumRow) {
            if (c.getCellType() != CellType.FORMULA) continue;
            String f = c.getCellFormula();
            String nf = endRef.matcher(f).replaceAll("$1" + newR);
            if (!nf.equals(f)) {
                try { c.setCellFormula(nf); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 셀이 해당 필드의 '열 머리글'인지 판별한다. 공백 제거 후 셀 텍스트가 필드명으로 시작하면 머리글로 본다. (#1)
     * 예) 필드 "잔액": "잔액"·"잔액(원)"은 머리글(true), "이전 잔액"·"(최종 잔액)"은 아님(false).
     * 다중행(표) 데이터를 엉뚱한 칸에 채워 양식이 깨지는 것을 막기 위해 단순 contains 대신 사용한다.
     */
    private boolean isColumnHeaderMatch(String cellText, String field) {
        String c = normalize(cellText).replace(" ", "");
        String f = normalize(field).replace(" ", "");
        return !f.isEmpty() && c.startsWith(f);
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
            // 수입지출관리대장(원장)은 통장 거래를 거래(행) 단위로 '월별 시트'에 분산 기록하므로 전용 경로로 처리한다.
            // (#2: 금액 숫자화→합계 계산, 최종 잔액, 번호 순번 재부여, 월시트 분산)
            if (isLedger(workbook, allFields.keySet())) {
                fillLedger(workbook, allFields, centerStyleCache);
            } else {
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
                                // 표(다중행) 데이터: 이 셀이 해당 필드의 '열 머리글'일 때만 수집한다.
                                // "이전 잔액"·"(최종 잔액)"처럼 필드명("잔액")을 부분 포함하는 칸이 표 머리글로
                                // 오인되어 제목/머리글 영역에 행이 삽입·오염되는 것을 막는다. (#1)
                                if (isColumnHeaderMatch(cellText, field)) {
                                    multiRowFills.add(new MultiRowFill(si, row.getRowNum(), ci, rowValues));
                                }
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
            } // end else (원장이 아닌 일반 표 양식)

            // 자리표시자 교체: 단체명·오늘 날짜·지출인/수령인 서명 (복제된 페이지의 머리글도 함께 교체됨)
            applyTemplateReplacements(workbook, reserved);

            // '담당'·'회장' 라벨 아래의 "OOO (인)" 서명 칸에 지출인 이름·회장 직책 멤버 이름을 채운다.
            applyManagerPresidentSignatures(workbook, reserved);

            // 이미지 필드 삽입 - 텍스트 채우기 완료 후 별도 단계로 실행
            if (!imageFieldsBytes.isEmpty()) {
                insertXlsxImages(workbook, imageFieldsBytes);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    // ===== 수입지출관리대장(원장) 전용 채우기 (#2) =====

    /** 원장 양식인지: 수입금액·지출금액·잔액 필드를 모두 채우려 하고, 월별 시트("N월")가 2개 이상. */
    private boolean isLedger(Workbook wb, java.util.Set<String> fieldKeys) {
        boolean income = false, expense = false, balance = false;
        for (String k : fieldKeys) {
            if (k.contains("수입") && k.contains("금액")) income = true;
            else if (k.contains("지출") && k.contains("금액")) expense = true;
            if (k.contains("잔액")) balance = true;
        }
        if (!(income && expense && balance)) return false;
        int monthSheets = 0;
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if (monthOfSheetName(wb.getSheetName(i)) > 0) monthSheets++;
        }
        return monthSheets >= 2;
    }

    private static final Pattern MONTH_IN_NAME = Pattern.compile("(\\d{1,2})\\s*월");
    private int monthOfSheetName(String name) {
        if (name == null) return -1;
        Matcher m = MONTH_IN_NAME.matcher(name);
        if (m.find()) { int v = Integer.parseInt(m.group(1)); if (v >= 1 && v <= 12) return v; }
        return -1;
    }

    /** 정규화된 날짜("yy/mm/dd"·"yyyy.mm.dd"·"9월" 등)에서 월(1~12)을 추출. 못 찾으면 -1. */
    private static final Pattern DATE_MONTH = Pattern.compile("\\d{2,4}[./-](\\d{1,2})[./-]\\d{1,2}");
    private int parseMonth(String date) {
        if (date == null || date.isBlank()) return -1;
        Matcher m = DATE_MONTH.matcher(date.trim());
        if (m.find()) { int v = Integer.parseInt(m.group(1)); if (v >= 1 && v <= 12) return v; }
        m = MONTH_IN_NAME.matcher(date);
        if (m.find()) { int v = Integer.parseInt(m.group(1)); if (v >= 1 && v <= 12) return v; }
        return -1;
    }

    private Sheet findMonthSheet(Workbook wb, int month) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            if (monthOfSheetName(wb.getSheetName(i)) == month) return wb.getSheetAt(i);
        }
        return null;
    }

    /** allFields에서 키워드(부분일치)에 맞는 필드 값을 행 토큰 리스트로 분리. */
    private List<String> ledgerTokens(Map<String, String> allFields, String... keywords) {
        for (Map.Entry<String, String> e : allFields.entrySet()) {
            for (String kw : keywords) {
                if (e.getKey().contains(kw)) return splitMultiRowValue(e.getValue());
            }
        }
        return Collections.emptyList();
    }

    private List<String> ledgerAmountTokens(Map<String, String> allFields, boolean income) {
        for (Map.Entry<String, String> e : allFields.entrySet()) {
            String k = e.getKey();
            if (income && k.contains("수입") && k.contains("금액")) return splitMultiRowValue(e.getValue());
            if (!income && k.contains("지출") && k.contains("금액")) return splitMultiRowValue(e.getValue());
        }
        return Collections.emptyList();
    }

    private static String at(List<String> list, int i) {
        return (i >= 0 && i < list.size()) ? list.get(i) : "";
    }

    /**
     * 원장 채우기: 통장 거래내역(행별 다중값) 컬럼을 거래 단위로 합쳐 날짜의 '월'에 맞는 시트로 분산 기록한다.
     * 금액은 숫자로 채워 합계(SUM)가 계산되게 하고, 번호는 1부터 순번을 재부여하며, 합계 행의 잔액 칸에는 마지막 잔액을 적는다.
     */
    private void fillLedger(Workbook wb, Map<String, String> allFields, Map<Short, CellStyle> cache) {
        List<String> dates = ledgerTokens(allFields, "날짜", "일자");
        List<String> contents = ledgerTokens(allFields, "내용", "적요");
        List<String> incomes = ledgerAmountTokens(allFields, true);
        List<String> expenses = ledgerAmountTokens(allFields, false);
        List<String> balances = ledgerTokens(allFields, "잔액");
        int n = Math.max(Math.max(dates.size(), balances.size()), Math.max(incomes.size(), expenses.size()));
        n = Math.max(n, contents.size());
        if (n == 0) return;

        // 거래일의 월별로 행 인덱스를 모은다 (월 미상은 -1 그룹 → 첫 월 시트로 폴백)
        Map<Integer, List<Integer>> byMonth = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byMonth.computeIfAbsent(parseMonth(at(dates, i)), k -> new java.util.ArrayList<>()).add(i);
        }
        // 월 오름차순으로 처리하고 번호는 시트 간 연속 부여한다 (예: 1월 1,2,3 → 2월 4,5,6). 월 미상은 끝으로.
        List<Map.Entry<Integer, List<Integer>>> entries = new java.util.ArrayList<>(byMonth.entrySet());
        entries.sort(java.util.Comparator.comparingInt(e -> e.getKey() > 0 ? e.getKey() : 13));
        int startNo = 1;
        for (Map.Entry<Integer, List<Integer>> e : entries) {
            int month = e.getKey();
            Sheet sheet = (month > 0) ? findMonthSheet(wb, month) : null;
            if (sheet == null) {
                // 월 미상이거나 해당 월 시트가 없으면 첫 번째 월 시트에 기록
                for (int i = 0; i < wb.getNumberOfSheets() && sheet == null; i++) {
                    if (monthOfSheetName(wb.getSheetName(i)) > 0) sheet = wb.getSheetAt(i);
                }
            }
            if (sheet == null) continue;
            int filled = fillLedgerSheet(wb, sheet, e.getValue(), dates, contents, incomes, expenses, balances, startNo, cache);
            startNo += filled; // 다음 월 시트는 이어지는 번호부터
        }
        wb.setForceFormulaRecalculation(true); // 열어보면 수식이 재계산되도록
    }

    private int fillLedgerSheet(Workbook wb, Sheet sheet, List<Integer> rowIdxs,
                                 List<String> dates, List<String> contents, List<String> incomes,
                                 List<String> expenses, List<String> balances, int startNo, Map<Short, CellStyle> cache) {
        // 헤더 행/열 탐색 (수입금액·지출금액·잔액이 모두 있는 행)
        int headerRow = -1, colNo = -1, colDate = -1, colContent = -1, colIncome = -1, colExpense = -1, colBalance = -1;
        int lastRow = sheet.getLastRowNum();
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int no = -1, date = -1, content = -1, income = -1, expense = -1, balance = -1;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                if (cell == null || cell.getCellType() != CellType.STRING) continue;
                String t = normalize(cell.getStringCellValue()).replace(" ", "");
                if (t.equals("번호")) no = c;
                else if (t.startsWith("날짜") || t.startsWith("일자")) date = c;
                else if (t.startsWith("내용") || t.startsWith("적요")) content = c;
                else if (t.startsWith("수입금액")) income = c;
                else if (t.startsWith("지출금액")) expense = c;
                else if (t.equals("잔액")) balance = c;
            }
            if (income >= 0 && expense >= 0 && balance >= 0) {
                headerRow = r; colNo = no; colDate = date; colContent = content;
                colIncome = income; colExpense = expense; colBalance = balance;
                break;
            }
        }
        if (headerRow < 0) return 0;

        int startRow = headerRow + 1;
        int count = rowIdxs.size();
        int sumRowIdx = findSumRowIndex(sheet, startRow);

        // 데이터가 합계 행 위 칸을 초과하면 합계 행 앞에 행을 삽입(성장)
        if (sumRowIdx >= 0) {
            int capacity = sumRowIdx - startRow;
            if (capacity > 0 && count > capacity) {
                int extra = count - capacity;
                try {
                    int templateRow = sumRowIdx - 1;
                    sheet.shiftRows(sumRowIdx, sheet.getLastRowNum(), extra, true, true);
                    for (int r = 0; r < extra; r++) copyRowLayout(sheet, templateRow, sumRowIdx + r);
                    int newSum = sumRowIdx + extra;
                    extendSumFormulas(sheet, newSum, sumRowIdx - 1, newSum - 1);
                    sumRowIdx = newSum;
                } catch (Exception ex) {
                    log.warn("원장 행 확장 실패, 칸까지만 채움: {}", ex.getMessage());
                    count = Math.min(count, capacity);
                }
            }
        }

        // 데이터 채우기 — 번호는 startNo부터 시트 간 연속 부여, 금액·잔액은 숫자로
        String lastBalance = null;
        double sumIncome = 0, sumExpense = 0;
        for (int k = 0; k < count; k++) {
            int gi = rowIdxs.get(k);
            Row tr = sheet.getRow(startRow + k);
            if (tr == null) tr = sheet.createRow(startRow + k);
            if (colNo >= 0) setLedgerCell(wb, tr, colNo, String.valueOf(startNo + k), true, cache);
            if (colDate >= 0) setLedgerCell(wb, tr, colDate, at(dates, gi), false, cache);
            if (colContent >= 0) setLedgerCell(wb, tr, colContent, at(contents, gi), false, cache);
            if (colIncome >= 0) {
                String v = at(incomes, gi);
                setLedgerCell(wb, tr, colIncome, v, true, cache);
                sumIncome += parseAmount(v);
            }
            if (colExpense >= 0) {
                String v = at(expenses, gi);
                setLedgerCell(wb, tr, colExpense, v, true, cache);
                sumExpense += parseAmount(v);
            }
            if (colBalance >= 0) {
                String bal = at(balances, gi);
                setLedgerCell(wb, tr, colBalance, bal, true, cache);
                if (bal != null && !bal.replace(",", "").trim().isEmpty()) lastBalance = bal;
            }
        }

        // 합계 행: 수입금액·지출금액 합계(숫자)와 최종 잔액을 직접 기입 (재계산 없이도 보이도록)
        if (sumRowIdx >= 0) {
            Row sumRow = sheet.getRow(sumRowIdx);
            if (sumRow == null) sumRow = sheet.createRow(sumRowIdx);
            if (colIncome >= 0) setLedgerCellNumber(wb, sumRow, colIncome, sumIncome, cache);
            if (colExpense >= 0) setLedgerCellNumber(wb, sumRow, colExpense, sumExpense, cache);
            if (colBalance >= 0 && lastBalance != null) setLedgerCell(wb, sumRow, colBalance, lastBalance, true, cache);
        }
        return count;
    }

    private static double parseAmount(String s) {
        if (s == null) return 0;
        String d = s.replace(",", "").trim();
        if (d.isEmpty()) return 0;
        try { return Double.parseDouble(d); } catch (NumberFormatException e) { return 0; }
    }

    private void setLedgerCellNumber(Workbook wb, Row row, int col, double value, Map<Short, CellStyle> cache) {
        Cell c = row.getCell(col);
        if (c == null) c = row.createCell(col);
        if (c.getCellType() == CellType.FORMULA) c.setBlank(); // 수식 제거 후 숫자 기입 (재계산 없이도 합계가 보이도록)
        c.setCellValue(value);
        applyCenter(wb, c, cache);
    }

    /**
     * XLSX에서 '담당'·'회장' 라벨 아래의 "OOO (인)" 서명 칸을 채운다. 라벨 셀과 (인) 셀이 분리된 양식(원장 등) 대응.
     * 담당 ← 지출인 이름(PAYER_SIGN_KEY), 회장 ← 회장 직책 멤버 이름(REVIEWER_SIGN_KEY). "OOO/박세현" 등 기존 값은 "{이름} (인)"으로 교체.
     */
    private void applyManagerPresidentSignatures(Workbook wb, Map<String, String> reserved) {
        String payer = reserved.get(PAYER_SIGN_KEY);
        String reviewer = reserved.get(REVIEWER_SIGN_KEY);
        if ((payer == null || payer.isBlank()) && (reviewer == null || reviewer.isBlank())) return;
        for (int si = 0; si < wb.getNumberOfSheets(); si++) {
            Sheet sheet = wb.getSheetAt(si);
            for (Row row : sheet) {
                if (row == null) continue;
                short last = row.getLastCellNum();
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null || cell.getCellType() != CellType.STRING) continue;
                    String t = normalize(cell.getStringCellValue()).replace(" ", "");
                    if (t.equals("담당") && payer != null && !payer.isBlank()) {
                        setSignCellBelow(sheet, row.getRowNum(), c, payer);
                    } else if (t.equals("회장") && reviewer != null && !reviewer.isBlank()) {
                        setSignCellBelow(sheet, row.getRowNum(), c, reviewer);
                    }
                }
            }
        }
    }

    /** 라벨 (labelRow,col) 아래 같은 열에서 "(인)"이 있는 서명 칸을 찾아 "{name} (인)"으로 교체. */
    private void setSignCellBelow(Sheet sheet, int labelRow, int col, String name) {
        int last = Math.min(labelRow + 6, sheet.getLastRowNum());
        for (int r = labelRow + 1; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Cell cell = row.getCell(col);
            if (cell == null || cell.getCellType() != CellType.STRING) continue;
            String v = cell.getStringCellValue();
            if (v == null) continue;
            if (normalize(v).replace(" ", "").contains("(인)")) {
                cell.setCellValue(name + " (인)");
                return;
            }
        }
    }

    /** 원장 셀 채우기: numeric이면 숫자로(합계 계산 가능), 아니면 텍스트로. 가운데 정렬 적용. */
    private void setLedgerCell(Workbook wb, Row row, int col, String token, boolean numeric, Map<Short, CellStyle> cache) {
        Cell c = row.getCell(col);
        if (c == null) c = row.createCell(col);
        // 잔액 등 기존 수식 셀을 실제 값으로 덮어쓸 때 수식이 남지 않도록 먼저 비운다.
        if (c.getCellType() == CellType.FORMULA) c.setBlank();
        String t = (token == null) ? "" : token.trim();
        if (numeric) {
            String digits = t.replace(",", "");
            if (digits.isEmpty()) c.setCellValue(0);
            else {
                try { c.setCellValue(Double.parseDouble(digits)); }
                catch (NumberFormatException e) { c.setCellValue(t); }
            }
        } else {
            c.setCellValue(t);
        }
        applyCenter(wb, c, cache);
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
