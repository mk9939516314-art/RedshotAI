/* Redshot Tatkal engine - runs INSIDE the IRCTC page (in-app browser), like a browser extension.
 * Works on the page's DOM (Angular / PrimeNG), never on screen coordinates: every click goes through safeClick(),
 * which only clicks a real, visible, enabled element it identified by selector or by its visible text.
 * The engine is a loop: look at which IRCTC page this is, do the next safe thing, repeat. It recovers from errors
 * (IRCTC "unable to process", dialogs, slow dropdowns) by retrying that same step - never by guessing.
 * Captcha, OTP and the final payment approval stay with the owner (the engine tells him exactly when). */
(function () {
  'use strict';
  if (window.__RS && window.__RS.stop) { try { window.__RS.stop(); } catch (e) {} }

  var CLASS_LABELS = { '1A': 'AC First Class (1A)', '2A': 'AC 2 Tier (2A)', '3A': 'AC 3 Tier (3A)', '3E': 'AC 3 Economy (3E)',
    '2S': 'Second Sitting (2S)', 'SL': 'Sleeper (SL)', 'CC': 'AC Chair car (CC)', 'EC': 'Exec. Chair Car (EC)', 'FC': 'First Class (FC)' };
  var QUOTA_LABELS = { 'TQ': 'TATKAL', 'GN': 'GENERAL', 'PT': 'PREMIUM TATKAL', 'LD': 'LADIES', 'SS': 'LOWER BERTH/SR.CITIZEN', 'HP': 'PERSON WITH DISABILITY', 'DF': 'DEFENCE', 'YU': 'YUVA', 'FT': 'FOREIGN TOURIST' };
  var MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];

  var RS = window.__RS = { version: '1.0', job: null, running: false, stage: '', events: [] };
  var timer = null, busy = false, stopped = false;
  var secret = { user: '', pw: '' };   // closure only, never on window.__RS
  var st = {};           // per-run state: attempts, flags per stage

  // ---------------------------------------------------------------- helpers
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function qs(s, root) { try { return (root || document).querySelector(s); } catch (e) { return null; } }
  function qsa(s, root) { try { return Array.prototype.slice.call((root || document).querySelectorAll(s)); } catch (e) { return []; } }
  function visible(el) { return !!(el && (el.offsetWidth || el.offsetHeight || (el.getClientRects && el.getClientRects().length))); }
  function enabled(el) { return !!(el && !el.disabled && el.getAttribute('aria-disabled') !== 'true' && !/\bdisabled\b/.test(el.className || '')); }
  function txt(el) { return ((el && (el.innerText || el.textContent)) || '').replace(/\s+/g, ' ').trim(); }
  function now() { var sk = 0; try { if (window.RSBridge && RSBridge.skew) sk = Number(RSBridge.skew()) || 0; } catch (e) {} return Date.now() + sk; }
  function norm(s) { return String(s || '').toUpperCase().replace(/[^A-Z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim(); }
  function emit(type, data) {
    var rec = { t: Date.now(), type: type, data: data || {} };
    RS.events.push(rec); if (RS.events.length > 300) RS.events.shift();
    try { if (window.RSBridge && RSBridge.event) RSBridge.event(type, JSON.stringify(data || {})); } catch (e) {}
  }
  function log(msg) { emit('log', { msg: msg }); }

  function waitFor(fn, timeout, every) {
    var end = Date.now() + (timeout || 8000);
    return new Promise(function (resolve) {
      (function poll() {
        var v = null; try { v = fn(); } catch (e) {}
        if (v) return resolve(v);
        if (Date.now() > end || stopped) return resolve(null);
        setTimeout(poll, every || 120);
      })();
    });
  }

  // Angular only notices a value change when the NATIVE setter is used and input/change events follow
  function nativeSet(el, value) {
    if (!el) return false;
    var proto = el.tagName === 'SELECT' ? HTMLSelectElement.prototype : el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var d = Object.getOwnPropertyDescriptor(proto, 'value');
    if (d && d.set) d.set.call(el, String(value)); else el.value = String(value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  }
  function typeInto(el, value) {          // human-like enough: focus, set, key events for autocompletes that listen to keyup
    el.focus();
    nativeSet(el, value);
    var last = String(value).slice(-1) || 'a';
    el.dispatchEvent(new KeyboardEvent('keydown', { key: last, bubbles: true }));
    el.dispatchEvent(new KeyboardEvent('keyup', { key: last, bubbles: true }));
  }
  function mouseClick(el) {               // full pointer sequence: PrimeNG list items react to mousedown, not just click
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (t) {
      var ev;
      try { ev = new MouseEvent(t, { bubbles: true, cancelable: true, view: window }); } catch (e) { ev = new Event(t, { bubbles: true }); }
      el.dispatchEvent(ev);
    });
  }
  function safeClick(el, why) {
    if (!el || !visible(el)) { log('click skipped (not visible): ' + why); return false; }
    if (!enabled(el)) { log('click skipped (disabled): ' + why); return false; }
    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) {}
    if (typeof el.click === 'function' && !/^(LI|DIV|SPAN|A|TD|LABEL|P-RADIOBUTTON)$/.test(el.tagName)) el.click(); else mouseClick(el);
    return true;
  }
  function buttonByText(root, re) {
    return qsa('button, a[role="button"], input[type="button"], input[type="submit"]', root || document)
      .filter(function (b) { return visible(b) && re.test((txt(b) || b.value || '').trim()); })[0] || null;
  }
  function smallestWithText(root, test) {
    return qsa('h1,h2,h3,h4,strong,span,div,td,th,label,button,a,li', root)
      .filter(function (e) { return visible(e) && test(txt(e), e); })
      .sort(function (a, b) { return txt(a).length - txt(b).length; })[0] || null;
  }
  function similarity(a, b) {              // 0..1, Levenshtein based
    a = norm(a); b = norm(b); if (!a || !b) return 0; if (a === b) return 1;
    var m = a.length, n = b.length, d = [], i, j;
    for (i = 0; i <= m; i++) { d[i] = [i]; } for (j = 1; j <= n; j++) d[0][j] = j;
    for (i = 1; i <= m; i++) for (j = 1; j <= n; j++) d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    return 1 - d[m][n] / Math.max(m, n);
  }
  RS._similarity = similarity;

  // ---------------------------------------------------------------- page / dialog detection
  var BERTH = { lower: 'LB', lb: 'LB', middle: 'MB', mb: 'MB', upper: 'UB', ub: 'UB', 'side lower': 'SL', sl: 'SL', 'side upper': 'SU', su: 'SU', none: '', nopref: '', 'no preference': '', '': '' };
  function berthCode(v) { var k = String(v == null ? '' : v).toLowerCase().replace(/[^a-z ]/g, '').trim(); return k in BERTH ? BERTH[k] : String(v); }
  function noInsurance() {                    // IRCTC asks about travel insurance; always answer NO (never pay extra by accident)
    var box = smallestWithText(document, function (t) { return /travel insurance/i.test(t) && t.length < 600 && /\bno\b/i.test(t); });
    if (!box) return false;
    var no = smallestWithText(box.parentElement || box, function (t) { return /^no\b/i.test(t) && t.length < 120; });
    if (no && !st.insPicked) { st.insPicked = true; mouseClick(no); log('travel insurance: NO'); return true; }
    return false;
  }
  function path() { return (location.pathname || '').toLowerCase(); }
  function errorDialog() {
    var dlg = qsa('p-dialog, p-confirmdialog, [role="dialog"], .ui-dialog, .modal-dialog, .p-dialog').filter(visible)[0];
    if (!dlg) return null;
    var t = txt(dlg);
    if (/unable to process|something went wrong|try again|session (has )?(expired|timed)|high (load|traffic)|service (is )?(temporarily )?unavailable|technical (error|difficult)/i.test(t)) return dlg;
    return null;
  }
  function dialogs() {
    return qsa('p-dialog, p-confirmdialog, [role="dialog"], .ui-dialog, .p-dialog, .modal-dialog').filter(visible).filter(function (d) {
      return !/disha|chat ?bot|cookie/i.test((d.id || '') + ' ' + (d.className || '') + ' ' + txt(d).slice(0, 30));       // the chat helper / cookie bar are not notices that block the page
    });
  }
  function dismissBtn(dlg) {
    return buttonByText(dlg, /^(ok|okay|yes|close|got it|continue|proceed|accept|i agree|english|dismiss|no thanks|later|skip|understood|agree|\u00d7|x)$/i)
      || qs('.ui-dialog-titlebar-close, .p-dialog-header-close, button[aria-label*="close" i], [class*="close" i], .pi-times, .fa-times', dlg);
  }
  function infoDialog() {        // harmless notices that only need an OK (language, aadhaar notice, alerts)
    var all = dialogs();
    for (var i = 0; i < all.length; i++) {
      var dlg = all[i];
      if (qs('input[type="password"]', dlg)) continue;                       // that is the login window
      var t = txt(dlg); if (!t) continue;
      if (/payment|pay (&|and) book|cancel (your )?(ticket|booking)|logout|log out/i.test(t)) continue; // never auto-dismiss these
      var sig = norm(t).slice(0, 80);
      if (st.deadDlg && st.deadDlg[sig] >= 3 && !dismissBtn(dlg)) continue;     // a popup nothing can close: stop waiting for it, carry on with the page underneath
      return dlg;
    }
    return null;
  }
  function loginDialog() {
    var pw = qsa('input[type="password"]').filter(visible)[0];
    if (!pw) return null;
    var root = pw.closest('[role="dialog"], p-dialog, .ui-dialog, .modal, .login-modal, .p-dialog') || pw.parentElement;
    while (root && root !== document.body) {
      var btn = buttonByText(root, /^sign\s*in$/i);
      if (btn) {
        var user = ['input[formcontrolname="userid"]', 'input[formcontrolname="userName"]', 'input[formcontrolname="username"]', 'input[name="userid"]',
          'input[name="userName"]', 'input[placeholder*="user" i]', 'input[type="text"]']
          .map(function (s) { return qsa(s, root).filter(visible)[0]; }).filter(Boolean)[0];
        if (user) return { root: root, user: user, pw: pw, btn: btn };
      }
      root = root.parentElement;
    }
    return null;
  }
  function captchaField(root) {
    return qsa('input', root || document).filter(function (i) {
      if (!visible(i) || i.type === 'checkbox' || i.type === 'password' || i.type === 'radio') return false;
      return /captcha/i.test([i.getAttribute('formcontrolname'), i.id, i.name, i.placeholder, i.getAttribute('aria-label')].filter(Boolean).join(' '));
    })[0] || null;
  }
  function loggedIn() {
    return qsa('a, button, span, li').some(function (e) { return visible(e) && /^log\s*out$/i.test(txt(e)); });
  }
  // log in EARLY (owner only types the captcha); never wait for Book Now to discover we are logged out
  function loginTrigger() {          // the LOGIN link may sit behind a menu / icon: find it by text, then by attributes, visible or not (a DOM click works on hidden links too)
    var vis = qsa('a, button').filter(function (e) { return visible(e) && /^log\s*in(\s*\/\s*register)?$/i.test(txt(e)); })[0];
    if (vis) return { el: vis, how: 'visible' };
    var any = qsa('a, button, li, span, div').filter(function (e) { return /^log\s*in(\s*\/\s*register)?$/i.test(txt(e)) && e.children.length < 3 && !e.closest('p-dialog, [role="dialog"]'); })[0];
    if (any) return { el: any.closest('a, button') || any, how: 'hidden' };
    var attr = qsa('[aria-label*="login" i], [title*="login" i], [id*="login" i], [class*="login" i]').filter(function (e) {
      return !e.closest('p-dialog, [role="dialog"], form') && !qs('input', e) && e.children.length < 4 && !/sign\s*in|logout|log out|register/i.test(txt(e));
    })[0];
    return attr ? { el: attr, how: 'attr' } : null;
  }
  function openMenu() {
    var m = qsa('a, button, i, span, div').filter(function (e) {
      return visible(e) && (/h_menu|menu_drop|hamburger|fa-align-justify|fa-bars|navbar-toggle|user|account/i.test((e.className && e.className.baseVal === undefined ? e.className : '') + ' ' + (e.id || '')) || /menu|account|user/i.test(e.getAttribute('aria-label') || ''));
    })[0];
    if (m) { mouseClick(m.closest('a, button') || m); return true; }
    return false;
  }
  function headerHtml() {
    var h = qs('app-header, header, .h_container, nav');
    return h ? h.outerHTML.replace(/\s+/g, ' ').replace(/<(svg|path|img)[^>]*>/g, '').replace(/ (ng-[a-z0-9-]+|_ngcontent-[a-z0-9-]+)(="[^"]*")?/g, '') : '(no header element)';
  }
  // log in EARLY (owner only types the captcha, if IRCTC asks for one); never wait for Book Now to discover we are logged out
  function ensureLogin() {
    if (!secret.user || loggedIn() || loginDialog() || infoDialog() || errorDialog()) return;
    if (st.loginOpenedAt && Date.now() - st.loginOpenedAt < 6000) return;
    var t = loginTrigger();
    if (t) { st.loginOpenedAt = Date.now(); st.loginTries = (st.loginTries || 0) + 1; if (st.loginTries % 2 === 0 && nativeTap(t.el)) log('login: native tap'); else mouseClick(t.el); log('login window opened early (' + t.how + ') #' + st.loginTries); return; }
    if (Date.now() - (st.menuTried || 0) > 5000) {
      st.menuTried = Date.now();
      log('login link not found' + (openMenu() ? ', opened a menu' : ''));
      if (!st.hdrDump) { st.hdrDump = 1; var h = headerHtml(); log('header[1/2] ' + h.slice(0, 900)); log('header[2/2] ' + h.slice(900, 1800)); }
    }
  }
  function stage() {
    var p = path();
    if (/bookingconfirm|booking\/confirm|ticketdetails|booking\/bkgconfirm/.test(p) || confirmedPage()) return 'confirmed';
    if (errorDialog()) return 'error';
    if (loginDialog()) return 'login';
    if (infoDialog()) return 'dialog';
    if (/ewallet-confirm/.test(p) || qs('app-ewallet-confirm')) return 'wallet';
    if (/bkgpaymentoptions/.test(p)) return 'payment';
    if (/reviewbooking/.test(p)) return 'review';
    if (/psgninput/.test(p)) return 'passengers';
    if (/booking\/train-list|train-list/.test(p)) return 'trainlist';
    if (/train-search|\/nget\/?$|\/nget\/home/.test(p)) return 'search';
    return 'other';
  }
  function confirmedPage() {
    var t = txt(document.body).slice(0, 4000);
    return /booking\s*(is\s*)?(successful|confirmed)|ticket\s*booked|PNR\s*(No\.?|Number)?\s*:?\s*\d{10}/i.test(t) && /\b\d{10}\b/.test(t);
  }

  // ---------------------------------------------------------------- generic controls
  async function chooseDropdown(control, label, code) {
    if (!control || !label) return false;
    if (control.tagName === 'SELECT') {
      var o = qsa('option', control).filter(function (x) { return x.value === code || txt(x).toLowerCase() === label.toLowerCase() || txt(x).toLowerCase().indexOf(label.toLowerCase()) >= 0; })[0];
      if (!o) return false; nativeSet(control, o.value); return true;
    }
    var want = label.toLowerCase();
    var cur = txt(control).toLowerCase();
    if (cur === want || (cur.indexOf(want) >= 0 && want.length > 6 && !(want === 'tatkal' && cur.indexOf('premium') >= 0))) return true;
    for (var attempt = 0; attempt < 3; attempt++) {
      var trigger = qs('.ui-dropdown, .p-dropdown', control) || control;
      mouseClick(trigger);
      var opts = await waitFor(function () {
        var l = qsa('li[role="option"], .ui-dropdown-item, .p-dropdown-item, p-dropdownitem li').filter(visible); return l.length ? l : null;
      }, 2500);
      if (opts) {
        var pick = opts.filter(function (x) { return txt(x).toLowerCase() === want; })[0]
          || opts.filter(function (x) { return code && txt(x).indexOf('(' + code + ')') >= 0; })[0]
          || opts.filter(function (x) { return txt(x).toLowerCase().indexOf(want) >= 0 && !(want === 'tatkal' && /premium/i.test(txt(x))); })[0];
        if (pick) { mouseClick(pick); await sleep(120); var now_ = txt(control).toLowerCase(); if (now_.indexOf(want.split(' (')[0]) >= 0 || now_.indexOf(want) >= 0) return true; }
      }
      document.body.click(); await sleep(150);
    }
    return false;
  }

  function optionScore(optText, want) {
    var o = norm(optText), code = norm(want.code), name = norm(want.name);
    var tokens = o.split(' ');
    var s = 0;
    if (code && tokens.indexOf(code) >= 0) s = Math.max(s, 100);          // "BETTIAH - BTH" contains the code as its own word
    if (name) {
      if (o.indexOf(name) === 0) s = Math.max(s, 85);
      else if (o.indexOf(name) >= 0) s = Math.max(s, 70);
      s = Math.max(s, similarity(o.replace(/\b[A-Z]{1,5}$/, '').trim(), name) * 80);
    }
    return s;
  }

  // ---- fields are found by their IRCTC ids first, then by what they ARE (placeholder / label / role), so a renamed form still works
  function findField(kind) {
    var sels = kind === 'origin' ? ['p-autocomplete[formcontrolname="origin"]', '#origin'] : kind === 'destination' ? ['p-autocomplete[formcontrolname="destination"]', '#destination'] : ['p-calendar[formcontrolname="journeyDate"]', '#jDate'];
    for (var i = 0; i < sels.length; i++) { var c = qs(sels[i]); if (c && visible(c)) return c; }
    var re = kind === 'origin' ? /\bfrom\b|origin|source|boarding|departure/i : kind === 'destination' ? /\bto\b|destination|arrival/i : /date|calendar|journey/i;
    var inputs = qsa('input').filter(function (x) { return visible(x) && !/^(checkbox|radio|password|hidden|button|submit)$/.test(x.type); });
    var hit = inputs.filter(function (x) { return re.test([x.placeholder, x.getAttribute('aria-label'), x.id, x.name, x.getAttribute('formcontrolname')].filter(Boolean).join(' ')); })[0];
    return hit || (kind === 'date' ? (qsa('p-calendar input, .p-calendar input, .ui-calendar input').filter(visible)[0] || null) : null);
  }
  function findDropdown(kind) {
    var sels = kind === 'class' ? ['p-dropdown[formcontrolname="journeyClass"]', '#journeyClass'] : ['p-dropdown[formcontrolname="journeyQuota"]', '#journeyQuota'];
    for (var i = 0; i < sels.length; i++) { var c = qs(sels[i]); if (c && visible(c)) return c; }
    var re = kind === 'class' ? /all classes|sleeper|ac chair|first class|second sitting|3a|2a|1a|\bsl\b|\bcc\b/i : /general|tatkal|ladies|lower berth|person with disability|duty pass|premium/i;
    var all = qsa('p-dropdown, .p-dropdown, .ui-dropdown, select').filter(function (d) { return visible(d) && txt(d).length < 80; });
    var byText = all.filter(function (d) { return re.test(txt(d)); })[0];
    if (byText) return byText;
    var top = qsa('p-dropdown, .p-dropdown, select').filter(function (d) { return visible(d) && !d.parentElement.closest('p-dropdown, .p-dropdown'); });
    return (kind === 'class' ? top[0] : top[1]) || null;      // last resort: the form lists Class first, Quota second
  }
  function dismissLanguage() {        // the English / Hindi chooser is not a button: it is clicked by its words
    if (st.langAt && Date.now() - st.langAt < 6000) return false;
    var el = smallestWithText(document, function (t, e) {
      if (t !== 'English' && t !== 'ENGLISH') return false;
      var up = e.parentElement && e.parentElement.parentElement || e.parentElement;
      return !!up && /hindi|\u0939\u093f\u0928\u094d\u0926\u0940|\u0939\u093f\u0902\u0926\u0940/i.test(txt(up)) && txt(up).length < 400;
    });
    if (!el) return false;
    st.langAt = Date.now(); mouseClick(el.closest('button, a, li, label, [role="button"], [role="radio"]') || el); log('language popup: English chosen'); return true;
  }
  // a compact map of what is really on the page, written to the brain log so a wrong guess can be fixed without a new app
  function domMap() {
    var out = [];
    qsa('input, select, textarea, button, a, p-dropdown, p-autocomplete, p-calendar, [role="button"], [role="dialog"], p-dialog, [role="radio"], [role="tab"]').filter(visible).slice(0, 70).forEach(function (e) {
      var a = [e.tagName.toLowerCase()];
      ['id', 'formcontrolname', 'name', 'type', 'placeholder', 'aria-label', 'role'].forEach(function (k) { var v = e.getAttribute(k); if (v) a.push(k.replace('formcontrolname', 'fc') + '=' + String(v).slice(0, 28)); });
      var c = (typeof e.className === 'string' ? e.className : '').split(/\s+/).filter(Boolean).slice(0, 2).join('.'); if (c) a.push('.' + c.slice(0, 30));
      var t = txt(e).slice(0, 22); if (t) a.push('"' + t + '"');
      out.push(a.join(' '));
    });
    return out.join(' ; ');
  }
  function dumpDom(why) {
    RS.dumps = (RS.dumps || 0) + 1; if (RS.dumps > 14) return;
    var m = domMap(), n = Math.ceil(m.length / 850) || 1;
    for (var i = 0; i < n && i < 5; i++) log('dom[' + why + ' ' + path() + ' ' + (i + 1) + '/' + n + '] ' + m.slice(i * 850, (i + 1) * 850));
  }

  async function fillStation(field, code, name) {
    var ctl = findField(field);
    var input = ctl && (ctl.tagName === 'INPUT' ? ctl : qs('input', ctl));
    if (!input) return false;
    var want = { code: code, name: name };
    var isSet = function () {
      var v = norm(input.value);
      var okVal = (code && v.split(' ').indexOf(norm(code)) >= 0) || (name && v.indexOf(norm(name)) >= 0 && v.length > 3);
      var valid = !ctl.classList || ctl.classList.contains('ng-valid') || ctl.tagName === 'INPUT';
      return okVal && valid && !qsa('li[role="option"], .ui-autocomplete-list-item').filter(visible).length;
    };
    if (isSet()) return true;
    var terms = [];
    if (code) terms.push(code); if (name) terms.push(name); if (!terms.length) return false;
    for (var attempt = 0; attempt < 5; attempt++) {
      var term = terms[attempt % terms.length];
      input.focus(); nativeSet(input, ''); await sleep(60);
      typeInto(input, term);
      var opts = await waitFor(function () {
        var l = qsa('li[role="option"], .ui-autocomplete-list-item, .ui-autocomplete-items li, .p-autocomplete-item').filter(visible);
        return l.length ? l : null;
      }, 3500);
      if (opts) {
        var best = null, bs = 0;
        opts.forEach(function (o) { var sc = optionScore(txt(o), want); if (sc > bs) { bs = sc; best = o; } });
        if (best && bs >= 40) {
          mouseClick(best);
          input.dispatchEvent(new Event('blur', { bubbles: true }));
          if (await waitFor(isSet, 2500)) return true;
        } else {
          input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', keyCode: 40, bubbles: true })); await sleep(120);
          input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
          if (await waitFor(isSet, 1500)) return true;
        }
      }
      await sleep(250);
    }
    return false;
  }

  function parseDate(v) {
    var m = /^(\d{1,2})[\/-](\d{1,2})[\/-](\d{4})$/.exec(v || ''); if (m) return { d: +m[1], mo: +m[2] - 1, y: +m[3] };
    m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(v || ''); return m ? { d: +m[3], mo: +m[2] - 1, y: +m[1] } : null;
  }
  async function fillDate(value) {
    var t = parseDate(value), ctl = findField('date');
    var input = ctl && (ctl.tagName === 'INPUT' ? ctl : qs('input', ctl));
    if (!t || !input) return false;
    var f = ('0' + t.d).slice(-2) + '/' + ('0' + (t.mo + 1)).slice(-2) + '/' + t.y;
    if (input.value === f) return true;
    mouseClick(input); input.focus();
    await waitFor(function () { return qs('.ui-datepicker-calendar, .p-datepicker-calendar'); }, 2500);
    for (var i = 0; i < 26; i++) {
      var mEl = qs('.ui-datepicker-month, .p-datepicker-month'), yEl = qs('.ui-datepicker-year, .p-datepicker-year');
      if (!mEl || !yEl) break;
      var mi = MONTHS.indexOf(txt(mEl).toLowerCase()); if (mi < 0) mi = MONTHS.map(function (x) { return x.slice(0, 3); }).indexOf(txt(mEl).toLowerCase().slice(0, 3));
      var diff = (t.y - Number(txt(yEl))) * 12 + t.mo - mi;
      if (!diff) break;
      var arrow = qs(diff > 0 ? '.ui-datepicker-next, .p-datepicker-next' : '.ui-datepicker-prev, .p-datepicker-prev');
      if (!arrow) break; mouseClick(arrow); await sleep(80);
    }
    var cell = qsa('.ui-datepicker-calendar td:not(.ui-datepicker-other-month) a, .p-datepicker-calendar td:not(.p-datepicker-other-month) span').filter(function (a) { return txt(a) === String(t.d) && !/disabled/.test(a.className || ''); })[0];
    if (!cell) return false;
    mouseClick(cell); await sleep(120);
    return !!(await waitFor(function () { return input.value === f; }, 1500));
  }

  // ---- what IRCTC answered: last calls and their status codes, kept for diagnosis
  function netRec(u, code) {
    u = String(u || ''); if (!/irctc|eticketing|\/nget\//i.test(u) && u.charAt(0) !== '/') return;
    RS.net = RS.net || []; RS.net.push(new Date().toISOString().slice(11, 19) + ' ' + code + ' ' + u.replace(/^https?:\/\/[^/]+/, '').slice(0, 80)); if (RS.net.length > 14) RS.net.shift();
  }
  (function hookNet() {
    try {
      if (window.__rsNetHook) return; window.__rsNetHook = true;
      var of = window.fetch;
      if (of) window.fetch = function () { var a = arguments, u = a[0] && (a[0].url || a[0]); return of.apply(this, a).then(function (r) { netRec(u, r.status); return r; }, function (e) { netRec(u, 'ERR'); throw e; }); };
      var ox = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (m, u) { var self = this; this.addEventListener('loadend', function () { netRec(u, self.status); }); return ox.apply(this, arguments); };
    } catch (e) {}
  })();
  function formState() {
    return ['origin', 'destination', 'journeyDate', 'journeyClass', 'journeyQuota'].map(function (n) {
      var c = qs('[formcontrolname="' + n + '"]') || qs('#' + n); if (!c) return n + '=none';
      var i = c.tagName === 'INPUT' ? c : qs('input', c);
      return n + '=' + (/ng-invalid/.test(c.className) ? 'INVALID' : /ng-valid/.test(c.className) ? 'ok' : '?') + ':' + ((i && i.value) || txt(c)).slice(0, 22);
    }).join(' | ');
  }
  function toastText() {
    var a = qsa('p-toast, .ui-toast, .p-toast, [role="alert"], .ui-messages, .ui-toast-message, .alert, .error-message, .ui-state-error').filter(visible).map(function (e) { return txt(e).slice(0, 120); }).filter(Boolean);
    var b = qsa('div, span, p, li').filter(function (e) { return visible(e) && e.children.length === 0 && /\b(invalid|please (select|enter|fill)|unable to|not allowed|try again|exceed|captcha)/i.test(txt(e)) && txt(e).length < 140; }).map(txt);
    return a.concat(b).slice(0, 4).join(' || ');
  }
  function nativeTap(el) {            // trusted touch from the app itself
    try {
      el.scrollIntoView({ block: 'center' });
      var r = el.getBoundingClientRect();
      if (window.RSBridge && RSBridge.tap) { RSBridge.tap(r.left + r.width / 2, r.top + r.height / 2, document.documentElement.clientWidth || window.innerWidth); return true; }
    } catch (e) {}
    return false;
  }
  function mouseClickAt(el) {
    try { el.scrollIntoView({ block: 'center' }); } catch (e) {}
    var r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
    ['pointerover', 'mouseover', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function (t) {
      var ev; var o = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, screenX: x, screenY: y, button: 0, buttons: t.indexOf('up') >= 0 || t === 'click' ? 0 : 1, detail: 1 };
      try { ev = t.indexOf('pointer') === 0 ? new PointerEvent(t, Object.assign({ pointerId: 1, pointerType: 'mouse', isPrimary: true }, o)) : new MouseEvent(t, o); } catch (e) { ev = new MouseEvent(t, o); }
      el.dispatchEvent(ev);
    });
  }

  // ---------------------------------------------------------------- stages
  async function onDialog() {
    var dlg = infoDialog(); if (!dlg) return;
    var btn = dismissBtn(dlg);
    var sig = norm(txt(dlg)).slice(0, 80); st.dlg = st.dlg || {}; var rec = st.dlg[sig] = st.dlg[sig] || { n: 0, t0: Date.now() };
    if (Date.now() - rec.t0 > 60000) { rec.n = 0; rec.t0 = Date.now(); }
    rec.n++;
    if (rec.n >= 8 && /not (available|allowed|running|permitted)|no (seats|trains)|booking (is )?not|invalid|restricted|quota/i.test(txt(dlg))) {
      fail('IRCTC baar baar ye bol raha hai: ' + txt(dlg).slice(0, 110), true); return;      // same refusal again and again: stop instead of spamming IRCTC
    }
    if (btn) { log('dialog: ' + txt(dlg).slice(0, 60)); safeClick(btn, 'dialog ok'); await sleep(300); }
    else {
      st.deadDlg = st.deadDlg || {}; st.deadDlg[sig] = (st.deadDlg[sig] || 0) + 1;
      if (st.deadDlg[sig] === 1 || st.deadDlg[sig] === 3) log('popup without a button: ' + txt(dlg).slice(0, 100) + ' || ' + (dlg.outerHTML || '').replace(/\s+/g, ' ').slice(0, 260));
      try { document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true })); } catch (e) {}
      await sleep(250);
    }
  }
  function snap() {
    var d = dialogs().map(function (x) { return txt(x).slice(0, 70); });
    var bt = qsa('button, a').filter(visible).map(function (x) { return txt(x).slice(0, 18); }).filter(Boolean).slice(0, 14);
    return path() + ' dlg=[' + d.join(' | ') + '] btn=[' + bt.join(', ') + ']';
  }

  async function onError() {
    var dlg = errorDialog(); if (!dlg) return;
    st.errors = (st.errors || 0) + 1;
    log('IRCTC error dialog (' + st.errors + '): ' + txt(dlg).slice(0, 80));
    var btn = buttonByText(dlg, /^(ok|okay|close|retry|try again|continue)$/i) || qs('.ui-dialog-titlebar-close, .p-dialog-header-close', dlg);
    if (btn) safeClick(btn, 'error ok');
    st.bookClickedAt = 0;         // retry the step that failed
    await sleep(350);
  }

  async function onLogin() {
    var L = loginDialog(); if (!L) return;
    var j = RS.job || {};
    if (!secret.user || !secret.pw) {
      if (!st.needLogin) { st.needLogin = true; emit('need_login', {}); log('login details not saved - owner must sign in'); }
      return;
    }
    if (L.user.value !== secret.user) { typeInto(L.user, secret.user); L.user.dispatchEvent(new Event('blur', { bubbles: true })); }
    if (L.pw.value !== secret.pw) { typeInto(L.pw, secret.pw); L.pw.dispatchEvent(new Event('blur', { bubbles: true })); }
    var dtxt = txt(L.root);
    if (/invalid\s*(user|password|credential|login)|incorrect (user|password)|wrong password|user id or password|account (is )?(locked|blocked|disabled)|too many (failed|attempts)/i.test(dtxt) && !/captcha/i.test(dtxt)) {
      fail('IRCTC ne login mana kiya: ' + dtxt.replace(/.*?(invalid|incorrect|wrong|account|too many)/i, '$1').slice(0, 90) + ' (password galat ho sakta hai, galat try nahi karunga taaki account lock na ho)', true); return;
    }
    var cap = captchaField(L.root);
    if (!cap) {
      if (enabled(L.btn) && !st.signClickedAt) { st.signClickedAt = Date.now(); safeClick(L.btn, 'sign in'); log('sign in clicked (no captcha)'); }
      return;
    }
    await captchaFlow(cap, L.btn, 'login');
  }

  // captcha is the owner's: focus it, tell him, and submit as soon as he has typed it
  async function captchaFlow(cap, submitBtn, kind) {
    if (st.capEl !== cap) { st.capEl = cap; st.capNotified = false; st.capSubmitted = ''; st.capSeenAt = 0; }   // a fresh captcha box (dialog reopened / reloaded)
    if (st.capKind !== kind) { st.capKind = kind; st.capSubmitted = ''; st.capNotified = false; }
    if (!st.capNotified) {
      st.capNotified = true; st.capNotifiedAt = Date.now(); cap.focus();
      try { cap.scrollIntoView({ block: 'center' }); } catch (e) {}
      banner('Captcha daalo (' + kind + ')'); emit('captcha', { kind: kind }); log('waiting for owner captcha: ' + kind);
    }
    if (st.capNotified && !(cap.value || '').length && Date.now() - (st.capNotifiedAt || 0) > 20000) { st.capNotifiedAt = Date.now(); cap.focus(); emit('captcha', { kind: kind, again: true }); }
    var v = cap.value || '';
    if (v.length >= (RS.job.captchaMin || 4)) {
      st.capVal = st.capVal === v ? st.capVal : v; st.capSeenAt = st.capSeenAt && st.capVal === v && st.capSeenFor === v ? st.capSeenAt : Date.now(); st.capSeenFor = v;
      if (Date.now() - st.capSeenAt >= (RS.job.captchaIdleMs || 900) && st.capSubmitted !== v && enabled(submitBtn)) {
        st.capSubmitted = v; safeClick(submitBtn, kind + ' submit after captcha'); log('captcha entered, submitted');
        st.capNotified = false;          // wrong captcha -> ask again
      }
    }
  }

  async function onSearch() {
    var j = RS.job;
    if (st.searchClicked && Date.now() - st.searchClicked < 7000) return;      // waiting for results
    if (!findField('origin')) {
      if (Date.now() - (st.noFormAt || 0) > 8000) { st.noFormAt = Date.now(); log('search form not on this page yet (' + path() + ')'); dumpDom('noform'); }
      return;
    }
    ensureLogin();
    if (st.searchTries > 40) { fail('search form could not be filled'); return; }
    st.searchTries = (st.searchTries || 0) + 1;
    await sleep(300);
    var ok1 = await fillStation('origin', j.from, j.fromName);
    var ok2 = await fillStation('destination', j.to, j.toName);
    var ok3 = await fillDate(j.date);
    var okC = await chooseDropdown(findDropdown('class'), j.cls ? (CLASS_LABELS[j.cls] || j.cls) : 'All Classes', j.cls);
    var okQ = await chooseDropdown(findDropdown('quota'), QUOTA_LABELS[j.quota || 'GN'] || j.quota, null);
    if (j.checkOnly && ok1 && ok2 && !ok3 && st.searchTries >= 3) { emit('availability', { text: 'is date ki booking abhi khuli nahi hai (IRCTC calendar me date disabled)', train: j.trainNo, cls: j.cls, date: j.date }); RS.stop(); return; }
    var so = openAt();
    if (!ok3 && so && now() < so + 8000) {          // advance date not selectable yet: sit logged-in on this form until the window opens
      st.searchTries--; var left = so - now();
      if (!st.armedAt) { st.armedAt = Date.now(); emit('armed', { inMs: left }); }
      if (!st.waitLog || Date.now() - st.waitLog > 20000) { st.waitLog = Date.now(); log('date not open yet, opens in ' + Math.round(left / 1000) + 's'); }
      if (!loggedIn() && secret.user) ensureLogin();
      await sleep(left > 15000 ? 1500 : 120); return;
    }
    log('search form: from=' + ok1 + ' to=' + ok2 + ' date=' + ok3 + ' class=' + okC + ' quota=' + okQ);
    if (!(ok1 && ok2 && ok3 && okQ)) { await sleep(500); return; }
    var btn = await waitFor(function () { var b = buttonByText(document, /^(search|search trains|find trains)$/i); return b && enabled(b) ? b : null; }, 4000);
    if (btn) {
      st.searchClicked = Date.now(); var n = st.searchClicks = (st.searchClicks || 0) + 1;
      log('form before search: ' + formState());
      if (n === 1) safeClick(btn, 'search');
      else if (n === 2 && nativeTap(btn)) log('native tap on search');
      else {                                  // IRCTC ignored the first click: pointer events with coordinates, then a real form submit, then Enter
        mouseClickAt(btn); await sleep(250);
        var fm = btn.closest('form'); try { if (fm && fm.requestSubmit) fm.requestSubmit(); } catch (e) {}
        if (n >= 3) { var di = findField('destination'); di = di && (di.tagName === 'INPUT' ? di : qs('input', di)); if (di) { di.focus(); ['keydown', 'keypress', 'keyup'].forEach(function (t) { di.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', keyCode: 13, which: 13, bubbles: true })); }); } }
      }
      log('search clicked #' + n);
      setTimeout(function () { log('after search: path=' + path() + ' | ' + formState() + ' | toast=' + toastText() + ' | net=' + (RS.net || []).slice(-4).join(' ; ')); }, 2500);
    }
  }

  function openAt() { return Number((RS.job && RS.job.openAtMs) || 0); }

  function wants() {      // ordered (train, class) candidates: main first, then the owner's fallbacks
    var j = RS.job, trains = [j.trainNo].concat(j.altTrains || []), classes = [j.cls].concat(j.clsFallback || []), out = [];
    trains.forEach(function (t) { classes.forEach(function (c) { out.push({ train: String(t), cls: c }); }); });
    return out;
  }
  async function onTrainList() {
    var j = RS.job;
    st.searchClicked = 0;
    ensureLogin();
    var W = wants(); st.wi = st.wi || 0; var want = W[Math.min(st.wi, W.length - 1)]; j.trainNo = want.train; j.cls = want.cls;
    var card = await waitFor(function () {
      var title = smallestWithText(document, function (t) { return new RegExp('\\(' + j.trainNo + '\\)').test(t) && t.length < 120; });
      if (!title) return null;
      var n = title;
      while (n && n !== document.body) { if (/book now/i.test(n.innerText || '') && (n.innerText || '').length < 14000) return n; n = n.parentElement; }
      return title.parentElement;
    }, 6000);
    if (!card) {
      st.noTrain = (st.noTrain || 0) + 1;
      log('train ' + j.trainNo + ' not in the list yet (' + st.noTrain + ')');
      if (st.noTrain > 8) fail('train ' + j.trainNo + ' is not in the search results');
      return;
    }
    // armed: everything is selected, the engine only waits for the quota to open
    var o = openAt(), wait = o ? o - now() : 0;
    if (wait > 4000) {
      if (!st.armedAt) { st.armedAt = Date.now(); emit('armed', { inMs: wait }); log('armed, quota opens in ' + Math.round(wait / 1000) + 's'); }
      if (!loggedIn() && secret.user) ensureLogin();
      if (Date.now() - (st.keepAt || st.armedAt) > 75000) {          // IRCTC logs idle sessions out: a harmless availability refresh every ~75 s keeps it alive
        st.keepAt = Date.now();
        var dd = parseDate(j.date), dlab = '';
        if (dd) { var dtt = new Date(dd.y, dd.mo, dd.d); dlab = dtt.toLocaleDateString('en-US', { weekday: 'short' }) + ', ' + ('0' + dd.d).slice(-2) + ' ' + dtt.toLocaleDateString('en-US', { month: 'short' }); }
        var kt = dlab && smallestWithText(card, function (t) { return t.indexOf(dlab) === 0 && t.length < 80; });
        if (kt) { mouseClick(kt); log('session keep-alive refresh'); }
      }
      await sleep(Math.min(wait - 3500, 1000)); return;
    }

    var label = CLASS_LABELS[j.cls] || j.cls;
    var tile = smallestWithText(card, function (t) { return t === label || t === j.cls || t.indexOf('(' + j.cls + ')') >= 0 && t.length < 40; });
    if (!tile) { st.noClass = (st.noClass || 0) + 1; log('class tile ' + label + ' not visible (' + st.noClass + ')'); if (st.noClass > 10) fail(label + ' is not available on train ' + j.trainNo); return; }
    if (!st.tileClicked || Date.now() - st.tileClicked > 4000) { mouseClick(tile); st.tileClicked = Date.now(); await sleep(450); }

    var d = parseDate(j.date), dl = '';
    if (d) { var dt = new Date(d.y, d.mo, d.d); dl = dt.toLocaleDateString('en-US', { weekday: 'short' }) + ', ' + ('0' + d.d).slice(-2) + ' ' + dt.toLocaleDateString('en-US', { month: 'short' }); }
    var dateTile = dl && smallestWithText(card, function (t) { return t.indexOf(dl) === 0 && t.length < 80; });
    if (dateTile && !/selected|active|highlight/.test(dateTile.className || '') && !st.dateClicked) { mouseClick(dateTile); st.dateClicked = Date.now(); await sleep(350); }

    if (j.checkOnly) {        // look only: report what IRCTC shows for this train / class / date and stop; never touches Book Now
      st.checkAt = st.checkAt || Date.now();
      if (Date.now() - Math.max(st.checkAt, st.dateClicked || 0, st.tileClicked || 0) < 900) { await sleep(150); return; }
      var shown = dateTile ? txt(dateTile) : txt(card).replace(/\s+/g, ' ').slice(0, 300);
      emit('availability', { text: shown, train: j.trainNo, cls: j.cls, date: j.date }); log('availability: ' + shown); RS.stop(); return;
    }
    var openNow = !o || now() >= o - 120;
    if (dateTile && openNow && /regret|not available|train departed|charting|no seats|\bwl\b/i.test(txt(dateTile)) && !/\bavl|available\s*-?\d|\bcnf/i.test(txt(dateTile)) && st.wi < W.length - 1) {
      st.wi++; st.tileClicked = 0; st.dateClicked = 0; st.noClass = 0; st.noTrain = 0; var nx = W[st.wi];
      log('sold out (' + want.train + ' ' + want.cls + ') -> trying ' + nx.train + ' ' + nx.cls); return;
    }
    if (dateTile && openNow && /regret|not available/i.test(txt(dateTile)) && st.wi >= W.length - 1) { st.soldOutN = (st.soldOutN || 0) + 1; if (st.soldOutN > 40) fail('sab options sold out (REGRET)'); }
    var book = buttonByText(card, /^book now$/i);
    if (!book || !enabled(book)) { await sleep(120); return; }
    if (o && !st.refreshed && now() >= o - 400 && dateTile) { st.refreshed = true; mouseClick(dateTile); await sleep(60); }      // re-pull availability right at the opening
    if (o && now() < o - 120) { await sleep(25); return; }      // everything selected; fire at the exact opening moment
    // quota open: hammer Book Now until the page moves on (IRCTC drops the first clicks under load)
    if (!st.bookClickedAt || Date.now() - st.bookClickedAt > ((st.bookClicks || 0) < 6 ? 250 + Math.random() * 150 : 600 + Math.random() * 500)) {
      st.bookClickedAt = Date.now(); st.bookClicks = (st.bookClicks || 0) + 1;
      safeClick(book, 'book now #' + st.bookClicks);
      if (st.bookClicks === 1) log('Book Now clicked'); if (st.bookClicks > 60) fail('Book Now did not go through');
    }
  }

  async function onPassengers() {
    var j = RS.job, ps = j.passengers || [];
    if (st.paxDone) return;
    await waitFor(function () { return qsa('app-passenger').length || qs('input[formcontrolname="passengerName"]'); }, 12000);
    var rows = function () { return qsa('app-passenger').filter(visible).length ? qsa('app-passenger').filter(visible) : qsa('input[formcontrolname="passengerName"]').filter(visible).map(function (i) { return i.closest('div'); }); };
    // add rows
    for (var guard = 0; rows().length < ps.length && guard < ps.length + 3; guard++) {
      var add = smallestWithText(document, function (t) { return /^\+?\s*add passenger/i.test(t) && t.length < 60; });
      if (!add) break;
      var before = rows().length; mouseClick(add.closest('button, a, [role="button"]') || add);
      await waitFor(function () { return rows().length > before; }, 3000);
    }
    var R = rows();
    for (var i = 0; i < ps.length && i < R.length; i++) {
      var p = ps[i], row = R[i];
      var nameIn = qs('p-autocomplete input', row) || qs('input[formcontrolname="passengerName"]', row) || qs('input[placeholder*="name" i]', row);
      if (nameIn && nameIn.value !== p.name) {
        typeInto(nameIn, p.name);
        var pick = await waitFor(function () {      // master-list suggestion with the exact name fills age/gender itself
          return qsa('li[role="option"], .ui-autocomplete-list-item').filter(visible).filter(function (o) { return norm(txt(o)) === norm(p.name); })[0];
        }, 900);
        if (pick) { mouseClick(pick); await sleep(250); }
        nameIn.dispatchEvent(new Event('blur', { bubbles: true }));
      }
      var age = qs('input[formcontrolname="passengerAge"]', row) || qs('input[placeholder="Age" i]', row);
      if (age && String(age.value) !== String(p.age)) { typeInto(age, p.age); age.dispatchEvent(new Event('blur', { bubbles: true })); }
      var g = qs('select[formcontrolname="passengerGender"]', row);
      if (g && p.gender && g.value !== p.gender) nativeSet(g, p.gender);
      var b = qs('select[formcontrolname="passengerBerthChoice"]', row);
      if (b && p.berth !== undefined && b.value !== berthCode(p.berth)) nativeSet(b, berthCode(p.berth));
      var nat = qs('select[formcontrolname="passengerNationality"]', row);
      if (nat && !nat.value) nativeSet(nat, 'IN');
      var f = qs('select[formcontrolname="passengerFoodChoice"]', row);
      if (f && p.food) nativeSet(f, p.food);
      await sleep(120);
    }
    var mob = qs('input[formcontrolname="mobileNumber"]') || qs('input[name="mobileNumber"]');
    if (mob && j.mobile && mob.value !== j.mobile) { typeInto(mob, j.mobile); mob.dispatchEvent(new Event('blur', { bubbles: true })); }
    function setCheck(sel, want) {
      var c = qs(sel); if (!c) return; var box = c.tagName === 'INPUT' ? c : qs('input[type="checkbox"]', c);
      var host = c.closest('p-checkbox') || c; var on = box ? box.checked : false;
      if (want !== undefined && on !== !!want) mouseClick(qs('.ui-chkbox-box, .p-checkbox-box', host) || box || host);
    }
    setCheck('p-checkbox[formcontrolname="autoUpgradationSelected"], input[formcontrolname="autoUpgradationSelected"]', j.autoUpgrade !== false);
    setCheck('p-checkbox[formcontrolname="bookOnlyIfCnf"], input[formcontrolname="bookOnlyIfCnf"]', !!j.confirmOnly);
    // payment mode radio on this page
    var payTxt = j.payMode === 'upi' ? /upi/i : /(cards|wallet|net\s*banking)/i;
    var radio = smallestWithText(document, function (t, e) { return /pay through/i.test(t) && payTxt.test(t) && t.length < 120 && !!(e.closest('label, p-radiobutton, .ui-radiobutton, div')); });
    if (radio && !st.payPicked) { mouseClick(radio); st.payPicked = true; await sleep(150); }
    noInsurance();
    var ok = ps.length && R.length >= ps.length && ps.every(function (p, i) { var n = qs('p-autocomplete input, input[formcontrolname="passengerName"]', R[i]); return n && n.value === p.name; });
    log('passengers filled: ' + ok + ' (' + ps.length + ')');
    if (!ok) { st.paxTries = (st.paxTries || 0) + 1; if (st.paxTries > 12) fail('passenger form could not be filled'); return; }
    var go = qs('button.train_Search.btnDefault[type="submit"]') || buttonByText(document, /^(continue|next)$/i);
    if (go && enabled(go)) { st.paxDone = true; await sleep(250); safeClick(go, 'continue (passengers)'); log('passengers submitted'); }
  }

  async function onReview() {
    var cap = captchaField(document);
    var btn = buttonByText(document, /^(continue|proceed|book now)$/i);
    if (cap) { await captchaFlow(cap, btn, 'review'); return; }
    if (btn && enabled(btn) && !st.reviewClicked) { st.reviewClicked = true; safeClick(btn, 'continue (review)'); log('review continued'); }
  }

  async function onPayment() {
    var j = RS.job;
    if (payProblem()) return;
    if (j.payMode === 'manual') { if (!st.payNotified) { st.payNotified = true; emit('payment_ready', { mode: 'manual' }); banner('Payment tum karo'); } return; }
    if (j.payMode === 'ewallet' && !st.walletPicked) {
      var opt = qsa('#pay-type .bank-type, .bank-type').filter(function (o) { return /e-?wallet|instant payment/i.test(txt(o)); })[0];
      if (!opt) { st.walletMiss = (st.walletMiss || 0) + 1; if (st.walletMiss > 20) fail('eWallet option not found on payment page'); return; }
      mouseClick(opt); st.walletPicked = true; await sleep(350); log('eWallet selected');
    }
    var pay = qs('button.btn.btn-primary.hidden-xs') || buttonByText(document, /pay\s*(&|and)\s*book/i);
    if (pay && enabled(pay) && !st.payClicked) { st.payClicked = true; await sleep(300); safeClick(pay, 'pay & book'); log('Pay & Book clicked'); if (j.payMode === 'upi') emit('payment_ready', { mode: 'upi' }); }
  }

  function payProblem() {
    var t = txt(document.body);
    if (/insufficient|low (wallet )?balance|not enough balance/i.test(t) && !st.balNote) { st.balNote = true; RS.job.payMode = 'manual'; emit('payment_ready', { mode: 'manual', reason: 'balance' }); banner('Wallet me balance kam hai, payment tum karo'); log('wallet balance low -> manual payment'); return true; }
    if (/transaction (has )?failed|payment (has )?failed|payment unsuccessful/i.test(t) && !st.payFailNote) { st.payFailNote = true; emit('error', { reason: 'payment fail dikha. Dobara pay mat karna jab tak paisa kata ya nahi check na ho (booking history dekho)', msg: 'payment fail dikha. Paisa kata ho to booking history check karo, dobara pay mat karo', fatal: false }); return true; }
    return false;
  }
  async function onWallet() {
    var j = RS.job;
    if (payProblem()) return;
    if (j.payMode === 'ewallet' && j.autoWalletConfirm) {
      var c = buttonByText(document, /^confirm$/i);
      if (c && enabled(c) && !st.walletConfirmed) { st.walletConfirmed = true; await sleep(400); safeClick(c, 'wallet confirm'); log('wallet CONFIRM clicked'); }
    } else if (!st.walletNotified) { st.walletNotified = true; emit('payment_ready', { mode: 'wallet' }); banner('Wallet CONFIRM dabao'); }
  }

  function onConfirmed() {
    if (st.doneSent) return; st.doneSent = true;
    var m = /\b(\d{10})\b/.exec(txt(document.body)); emit('confirmed', { pnr: m ? m[1] : '' });
    log('BOOKED, PNR ' + (m ? m[1] : '?')); RS.stop();
  }

  function fail(why, fatal) { if (st.failed) return; st.failed = true; log('FAILED: ' + why); emit('error', { reason: why, msg: why, fatal: !!fatal }); if (fatal) RS.stop(); }

  function banner(text) {
    try {
      var b = document.getElementById('__rs_banner');
      if (!b) { b = document.createElement('div'); b.id = '__rs_banner';
        b.style.cssText = 'position:fixed;left:8px;right:8px;top:8px;z-index:2147483647;background:#c4001d;color:#fff;font:600 16px sans-serif;padding:10px 14px;border-radius:10px;text-align:center;box-shadow:0 4px 18px rgba(0,0,0,.4)';
        document.body.appendChild(b); }
      b.textContent = '🚆 Redshot: ' + text; clearTimeout(b.__t); b.__t = setTimeout(function () { try { b.remove(); } catch (e) {} }, 6000);
    } catch (e) {}
  }

  // ---------------------------------------------------------------- main loop
  async function tick() {
    if (busy || stopped || !RS.running) return; busy = true;
    try {
      var s = stage();
      if (/access denied|you don't have permission to access|reference #\d+\./i.test((document.body && document.body.innerText || '').slice(0, 400))) s = 'blocked';
      var RANK = { search: 1, trainlist: 2, passengers: 3, review: 4, payment: 5, wallet: 6 };
      if (s === 'login' && RS.stage !== 'login') st.loginFromRank = RANK[RS.stage] || st.loginFromRank || 0;
      if (RS.stage === 'login' && s !== 'login' && (st.loginFromRank || 0) >= 3) {
        log('re-login in the middle of booking: redoing steps');
        st = { errors: st.errors, payClicked: st.payClicked, walletConfirmed: st.walletConfirmed, doneSent: st.doneSent, regress: st.regress || 0 };
      }
      if (RANK[s] && RANK[RS.stage] && RANK[s] < RANK[RS.stage]) {
        log('went back ' + RS.stage + ' -> ' + s + ' (session/page reset): redoing steps');
        st = { errors: st.errors, payClicked: st.payClicked, walletConfirmed: st.walletConfirmed, doneSent: st.doneSent, regress: (st.regress || 0) + 1 };
        if (st.regress > 12) fail('page baar baar peeche ja raha hai', true);
      }
      if (s === 'other' || s === 'blocked') {
        st.otherSince = st.otherSince || Date.now();
        if (Date.now() - st.otherSince > (s === 'blocked' ? 2500 : 25000) && Date.now() - (st.stuckAt || 0) > 8000) { st.stuckAt = Date.now(); emit(s === 'blocked' ? 'blocked' : 'stuck', { url: location.href }); log(s + ' page for a while'); }
      } else st.otherSince = 0;
      if (s !== RS.stage) { var tn = Date.now(); log('stage -> ' + s + (RS.stageAt ? ' (' + (tn - RS.stageAt) + 'ms in ' + RS.stage + ')' : '')); RS.stageAt = tn; RS.stage = s; emit('stage', { stage: s, url: location.pathname }); }
      if ((s === 'dialog' || s === 'search' || s === 'other') && dismissLanguage()) { await sleep(350); busy = false; return; }
      if (s !== st.dumpStage && RS.stageAt && Date.now() - RS.stageAt > 2500) { st.dumpStage = s; dumpDom('stage ' + s); }
      if (RS.stageAt && Date.now() - RS.stageAt > 12000 && Date.now() - (st.diagAt || 0) > 12000 && !st.armedAt) { st.diagAt = Date.now(); log('where: ' + snap()); if ((RS.dumps || 0) < 14 && Date.now() - (st.dumpAt || 0) > 40000) { st.dumpAt = Date.now(); dumpDom('stuck'); } }
      if (s === 'confirmed') onConfirmed();
      else if (s === 'error') await onError();
      else if (s === 'dialog') await onDialog();
      else if (s === 'login') await onLogin();
      else if (s === 'search') await onSearch();
      else if (s === 'trainlist') await onTrainList();
      else if (s === 'passengers') await onPassengers();
      else if (s === 'review') await onReview();
      else if (s === 'payment') await onPayment();
      else if (s === 'wallet') await onWallet();
    } catch (e) { log('engine error: ' + (e && e.message)); }
    busy = false;
  }

  RS.start = function (job) {
    try { var mv = document.querySelector('meta[name=viewport]'); if (!mv) { mv = document.createElement('meta'); mv.setAttribute('name', 'viewport'); document.head.appendChild(mv); } mv.setAttribute('content', 'width=1366'); } catch (e) {}      // desktop layout on a phone screen
    RS.stop(); stopped = false; st = {}; secret = { user: job.username || '', pw: job.password || '' }; delete job.username; delete job.password; RS.job = job; RS.running = true; RS.events = [];
    timer = setInterval(tick, 120); emit('started', { trainNo: job.trainNo, date: job.date }); log('engine started');
    return 'started';
  };
  RS.stop = function () { stopped = true; RS.running = false; if (timer) { clearInterval(timer); timer = null; } return 'stopped'; };
  RS.status = function () { return JSON.stringify({ running: RS.running, stage: RS.stage, last: RS.events.slice(-6).map(function (e) { return e.data.msg || e.type; }) }); };
  RS._stage = stage;
})();
