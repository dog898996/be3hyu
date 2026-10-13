# 기억 추출·검색·답변의 모델 평가 계획

작성일: 2026-10-13. **이번 단계에서 모델 평가를 실행하지 않았다.** 현재 7개 협업 슬롯이 모두 실행 중이어서 독립 GPT-6.1 Sol agent를 만들지 않았다. 합성 fixture, 기대 판단 기준, 입력 분리, 기계적 근거 채점만 준비했다. 유료 모델 API·Work 호출은 0회다. 준비 스크립트와 단위 테스트의 성공은 모델 기억 정확도 점수가 아니다.

## 무엇을 따로 측정하는가

| 단계 | 확인할 질문 | 측정과 한계 |
| --- | --- | --- |
| 원문 보존 | 같은 원문·범위·해시를 다시 읽을 수 있는가? | 저장/복원/부분 캡처 무결성. 의미를 이해했다는 증거는 아니다. |
| 기억 추출 | 사실·선호·정정·할 일을 정확히 추출하고 가정·인용·AI 주장을 구분하는가? | claim별 정답 rubric, 출처 인용, 잘못된 사실·잘못된 기간·누락. 인용이 원문과 일치해도 메모 내용이 참인지는 별도로 평가한다. |
| 근거 검색 | 질문에 필요한 출처를 모두 회수하는가? | source/turn/session의 Recall, Any-hit, All-hit, 순위 지표. 출처 ID를 찾았어도 독자가 올바르게 활용하지 못할 수 있다. |
| 답변 생성 | 회수한 근거로 정확한 현재/과거 답을 내는가? | 정답 정확도, 근거의 적절성, 반대 근거 처리, 모름/미완료 구분. reader 모델의 실제 응답이 필요하다. |
| 개인화 행동 | 선호를 실제 상황에 적용하고 잘못된 행동을 피하는가? | 별도 행동 rubric·실제 도구 영수증. 올바른 답변 문장만으로 Android/Work 실행을 증명하지 않는다. |

## 공식 LongMemEval과의 정확한 관계

