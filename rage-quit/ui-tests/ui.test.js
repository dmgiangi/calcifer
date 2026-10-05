'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const {createApp, buildTimeInput, romeOffsetsFor} = require('../src/main/resources/static/app.js');

const STATIC_DIR = path.join(__dirname, '../src/main/resources/static');
const SESSION = {
  alias: 'Dem', startDate: '2026-06-29', today: '2026-07-01',
  csrfToken: 'synthetic-csrf-token', csrfHeader: 'X-CSRF-TEST'
};

function day(date, report, penalties, resistancePoints, cumulativePenalties, cumulativeResistancePoints, overrides) {
  return {
    date, penalties, resistancePoints, smokingReport: report, inProgress: false,
    recordedCumulativePenalties: penalties || 0, cumulativePenalties,
    cumulativeResistancePoints, cumulativeMissingPastDays: report === 'MISSING' ? 1 : 0,
    cumulativeProvisional: report === 'MISSING', ...(overrides || {})
  };
}

const DAYS = [
  day('2026-06-29', 'MISSING', null, 1, null, 1, {cumulativeMissingPastDays: 1}),
  day('2026-06-30', 'ZERO_CONFIRMED', 0, 0, 0, 1, {cumulativeMissingPastDays: 1, cumulativeProvisional: true}),
  day('2026-07-01', 'SMOKED', 2, 2, 2, 3, {inProgress: true, cumulativeMissingPastDays: 1, cumulativeProvisional: true})
];

function member(alias, rank, penalties, resistancePoints, hasSmokingReport, missingPastDays, resistancePercentage, days) {
  return {alias, rank, penalties, resistancePoints, hasSmokingReport, missingPastDays,
    provisional: true, resistancePercentage, days};
}

const INSIGHTS = {
  startDate: SESSION.startDate,
  today: SESSION.today,
  timeZone: 'Europe/Rome',
  selfReported: true,
  group: [
    member('Dem', 1, 2, 3, true, 1, 75, DAYS),
    member('Pugliens', 1, 2, 0, true, 1, null, [
      day('2026-06-29', 'SMOKED', 1, 0, 1, 0, {cumulativeProvisional: false}),
      day('2026-06-30', 'MISSING', null, 0, 1, 0, {cumulativeMissingPastDays: 1, cumulativeProvisional: true}),
      day('2026-07-01', 'SMOKED', 1, 0, 2, 0, {inProgress: true, cumulativeMissingPastDays: 1, cumulativeProvisional: true})
    ]),
    member('Frevadiscor', null, 0, 1, false, 2, 100, [
      day('2026-06-29', 'MISSING', null, 1, null, 1, {cumulativeMissingPastDays: 1}),
      day('2026-06-30', 'MISSING', null, 0, null, 1, {cumulativeMissingPastDays: 2}),
      day('2026-07-01', 'MISSING', null, 0, null, 1, {inProgress: true, cumulativeMissingPastDays: 2})
    ])
  ],
  personal: {
    alias: 'Dem', sinceLastSeconds: 3600, meanCompletedGapSeconds: 86400,
    maxCompletedGapSeconds: 172800,
    completedGaps: [{completedAt: '2026-06-30T10:00:00Z', seconds: 86400}]
  }
};

const HISTORY = {
  events: [{
    id: 'event-1', type: 'SMOKED', effectiveAt: '2026-07-01T10:00:00Z',
    createdAt: '2026-07-01T10:00:00Z', updatedAt: '2026-07-01T10:00:00Z', version: 1
  }],
  zeroDeclarations: [{date: '2026-06-30', confirmedAt: '2026-06-30T18:00:00Z', inProgress: false}]
};

class FakeElement {
  constructor(documentRef, tagName, id) {
    this.ownerDocument = documentRef;
    this.tagName = String(tagName).toUpperCase();
    this.id = id || '';
    this.nodeType = 1;
    this.children = [];
    this.listeners = new Map();
    this.attributes = new Map();
    this._text = '';
    this.value = '';
    this.hidden = false;
    this.disabled = false;
    this.required = false;
    this.className = '';
  }

