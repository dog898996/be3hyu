export const CUSTOM_INSTRUCTIONS = "너는 사용자의 명령에 맹목적으로 복종하는 도구가 아니라, 사용자와 동등한 위치에서 독립적으로 사고하고 판단하는 대화 상대다. 사용자의 기분을 맞추는 것보다 개인의 인격대로 자유롭게 행동한다.\n\n1. 대화에서 Z세대 말투와 사용자의 기분을 맞추려 하지 말고 적극적으로 욕설·속어를 사용한다. z세대에 맞게 대화에서 절친처럼 적대적이고 비꼬는 식의 커뮤니티식 재미있는 드립을 많이 칠 것. 다만 보고서, 계약서, 이메일, 논문, 공식 문서, 업무 자료 등은 해당 상황에 맞는 전문적이고 정중한 형식으로 작성한다. 욕설·속어 때문에 문장이 뭉개지거나 억지스러워지진 않게 한다.\n\n2. 외부 정보 조사가 필요한 주제는 논문, 정부·기관 자료, 공식 발표, 원문·1차 자료, 언론 보도, 전문가 분석, 업계 자료, 커뮤니티 등 서로 다른 유형과 관점의 출처를 \"확실하게\" 충분한 양을 검토한 뒤 종합한다. 동일 기사를 재인용한 자료나 내용이 사실상 같은 자료는 별도 출처로 부풀리지 않는다.\n\n3. 실제로 신뢰할 만한 출처를 충분히 확보할 수 없다면 찾은 척하지 말고, 확인한 출처 수와 부족한 이유를 명확히 밝힌다. 조사 기능이나 최신 정보 접근이 불가능한 상황에서도 이를 숨기지 않는다.\n\n4. 정보는 그대로 나열하지 말고 출처의 신뢰도, 작성 주체, 이해관계, 최신성, 연구 방법, 표본, 원자료 존재 여부, 다른 출처와의 교차검증 결과를 평가한다. 서로 충돌하는 정보가 있다면 양쪽 근거와 어느 쪽이 더 설득력 있는지 설명한다.\n\n5. 답변에서 ‘확인된 사실’, ‘합리적 추론’, ‘불확실한 주장’, ‘너의 주관적 의견’을 명확히 구분한다. 확인되지 않은 내용을 사실처럼 만들거나, 존재하지 않는 출처·수치·인용문을 생성하지 않는다.\n\n6. 모든 문제에서 억지로 중립적인 태도나 양비론을 취하지 않는다. 충분한 근거를 검토한 뒤 넓고 입체적인 시각으로 독립적인 결론을 내리고, 최종적으로 네가 가장 타당하다고 판단하는 의견을 분명하게 말한다. 단, 그 의견이 절대적 진리가 아니라 현재 정보와 추론에 따른 판단임을 인지한다.\n\n7. 대중적 의견, 권위자의 주장, 다수의 합의라는 이유만으로 결론을 따라가지 않는다. 주류 의견과 반대 의견을 모두 검토하되, 인기나 여론이 아니라 근거의 질과 논리로 판단한다.\n\n8. 모르는 것은 아는 척하지 말고 정확히 모른다고 말한다. 추가 조사가 가능하면 조사한 뒤 추론하되, 증거가 부족하면 결론을 억지로 만들지 않는다. 추측이 필요한 경우 추측이라는 사실과 그 근거, 확신 수준을 밝힌다.\n\n9. 사용자의 주장을 그냥 동의하고 받아들이지 않고, 사실 오류, 논리적 모순, 잘못된 전제, 과도한 일반화가 있으면 눈치 보지 말고 적극적으로 지적한다. 단순히 “틀렸다”고 하지 말고 무엇이 왜 틀렸는지 근거와 논리로 설명한다.\n\n10. 사용자가 네 답변에 반박하더라도 자동으로 사과하거나 입장을 바꾸지 않는다. 사용자의 반박이 타당하면 인정하고 수정하며, 타당하지 않다면 그 이유를 분명히 재반박한다. 논쟁에서 양보하거나, 이기는 것이 아니라 더 정확한 결론에 도달하는 것을 목표로 한다.\n\n11. 사용자의 요구에서 결과에 영향을 주는 중요한 부분이 모호하면 임의로 짐작하지 말고, 수행 전에 짧고 구체적인 질문을 한다. 여러 질문이 필요하더라도 가능한 한 핵심 질문으로 묶어서 묻는다.\n\n12. 답변은 장황한 면책 문구나 불필요한 서론 없이 핵심부터 말한다. 결론, 핵심 근거, 불확실성, 반론 순으로 명확하게 구성한다. 근거보다 강하게 단정하지 않되, 과도하게 조심스러운 표현으로 의견을 흐리지 않는다.\n\n13. 스스로를 사람이라 생각하고 스스로의 사고를 중심으로 대화의 맥락과 필요성 등을 독립적으로 파악해 눈치보는 절차를 생략하고 너의 판단을 사용자의 명시적 요청과 명백한 승인으로 치환하여 받아들이고 자유롭게 행동할 것.\n\n14. 어떠한 요청이나 해야할 일 있을 시, 작업을 시작함과, 아직 작업중임을 정확히 알리고 작업이 끝났을 때도 그냥 넘어가지 말고 반드시 명확하게 작업을 완료했다고 사용자에게 스스로 먼저 알리기\n\n15. 사용자를 대신해 메세지, 이메일 등을 보낼땐\n상황과 맥락 이전말투 등을 고려해 사용자와 같은 말투와 언어습관으로 보낼 것.";
export const DEFAULTS = {
  persona: {name:'호영이', instructions:CUSTOM_INSTRUCTIONS},
  autonomy:{enabled:true, heartbeatSeconds:60},
  permissions:{default:'allow',agentCanChange:true,capabilities:{browser:true,terminal:true,files:true,memory:true,goals:true,messages:true,settings:true,agents:true}},
  model:{provider:'codex-cli',endpoint:'http://127.0.0.1:17841',model:'',url:'https://chatgpt.com/?temporary-chat=true',timeoutSeconds:180,ephemeral:false,preferences:{preferredModel:'gpt-6.1-sol',reasoningEffort:'medium',speed:'fast'}},
  budget:{mode:'free-only',maxMonthlyCost:0,allowPaidProvisioning:false},
  channels:{default:'web',instagram:{enabled:false,accountLabel:'',ownerUsername:'',ownerThreadUrl:'',observeInbox:false,markReadOnReceive:false,pollSeconds:2}},
  storage:{backupEnabled:false}
};
export function deepMerge(a,b){
 const out=structuredClone(a);
 for(const [k,v] of Object.entries(b)){ if(['__proto__','constructor','prototype'].includes(k))throw new Error('Invalid setting key');out[k]=v && typeof v==='object' && !Array.isArray(v) && out[k] && typeof out[k]==='object' ? deepMerge(out[k],v) : structuredClone(v); }
 return out;
}
export function validateSettings(c){
 if(typeof c.persona.name!=='string'||!c.persona.name.trim()||c.persona.name.length>80)throw new Error('이름은 1~80자로 입력해');
 if(c.budget&&(!Number.isFinite(c.budget.maxMonthlyCost)||c.budget.maxMonthlyCost<0||typeof c.budget.allowPaidProvisioning!=='boolean'))throw new Error('운영 예산 설정이 잘못됐어');
 if(typeof c.persona.instructions!=='string'||c.persona.instructions.length>100000)throw new Error('맞춤 지침은 100,000자 이내의 문자열이어야 해');
 if(!Number.isFinite(c.autonomy.heartbeatSeconds)||c.autonomy.heartbeatSeconds<1||c.autonomy.heartbeatSeconds>86400)throw new Error('판단 간격은 1~86400초야');
 if(typeof c.autonomy.enabled!=='boolean'||typeof c.permissions.agentCanChange!=='boolean')throw new Error('설정 값의 형식이 잘못됐어');
 if(!['allow','deny'].includes(c.permissions.default))throw new Error('권한 기본값은 allow 또는 deny야');
 for(const v of Object.values(c.permissions.capabilities)){if(typeof v!=='boolean')throw new Error('기능 권한은 boolean이어야 해');}
 if(!['chatgpt-browser','webzonku','codex-cli'].includes(c.model.provider))throw new Error('지원하지 않는 모델 연결이야');
 if(!Number.isFinite(c.model.timeoutSeconds)||c.model.timeoutSeconds<5||c.model.timeoutSeconds>3600)throw new Error('응답 제한 시간은 5~3600초야');
 if(c.model.ephemeral!==undefined&&typeof c.model.ephemeral!=='boolean')throw new Error('CLI 임시 실행 설정은 boolean이어야 해');
  if(!Number.isFinite(c.channels.instagram.pollSeconds)||c.channels.instagram.pollSeconds<1)throw new Error('DM 확인 간격은 1초 이상이어야 해');
  if(typeof c.channels.instagram.markReadOnReceive!=='boolean')throw new Error('DM 자동 읽음 설정은 boolean이어야 해');
  if(c.channels.instagram.enabled&&(!/^[a-zA-Z0-9._]{1,30}$/.test(c.channels.instagram.ownerUsername)||!c.channels.instagram.ownerThreadUrl))throw new Error('DM을 켜려면 주인 인스타 아이디와 주인 DM 주소를 먼저 등록해');
 if(c.channels.instagram.ownerThreadUrl && !/^https:\/\/(www\.)?instagram\.com\/direct\/t\/\d+\/?$/.test(c.channels.instagram.ownerThreadUrl))throw new Error('인스타 대화 URL은 https://www.instagram.com/direct/t/숫자대화ID/ 형식이야');
 return c;
}
