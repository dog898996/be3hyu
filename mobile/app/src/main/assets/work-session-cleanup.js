/* Delete exactly one native-owned conversation through visible provider UI.
 * No history-wide deletion, provider APIs, cookies or account-memory mutation. */
(function () {
  'use strict';
  window.HoyoungDeleteConversation = async function (id) {
    const path = location.pathname;
    const target = /^\/(?:g\/[^/]+\/)?c\/([A-Za-z0-9_-]{8,128})\/?$/.exec(path);
    const result = code => ({ type: 'session_cleanup', code, conversationId: id });
    if (!/^[A-Za-z0-9_-]{8,128}$/.test(id) || location.origin !== 'https://chatgpt.com' || !target || target[1] !== id)
      return result('conversation_cleanup_wrong_target');
    const visible = node => !!node && node.isConnected && !!node.getClientRects().length
      && !node.closest('[hidden],[inert],[aria-hidden="true"]') && getComputedStyle(node).visibility !== 'hidden';
    const label = node => (node.getAttribute('aria-label') || node.innerText || node.textContent || '').trim();
    const all = selector => Array.from(document.querySelectorAll(selector)).filter(visible);
    const wait = async predicate => {
      const until = Date.now() + 5000;
      do { const found = predicate(); if (found) return found; await new Promise(resolve => setTimeout(resolve, 100)); } while (Date.now() < until);
      return null;
    };
    const exactLinks = () => Array.from(document.querySelectorAll('a[href]')).filter(a => {
      try { const url = new URL(a.href, location.href); return url.origin === location.origin && url.pathname === path; } catch (_) { return false; }
    });
    const oldToasts = new Set(all('[role="status"],[role="alert"],[data-sonner-toast]').map(node => node.textContent));
    const oldLinks = exactLinks();
    const headerButtons = all('header button, [data-testid="conversation-header"] button').filter(node =>
      node.matches('[data-testid="conversation-options-button"]') || /^(?:Conversation options|Open conversation options|대화 옵션|대화 옵션 열기|Open conversation menu|대화 메뉴 열기)$/i.test(label(node)));
    const rowButtons = [];
    for (const link of oldLinks) {
      // One exact sidebar row; never ascend into the history list itself.
      const row = link.closest('li') || link.parentElement;
      if (!row || row.querySelectorAll('a[href*="/c/"]').length !== 1) continue;
      for (const node of row.querySelectorAll('button')) if (visible(node) &&
          (/^(?:Open conversation options|Conversation options|대화 옵션|대화 옵션 열기|Open menu|메뉴 열기|More|더 보기)$/i.test(label(node)) || node.getAttribute('data-testid') === 'history-item-options')) rowButtons.push(node);
    }
    const menus = headerButtons.length === 1 ? headerButtons : [...new Set(rowButtons)];
    if (menus.length !== 1 || menus[0].disabled) return result('conversation_cleanup_control_unverified');
    menus[0].click();
    const deletion = await wait(() => {
      const items = all('[role="menu"] [role="menuitem"], [role="menu"] button').filter(node => /^(?:Delete|Delete chat|Delete conversation|삭제|채팅 삭제|대화 삭제)$/i.test(label(node)));
      return items.length === 1 ? items[0] : null;
    });
    if (!deletion || location.pathname !== path) return result('conversation_cleanup_control_unverified');
    deletion.click();
    const confirm = await wait(() => {
      const dialogs = all('[role="dialog"],[role="alertdialog"]');
      if (dialogs.length !== 1 || !/(?:delete|삭제)/i.test(dialogs[0].innerText)) return null;
      const buttons = Array.from(dialogs[0].querySelectorAll('button')).filter(visible).filter(node => /^(?:Delete|Delete chat|Delete conversation|삭제|채팅 삭제|대화 삭제)$/i.test(label(node)));
      return buttons.length === 1 ? buttons[0] : null;
    });
    if (!confirm || confirm.disabled || location.pathname !== path) return result('conversation_cleanup_confirmation_unverified');
    confirm.click(); // Never retry this click automatically after an unknown outcome.
    const confirmed = await wait(() => {
      if (location.pathname === path || all('[role="dialog"],[role="alertdialog"]').length) return false;
      const toast = all('[role="status"],[role="alert"],[data-sonner-toast]').some(node => !oldToasts.has(node.textContent)
        && /^(?:Chat deleted\.?|Conversation deleted\.?|대화가 삭제되었습니다\.?|채팅이 삭제되었습니다\.?|대화 삭제됨|채팅 삭제됨)$/i.test(label(node)));
      // A sidebar can virtualize or unmount after navigation. Missing links or
      // a closed confirmation dialog alone are not proof of server deletion.
      return toast;
    });
    return result(confirmed ? 'conversation_deleted' : 'conversation_cleanup_unconfirmed');
  };
})();