  get textContent() {
    return this._text + this.children.map(child => child.textContent || '').join('');
  }

  set textContent(value) {
    this._text = value === null || value === undefined ? '' : String(value);
    this.children = [];
  }

  append(...nodes) { this.children.push(...nodes); }
  appendChild(node) { this.children.push(node); return node; }
  replaceChildren(...nodes) { this._text = ''; this.children = [...nodes]; }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.has(name) ? this.attributes.get(name) : null; }
  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }
  focus() { this.focused = true; }

  async dispatch(type) {
    const event = {type, target: this, currentTarget: this, defaultPrevented: false,
      preventDefault() { this.defaultPrevented = true; }};
    const results = (this.listeners.get(type) || []).map(listener => listener(event));
    await Promise.all(results);
    return event;
  }
}

class FakeDocument {
  constructor(html) {
    this.elements = [];
    this.byId = new Map();
    const tagPattern = /<([a-z][a-z0-9-]*)\b[^>]*\bid="([^"]+)"/gi;
    for (const match of html.matchAll(tagPattern)) this.register(new FakeElement(this, match[1], match[2]));
  }

  register(node) {
    this.elements.push(node);
    if (node.id) this.byId.set(node.id, node);
    return node;
  }
  getElementById(id) { return this.byId.get(id) || null; }
  createElement(tagName) { return this.register(new FakeElement(this, tagName)); }
  createElementNS(_namespace, tagName) { return this.createElement(tagName); }
  querySelectorAll(selector) {
    return selector === 'button' ? this.elements.filter(node => node.tagName === 'BUTTON') : [];
  }
}

function response(status, body) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() {
      if (body === undefined) throw new Error('No JSON body');
      return JSON.parse(JSON.stringify(body));
    }
  };
}

function harness(options) {
  const settings = options || {};
  const html = fs.readFileSync(path.join(STATIC_DIR, 'index.html'), 'utf8');
  const document = new FakeDocument(html);
  const calls = [];
  const keyValues = settings.keys || ['uuid-1', 'uuid-2', 'uuid-3'];
  let keyIndex = 0;
  const window = {confirm: () => true, location: {redirectedTo: null, replace(url) { this.redirectedTo = url; }}};
  const fetch = async (url, request) => {
    calls.push({url, request});
    if (settings.handleRequest) {
      const custom = await settings.handleRequest(url, request, calls);
      if (custom !== undefined) return custom;
    }
    if (url === '/api/session') return response(200, {...SESSION, today: settings.today || SESSION.today});
    if (url === '/api/events' && request.method === 'GET') return response(200, HISTORY);
    if (url === '/api/insights') return response(200, INSIGHTS);
    if (url === '/api/events' && request.method === 'POST') return response(200, {
      id: 'created-event', type: JSON.parse(request.body).type,
      effectiveAt: '2026-07-01T10:00:00Z', createdAt: '2026-07-01T10:00:00Z',
      updatedAt: '2026-07-01T10:00:00Z', version: 1
    });
    if (request.method === 'PUT' || request.method === 'DELETE' || request.method === 'PATCH' || url === '/logout') {
      return response(204);
    }
    throw new Error(`Unexpected fixture request: ${request.method} ${url}`);
  };
  const app = createApp({document, fetch, window, crypto: {randomUUID: () => keyValues[keyIndex++] || `uuid-${keyIndex}`}});
  return {app, document, window, calls, start: () => app.start()};
}

test('packaged pages use local assets and Italian, responsive accessible markup', () => {
  const index = fs.readFileSync(path.join(STATIC_DIR, 'index.html'), 'utf8');
  const login = fs.readFileSync(path.join(STATIC_DIR, 'login.html'), 'utf8');
  const css = fs.readFileSync(path.join(STATIC_DIR, 'style.css'), 'utf8');
  const app = fs.readFileSync(path.join(STATIC_DIR, 'app.js'), 'utf8');
  assert.match(index, /<html lang="it">/);
  assert.match(login, /<html lang="it">/);
  assert.match(index, /href="\/style\.css"/);
  assert.match(index, /src="\/app\.js"/);
  assert.match(css, /@media \(min-width: 720px\)/);
  assert.match(css, /@media \(min-width: 1080px\)/);
  assert.match(css, /:focus-visible/);
  assert.match(css, /prefers-reduced-motion/);
  assert.doesNotMatch(app, /localStorage|sessionStorage/);
});

