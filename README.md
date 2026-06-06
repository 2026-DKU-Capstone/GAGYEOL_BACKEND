# 가결 — Backend

> 단국대학교 학생 단체를 위한 예산 결재·관리 서비스 백엔드

## 기술 스택

| 분류 | 기술 |
|------|------|
| Framework | Spring Boot 3.4.4, Java 17 |
| Database | PostgreSQL 16 + pgvector |
| DB 마이그레이션 | Flyway |
| 인증 | JWT (HS256, 24시간 유효) |
| 문서 처리 | Apache POI (DOCX/XLSX), Apache PDFBox |
| AI | OpenAI GPT-4o, text-embedding-3-small |
| 외부 API | Upstage Information Extract API |
| 빌드 | Gradle |
| 배포 | Docker, Docker Compose |

## 주요 기능

- **증빙서류 AI 분석** — 영수증/거래명세서 업로드 → Upstage IE로 결제수단 분류 및 필드 자동 추출
- **양식지 자동 채우기** — 추출된 필드를 DOCX/XLSX 양식지에 자동 매핑 후 다운로드
- **규정 검토 (RAG)** — 규정책 PDF를 벡터 DB에 인덱싱하여 GPT 분석 시 관련 규정 자동 참조
- **다단계 결재 워크플로우** — 역할 기반 순차 승인, 동시성 제어(비관적 락), 재결재 지원
- **그룹 관리** — 역할 계층, 초대코드 기반 가입, 멤버 관리

## 시작하기

### Docker Compose (권장)

```bash
# 1. 환경변수 설정
cp .env.example .env
# .env 파일에 API 키 입력

# 2. 실행 (PostgreSQL + Spring Boot)
docker-compose up --build
```

- API: `http://localhost:8080`
- DB: `localhost:5432/gagyelol`

### 로컬 개발

```bash
# DB만 Docker로 실행
docker-compose up db

# 앱 실행
./gradlew bootRun
```

## 환경 변수

| 변수명 | 설명 | 기본값 |
|--------|------|--------|
| `SPRING_DATASOURCE_URL` | DB 주소 | `jdbc:postgresql://localhost:5432/gagyelol` |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자명 | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 | `gagyelol` |
| `OPENAI_API_KEY` | OpenAI API 키 | — |
| `UPSTAGE_API_KEY` | Upstage API 키 | — |
| `JWT_SECRET` | JWT 서명 키 | 기본값 있음 (운영 시 반드시 변경) |

## 프로젝트 구조

```
src/main/java/GAGYELOL/
├── config/          # Security, JWT, 전역 예외 처리
├── controller/      # REST 컨트롤러
├── service/         # 비즈니스 로직 (AI, 문서 처리, 결재 등)
├── entity/          # JPA 엔티티
├── repository/      # Spring Data JPA Repository
└── dto/             # 요청/응답 DTO

src/main/resources/
├── application.properties
└── db/migration/    # Flyway 마이그레이션 스크립트 (V1~V8)
```

## API 엔드포인트 요약

| 도메인 | Base URL | 주요 기능 |
|--------|----------|-----------|
| 인증 | `/api/auth` | 회원가입, 로그인 (JWT 발급) |
| 그룹 | `/api/groups` | 그룹 생성/가입, 역할·멤버 관리 |
| 양식지 | `/api/forms` | DOCX/XLSX 업로드, AI 필드 분석 |
| 증빙서류 | `/api/evidence` | 영수증 분석, 필드 자동 채우기, 완성 파일 다운로드 |
| 결재 | `/api/approvals` | 결재 요청·승인·반려·재결재 |
| 규정책 | `/api/policies` | PDF 업로드, RAG 인덱싱 |
| 사진 | `/api/photos` | 학생증·도장 이미지 업로드 |
| 대시보드 | `/api/dashboard` | 월별 통계, 결재 현황 |

> 로그인/회원가입을 제외한 모든 요청에 `Authorization: Bearer <token>` 헤더 필요
>
> 상세 API 명세는 `GAGYEOL_API.postman_collection.json` 참고

## 데이터 모델

```
User ──< GroupMember >── UserGroup
                              │
                    GroupRole (approval_order)
                              │
                          Policy (activePolicy)

Evidence ── EvidenceForm ── Form

ApprovalRequest ──< ApprovalStep
                └── ApprovalEditHistory
```

### 결재 상태

`DRAFT` → `IN_PROGRESS` → `APPROVED` / `REJECTED` / `CANCELED`

## 외부 서비스 연동

| 서비스 | 용도 |
|--------|------|
| OpenAI GPT-4o | 양식지 분석·선택, 이미지 기반 PDF OCR |
| text-embedding-3-small | 규정책 청킹 임베딩 (1536차원, pgvector 저장) |
| Upstage IE | 증빙서류 결제수단 분류 + 필드 추출 |

## 관련 레포지토리

- **Frontend**: [2026-DKU-Capstone/2026_DKU_FRONTEND](https://github.com/2026-DKU-Capstone/2026_DKU_FRONTEND)

## 팀원

| 이름 | 역할 | GitHub |
|------|------|--------|
| 박세현 | 팀장 | [@parksehyn](https://github.com/parksehyn) |
| 안균승 | 팀원 | [@LOK-AeGS](https://github.com/LOK-AeGS) |
| 고동민 | 팀원 | [@kodongmin](https://github.com/kodongmin) |
| 김아름 | 팀원 | [@karuem](https://github.com/karuem) |
