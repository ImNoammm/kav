// Fills the National Public Transport Authority's form (govforms.gov.il, PublicTransportRequest@mot.gov.il) with a
// report Kav put together. Kav runs it in the form's own page as (function)(report). It never presses Send: the
// rider does, after checking the form. How far it got is left in window.__kav for Kav to read.
(function (d) {
  if (window.__kav) return;
  var k = window.__kav = { state: 'filling', step: 0, missed: [] };
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  function until(f, ms) {
    return new Promise(function (done) {
      var t = 0;
      (function poll() {
        var v = null;
        try { v = f(); } catch (e) { v = null; }
        if (v) return done(v);
        if ((t += 200) >= ms) return done(null);
        setTimeout(poll, 200);
      })();
    });
  }
  // The form's lists write Hebrew with stray spaces and dashes of every kind; compared without them.
  function plain(s) { return String(s || '').replace(/[\s\p{Pd}"'׳״.,()\/]/gu, ''); }
  function holder(id) { return document.querySelector('[data-testid="' + id + '"]'); }
  function field(id) {
    var e = holder(id);
    if (!e) return null;
    return e.matches('input,textarea') ? e : e.querySelector('input,textarea');
  }
  function shown(e) { return !!e && e.getBoundingClientRect().width > 0; }
  function key(el, name, code) {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: name, code: name, keyCode: code, which: code, bubbles: true }));
  }
  // The form keeps a typed value when its field loses focus. A page nobody has touched yet gets no focus events from
  // the browser, so they're sent here as well.
  function focusEvent(el, name) {
    el.dispatchEvent(new FocusEvent(name, { bubbles: name === 'focusin' || name === 'focusout' }));
  }
  function setValue(el, v) {
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }
  async function type(id, v) {
    if (v === undefined || v === null || v === '') return true;
    var el = await until(function () { var f = field(id); return shown(f) ? f : null; }, 4000);
    if (!el) { k.missed.push(id); return false; }
    el.focus();
    focusEvent(el, 'focus');
    focusEvent(el, 'focusin');
    await sleep(50);
    setValue(el, v);
    el.dispatchEvent(new Event('change', { bubbles: true }));
    await sleep(50);
    el.blur();
    focusEvent(el, 'blur');
    focusEvent(el, 'focusout');
    await sleep(150);
    return true;
  }
  async function tick(id, value) {
    var sel = '[data-testid="' + id + '"]' + (value ? '[value="' + value + '"]' : '');
    var el = await until(function () { var e = document.querySelector(sel); return shown(e) ? e : null; }, 4000);
    if (!el) { k.missed.push(id); return false; }
    if (!el.checked) el.click();
    await sleep(200);
    return true;
  }
  function options(el) {
    var list = document.getElementById(el.getAttribute('aria-controls') || '');
    var o = list ? list.querySelectorAll('[role=option]') : [];
    return Array.prototype.map.call(o, function (x) { return x.getAttribute('aria-label') || ''; });
  }
  // Picks a list entry with the keyboard, the way the form's own lists take a choice: arrows, then Enter.
  async function choose(id, match, filter) {
    var el = await until(function () { var f = field(id); return shown(f) ? f : null; }, 6000);
    if (!el) return false;
    el.focus();
    focusEvent(el, 'focus');
    focusEvent(el, 'focusin');
    await sleep(60);
    setValue(el, filter || '');
    await sleep(300);
    key(el, 'ArrowDown', 40);
    var labels = await until(function () { var l = options(el); return l.length ? l : null; }, 8000);
    var want = labels ? labels.filter(match)[0] : undefined;
    if (want === undefined) {
      key(el, 'Escape', 27);
      el.blur();
      return false;
    }
    for (var i = 0; i < labels.length + 2; i++) {
      var act = document.getElementById(el.getAttribute('aria-activedescendant') || '');
      if (act && act.getAttribute('aria-label') === want) break;
      key(el, 'ArrowDown', 40);
      await sleep(50);
    }
    key(el, 'Enter', 13);
    await sleep(400);
    el.blur();
    focusEvent(el, 'blur');
    focusEvent(el, 'focusout');
    await sleep(200);
    return true;
  }
  function same(text) { return function (l) { return plain(l) === plain(text); }; }

  // The entries behind a list's labels, with the ministry's own numbers, as the form's React components hold them.
  function entries(el) {
    var list = document.getElementById(el.getAttribute('aria-controls') || '');
    var first = list && list.querySelector('[role=option]');
    if (!first) return [];
    var key = Object.keys(first).filter(function (n) {
      return n.indexOf('__reactFiber') === 0 || n.indexOf('__reactInternalInstance') === 0;
    })[0];
    for (var f = key && first[key], i = 0; f && i < 8; f = f.return, i++) {
      var o = f.memoizedProps && f.memoizedProps.options;
      if (o && o.length) return Array.prototype.slice.call(o);
    }
    return [];
  }
  // The form's entries for a line number read "operator number from-city to-city". Kav's server knows the ministry's
  // operator and direction too, which settle it.
  function lineScore(label, entry) {
    if (entry && d.operatorId && String(entry.operatorId) !== d.operatorId) return 0;
    var p = plain(label), op = plain(d.operator), score = 0;
    if (entry && d.operatorId) score += 4;
    if (entry && d.direction && String(entry.direction) === d.direction) {
      score += 3;
      if (d.alternative && String(entry.lineAlternative) === d.alternative) score += 1;
    }
    if (op && p.indexOf(op) === 0) { score += 4; p = p.slice(op.length); }
    var n = String(d.line);
    if (p.indexOf(n) === 0) p = p.slice(n.length);
    var from = plain(d.from), to = plain(d.to);
    if (to && p.slice(-to.length) === to) score += 2; else if (to && p.indexOf(to) >= 0) score += 1;
    if (from && p.indexOf(from) === 0) score += 2; else if (from && p.indexOf(from) >= 0) score += 1;
    return score;
  }
  // The form lists a line's stops as "name - code": by the code on the stop's sign, else by its name.
  function isStop(l) {
    if (d.stop) return l.slice(-(' - ' + d.stop).length) === ' - ' + d.stop;
    return plain(l.replace(/ - \d+$/, '')) === plain(d.stopName);
  }
  async function stopListed() {
    var el = await until(function () { var f = field('StationEvent'); return shown(f) ? f : null; }, 3000);
    if (!el || !(d.stop || d.stopName)) return false;
    el.focus();
    key(el, 'ArrowDown', 40);
    var labels = await until(function () { var l = options(el); return l.length ? l : null; }, 6000);
    key(el, 'Escape', 27);
    el.blur();
    await sleep(150);
    return !!labels && labels.some(isStop);
  }
  async function pickLine() {
    if (!(await type('LineNumber1', d.line))) return;
    var el = await until(function () { var f = field('LinesList1'); return shown(f) ? f : null; }, 4000);
    if (!el) { k.missed.push('LinesList1'); return; }
    el.focus();
    key(el, 'ArrowDown', 40);
    var labels = await until(function () { var l = options(el); return l.length ? l : null; }, 10000);
    var known = labels ? entries(el) : [];
    key(el, 'Escape', 27);
    el.blur();
    if (!labels) { k.missed.push('LinesList1'); return; }
    var ranked = labels.map(function (l) {
      return { l: l, s: lineScore(l, known.filter(function (e) { return e.dataText === l; })[0]) };
    })
      .filter(function (x) { return x.s >= 2; })
      .sort(function (a, b) { return b.s - a.s; });
    var station = (d.stop || d.stopName) && shown(field('StationEvent'));
    // Two directions alike (a line inside one town) are told apart only by their stops; without a stop to check, the
    // rider picks.
    if (!ranked.length || (!station && ranked.length > 1 && ranked[1].s === ranked[0].s)) { k.missed.push('LinesList1'); return; }
    // When it happened at a stop, the right direction is the one that calls there.
    for (var i = 0; i < ranked.length; i++) {
      if (i > 0 && ranked[i].s < ranked[0].s - 2) break;
      if (!(await choose('LinesList1', same(ranked[i].l)))) continue;
      if (!station) return;
      await sleep(800);
      if (await stopListed()) {
        if (!(await choose('StationEvent', isStop))) {
          k.missed.push('StationEvent');
        }
        return;
      }
    }
    if (station) k.missed.push('StationEvent');
  }

  // The rider's photos: Kav serves each at an address on the form's own site that only this script knows. The form
  // takes up to four, one per row of its attachments, and uploads each as soon as it's given one.
  function attachmentRows() { return document.querySelectorAll('[data-testid^="item-repeatedFields-Attachments-"]'); }
  async function photoInput(i) {
    for (var tries = 0; attachmentRows().length <= i && tries < 3; tries++) {
      var add = holder('repeatedFields-addButton-Attachments');
      if (!add) return null;
      add.click();
      await sleep(600);
    }
    var row = attachmentRows()[i];
    return row ? row.querySelector('input[type=file]') : null;
  }
  async function attachPhotos() {
    var list = d.photos || [];
    for (var i = 0; i < list.length && i < 4; i++) {
      var input = await photoInput(i);
      if (!input) { k.missed.push('Attachments'); return; }
      try {
        var res = await fetch(list[i]);
        if (!res.ok) throw new Error('photo ' + res.status);
        var files = new DataTransfer();
        files.items.add(new File([await res.blob()], 'photo-' + (i + 1) + '.jpg', { type: 'image/jpeg' }));
        input.files = files.files;
        input.dispatchEvent(new Event('change', { bubbles: true }));
      } catch (e) { k.missed.push('Attachments'); return; }
      // The row gets its view and delete buttons once the upload is through; one that fails empties the row again.
      var named = false;
      var upload = await until(function () {
        var row = attachmentRows()[i];
        if (!row) return 'failed';
        if (row.querySelector('[data-testid^="viewFile_"], [data-testid^="deleteFile_"]')) return 'done';
        if (row.querySelector('[data-testid="fileName"]')) named = true;
        else if (named) return 'failed';
        return null;
      }, 120000);
      if (upload !== 'done') { k.missed.push('Attachments'); return; }
    }
  }

  // The form's phone fields look their input up again on every render; one left over from the first step does so after
  // its input has left the page, and the error takes the second step's phone field down with it. A field that isn't on
  // the page gets a stand-in that does nothing instead.
  function steadyPhones() {
    var g = window.intlTelInputGlobals;
    if (!g || g.kavSteady) return;
    var noop = function () {};
    var idle = {
      telInput: { parentElement: { classList: { add: noop, remove: noop } } },
      selectedFlag: { setAttribute: noop, removeAttribute: noop },
      setNumber: noop, isValidNumber: function () { return false; }, getNumber: function () { return ''; },
    };
    var find = g.getInstance;
    g.getInstance = function (el) { return el ? find.call(g, el) : idle; };
    g.kavSteady = true;
  }

  async function run() {
    steadyPhones();
    var start = await until(function () { return document.querySelector('[data-testid="RequestedOption"][value="1"]'); }, 20000);
    if (!start) { k.state = 'error'; return; }
    start.click();
    await sleep(500);
    var ids = ['TransportMeans', 'RequestSubjectTran', 'RequestComplaintAppealTran'], texts = ['אוטובוס', d.subject, d.second];
    for (var i = 0; i < ids.length; i++) {
      if (!(await choose(ids[i], same(texts[i])))) { k.missed.push(ids[i]); k.state = 'partial'; return; }
    }
    var enter = holder('enter-service-button');
    if (enter) enter.click();
    if (!(await until(function () { return holder('nextButton'); }, 15000))) { k.state = 'partial'; return; }
    k.step = 1;
    await sleep(800);
    if (d.location) await tick('EventLocation', d.location);
    await type('EventDate', d.date);
    await type('EventHour2', d.time);
    await pickLine();
    if (d.plate && shown(field('BusNumber'))) await type('BusNumber', d.plate);
    if (d.demand) await type('RequisitionNumber', d.demand);
    if (d.validation) await type('ValidationHour2', d.validation);
    if (d.paymentMeans) {
      await tick('PaymentMeans', d.paymentMeans);
      await sleep(600);
      if (d.ravKav) await type('RavKavNO', d.ravKav);
      if (d.paymentApp) await tick('PaymentApplication', d.paymentApp);
      if (d.appPhone) await type('PaymentApplicationPhoneNumber', d.appPhone);
      if (d.cardholder) await type('CardholderName', d.cardholder);
    }
    if (d.conduct) {
      if (d.willing) { await tick('MOTTestimonyConfirmation'); await tick('CourtTestimonyConfirmation'); }
      if (d.recorded) await tick('EventDocumentation', d.recorded);
      if (d.witnesses) await tick('EventWitness', d.witnesses);
      if (d.testifyAt) await tick('WitnessLocation', d.testifyAt);
    }
    await type('ReferenceDetails', d.details);
    await attachPhotos();
    await sleep(300);
    holder('nextButton').click();
    if (!(await until(function () { return shown(field('ClientID')) ? 1 : null; }, 6000))) {
      // Still on the first step: the form marks what it wants. Once the rider has put it right and gone on, the second
      // step is still filled in.
      Array.prototype.forEach.call(document.querySelectorAll('[aria-invalid="true"]'), function (e) {
        var l = e.id && document.querySelector('label[for="' + e.id + '"]');
        k.missed.push(l ? l.innerText.replace(/\s*\*\s*$/, '').trim() : e.id);
      });
      k.state = 'partial';
      if (!(await until(function () { return shown(field('ClientID')) ? 1 : null; }, 30 * 60 * 1000))) return;
      k.missed = [];
      k.state = 'filling';
    }
    k.step = 2;
    await sleep(500);
    await type('ClientID', d.id);
    await type('ClientLastName', d.last);
    await type('ClientName', d.first);
    if (d.gender) await tick('ClientGender', d.gender);
    await type('ClientPhone', d.phone);
    await type('ClientEmail', d.email);
    await type('ClientValidationEmail', d.email);
    if (d.city) {
      var city = plain(d.city);
      // "תל אביב" for "תל אביב - יפו": a name that starts only one town in the list is that town.
      var found = await choose('ClientCity', function (l) { return plain(l) === city; }, d.city) ||
        await choose('ClientCity', function (l, i, all) {
          return plain(l).indexOf(city) === 0 && all.filter(function (m) { return plain(m).indexOf(city) === 0; }).length === 1;
        }, d.city);
      if (!found) k.missed.push('ClientCity');
    }
    if (d.consent) await tick('ClientContact2');
    window.scrollTo(0, 0);
    k.state = k.missed.length ? 'partial' : 'ready';
  }

  run().catch(function (e) { k.state = 'error'; k.error = String(e); });
})