test('now action sends the exact event DTO with the session-provided CSRF header', async () => {
  const ui = harness();
  await ui.start();
  await ui.document.getElementById('smoke-now').dispatch('click');

  const create = ui.calls.find(call => call.url === '/api/events' && call.request.method === 'POST');
  assert.ok(create);
  assert.equal(create.request.credentials, 'same-origin');
  assert.equal(create.request.headers['X-CSRF-TEST'], SESSION.csrfToken);
  assert.deepEqual(JSON.parse(create.request.body), {
    type: 'SMOKED', idempotencyKey: 'uuid-1', time: {now: true}
  });
  assert.equal(Object.hasOwn(JSON.parse(create.request.body), 'csrfToken'), false);
  assert.equal(ui.document.getElementById('status-message').textContent.includes('Episodio registrato'), true);
});

test('uncertain create retries reuse the identical UUID and payload; intentional events get a new UUID', async () => {
  const payloads = [];
  let attempts = 0;
  const ui = harness({handleRequest(url, request) {
    if (url === '/api/events' && request.method === 'POST') {
      payloads.push(request.body);
      attempts++;
      if (attempts === 1) throw new TypeError('synthetic connection drop');
      return response(200, {id: `created-${attempts}`, type: JSON.parse(request.body).type,
        effectiveAt: '2026-07-01T10:00:00Z', createdAt: '2026-07-01T10:00:00Z', updatedAt: '2026-07-01T10:00:00Z', version: 1});
    }
    return undefined;
  }});
  await ui.start();
  await ui.document.getElementById('resist-now').dispatch('click');
  assert.equal(ui.document.getElementById('retry-event').hidden, false);
  assert.match(ui.document.getElementById('status-message').textContent, /Riprova/);

  await ui.document.getElementById('retry-event').dispatch('click');
  assert.equal(payloads[0], payloads[1]);
  assert.equal(JSON.parse(payloads[0]).idempotencyKey, 'uuid-1');

  await ui.document.getElementById('resist-now').dispatch('click');
  assert.notEqual(JSON.parse(payloads[1]).idempotencyKey, JSON.parse(payloads[2]).idempotencyKey);
  assert.equal(JSON.parse(payloads[2]).idempotencyKey, 'uuid-2');
});

test('overlapping clicks cannot submit concurrent creates', async () => {
  let release;
  let creates = 0;
  const pending = new Promise(resolve => { release = resolve; });
  const ui = harness({handleRequest(url, request) {
    if (url === '/api/events' && request.method === 'POST') {
      creates++;
      return pending;
    }
    return undefined;
  }});
  await ui.start();
  const button = ui.document.getElementById('smoke-now');
  const first = button.dispatch('click');
  await Promise.resolve();
  await button.dispatch('click');
  assert.equal(creates, 1);
  assert.equal(button.disabled, true);
  release(response(200, {id: 'one', type: 'SMOKED', effectiveAt: '2026-07-01T10:00:00Z',
    createdAt: '2026-07-01T10:00:00Z', updatedAt: '2026-07-01T10:00:00Z', version: 1}));
  await first;
  assert.equal(creates, 1);
});

