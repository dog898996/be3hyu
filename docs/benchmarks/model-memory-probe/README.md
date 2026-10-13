# 공개 합성 모델 기억 probe

실제 계정·연락처·개인정보가 아닌 직접 작성한 한국어 12사례다. writer와 reader 모델의 최초 출력, 형식 정규화본과 정성 검토를 함께 보존한다. 전체 해시는 [execution-manifest.json](execution-manifest.json)에 있다.

- `preparation-manifest.json`은 모델 실행 이전 준비 단계의 원본이므로 `actualLmMeasured=false`를 그대로 보존했다. 실행 관측 범위는 별도의 execution manifest를 본다.
- `writer-outputs.json`은 최초 형식 실패까지 포함한 원본이다. `writer-outputs-contract-v2.json`은 문장을 고치지 않은 형식 변환본이다.
- `reader-inputs.json`에는 생성 메모 전체·그 안의 정확한 인용·metadata·coverage와 질문이 있다. 원문 파일과 golden은 별도로 주지 않았다. 입력 분리 지시를 공유 파일시스템의 기술적 접근 격리로 간주하지 않는다.
- `evidence-metrics.json`은 기계적 인용/출처 검사다. `rankedSources`가 없어 retrieval 지표가 null이며 semantic/answer 정확도도 null이다.
- `qualitative-review.json`은 개발 에이전트의 별도 루브릭 대조다. 공식 judge나 사람이 수행한 독립 검증이 아니다.

앱·Work·Android에서 이 writer→reader 호출을 실행한 결과나 표준 LongMemEval 점수가 아니다. 자세한 방법과 실패는 [평가 보고서](../../model-memory-evaluation.md)에 있다.
