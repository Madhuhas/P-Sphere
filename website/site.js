// Small progressive enhancements. Every page works without this file.
(function () {
  // Mobile menu
  var header = document.querySelector('.site-header');
  var toggle = document.querySelector('.nav-toggle');
  if (header && toggle) {
    toggle.addEventListener('click', function () {
      var open = header.classList.toggle('open');
      toggle.setAttribute('aria-expanded', String(open));
      toggle.setAttribute('aria-label', open ? 'Close menu' : 'Open menu');
    });
    header.querySelectorAll('.nav a').forEach(function (a) {
      a.addEventListener('click', function () { header.classList.remove('open'); toggle.setAttribute('aria-expanded', 'false'); });
    });
  }

  // Docs: collapsible contents on small screens
  var docsNav = document.querySelector('.docs-nav');
  var docsToggle = document.querySelector('.docs-nav-toggle');
  if (docsNav && docsToggle) {
    docsToggle.addEventListener('click', function () {
      var open = docsNav.classList.toggle('open');
      docsToggle.setAttribute('aria-expanded', String(open));
    });
  }

  // Docs: highlight the section being read
  var links = Array.prototype.slice.call(document.querySelectorAll('.docs-nav a[href^="#"]'));
  if (links.length && 'IntersectionObserver' in window) {
    var byId = {};
    links.forEach(function (a) { byId[a.getAttribute('href').slice(1)] = a; });
    var visible = {};
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { visible[e.target.id] = e.isIntersecting; });
      var first = links.find(function (a) { return visible[a.getAttribute('href').slice(1)]; });
      if (first) { links.forEach(function (a) { a.classList.toggle('active', a === first); }); }
    }, { rootMargin: '-80px 0px -65% 0px' });
    Object.keys(byId).forEach(function (id) { var el = document.getElementById(id); if (el) io.observe(el); });
  }

  // Copy buttons on code blocks
  document.querySelectorAll('pre').forEach(function (pre) {
    if (!navigator.clipboard) return;
    var b = document.createElement('button');
    b.className = 'copy'; b.type = 'button'; b.textContent = 'Copy';
    b.addEventListener('click', function () {
      var text = pre.querySelector('code') ? pre.querySelector('code').innerText : pre.innerText.replace(/Copy$/, '');
      navigator.clipboard.writeText(text).then(function () {
        b.textContent = 'Copied'; setTimeout(function () { b.textContent = 'Copy'; }, 1500);
      }, function () { b.textContent = 'Select and copy'; });
    });
    pre.appendChild(b);
  });
})();