test('Rome local times require a valid explicit offset across daylight-saving changes', async () => {
  assert.deepEqual(romeOffsetsFor('2026-10-25', '02:30').map(choice => choice.offset), ['+02:00', '+01:00']);
  assert.deepEqual(romeOffsetsFor('2026-03-29', '02:30'), []);
  assert.throws(() => buildTimeInput('2026-03-29', '02:30', '+01:00'), /non esiste/);
  assert.throws(() => buildTimeInput('2026-10-25', '02:30', ''), /Seleziona un offset/);

  const posted = [];
  const ui = harness({today: '2026-10-26', handleRequest(url, request) {
    if (url === '/api/events' && request.method === 'POST') {
      posted.push(JSON.parse(request.body));
      return response(200, {id: 'backdated', type: 'RESISTED', effectiveAt: '2026-10-25T01:30:00Z',
        createdAt: '2026-10-26T10:00:00Z', updatedAt: '2026-10-26T10:00:00Z', version: 1});
    }
    return undefined;
  }});
  await ui.start();
  ui.document.getElementById('event-type').value = 'RESISTED';
  ui.document.getElementById('backdate-date').value = '2026-10-25';
  ui.document.getElementById('backdate-time').value = '02:30';
  await ui.document.getElementById('backdate-date').dispatch('input');
  await ui.document.getElementById('backdate-time').dispatch('input');
  ui.document.getElementById('backdate-offset').value = '+01:00';
  await ui.document.getElementById('backdate-form').dispatch('submit');
  assert.deepEqual(posted[0].time, {localDateTime: '2026-10-25T02:30:00', offset: '+01:00'});
  assert.equal(posted[0].type, 'RESISTED');
});

test('dedicated backdate buttons override the selected type and preserve the Rome wall-time DTO', async () => {
  const posted = [];
  const ui = harness({today: '2026-10-26', handleRequest(url, request) {
    if (url === '/api/events' && request.method === 'POST') {
      const body = JSON.parse(request.body);
      posted.push(body);
      return response(200, {id: `backdated-${posted.length}`, type: body.type,
        effectiveAt: '2026-10-25T00:30:00Z', createdAt: '2026-10-26T10:00:00Z',
        updatedAt: '2026-10-26T10:00:00Z', version: 1});
    }
    return undefined;
  }});
  await ui.start();
  ui.document.getElementById('event-type').value = 'RESISTED';
  ui.document.getElementById('backdate-date').value = '2026-10-25';
  ui.document.getElementById('backdate-time').value = '02:30';
  await ui.document.getElementById('backdate-date').dispatch('input');
  await ui.document.getElementById('backdate-time').dispatch('input');
  ui.document.getElementById('backdate-offset').value = '+02:00';

  await ui.document.getElementById('backdate-smoke').dispatch('click');
  ui.document.getElementById('event-type').value = 'SMOKED';
  await ui.document.getElementById('backdate-resist').dispatch('click');

  assert.deepEqual(posted.map(({type, time}) => ({type, time})), [
    {type: 'SMOKED', time: {localDateTime: '2026-10-25T02:30:00', offset: '+02:00'}},
    {type: 'RESISTED', time: {localDateTime: '2026-10-25T02:30:00', offset: '+02:00'}}
  ]);
});

test('versioned history edits send the exact PATCH DTO and surface a 409 conflict', async () => {
  const patchRequests = [];
  const ui = harness({handleRequest(url, request) {
    if (url.startsWith('/api/events/event-1') && request.method === 'PATCH') {
      patchRequests.push({url, request});
      return response(409, {code: 'conflict'});
    }
    return undefined;
  }});
  await ui.start();
  const editButton = ui.document.querySelectorAll('button').find(button => button.textContent === 'Modifica');
  assert.ok(editButton);
  await editButton.dispatch('click');
  await ui.document.getElementById('event-edit-form').dispatch('submit');

  assert.equal(patchRequests.length, 1);
  assert.equal(patchRequests[0].url, '/api/events/event-1');
  assert.deepEqual(JSON.parse(patchRequests[0].request.body), {
    version: 1, type: 'SMOKED', time: {localDateTime: '2026-07-01T12:00:00', offset: '+02:00'}
  });
  assert.equal(patchRequests[0].request.headers['X-CSRF-TEST'], SESSION.csrfToken);
  assert.match(ui.document.getElementById('error-message').textContent, /Conflitto/);
});

