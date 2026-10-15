/* Loads after the pinned MIT Chat On Steroids DOM module in a native-owned closure.
 * Expose only the DOM controls we use; Android still owns queue/attachments/receipts.
 * No Electron, MCP tunnel, extension worker, provider API or React reader is started.
 */
window.HoyoungCoSDom = Object.freeze({
  version: '2.1.37',
  composer: CLF_DOM.composer,
  sendButton: CLF_DOM.sendButton,
  stopButton: CLF_DOM.stopButton,
  generating: CLF_DOM.generating
});
