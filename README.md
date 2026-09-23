# 🍶 SakeZuki

> **실제 사케 데이터를 기반으로 사케 탐색과 AI 음식 페어링을 제공하는 개인 프로젝트**

SakeZuki는 사케의 기본 정보와 브랜드·양조장 정보를 탐색하고,  
음식과 사케의 조합을 AI를 활용해 추천받을 수 있는 웹 서비스입니다.

단순히 생성형 AI가 추천 결과를 만들어내는 방식이 아니라,  
**실제 DB에 저장된 사케를 후보로 조회한 뒤 Gemini가 재랭킹하는 방식**으로 추천 기능을 구성했습니다.

---

## Project Overview

| 항목 | 내용                                                               |
| --- |------------------------------------------------------------------|
| 개발 기간 | 2026.09                                                          |
| 개발 인원 | 1명                                                               |
| 역할 | 기획 · 설계 · Backend · Frontend · 데이터 구축 · 배포                       |
| Backend | Java 21 · Spring Boot 4.1.1 · Spring AI 2.0.1 · MyBatis · Flyway |
| Frontend | Next.js 16.3.5 · React 19 · TypeScript · TanStack Query · Axios · Tailwind CSS |
| Database | MySQL 8.4                                                        |
| AI | Google Gemini                                                    |
| Infrastructure | Docker Compose · Nginx · AWS EC2                                 |

---

## 주요 기능

### 1. 사케 탐색

- 10,337건의 사케 데이터 조회
- 사케명 검색
- 사케 종류별 필터
- 페이지 단위 목록 조회
- 이미지가 존재하는 사케 우선 노출
- 사케명 자동완성 검색

### 2. 사케 상세 정보

- 사케 기본 정보 조회
- 브랜드 및 양조장 정보 연계
- 한국어·일본어 명칭 제공
- 원료미·정미율 등 상세 정보 제공
- Google Maps 기반 양조장 위치 확인
- 상세 페이지에서 해당 사케의 AI 음식 추천으로 바로 이동

### 3. 음식 → 사케 AI 추천

사용자가 음식명을 입력하면 음식의 특징을 분석하고  
실제 DB에 존재하는 사케 중 어울리는 **TOP 3**를 추천합니다.

```text
음식 입력
   ↓
추천 결과 Cache 조회
   ↓
Cache Miss
   ↓
Gemini 음식 특성 분석
   ↓
추천 조건 구조화
   ↓
MySQL 후보 TOP 40 조회
   ↓
Gemini Re-ranking
   ↓
추천 TOP 3
   ↓
결과 Cache 저장
```

### 4. 사케 → 음식 AI 추천

사용자가 선택한 사케의 실제 정보를 DB에서 조회한 뒤  
Gemini를 활용하여 어울리는 음식과 페어링 이유를 제공합니다.

```text
사케 선택
   ↓
추천 결과 Cache 조회
   ↓
Cache Miss
   ↓
사케 · 브랜드 · 양조장 정보 조회
   ↓
Gemini 음식 페어링 요청
   ↓
추천 음식 및 이유 생성
   ↓
결과 Cache 저장
```

---

## Tech Stack

### Backend

<p>
  <img src="https://img.shields.io/badge/Java_21-ED8B00?style=flat-square&logo=openjdk&logoColor=white">
  <img src="https://img.shields.io/badge/Spring_Boot_4-6DB33F?style=flat-square&logo=springboot&logoColor=white">
  <img src="https://img.shields.io/badge/Spring_AI-6DB33F?style=flat-square&logo=spring&logoColor=white">
  <img src="https://img.shields.io/badge/MyBatis-000000?style=flat-square">
</p>

### Frontend

<p>
  <img src="https://img.shields.io/badge/Next.js_16-000000?style=flat-square&logo=nextdotjs&logoColor=white">
  <img src="https://img.shields.io/badge/React_19-61DAFB?style=flat-square&logo=react&logoColor=black">
  <img src="https://img.shields.io/badge/TypeScript-3178C6?style=flat-square&logo=typescript&logoColor=white">
  <img src="https://img.shields.io/badge/TanStack_Query-FF4154?style=flat-square&logo=reactquery&logoColor=white">
  <img src="https://img.shields.io/badge/Axios-5A29E4?style=flat-square&logo=axios&logoColor=white">
  <img src="https://img.shields.io/badge/Tailwind_CSS-06B6D4?style=flat-square&logo=tailwindcss&logoColor=white">
</p>

### Database & Infrastructure

<p>
  <img src="https://img.shields.io/badge/MySQL_8.4-4479A1?style=flat-square&logo=mysql&logoColor=white">
  <img src="https://img.shields.io/badge/Docker_Compose-2496ED?style=flat-square&logo=docker&logoColor=white">
  <img src="https://img.shields.io/badge/Nginx-009639?style=flat-square&logo=nginx&logoColor=white">
  <img src="https://img.shields.io/badge/AWS_EC2-FF9900?style=flat-square&logo=amazonec2&logoColor=white">