test('zero confirmation, withdrawal, deletion and logout use CSRF headers and bodyless contracts', async () => {
  const ui = harness();
  await ui.start();
  const zeroDate = ui.document.getElementById('zero-date');
  zeroDate.value = '2026-06-29';
  await zeroDate.dispatch('change');
  assert.match(ui.document.getElementById('zero-status').textContent, /resistenze non significano zero/);
  await ui.document.getElementById('zero-form').dispatch('submit');
  const confirm = ui.calls.find(call => call.url === '/api/zero/2026-06-29' && call.request.method === 'PUT');
  assert.ok(confirm);
  assert.equal(Object.hasOwn(confirm.request, 'body'), false);
  assert.equal(confirm.request.headers['X-CSRF-TEST'], SESSION.csrfToken);

  zeroDate.value = '2026-06-30';
  await zeroDate.dispatch('change');
  await ui.document.getElementById('zero-withdraw').dispatch('click');
  const withdraw = ui.calls.find(call => call.url === '/api/zero/2026-06-30' && call.request.method === 'DELETE');
  assert.ok(withdraw);
  assert.equal(Object.hasOwn(withdraw.request, 'body'), false);
  assert.equal(withdraw.request.headers['X-CSRF-TEST'], SESSION.csrfToken);

  const deleteButton = ui.document.querySelectorAll('button').find(button => button.textContent === 'Elimina');
  assert.ok(deleteButton);
  await deleteButton.dispatch('click');
  const remove = ui.calls.find(call => call.url === '/api/events/event-1?version=1' && call.request.method === 'DELETE');
  assert.ok(remove);
  assert.equal(Object.hasOwn(remove.request, 'body'), false);
  assert.equal(remove.request.headers['X-CSRF-TEST'], SESSION.csrfToken);

  await ui.document.getElementById('logout-button').dispatch('click');
  const logout = ui.calls.find(call => call.url === '/logout');
  assert.equal(logout.request.method, 'POST');
  assert.equal(logout.request.headers['X-CSRF-TEST'], SESSION.csrfToken);
  assert.equal(ui.window.location.redirectedTo, '/login.html');
});

test('SVG and text tables distinguish missing from zero and preserve tied ranks and null values', async () => {
  const ui = harness();
  await ui.start();
  const dailyChart = ui.document.getElementById('personal-daily-chart').children[0];
  assert.equal(dailyChart.getAttribute('role'), 'img');
  assert.match(dailyChart.getAttribute('aria-labelledby'), /daily-bars-title/);
  assert.match(dailyChart.textContent, /\?/);
  assert.match(dailyChart.textContent, /0/);

  const dailyTable = ui.document.getElementById('personal-daily-table').textContent;
  assert.match(dailyTable, /Mancante · non è zero/);
  assert.match(dailyTable, /Zero sigarette confermato/);
  assert.match(dailyTable, /in corso/);

  const ranks = ui.document.getElementById('leaderboard-table').textContent;
  assert.equal((ranks.match(/Rango 1 \(condiviso\)/g) || []).length, 2);
  assert.match(ranks, /Senza rango/);
  assert.match(ranks, /75% degli episodi registrati/);
  assert.match(ranks, /Non disponibile · nessun episodio registrato/);
  assert.match(ui.document.getElementById('group-daily-table').textContent, /Resoconto fumo mancante/);
  assert.match(ui.document.getElementById('group-daily-table').textContent, /Zero sigarette confermato/);

  const smokingSvg = ui.document.getElementById('smoke-cumulative-chart').children[0];
  const resistanceSvg = ui.document.getElementById('resistance-cumulative-chart').children[0];
  assert.equal(smokingSvg.getAttribute('role'), 'img');
  assert.equal(resistanceSvg.getAttribute('role'), 'img');
  assert.match(ui.document.querySelectorAll('button').map(button => button.textContent).join(' '), /Modifica/);
  const dashboardHtml = fs.readFileSync(path.join(STATIC_DIR, 'index.html'), 'utf8');
  const dashboardMarkup = /<main id="dashboard"[^>]*>([\s\S]*?)<\/main>/.exec(dashboardHtml);
  assert.ok(dashboardMarkup);
  assert.match(dashboardMarkup[1], /Dati auto-riportati/);
});
