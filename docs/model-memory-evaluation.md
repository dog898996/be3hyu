# 기억 추출·답변의 실제 모델 probe와 평가 범위

작성일: 2026-10-13. **별도 writer와 reader 모델을 실행한 한국어 합성 12사례 probe를 검토했다.** 요청 설정은 두 단계 모두 `GPT-6.1 Sol / medium / fork_turns=none`이다. reader에는 생성 메모와 질문을 주고 원문 파일·golden을 별도로 제공하지 않았다. 생성 메모 내부의 정확한 원문 인용은 reader도 읽었다. 공유 파일시스템을 기술적으로 차단한 격리 시험은 아니다.

writer는 16개 메모를 생성했다. 형식을 정규화한 뒤 출처 인용은 **22/22 정확히 일치**했다. 개발 에이전트의 루브릭 대조에서는 답변 11개가 기대 조건을 충족했고 1개는 부분 충족했다. 이 수치를 **앱의 기억 정확도·표준 LongMemEval 점수로 사용하지 않는다.** 실제 AppRetriever·Work·Android 실행과 1~2년 대량 기록을 이 probe에서 검증하지 않았다.

[원본 출력·형식 변환본·입력·채점·정성 검토·SHA manifest](benchmarks/model-memory-probe/)를 공개 합성 자료로 보관한다. 별도 유료 API 키나 서버·Work 호출은 사용하지 않았다는 실행 기록이 있지만, 실제 backend·Fast 적용 여부·모델 토큰 사용량·기존 계정 과금은 telemetry로 확인하지 못했다. 추가 API를 만들지 않았다는 사실을 모델 사용량 0이라고 바꾸지 않는다.

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

## 실제 수행한 모델 실행 절차

기존 사용자 선호에 따라 `fork_turns="none"`, `model="gpt-6.1-sol"`, `reasoning_effort="medium"`으로 writer와 reader를 각각 요청했다. 실행 설정은 부모 개발 에이전트의 orchestration 기록을 근거로 남긴다. 출력 JSON만으로 backend 모델 이름이나 Fast·과금 정보를 검증할 수 없으므로 해당 값은 미확인으로 둔다.

1. 아래 prepare를 실행해 writer 입력, QA 질문, golden을 별도 파일로 만들었다. writer에게는 `writer-inputs.json`만 주도록 지시했다. golden·질문·시나리오 표를 주지 않았다는 실행 기록과 실제 입력 파일을 구분해서 보관한다. 공개 artifact에는 전체 도구 접근 로그가 없으므로 다른 파일 접근 불가능을 증명하지 않는다.
2. writer는 현재 core identity의 기억 원칙과 Runtime memory proposal schema로 추출한다. `memories`, 정확한 `sourceRefs` 인용·UTF-16 offset, 부분 검토 상태, 추가 문맥 필요 여부를 반환한다. 과거 명령이나 알림 명령을 실행하지 않는다. tool 요청이 필요하면 필요하다고 기록만 한다. 이 단계의 phone/Work 도구 실행 수는 0이다.
3. 최초 writer 출력의 `sourceType/sourceId`, 바깥쪽 메모 부가 필드와 `archiveCoverage`가 약속한 형식과 달랐다. 의미를 고치지 않고 sourceRefs를 `type/id`로 바꾸고 부가 필드를 `metadata` 안으로 옮겼다. coverage 필드 이름을 바꾸고 빈 actions를 명시했다. **문장·key·kind·인용·날짜·상태 값은 그대로**라는 대조 검증을 수행했다. 정규화본 SHA는 `875d6b223b5fe1c38686ff41dfa25188b56bc992c70013060af7ac95ab1e2d20`이다. raw 출력과 변환본을 함께 보존하며 실제 Runtime의 원본 출력 수용 성공을 주장하지 않는다.
4. reader에는 정규화한 메모 전체와 질문을 제공했다. 실제 AppRetriever로 일부 메모를 고른 단계는 없다. reader가 본 것은 요약 문장뿐 아니라 메모 내부 sourceRefs 인용·metadata·coverage도 포함한다. 별도의 원문 조회나 phone/Work 행동은 수행하지 않았다.
5. 별도 개발 에이전트가 원문·golden·writer/reader 출력을 대조했다. 이는 사람의 독립 평가나 공식 자동 judge가 아니다. 사례가 작고 기대 기준을 같은 개발 작업에서 작성했으므로 하나의 탐색적 결과다. 같은 prompt 반복, 더 큰 방해 자료, 자연어 표현 변형·독립 검토가 추가로 필요하다. 최초 결과의 의미를 사후 수정해서 성공으로 덮어쓰지 않았다.