</p>

### AI & Data Tooling

<p>
  <img src="https://img.shields.io/badge/Google_Gemini-8E75B2?style=flat-square&logo=googlegemini&logoColor=white">
  <img src="https://img.shields.io/badge/Jsoup-59666C?style=flat-square">
</p>

---

## Architecture

```text
                         ┌──────────────────────┐
                         │       Browser        │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │        Nginx         │
                         │    Reverse Proxy     │
                         └──────┬────────┬──────┘
                                │        │
                              / │        │ /api
                                │        │
                ┌───────────────▼──┐  ┌──▼────────────────┐
                │     Next.js      │  │    Spring Boot    │
                │ React / TS       │  │ MyBatis / AI      │
                │ TanStack Query   │  └──────┬───────┬────┘
                └──────────────────┘         │       │
                                             │       │
                                    ┌────────▼──┐ ┌──▼──────────┐
                                    │ MySQL 8.4 │ │   Gemini    │
                                    └───────────┘ └─────────────┘
```

---

## Database

주요 데이터는 다음과 같이 구성했습니다.

```text
BREWERY
   │
   └── BRAND
         │
         └── SAKE
               │
               ├── SAKE_RECOMMEND_PROFILE
               ├── RECOMMEND_FOOD
               └── RECOMMEND_SAKE
```

### 데이터 구성

- **BREWERY** : 양조장 정보
- **BRAND** : 양조장별 사케 브랜드
- **SAKE** : 사케 기본·상세 정보
- **SAKE_RECOMMEND_PROFILE** : AI 사케 추천 검색에 사용하는 정규화 데이터
- **RECOMMEND_FOOD** : 사케 → 음식 추천 결과 Cache
- **RECOMMEND_SAKE** : 음식 → 사케 추천 결과 Cache

총 **10,337건의 사케 데이터**에 추천 검색용 프로필을 구성했습니다.

원본 일본어 데이터는 유지하면서 서비스 표시를 위한 한국어 필드를 별도로 구성했습니다.

---

## Technical Points

### 1. React · TanStack Query 기반 서버 상태 관리

검색어, 사케 종류, 페이지를 `queryKey`에 포함하여  
조회 조건이 변경되면 해당 조건에 맞는 데이터를 다시 조회하도록 구성했습니다.

```text
검색어 · 종류 · 페이지
        ↓
    React State
        ↓
  TanStack Query
   queryKey 변경
        ↓
     Axios
        ↓
 Spring Boot API
```

페이지 이동 중에는 이전 데이터를 유지하여  
새로운 데이터를 조회하는 동안 목록이 사라지는 현상을 줄였습니다.

---

### 2. 실제 사케 데이터를 기반으로 한 AI 추천

초기에는 Gemini가 추천할 사케 자체를 생성하도록 할 수도 있었지만,  
이 경우 DB에 존재하지 않거나 실제 정보와 다른 사케가 추천될 가능성이 있었습니다.

이를 방지하기 위해 AI와 DB의 역할을 분리했습니다.

```text
Gemini
음식 특징 및 추천 조건 추출
        ↓
MySQL
조건에 맞는 실제 사케 후보 조회
        ↓
Gemini
후보군 Re-ranking
        ↓
실제 등록된 사케 TOP 3 반환
```

Gemini는 **조건 분석과 후보 평가**를 담당하고,  
실제 추천 대상은 DB에서 조회된 사케로 제한했습니다.

---

### 3. AI 추천 결과 캐싱

AI 추천은 외부 API 호출과 후보 평가 과정 때문에  
일반적인 DB 조회보다 응답 시간이 길었습니다.

동일한 추천 조건에 대해 이미 생성된 결과가 존재하면  
Gemini를 다시 호출하지 않고 DB에 저장된 추천 결과를 반환하도록 구성했습니다.

실제 동일 요청을 기준으로:

```text
Cache Miss : 31,449ms
Cache Hit  :     89ms
```

반복 요청의 응답 시간을 크게 줄이고 불필요한 Gemini API 호출을 방지했습니다.

---

### 4. 추천용 수치 데이터 정규화

사케 원본 데이터에는 알코올 도수, 정미율 등 추천에 필요한 값이  
문자열 형태이거나 일정하지 않은 형식으로 저장된 경우가 있었습니다.

원본 값은 그대로 유지하면서 추천 검색에 사용할 수 있는 형태로 별도의 프로필 데이터를 구성하여  
AI 추천 과정에서 안정적으로 조건 검색을 수행할 수 있도록 했습니다.

---

## Project Structure

