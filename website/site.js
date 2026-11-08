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

  // Home page update log: the list the page was built with, then newer changes straight from
  // GitHub's public API (no key: 60 requests an hour per visitor, so answers are kept for a few minutes).
  var updates = document.querySelector('[data-updates]');
  if (updates) {
    var API = 'https://api.github.com/repos/Madhuhas/P-Sphere/commits?per_page=30';
    var REPO = 'https://github.com/Madhuhas/P-Sphere';
    var list = updates.querySelector('.log-list');
    var empty = updates.querySelector('.log-empty');
    var live = updates.querySelector('[data-live]');
    var liveText = updates.querySelector('[data-live-text]');
    var limit = Number(updates.getAttribute('data-limit')) || 0;

    var kindOf = function (subject) {
      var s = subject.toLowerCase();
      if (/security|vulnerab|cve-/.test(s)) return 'security';
      if (/^(p-?sphere )?v?\d+\.\d+\.\d+/.test(s)) return 'release';
      if (/^fix|\bfix(es|ed)?\b|broke|crash/.test(s)) return 'fix';
      if (/^(docs|readme|discussions|licen[cs]e)\b|contributing|\bguide\b/.test(s)) return 'docs';
      return 'new';
    };
    var applyFilter = function () {
      var pressed = updates.querySelector('.chips [aria-pressed="true"]');
      var kind = pressed ? pressed.getAttribute('data-kind') : '';
      var shown = 0;
      list.querySelectorAll('.log').forEach(function (li) {
        li.hidden = !!kind && li.getAttribute('data-kind') !== kind;
        if (!li.hidden) shown++;
      });
      empty.hidden = shown > 0;
    };
    updates.querySelectorAll('.chips button').forEach(function (b) {
      b.addEventListener('click', function () {
        updates.querySelectorAll('.chips button').forEach(function (x) { x.setAttribute('aria-pressed', String(x === b)); });
        applyFilter();
      });
    });
    // Built with createElement and textContent only: commit messages are shown as text, never as HTML.
    var line = function (c, fresh) {
      var li = document.createElement('li');
      li.className = 'log k-' + c.kind + (fresh ? ' fresh' : '');
      li.setAttribute('data-kind', c.kind);
      li.setAttribute('data-sha', c.sha);
      var time = document.createElement('time'); time.dateTime = c.date; time.textContent = c.date;
      var tag = document.createElement('span'); tag.className = 'tag'; tag.textContent = c.kind;
      var a = document.createElement('a'); a.href = REPO + '/commit/' + c.sha; a.rel = 'noopener'; a.textContent = c.subject;
      var code = document.createElement('code'); code.textContent = c.sha.slice(0, 7);
      li.append(time, tag, a, code);
      return li;
    };
    var fetchCommits = function () {
      var key = 'psphere-gh-commits';
      try {
        var hit = JSON.parse(sessionStorage.getItem(key));
        if (hit && Date.now() - hit.at < 4 * 60e3) return Promise.resolve(hit.data);
      } catch (e) {}
      return fetch(API, { headers: { Accept: 'application/vnd.github+json' } }).then(function (res) {
        if (!res.ok) throw new Error('GitHub answered ' + res.status);
        return res.json();
      }).then(function (all) {
        var data = all.filter(function (c) {
          var who = (c.author && c.author.login) || c.commit.author.name || '';
          var subject = c.commit.message.split('\n')[0];
          // Hide noise: bots, CLA signatures, version bumps and merge commits.
          return !/bot/i.test(who) && !/signed the cla|cla signature/i.test(c.commit.message) && !/^(bump |merge )/i.test(subject);
        }).map(function (c) {
          var subject = c.commit.message.split('\n')[0];
          return { sha: c.sha, date: c.commit.author.date.slice(0, 10), subject: subject, kind: kindOf(subject) };
        });
        try { sessionStorage.setItem(key, JSON.stringify({ at: Date.now(), data: data })); } catch (e) {}
        return data;
      });
    };
    var refresh = function () {
      fetchCommits().then(function (commits) {
        var known = {};
        list.querySelectorAll('[data-sha]').forEach(function (li) { known[li.getAttribute('data-sha')] = li; });
        var hadAny = Object.keys(known).length > 0;
        var lines = commits.map(function (c) { return known[c.sha] || line(c, hadAny); });
        list.replaceChildren.apply(list, limit ? lines.slice(0, limit) : lines);
        applyFilter();
        live.classList.add('on');
        liveText.textContent = 'live, checked ' + new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      }).catch(function () {
        live.classList.remove('on');
        liveText.textContent = 'GitHub not reachable: showing the last build';
      });
    };
    if (window.fetch) {
      refresh();
      setInterval(function () { if (!document.hidden) refresh(); }, 5 * 60e3);
    }
  }
})();
