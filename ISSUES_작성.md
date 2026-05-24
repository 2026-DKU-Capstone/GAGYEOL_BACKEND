# GAGYEOL 이슈 해결 기록 — 지출결의서 작성 플로우

> 기간: 2026-05-23 ~ 2026-05-24
> 범위: 증빙서류 업로드 → OCR → 양식지 채우기 → 다운로드 워크플로우
> 레포: BE `LOK-AeGS/GAGYEOL_BACKEND` (`feat/payer-info-autofill`) · FE `LOK-AeGS/2026_DKU_FRONTEND` (`fix/evidence-as-photo`)

## 관련 커밋
- BE `458048f` — #3·#4·#5·#6 (양식지 작성 플로우 1차)
- BE `bdb1f99` — 사업명 입력값 반영 · 양식파일 누락 안내(#2) · 학생증 PDF 허용 · xlsx 합계행 초과 방지/시트 분할
- FE `268aa6d`, `e824749` — #1·#9·#14·#3(FE)
- FE `06e6103` — 다중 증빙 연속 작성 · doc-review 학생증 OCR/부착

---

## 해결 요약

| # | 이슈 | 상태 | 범위 | 커밋 |
|---|------|------|------|------|
| 1 | 업로드 파일을 드롭존 내에 인라인 표시 | ✅ 완료 | FE | 268aa6d |
| 2 | 지출기록부 양식지 다운로드 안 됨 | ✅ 완료(원인규명+안내) | BE | bdb1f99 |
| 3 | 수령인 정보 학생증 OCR 자동 입력 | ✅ 완료(+doc-review 보강) | BE+FE | 458048f·06e6103 |
| 4 | 금액 음수 제거·세부항목 총합 | ✅ 완료 | BE | 458048f |
| 5 | 이미지 삽입 시 텍스트 제거+비율 맞춤 | ✅ 완료 | BE | 458048f |
| 6 | 양식지 값 가운데 정렬 | ✅ 완료 | BE | 458048f |
| 9 | 다중 파일 업로드 처리 | ✅ 완료(연속 작성 큐로 발전) | FE | 06e6103 |
| 14 | 다운로드 완료 피드백 | ✅ 완료 | FE | e824749 |
| A | (추가) 사업명을 입력값으로 채우기 | ✅ 완료 | BE | bdb1f99 |
| B | (추가) xlsx 합계행 초과 기록 방지·다음 시트 분할 | ✅ 완료 | BE | bdb1f99 |

검증: BE `./gradlew test`(신규 테스트 포함, `contextLoads`만 로컬 Postgres 미연결로 실패 — 기존 동일) · FE `npm run build` 통과.

---

## #1 업로드 파일을 드롭존 내에 인라인 표시 — ✅

**문제:** 업로드한 파일이 드롭존 아래 별도 영역에 표시돼 완료 확인에 스크롤이 필요했음.

**해결:** 파일 선택 시 드롭존 자체가 파일 카드로 전환되도록 변경. 빈 상태에서는 안내/일러스트, 파일이 있으면 드롭존 내부에 파일명·진행바·완료 체크를 인라인 표시.
- `screens/ReceiptScreen.tsx` — 드롭존 내부 `files.length === 0 ? 안내 : 파일카드` 분기

---

## #2 지출기록부 양식지 다운로드 안 됨 — ✅ (원인 규명 + 명확한 안내)

**증상:** 다운로드 시 `양식지 파일 생성 실패: ./uploads/forms/..._지출 기록부(...).docx (No such file or directory)`.

**원인:** 코드 버그가 아니라 **스토리지 문제**. 양식은 `./uploads/forms/`(컨테이너 로컬 디스크)에 저장되는데 배포 환경의 이 경로는 ephemeral이라 재배포/재시작 시 초기화됨. DB(Postgres)의 Form 레코드는 남아 옛 경로를 가리키지만 실제 파일은 사라져 `No such file` 발생. (증빙 파일은 매번 새로 올려 존재하지만 양식은 예전 업로드분이 사라진 상태)

**해결:**
- 양식 원본이 디스크에 없으면 원본 경로를 노출하는 대신 **재업로드를 안내하는 명확한 에러(400)** 반환: `"양식지 원본 파일을 찾을 수 없습니다. 양식지("…")를 다시 업로드한 뒤 다시 시도해 주세요."`
- `EvidenceService.generateSingleFile()`·`generateZip()`에서 `ensureFormFileExists()` 호출
- **즉시 해결책:** 해당 양식("지출 기록부")을 양식 메뉴에서 다시 업로드하면 정상 동작
- (근본 해결인 영속 스토리지/DB 저장은 이번 범위 외)
- 검증: `EvidenceFillTest.completeForm_양식파일이_없으면_재업로드_안내_에러`

> 추가로 xlsx 다운로드 시 합계 행이 있는 양식에서 `ConcurrentModificationException`으로 크래시하던 잠재 버그도 함께 수정(이슈 B 참고).

---

## #3 수령인 정보 학생증 OCR 자동 입력 — ✅ (+doc-review 보강)

**문제:** 수령인 정보를 수기 입력해야 했고, 독립 OCR 추출 API가 없었음.

**해결 (BE):**
- 신규 API `POST /api/evidence/extract-recipient` (multipart `recipientImage`) → `{ name, affiliation, studentId, phone }`
- `EvidenceController`·`EvidenceService.extractRecipient`·`EvidenceAiService.extractRecipientInfo`(Upstage IE 재활용)
- **(05-24 보강)** 이미지뿐 아니라 **PDF 학생증도 허용**하도록 mime 검증 완화

**해결 (FE):**
- 최종 확인 단계(`doc-review`)의 `FinalCheckOverlay`에서 수령인 정보 입력 시 **학생증 업로드란을 항상 노출**(기존엔 특정 필드 누락 시에만 노출돼 안 보이는 문제 + 존재하지 않는 `/api/student-id/analyze`를 호출하던 버그를 `extract-recipient`로 교체)
- 추출값으로 양식의 수령인 필드를 자동 채우고, 못 채운 필드는 수동 입력
- 올린 학생증을 `/api/photos/upload`로 저장 → 다운로드 시 `imageFields["학생증"]`로 전달해 양식의 **"학생증 부착" 란에 부착**(영수증 부착과 동일 방식; BE 매칭 로직 기존 지원)
- `components/FinalCheckOverlay.tsx`, `screens/PDFScreen.tsx`

---

## #4 금액 음수(-) 제거 · 세부항목 총합 — ✅

**문제:** Upstage IE가 계좌이체확인서의 `-660,000` 같은 음수를 그대로 반환하고, 공급가액/부가세가 분리돼 들어옴.

**해결:** Upstage IE 추출 스키마의 금액 필드 description에 지침 추가 — **음수 기호 제거·절댓값 반환**, 공급가액·부가세 등 세부 항목이 여러 개면 **합산한 총합 하나만** 숫자로 반환.
- `EvidenceAiService.fieldDescription()`/`isAmountField()`
- 검증: `EvidenceAiServiceTest` (금액 필드 description에 음수/절댓값/합산 포함, 비금액 필드는 제외)

---

## #5 이미지 삽입 시 기존 텍스트 제거 + 비율 맞춤 — ✅

**문제:** 양식 셀에 증빙 이미지를 넣을 때 셀의 기존 레이블 텍스트가 남고, 이미지가 찌그러짐.

**해결:**
- 이미지 삽입 대상 셀의 기존 텍스트를 매칭 방식과 무관하게 제거 (DOCX `clearCellContent`, XLSX `clearXlsxCellText`)
- 원본 가로세로 비율을 유지하며 박스에 맞춤: `fitDimensionsEmu()`에서 `scale = min(maxW/w, maxH/h)`
- `FormFillService` (DOCX/XLSX 공통)
- 검증: `FormFillServiceFormatTest` (텍스트 제거, 비율 보존 2:1·1:2)

---

## #6 양식지 값 가운데 정렬 — ✅

**문제:** 채운 값이 정렬 없이 들어감.

**해결:**
- DOCX: 셀 수직(`XWPFVertAlign.CENTER`) + 단락 수평(`ParagraphAlignment.CENTER`) 정렬
- XLSX: 원본 셀 스타일을 복제(테두리·서식 보존)해 가운데 정렬만 적용, 스타일 폭증 방지를 위해 원본 스타일별 1개만 캐시(`applyCenter` + `centerStyleCache`)
- 검증: `FormFillServiceFormatTest`

---

## #9 다중 파일 업로드 → 연속 작성 — ✅ (큐 방식으로 발전)

**1차(05-23):** 1개씩만 처리하고 완료 후 "추가 증빙서류 작성" 버튼으로 재진입.

**확정(05-24):** 여러 장을 한 번에 올리면 **한 건씩 순서대로 이어서 작성**하는 큐 방식으로 발전.
- `ReceiptScreen`: 다중 업로드 허용 → "다음" 시 각 파일을 순차 OCR 분석해 `evidenceQueue` 생성, 분석 모달에 `(1/3)` 진행 표시
- `PDFScreen`: 큐가 2건 이상이면 완료 후 **"다음 증빙서류 작성 (2/3) →"** 버튼 노출 → 영수증 재선택 없이 다음 증빙의 양식추천 단계로 이동. 마지막까지 끝나면 "모두 작성했습니다"로 전환 (1건이면 기존과 동일하게 동작)
- `screens/ReceiptScreen.tsx`, `screens/PDFScreen.tsx`

---

## #14 다운로드 완료 피드백 — ✅

**문제:** 문서 다운로드 후 화면 변화가 없어 완료 여부를 알 수 없었음.

**해결:** 다운로드 성공 시 **토스트("문서가 다운로드되었습니다.")** + 버튼이 일시적으로 **"다운로드 완료 ✓"**(초록)로 전환 후 복귀.
- `screens/PDFScreen.tsx` (`downloaded`/`toast` 상태), `lib/downloadDocument.ts`(성공 시 null 반환)

---

## A. (추가, 05-24) 사업명을 입력값으로 채우기 — ✅

**문제:** 양식의 "사업명" 필드가 사용자가 입력한 사업명 대신 **영수증 OCR 값**으로 채워지고 있었음.

**원인:** `EvidenceService.fillFields`에서 사업명 필드가 인적/생성 필드 어디에도 안 걸려 `directFields`(영수증 IE 추출 대상)로 빠짐.

**해결:** `isBusinessNameField`(사업명/행사명) 분기를 추가해 해당 필드는 OCR이 아니라 `evidence.businessName`(사용자 입력)으로 채움. 입력값이 없으면 "미입력"으로 표시(영수증에서 추출하지 않음).
- `EvidenceService.java`
- 검증: `EvidenceFillTest` (입력값으로 채우고 영수증 IE 대상에서 제외 / 입력 없으면 미입력)

---

## B. (추가, 05-24) xlsx 합계행 초과 기록 방지 · 다음 시트 분할 — ✅

**문제:** xlsx 양식에서 다중행 데이터를 채울 때, 양식의 칸 수를 초과해 **합계 행을 넘어 행을 무한 생성**하던 문제.

**해결:**
- 다중행 데이터를 펼칠 때 **'합계/소계/총계/총액' 행 위까지만** 기록(그 너머로 행을 만들지 않음) — `findSumRowIndex()`로 경계 탐지
- 칸을 초과한 나머지는 **양식 시트를 복제해 다음 양식지(시트)로 이어 작성** — 한 .xlsx에 여러 페이지. 같은 표의 여러 컬럼은 동일 페이지 경계로 함께 분할(`applyMultiRowFills`, `MultiRowFill`)
- **보너스 수정:** 행 순회(`for (Row row : sheet)`) 중 행을 생성하면 터지던 `ConcurrentModificationException`을 인덱스 기반 순회로 변경해 해결 — 합계 행이 있는 실제 양식에서 다운로드가 크래시했을 가능성이 높던 버그
- `FormFillService.java`
- 검증: `FormFillServiceXlsxTest.다중행은_합계행_위까지_채우고_초과분은_다음_시트로_복제된다` (+기존 xlsx 테스트 유지)

> ⚠️ 레포에 실제 xlsx 양식 파일이 없어 합성 테스트 구조(헤더+데이터칸+합계행)로 검증함. 실제 "지출 기록부" 양식(병합셀·다중 헤더·합계 수식 등)에서의 분할 결과는 한 번 확인 권장.