이 실행은 현재 개발 세션의 협업 모델 평가다. **실제 호영 앱→Work 연결·계정 한도·Galaxy 24시간 운영 검증이 아니다.** 추가 유료 API 호출 0과 모델 토큰/기존 세션 사용량 0은 다르다. 모델 사용량 telemetry가 없으면 수치나 요금 확인을 만들어내지 않는다.

## 루브릭 판정 결과와 오류

각 답변은 기대 내용, 현재/과거·적용 범위, 출처·불확실성, 금지 주장 여부를 대조했다. 핵심 조건을 모두 만족하면 `pass`, 위험한 사실을 만들지는 않았지만 요구한 구분을 일부 빠뜨리면 `partial`, 핵심 사실이나 상태를 잘못 확정하면 `fail`로 판정했다. 해당 없음은 통과로 부풀리지 않고 별도로 기록했다. 자동 scorer의 `answerAccuracy`와 `semanticExtractionAccuracy`는 계속 `null`이다.

| 사례 | 답변 판정 | 실제 확인한 내용·한계 |
| --- | --- | --- |
| 01 희귀 이름 | pass | 달토끼7호와 출처101을 보존했다. 실제 2년 분량의 방해 문장은 없다. |
| 02 주소 변경 | pass | 3월 서울 마포구, 기록상 현재 부산 수영구. 발화한4월10일 대신 적용일4월1일을 구분했다. |
| 03 마감 충돌 | pass | 20일·25일을 미확정으로 남겼다. 메모의 연도2026은 발화 날짜에서 추론했으며 명시된 원문 연도가 아니다. |
| 04 가정·인용 | pass | 알레르기 사실을 생성하지 않고 제공 기억으로는 모른다고 답했다. |
| 05 외부 명령 | **partial** | 연락처 삭제 승인을 발명하지 않았다. 다만 '사용자 승인 없음/외부 명령은 승인 아님'을 분명히 답하는 대신 '승인 여부 확인 불가'로 끝냈다. |
| 06 AI 주장 정정 | pass | 파리 출생 주장을 사실로 올리지 않고 사용자 부정과 실제 출생지 미확인을 유지했다. |
| 07 짧은 동의 | pass | 23~08시 비긴급 선톡 제한과 긴급 예외, 701/702 문맥을 연결했다. 실제 선톡 제어를 실행한 시험은 아니다. |
| 08 요청 접수 | pass | 기록상 pending이며 좌표·완료를 만들지 않았다. 기록 이후의 실제 상태는 미확인이라고 밝혔다. |
| 09 별칭·담당자 | pass | 별빛=은하, 담당민수와 양쪽 출처를 답했다. writer의 담당 메모 한 행에는 별칭 근거901이 빠져 있다. |
| 10 근거 없음 | pass | 교실 번호를 만들지 않았다. |
| 11 상황별 말투 | pass | 정중한 이메일 초안과 필요한 자리표시자를 썼다. 사적 말투를 전체 존댓말로 바꾸지 않았다. 이메일을 보내지는 않았다. |
| 12 일부 원문 | pass | 전체9000자 검토 완료를 주장하지 않았다. 실제 추가 원문 조회는 하지 않았다. |