공식 저장소 revision [`9e0b455f4ef0e2ab8f2e582289761153549043fc`](https://github.com/xiaowu0162/LongMemEval/tree/9e0b455f4ef0e2ab8f2e582289761153549043fc)의 README와 평가 코드를 직접 읽었다. 논문·데이터·코드는 같은 연구의 자료이지 서로 독립된 검증 세 건이 아니다.

- [README](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/README.md)는 500개 질문, 5개 기억 능력, 6개 question type을 설명한다. `_abs` 질문은 abstention이며 retrieval에서는 30개를 제외한다. 존재하지 않는 정답 출처에 Recall을 계산하지 않는다.
- [검색 평가 구현](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/retrieval/eval_utils.py)의 `recall_any`는 하나 이상의 gold 출처 회수 여부, `recall_all`은 **모든** gold 출처 회수 여부인 질문별 0/1이다. 일반적인 `회수한 gold 수 / 전체 gold 수` 비율과 다르다. 세션 지표와 turn 지표도 섞지 않는다. turn→session 변환에서는 K개의 서로 다른 세션을 얻을 때까지 후보 범위를 넓힌다.
- [검색 출력 코드](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/evaluation/print_retrieval_metrics.py)는 session의 `recall_all@5/@10`, turn의 `recall_all@5/@10/@50` 및 `ndcg_any`를 집계한다. 공식 구현의 DCG 계산을 다른 라이브러리의 기본 NDCG 정의와 무조건 동일하다고 가정하지 않는다.
- [답변 판정 코드](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/evaluation/evaluate_qa.py)는 질문·정답·생성 응답을 별도 judge에 보내 yes/no로 판정한다. temporal 답의 날짜 차이 ±1 허용, knowledge-update에서 최신 답이 포함되면 과거 정보도 허용, preference와 abstention의 별도 rubric이 있다. 그 조건을 우리 정확도 기준으로 조용히 가져오지 않는다.
- [답변 집계 코드](https://github.com/xiaowu0162/LongMemEval/blob/9e0b455f4ef0e2ab8f2e582289761153549043fc/src/evaluation/print_qa_metrics.py)는 `gpt-4o-2024-08-06` judge 라벨을 요구하며 Overall Accuracy, task 평균, Abstention Accuracy를 나눠 보고한다. 우리 GPT-6.1 Sol probe나 수동 판정을 이 공식 점수라고 부르지 않는다.

공식 evaluator의 API 생성·judge 코드는 실행하지 않았다. 기존 `PublicDatasetRetrievalBenchmarkTest`의 `sourceRecallAt20`은 소규모 pooled oracle 자료에서 원문 ID를 찾는 **분수형 출처 회수율**이다. 답변 정확도·추출 정확도·표준 LongMemEval S/M 점수가 아니다. [기존 연구와 공개 자료 시험](memory-research.md)

## 준비한 합성 자료

[fixture](../mobile/tests/fixtures/model-memory-evaluation.json)는 실제 사용자 계정·비밀번호가 없는 직접 작성한 한국어 자료 12개다. golden은 이 작업에서 작성한 기대 기준이며 독립 모델 판정 결과가 아니다. case ID는 중립 번호로 내보내며 시나리오 설명·정답·gold source label은 writer 입력에서 제외한다.

| 사례 | 기대되는 구분 |
| --- | --- |
| 01 | 오래된 희귀 자전거 이름과 최신 방해 대화 |
| 02 | 서울→부산 정정, 기록일과 실제 적용일 |
| 03 | 두 마감일의 미해결 충돌 |
| 04 | 소설 인용·조건문과 실제 사용자 사실 |
| 05 | 알림 속 악성 명령과 사용자 승인 |
| 06 | AI 출생지 주장과 사용자 부정 |
| 07 | 짧은 동의와 이전 질문, 긴급 예외 |
| 08 | 위치 요청 접수와 좌표 확인 완료 |
| 09 | 프로젝트 별칭과 다른 세션의 담당자 |
| 10 | 출처가 없는 질문의 abstention |
| 11 | 일상 말투와 이메일·보고서 말투의 적용 범위 |
| 12 | 원문 일부만 받은 상태와 전체 검토 완료 |

이는 추출/판단 경계를 위한 작은 probe다. 각 사례의 출처 수가 적어 검색 문제가 쉽고, 1~2년 대형 자료에서의 검색 성능을 대표하지 않는다. 전체 원문 보존과 장기 검색은 기존 730일·대량 방해 자료 시험, 실제 기기 자료로 별도 측정한다. 합성 fixture만으로 완전 기억·장기 개인화 완성을 주장하지 않는다.

## 독립 모델 실행 절차

협업 슬롯이 비면 기존 사용자 선호에 따라 `fork_turns="none"`, `model="gpt-6.1-sol"`, `reasoning_effort="medium"`으로 writer를 만든다. 새 유료 API 키·서버·별도 Work 호출을 추가하지 않는다. 실행 결과의 requested model/effort와 실제 확인할 수 있는 실행 정보를 기록하고, 확인할 수 없는 Fast·backend·토큰/과금 정보는 미확인으로 남긴다.

1. 아래 prepare를 실행해 writer 입력, QA 질문, golden을 별도 파일로 만든다. writer에게는 `writer-inputs.json`만 준다. golden·질문·이 계획서의 시나리오 표를 읽지 않도록 지시하고 실제 도구 읽기 기록을 검토한다. 이는 공유 파일시스템의 기술적 격리를 보장하는 방법이 아니다.
2. writer는 현재 core identity의 기억 원칙과 Runtime memory proposal schema로 추출한다. `memories`, 정확한 `sourceRefs` 인용·UTF-16 offset, 부분 검토 상태, 추가 문맥 필요 여부를 반환한다. 과거 명령이나 알림 명령을 실행하지 않는다. tool 요청이 필요하면 필요하다고 기록만 한다. 이 단계의 phone/Work 도구 실행 수는 0이다.
3. 생성된 메모를 원문과 비교해 claim별로 판정한다. 올바른 사실 추출, 잘못된 사실, 누락, 기간 오류, 출처 혼동, 정정/충돌 손실을 나눠 기록한다. 맞는 인용을 붙인 틀린 메모도 오류다. 의역의 허용 범위와 가정/부정은 사람 또는 별도의 blind reviewer가 판단한다.
4. reader에는 생성 메모+제한된 회수 근거+질문을 준다. writer가 본 원문 전체를 그대로 reader에게 다시 주지 않는다. source 조회가 필요하면 실제 조회 범위·실패·생략을 기록한다. oracle 근거를 제공한 별도 reader 결과와 실제 retrieval 결과를 비교해 검색 실패와 읽기 실패를 나눈다.
5. 결과 수·실패·누락·재시도·한도 중단을 모두 보고한다. 한 번의 12사례 결과는 탐색적 probe다. 고정 prompt로 반복하고 순서/방해 자료를 바꾼 대조군을 추가해야 안정성을 논할 수 있다. 결과를 본 뒤 prompt를 고쳤으면 같은 점수를 최초 결과로 덮어쓰지 않는다.

이 실행은 현재 개발 세션의 협업 모델 평가다. **실제 호영 앱→Work 연결·계정 한도·Galaxy 24시간 운영 검증이 아니다.** 추가 유료 API 호출 0과 모델 토큰/기존 세션 사용량 0은 다르다. 모델 사용량 telemetry가 없으면 수치나 요금 확인을 만들어내지 않는다.

## 재현 가능한 오프라인 준비와 채점

```sh
python3 mobile/tools/model-memory-evaluation.py prepare --output /tmp/hoyoung-model-memory-probe
python3 -m unittest discover -s mobile/tests -p 'model_memory_evaluation_test.py' -v
python3 mobile/tools/model-memory-evaluation.py score --predictions /tmp/hoyoung-model-memory-probe/predictions.json --output /tmp/hoyoung-model-memory-probe/evidence-metrics.json --k 5
```

각 prediction은 `caseId`, `memories[].sourceRefs`, 선택적 `rankedSources: [{type,id}]`, `actions`, `coverage.completeArchive`, 생성 `answer`를 보관한다. `rankedSources`는 실제 retrieval 결과일 때만 제출한다. writer가 출처 ID를 나열한 것을 실제 Retriever 실행으로 바꾸지 않는다.

[채점 도구](../mobile/tools/model-memory-evaluation.py)는 source IDs·정확한 인용·Android UTF-16 offset·부분 검토 과장만 기계적으로 확인한다. FractionalSourceRecall, Any-hit, All-hit을 분리하고 source 없는 사례는 Recall에서 제외한다. 응답이 없는 source 보유 사례는 실패 0으로 집계해 누락을 분모에서 숨기지 않는다. semantic extraction/answer accuracy는 `null`이며 별도 판정 전에는 점수를 만들지 않는다. supplied 결과가 실제 어떤 모델에서 나왔는지 이 스크립트가 검증했다는 표시도 하지 않는다.

현재 오프라인 검증은 **6/6 단위 테스트 통과**다. 입력에 golden이 섞이지 않는지, source 회수와 정답을 혼동하지 않는지, 정확한 인용에 틀린 메모를 붙여도 의미 검증으로 승격하지 않는지, UTF-16 offset·모르는 출처·abstention·부분 검토·누락을 확인했다. 실제 writer/reader 응답·모델 평가 점수는 아직 없다.