```text
SakeZuki/
├── backend/                 # Spring Boot REST API
│   ├── src/
│   └── build.gradle
│
├── frontend/                # Next.js / React
│   ├── app/
│   ├── components/
│   ├── types/
│   └── package.json
│
├── tools/                   # 데이터 수집·정제·번역 도구
│   └── build.gradle
│
├── database/
│   ├── 01_schema.sql        # DB Schema
│   └── 02_seed.sql          # 초기 데이터
│
├── docker-compose.yaml      # MySQL 실행 및 초기화
├── .env.example
└── README.md
```

---

## Local Setup

### Requirements

- Java 21
- Node.js 22+
- Docker / Docker Compose
- Google Gemini API Key
- Google Maps API Key

### 1. Repository Clone

```bash
git clone https://github.com/ki-sd/SakeZuki.git
cd SakeZuki
```

### 2. Environment Variables

루트의 `.env.example`을 복사합니다.

```bash
cp .env.example .env
```

`.env`에 필요한 값을 설정합니다.

```env
MYSQL_DATABASE=sakezuki
MYSQL_USER=sakezuki
MYSQL_PASSWORD=YOUR_PASSWORD
MYSQL_ROOT_PASSWORD=YOUR_ROOT_PASSWORD
GEMINI_API_KEY=YOUR_GEMINI_API_KEY
```

Frontend 환경변수도 설정합니다.

```bash
cd frontend
cp .env.local.example .env.local
```

```env
NEXT_PUBLIC_GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
```

### 3. MySQL

프로젝트 루트에서 실행합니다.

```bash
docker compose up -d
```

최초 실행 시 `database/`의 SQL 파일을 통해 Schema와 초기 데이터가 자동으로 구성됩니다.

### 4. Backend

```bash
cd backend
./gradlew bootRun
```

Backend:

```text
http://localhost:8080
```

### 5. Frontend

```bash
cd frontend
npm ci
npm run dev
```

Frontend:

```text
http://localhost:3000
```

---

## Main API

| Method | Endpoint | Description |
| --- | --- | --- |
| GET | `/api/sake/list` | 사케 목록·검색·필터·페이징 |
| GET | `/api/sake/{no}` | 사케 상세 조회 |
| GET | `/api/sake/search` | 사케명 자동완성 검색 |
| POST | `/api/recommend/sake` | 음식 기반 사케 추천 |
| POST | `/api/recommend/food` | 사케 기반 음식 추천 |

---

## Deployment

AWS EC2 Ubuntu 환경에 서비스를 배포하고 Nginx를 Reverse Proxy로 사용했습니다.

```text
Browser
   ↓
Nginx :80
   ├── /      → Next.js :3000
   └── /api/* → Spring Boot :8080
                         ↓
                     MySQL :3306
```

Database는 Docker Compose로 실행하며,  
Spring Boot와 Next.js는 Nginx 뒤에서 서비스하도록 구성했습니다.

> 배포 자동화 및 전체 서비스 컨테이너화는 개선 예정입니다.

---

## Troubleshooting

### AI가 실제로 존재하지 않는 사케를 추천하는 문제

**Problem**

생성형 AI가 추천 대상까지 직접 결정하면 실제 DB에 존재하지 않거나  
서비스가 보유한 정보와 다른 결과가 생성될 가능성이 있었습니다.

**Solution**

음식 특징과 추천 조건 추출은 Gemini가 담당하고,  
실제 후보 검색은 MySQL에서 수행한 뒤 해당 후보만 Gemini가 재랭킹하도록 변경했습니다.

**Result**

AI의 분석 능력을 활용하면서도 실제 서비스에 등록된 사케만 추천하도록 구성했습니다.

---

### AI 추천 응답 지연

**Problem**

Gemini 호출과 후보 재랭킹 과정으로 인해 최초 추천 요청에 30초 이상의 시간이 소요될 수 있었습니다.

**Solution**

생성된 추천 결과를 DB에 저장하고 동일 조건의 요청에서는 기존 결과를 반환하도록 구성했습니다.

**Result**

```text
31,449ms → 89ms
```

동일 요청의 응답 시간을 크게 줄이고 반복적인 외부 API 호출을 방지했습니다.

---

## What I Learned

이 프로젝트를 통해 Vue 중심의 기존 프로젝트 경험에서 벗어나  
**Next.js · React · TypeScript · TanStack Query**를 활용한 프론트엔드 개발을 경험했습니다.

또한 생성형 AI의 결과를 그대로 사용하는 대신  
실제 서비스 데이터와 AI의 역할을 분리하여 추천 기능을 설계하면서  
AI 기능에서도 데이터의 신뢰성과 결과 검증이 중요하다는 점을 경험했습니다.

데이터 수집·정제부터 DB 설계, Backend·Frontend 구현과 실제 서버 배포까지  
개인 프로젝트로 전체 개발 과정을 직접 진행했습니다.

---

## Developer

**기승도 (ki-sd)**

Java/Spring Backend Developer

- GitHub: https://github.com/ki-sd
- Blog: https://ki-sd.tistory.com/