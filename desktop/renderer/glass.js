// Liquid-glass pointer tracking: feeds --mx/--my so highlights follow the cursor.
(function () {
  var SELECTOR = '.btn, .card, .quick-tile, .gesture-card, .qr-card';
  var frame = 0;
  document.addEventListener('pointermove', function (e) {
    var el = e.target && e.target.closest ? e.target.closest(SELECTOR) : null;
    if (!el || frame) return;
    frame = requestAnimationFrame(function () {
      frame = 0;
      var r = el.getBoundingClientRect();
      el.style.setProperty('--mx', (((e.clientX - r.left) / r.width) * 100).toFixed(1) + '%');
      el.style.setProperty('--my', (((e.clientY - r.top) / r.height) * 100).toFixed(1) + '%');
    });
  }, { passive: true });
})();
