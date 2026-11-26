// Android instrumentation drives the original controls from the author's document.
document.addEventListener('DOMContentLoaded', () => {
  if (location.href !== 'about:srcdoc') return;
  const opening = document.querySelector('.swipe-item[data-id="1"]');
  if (opening) {
    opening.click();
    CardProbe.postMessage('clicked');
    return;
  }
  if (!document.querySelector('#app')) return;
  const floor = getCurrentMessageId();
  CardProbe.postMessage('panel-' + floor);
  if (floor === 0) {
    const image = new Image();
    image.onload = () => { if (image.naturalWidth === 48 && image.naturalHeight === 36) CardProbe.postMessage('placeholder'); };
    image.src = 'https://images.example/unavailable.png';
    document.body.append(image);
  }
  const observer = new MutationObserver(check);
  observer.observe(document.body, { subtree: true, childList: true, characterData: true });
  let clicked = false;
  function check() {
    if (floor === 0 && document.querySelector('.options-panel .option-btn')) {
      observer.disconnect();
      CardProbe.postMessage('options');
    } else if (floor === 0 && !clicked && document.querySelector('.info-grid')) {
      const link = [...document.querySelectorAll('.nav-item')].find(item => item.textContent === '选项');
      if (link) { clicked = true; link.click(); }
    } else if (floor === 2 && document.querySelector('.info-grid')?.textContent.includes('Probe location')) {
      observer.disconnect();
      CardProbe.postMessage('refreshed');
    }
  }
  check();
});