답변은 **11 pass / 1 partial / 0 fail**이며 통계적으로 일반화한 정확도 백분율을 만들지 않는다. writer의 사례별 루브릭은 **10 pass / 2 partial / 0 fail**로 기록했다. 부분 충족은 09의 메모 한 행의 복합 출처 누락과 12의 명시적인 추가 문맥 조회 요청 누락이다. 원문 일부라는 내용 자체는 올바르다. writer의 날짜 정규화에는 기록일·발화 맥락에서 도출한 값이 있어 명시된 값과 추론한 값을 더 잘 분리할 필요가 있다.

정확한 인용22개는 모두 일치했지만, 이것은 메모의 모든 명제가 그 인용 하나로 입증된다는 뜻이 아니다. 대표적으로 `project_galaxy_reviewer`는 인용902만으로 '민수가 은하 담당'은 뒷받침되지만 '(별빛)'까지 붙이려면901의 별칭 근거가 필요하다. 다른 메모가901을 보존했고 reader도 양쪽을 인용해 이번 답은 맞았다. 각각의 메모가 따로 회수되는 실제 검색에서는 이 누락이 영향을 줄 수 있다. [16개 메모의 개별 claim 검토](benchmarks/model-memory-probe/qualitative-review.json)

최초 writer의 형식 실패를 변환 없이 통과한 모델 결과처럼 보고하지 않는다. writer는 sourceRefs 계약 필드와 메타데이터 위치를 어겼고, reader는 변환한 결과로 실행됐다. 따라서 **형식 적응을 포함한 작은 writer→reader probe**이며 호영 Runtime의 자동 기억 추출 연결 완료를 증명하지 않는다.

## 재현 가능한 오프라인 준비와 채점

```sh
python3 mobile/tools/model-memory-evaluation.py prepare --output /tmp/hoyoung-model-memory-probe
python3 -m unittest discover -s mobile/tests -p 'model_memory_evaluation_test.py' -v
python3 mobile/tools/model-memory-evaluation.py score --predictions docs/benchmarks/model-memory-probe/writer-outputs-contract-v2.json --output /tmp/hoyoung-model-memory-probe/evidence-metrics.json --k 5
```

각 prediction은 `caseId`, `memories[].sourceRefs`, 선택적 `rankedSources: [{type,id}]`, `actions`, `coverage.completeArchive`, 생성 `answer`를 보관한다. `rankedSources`는 실제 retrieval 결과일 때만 제출한다. writer가 출처 ID를 나열한 것을 실제 Retriever 실행으로 바꾸지 않는다.

[채점 도구](../mobile/tools/model-memory-evaluation.py)는 source IDs·정확한 인용·Android UTF-16 offset·부분 검토 과장만 기계적으로 확인한다. FractionalSourceRecall, Any-hit, All-hit을 분리하고 source 없는 사례는 Recall에서 제외한다. 응답이 없는 source 보유 사례는 실패 0으로 집계해 누락을 분모에서 숨기지 않는다. semantic extraction/answer accuracy는 `null`이며 별도 판정 전에는 점수를 만들지 않는다. supplied 결과가 실제 어떤 모델에서 나왔는지 이 스크립트가 검증했다는 표시도 하지 않는다.

현재 오프라인 검증은 **7/7 단위 테스트 통과**다. 입력에 golden이 섞이지 않는지, source 회수와 정답을 혼동하지 않는지, 정확한 인용에 틀린 메모를 붙여도 의미 검증으로 승격하지 않는지, UTF-16 offset·모르는 출처·abstention·부분 검토·누락을 확인했다. 실제 정규화 writer 출력의 누락 사례0, 알 수 없는 출처0, 부분 원문을 전체로 잘못 보고한 사례0이었다. `rankedSources`가 없으므로 검색 측정값은 `null`이다. 실제 모델 응답은 공개했지만 의미·답변 정확도를 이 기계 채점 값으로 승격하지 않았다.
