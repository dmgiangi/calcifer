(function () {
  'use strict';

  const ROME = 'Europe/Rome';
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const ROME_PARTS = new Intl.DateTimeFormat('en-GB', {
    timeZone: ROME, calendar: 'iso8601', numberingSystem: 'latn',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
  });
  const IT_DATE = new Intl.DateTimeFormat('it-IT', {timeZone: ROME, dateStyle: 'medium'});
  const IT_DATE_TIME = new Intl.DateTimeFormat('it-IT', {
    timeZone: ROME, dateStyle: 'medium', timeStyle: 'short'
  });
  const IT_NUMBER = new Intl.NumberFormat('it-IT', {maximumFractionDigits: 1});
  const SERIES_COLORS = ['#8b321d', '#145b78', '#55447f'];
  const SERIES_DASHES = ['none', '8 4', '2 4'];

  class ApiError extends Error {
    constructor(status, code) {
      super(code || `http_${status}`);
      this.name = 'ApiError';
      this.status = status;
      this.code = code || 'unknown_error';
    }
  }

  class NetworkError extends Error {
    constructor() {
      super('network_error');
      this.name = 'NetworkError';
    }
  }

  class ValidationError extends Error {
    constructor(message) {
      super(message);
      this.name = 'ValidationError';
    }
  }

  class ApiClient {
    constructor(fetchImpl) {
      if (typeof fetchImpl !== 'function') throw new Error('Fetch non disponibile');
      this.fetchImpl = fetchImpl;
      this.csrfToken = null;
      this.csrfHeader = null;
    }

    setSession(session) {
      if (!session || typeof session.csrfToken !== 'string' || typeof session.csrfHeader !== 'string') {
        throw new ApiError(500, 'invalid_session_response');
      }
      this.csrfToken = session.csrfToken;
      this.csrfHeader = session.csrfHeader;
    }

    async request(path, options) {
      const settings = options || {};
      const method = settings.method || 'GET';
      const headers = {Accept: 'application/json'};
      const request = {
        method,
        credentials: 'same-origin',
        cache: 'no-store',
        headers
      };
      if (settings.body !== undefined) {
        headers['Content-Type'] = 'application/json';
        request.body = JSON.stringify(settings.body);
      }
      if (settings.csrf) {
        if (!this.csrfHeader || !this.csrfToken) throw new ApiError(403, 'csrf_unavailable');
        headers[this.csrfHeader] = this.csrfToken;
      }

      let response;
      try {
        response = await this.fetchImpl(path, request);
      } catch (_) {
        throw new NetworkError();
      }
      if (!response.ok) {
        let payload = null;
        try { payload = await response.json(); } catch (_) { /* The status is still authoritative. */ }
        throw new ApiError(response.status, payload && payload.code);
      }
      if (response.status === 204) return null;
      try {
        return await response.json();
      } catch (_) {
        throw new NetworkError();
      }
    }
  }

  function parseDate(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
    const [year, month, day] = value.split('-').map(Number);
    const date = new Date(Date.UTC(year, month - 1, day));
    if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
    return {year, month, day};
  }

  function parseClock(value) {
    if (typeof value !== 'string') return null;
    const match = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
    if (!match) return null;
    const hour = Number(match[1]);
    const minute = Number(match[2]);
    const second = match[3] === undefined ? 0 : Number(match[3]);
    if (hour > 23 || minute > 59 || second > 59) return null;
    return {hour, minute, second, value: `${match[1]}:${match[2]}:${String(second).padStart(2, '0')}`};
  }

  function isValidDate(value) {
    return parseDate(value) !== null;
  }

  function isDateInRange(value, startDate, today) {
    return isValidDate(value) && value >= startDate && value <= today;
  }

  function romePartsAt(epochMilliseconds) {
    const parts = Object.create(null);
    for (const part of ROME_PARTS.formatToParts(new Date(epochMilliseconds))) {
      if (part.type !== 'literal') parts[part.type] = Number(part.value);
    }
    return {
      year: parts.year, month: parts.month, day: parts.day,
      hour: parts.hour, minute: parts.minute, second: parts.second
    };
  }

  function sameWallTime(left, right) {
    return left.year === right.year && left.month === right.month && left.day === right.day
      && left.hour === right.hour && left.minute === right.minute && left.second === right.second;
  }

  function offsetAt(epochMilliseconds) {
    const wholeSecond = Math.floor(epochMilliseconds / 1000) * 1000;
    const parts = romePartsAt(wholeSecond);
    const wallAsUtc = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second);
    return (wallAsUtc - wholeSecond) / 60000;
  }

  function formatOffset(minutes) {
    const sign = minutes >= 0 ? '+' : '-';
    const absolute = Math.abs(minutes);
    return `${sign}${String(Math.floor(absolute / 60)).padStart(2, '0')}:${String(absolute % 60).padStart(2, '0')}`;
  }

  function romeOffsetsFor(dateValue, timeValue) {
    const date = parseDate(dateValue);
    const clock = parseClock(timeValue);
    if (!date || !clock) return [];

    const wall = {
      year: date.year, month: date.month, day: date.day,
      hour: clock.hour, minute: clock.minute, second: clock.second
    };
    const wallAsUtc = Date.UTC(wall.year, wall.month - 1, wall.day, wall.hour, wall.minute, wall.second);
    const samples = [-36, -24, -12, 0, 12, 24, 36];
    const possibleOffsets = new Set(samples.map(hours => offsetAt(wallAsUtc + hours * 3600000)));
    const matches = [];
    for (const offset of possibleOffsets) {
      const instant = wallAsUtc - offset * 60000;
      if (sameWallTime(romePartsAt(instant), wall)) matches.push({offset: formatOffset(offset), instant});
    }
    return matches.sort((left, right) => left.instant - right.instant);
  }

  function buildTimeInput(date, time, offset) {
    const parsedDate = parseDate(date);
    const clock = parseClock(time);
    if (!parsedDate || !clock) throw new ValidationError('Inserisci una data e un’ora valide nel fuso Europe/Rome.');
    const choices = romeOffsetsFor(date, time);
    if (choices.length === 0) {
      throw new ValidationError('Quell’ora locale non esiste nel fuso Europe/Rome per il cambio dell’ora legale.');
    }
    if (!choices.some(choice => choice.offset === offset)) {
      throw new ValidationError('Seleziona un offset Europe/Rome valido per questa data e ora.');
    }
    return {localDateTime: `${date}T${clock.value}`, offset};
  }

  function partsForInstant(instant) {
    const milliseconds = Date.parse(instant);
    if (!Number.isFinite(milliseconds)) return null;
    const parts = romePartsAt(milliseconds);
    return {
      date: `${parts.year}-${String(parts.month).padStart(2, '0')}-${String(parts.day).padStart(2, '0')}`,
      time: `${String(parts.hour).padStart(2, '0')}:${String(parts.minute).padStart(2, '0')}:${String(parts.second).padStart(2, '0')}`,
      offset: formatOffset(offsetAt(milliseconds))
    };
  }

  function formatDate(value) {
    const parts = parseDate(value);
    if (!parts) return 'Data non disponibile';
    return IT_DATE.format(new Date(Date.UTC(parts.year, parts.month - 1, parts.day, 12)));
  }

  function formatInstant(value) {
    const milliseconds = Date.parse(value);
    return Number.isFinite(milliseconds) ? IT_DATE_TIME.format(new Date(milliseconds)) : 'Orario non disponibile';
  }

  function formatDuration(seconds) {
    if (seconds === null || seconds === undefined || !Number.isFinite(Number(seconds))) return 'Non disponibile';
    let remaining = Math.max(0, Math.floor(Number(seconds)));
    const days = Math.floor(remaining / 86400);
    remaining %= 86400;
    const hours = Math.floor(remaining / 3600);
    remaining %= 3600;
    const minutes = Math.floor(remaining / 60);
    const secs = remaining % 60;
    const parts = [];
    if (days) parts.push(`${days} g`);
    if (hours) parts.push(`${hours} h`);
    if (minutes) parts.push(`${minutes} min`);
    if (secs || parts.length === 0) parts.push(`${secs} s`);
    return parts.join(' ');
  }

  function smokingReportLabel(report) {
    if (report === 'MISSING') return 'Resoconto fumo mancante';
    if (report === 'SMOKED') return 'Sigarette registrate';
    if (report === 'ZERO_CONFIRMED') return 'Zero sigarette confermato';
    return 'Resoconto non disponibile';
  }

  function eventTypeLabel(type) {
    return type === 'SMOKED' ? 'Sigaretta fumata' : type === 'RESISTED' ? 'Resistenza' : 'Tipo non disponibile';
  }

  function element(documentRef, tag, className, text) {
    const node = documentRef.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function svgElement(documentRef, tag, attributes, text) {
    const node = documentRef.createElementNS(SVG_NS, tag);
    for (const [name, value] of Object.entries(attributes || {})) node.setAttribute(name, String(value));
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function makeSvg(documentRef, id, titleText, descriptionText, width, height) {
    const svg = documentRef.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('class', 'chart-svg');
    svg.setAttribute('role', 'img');
    svg.setAttribute('focusable', 'false');
    svg.setAttribute('width', width);
    svg.setAttribute('height', height);
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.setAttribute('aria-labelledby', `${id}-title ${id}-description`);
    svg.appendChild(svgElement(documentRef, 'title', {id: `${id}-title`}, titleText));
    svg.appendChild(svgElement(documentRef, 'desc', {id: `${id}-description`}, descriptionText));
    return svg;
  }

  function showEmpty(documentRef, container, message) {
    container.replaceChildren(element(documentRef, 'p', 'empty-state', message));
  }

  function makeTable(documentRef, captionText, headers, rows) {
    const table = element(documentRef, 'table');
    const caption = element(documentRef, 'caption', null, captionText);
    const head = element(documentRef, 'thead');
    const headRow = element(documentRef, 'tr');
    for (const label of headers) {
      const cell = element(documentRef, 'th', null, label);
      cell.setAttribute('scope', 'col');
      headRow.appendChild(cell);
    }
    head.appendChild(headRow);
    const body = element(documentRef, 'tbody');
    for (const row of rows) {
      const tableRow = element(documentRef, 'tr');
      for (const value of row) {
        const cell = element(documentRef, 'td');
        if (value && typeof value === 'object' && typeof value.nodeType === 'number') cell.appendChild(value);
        else cell.textContent = value === null || value === undefined ? '—' : String(value);
        tableRow.appendChild(cell);
      }
      body.appendChild(tableRow);
    }
    table.append(caption, head, body);
    return table;
  }

  function setOptionHint(documentRef, select, hint, date, time, preferredOffset) {
    const oldValue = preferredOffset || (select && select.value) || '';
    if (!select || !hint) return [];
    select.replaceChildren();
    const choices = romeOffsetsFor(date, time);
    const placeholder = element(documentRef, 'option', null,
      choices.length > 1 ? 'Scegli l’offset per l’ora ripetuta…' : choices.length === 0 ? 'Nessun offset valido' : 'Offset Europe/Rome');
    placeholder.value = '';
    placeholder.disabled = true;
    select.appendChild(placeholder);

    for (const choice of choices) {
      const label = choice.offset === '+02:00' ? 'UTC+02:00 · ora legale' :
        choice.offset === '+01:00' ? 'UTC+01:00 · ora solare' : `UTC${choice.offset}`;
      const option = element(documentRef, 'option', null, label);
      option.value = choice.offset;
      select.appendChild(option);
    }
    const selected = choices.find(choice => choice.offset === oldValue)
      || (choices.length === 1 ? choices[0] : null);
    select.value = selected ? selected.offset : '';

    if (!date || !time) hint.textContent = 'Scegli una data e un’ora per calcolare l’offset Europe/Rome.';
    else if (choices.length === 0) hint.textContent = 'Ora inesistente a Roma per il cambio dell’ora legale; scegli un altro orario.';
    else if (choices.length > 1) hint.textContent = 'Quest’ora si ripete a Roma: scegli esplicitamente UTC+02:00 oppure UTC+01:00.';
    else hint.textContent = `Offset valido per Europe/Rome: UTC${choices[0].offset}.`;
    return choices;
  }

  function secureUuid(cryptoObject) {
    if (cryptoObject && typeof cryptoObject.randomUUID === 'function') return cryptoObject.randomUUID();
    if (cryptoObject && typeof cryptoObject.getRandomValues === 'function') {
      const bytes = cryptoObject.getRandomValues(new Uint8Array(16));
      bytes[6] = (bytes[6] & 0x0f) | 0x40;
      bytes[8] = (bytes[8] & 0x3f) | 0x80;
      const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
      return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    }
    throw new ValidationError('Il browser non può creare un identificativo sicuro per l’invio. Aggiorna il browser e riprova.');
  }

  function createApp(options) {
    const settings = options || {};
    const documentRef = settings.document || (typeof document !== 'undefined' ? document : null);
    const windowRef = settings.window || (typeof window !== 'undefined' ? window : null);
    const fetchImpl = settings.fetch || (typeof fetch === 'function' ? fetch.bind(globalThis) : null);
    const cryptoObject = settings.crypto || (typeof crypto !== 'undefined' ? crypto : null);
    if (!documentRef) throw new Error('Documento HTML non disponibile');

    const api = new ApiClient(fetchImpl);
    const state = {
      session: null,
      history: {events: [], zeroDeclarations: []},
      insights: null,
      pendingCreate: null,
      editEvent: null,
      mutating: false,
      inputsInitialized: false,
      zeroCanConfirm: false,
      zeroCanWithdraw: false
    };
    const get = id => documentRef.getElementById(id);

    function setStatus(message) {
      const status = get('status-message');
      const error = get('error-message');
      if (status) { status.textContent = message || ''; status.hidden = !message; }
      if (error) { error.textContent = ''; error.hidden = true; }
    }

    function setError(message) {
      const status = get('status-message');
      const error = get('error-message');
      if (status) { status.textContent = ''; status.hidden = true; }
      if (error) { error.textContent = message; error.hidden = !message; }
    }

    function setMutationButtons() {
      const buttons = documentRef.querySelectorAll ? documentRef.querySelectorAll('button') : [];
      for (const button of buttons) button.disabled = state.mutating;
      const createIds = ['smoke-now', 'resist-now', 'backdate-submit', 'backdate-smoke', 'backdate-resist'];
      for (const id of createIds) {
        const button = get(id);
        if (button) button.disabled = state.mutating || state.pendingCreate !== null;
      }
      const retry = get('retry-event');
      if (retry) {
        retry.hidden = state.pendingCreate === null;
        retry.disabled = state.mutating || state.pendingCreate === null;
      }
      const confirm = get('zero-confirm');
      const withdraw = get('zero-withdraw');
      if (confirm) confirm.disabled = state.mutating || !state.zeroCanConfirm;
      if (withdraw) withdraw.disabled = state.mutating || !state.zeroCanWithdraw;
    }

    async function runMutation(work) {
      if (state.mutating) return false;
      state.mutating = true;
      setMutationButtons();
      try {
        await work();
        return true;
      } finally {
        state.mutating = false;
        setMutationButtons();
      }
    }

    function ownMember() {
      if (!state.insights || !state.session) return null;
      return state.insights.group.find(member => member.alias === state.session.alias) || null;
    }

    function initDateInputs() {
      if (!state.session) return;
      for (const id of ['backdate-date', 'zero-date', 'edit-date']) {
        const input = get(id);
        if (!input) continue;
        input.min = state.session.startDate;
        input.max = state.session.today;
      }
      if (!state.inputsInitialized) {
        const backdate = get('backdate-date');
        const zero = get('zero-date');
        if (backdate && !backdate.value) backdate.value = state.session.today;
        if (zero && !zero.value) zero.value = state.session.today;
        state.inputsInitialized = true;
      }
      updateOffsetSelect('backdate');
      renderZeroStatus();
    }

    function updateOffsetSelect(prefix, preferredOffset) {
      const isEdit = prefix === 'edit';
      const date = get(isEdit ? 'edit-date' : 'backdate-date');
      const time = get(isEdit ? 'edit-time' : 'backdate-time');
      const select = get(isEdit ? 'edit-offset' : 'backdate-offset');
      const hint = get(isEdit ? 'edit-offset-help' : 'backdate-offset-help');
      if (date && time && select && hint) {
        setOptionHint(documentRef, select, hint, date.value, time.value, preferredOffset);
      }
    }

    function selectedTime(prefix) {
      const isEdit = prefix === 'edit';
      const date = get(isEdit ? 'edit-date' : 'backdate-date');
      const time = get(isEdit ? 'edit-time' : 'backdate-time');
      const offset = get(isEdit ? 'edit-offset' : 'backdate-offset');
      if (!date || !time || !offset) throw new ValidationError('Inserisci data, ora e offset Europe/Rome.');
      if (!isDateInRange(date.value, state.session.startDate, state.session.today)) {
        throw new ValidationError('La data deve essere compresa tra l’inizio del periodo condiviso e oggi (Europe/Rome).');
      }
      return buildTimeInput(date.value, time.value, offset.value);
    }

    async function refreshData() {
      const session = await api.request('/api/session');
      api.setSession(session);
      const [history, insights] = await Promise.all([
        api.request('/api/events'),
        api.request('/api/insights')
      ]);
      state.session = session;
      state.history = history;
      state.insights = insights;
      const dashboard = get('dashboard');
      if (dashboard) dashboard.hidden = false;
      const alias = get('session-alias');
      if (alias) alias.textContent = `Ciao, ${session.alias}`;
      const period = get('period-summary');
      if (period) period.textContent = `Periodo condiviso: ${formatDate(session.startDate)} – ${formatDate(session.today)}. Fuso orario: ${ROME}.`;
      initDateInputs();
      renderAll();
    }

    async function refreshAfterSuccess(message) {
      try {
        await refreshData();
        setStatus(message);
      } catch (_) {
        setStatus(`${message} Non è stato possibile aggiornare tutti i dati: ricarica la pagina per vedere il riepilogo aggiornato.`);
      }
    }

    async function refreshAfterConflict() {
      try { await refreshData(); } catch (_) { /* Keep the conflict explanation visible. */ }
    }

    async function reportMutationError(error, context) {
      if (error instanceof ApiError && error.status === 409) {
        if (context === 'edit') closeEdit();
        await refreshAfterConflict();
        setError('Conflitto: il record o il resoconto è cambiato. Ho aggiornato i dati; controlla la cronologia e riprova.');
      } else if (error instanceof ApiError && error.status === 400) {
        setError('Dati non validi. Verifica il periodo consentito, l’ora locale e l’offset Europe/Rome selezionato.');
      } else if (error instanceof ApiError && error.status === 401) {
        const prompt = get('login-prompt');
        if (prompt) prompt.hidden = false;
        setError('Sessione scaduta o non valida. Accedi di nuovo per continuare.');
      } else if (error instanceof ApiError && error.status === 403) {
        setError('Richiesta rifiutata dalla protezione della sessione. Aggiorna la pagina e riprova.');
      } else if (error instanceof ApiError && error.status === 404) {
        if (context === 'edit') closeEdit();
        await refreshAfterConflict();
        setError('Il record non è più disponibile. Ho aggiornato i dati della cronologia.');
      } else if (error instanceof NetworkError) {
        setError('Esito non confermato per un problema di connessione. Controlla la cronologia prima di ripetere l’azione.');
      } else if (error instanceof ValidationError) {
        setError(error.message);
      } else {
        setError('Non è stato possibile completare l’azione. Riprova tra poco.');
      }
    }

    function isUncertain(error) {
      return error instanceof NetworkError || (error instanceof ApiError && error.status >= 500);
    }

    async function sendPendingCreate() {
      if (!state.pendingCreate) return;
      await runMutation(async () => {
        try {
          await api.request('/api/events', {method: 'POST', body: state.pendingCreate, csrf: true});
        } catch (error) {
          if (isUncertain(error)) {
            setStatus('Risposta non confermata. La richiesta originale è conservata in memoria: usa “Riprova” per reinviarla senza creare un duplicato.');
            return;
          }
          state.pendingCreate = null;
          setMutationButtons();
          await reportMutationError(error, 'create');
          return;
        }
        state.pendingCreate = null;
        setMutationButtons();
        await refreshAfterSuccess('Episodio registrato. Penalità e punti resistenza restano separati.');
      });
    }

    async function beginCreate(type, time) {
      if (state.pendingCreate || state.mutating) return;
      if (type !== 'SMOKED' && type !== 'RESISTED') throw new ValidationError('Tipo di episodio non valido.');
      state.pendingCreate = {type, idempotencyKey: secureUuid(cryptoObject), time};
      setMutationButtons();
      await sendPendingCreate();
    }

    async function createNow(type) {
      return beginCreate(type, {now: true});
    }

    async function createBackdated(typeOverride) {
      const type = typeOverride || (get('event-type') && get('event-type').value);
      const time = selectedTime('backdate');
      return beginCreate(type, time);
    }

    async function confirmZero() {
      const dateInput = get('zero-date');
      const date = dateInput && dateInput.value;
      if (!isDateInRange(date, state.session.startDate, state.session.today)) {
        throw new ValidationError('Scegli una data tra l’inizio del periodo condiviso e oggi (Europe/Rome).');
      }
      await runMutation(async () => {
        try {
          await api.request(`/api/zero/${encodeURIComponent(date)}`, {method: 'PUT', csrf: true});
        } catch (error) {
          await reportMutationError(error, 'zero');
          return;
        }
        await refreshAfterSuccess(date === state.session.today
          ? 'Zero sigarette confermato per oggi. Il resoconto resta in corso fino alla fine della giornata.'
          : 'Zero sigarette confermato per la giornata selezionata.');
      });
    }

    async function withdrawZero() {
      const dateInput = get('zero-date');
      const date = dateInput && dateInput.value;
      if (!isDateInRange(date, state.session.startDate, state.session.today)) {
        throw new ValidationError('Scegli una data valida nel periodo condiviso.');
      }
      await runMutation(async () => {
        try {
          await api.request(`/api/zero/${encodeURIComponent(date)}`, {method: 'DELETE', csrf: true});
        } catch (error) {
          await reportMutationError(error, 'zero');
          return;
        }
        await refreshAfterSuccess('Conferma ritirata. Le resistenze registrate per la giornata restano invariate.');
      });
    }

    function openEdit(event) {
      state.editEvent = event;
      const fields = partsForInstant(event.effectiveAt);
      if (!fields) {
        setError('Non è possibile interpretare l’orario di questo episodio.');
        return;
      }
      const panel = get('event-edit-panel');
      const type = get('edit-type');
      const date = get('edit-date');
      const time = get('edit-time');
      const version = get('edit-event-version');
      if (type) type.value = event.type;
      if (date) {
        date.min = state.session.startDate;
        date.max = state.session.today;
        date.value = fields.date;
      }
      if (time) time.value = fields.time;
      if (version) version.textContent = `Versione ${event.version}. Data e ora sono mostrate nel fuso Europe/Rome.`;
      updateOffsetSelect('edit', fields.offset);
      if (panel) panel.hidden = false;
      if (type && typeof type.focus === 'function') type.focus();
      setStatus(`Modifica dell’episodio del ${formatInstant(event.effectiveAt)}.`);
    }

    function closeEdit() {
      state.editEvent = null;
      const panel = get('event-edit-panel');
      if (panel) panel.hidden = true;
    }

    async function saveEdit() {
      if (!state.editEvent) return;
      const event = state.editEvent;
      const type = get('edit-type') && get('edit-type').value;
      if (type !== 'SMOKED' && type !== 'RESISTED') throw new ValidationError('Tipo di episodio non valido.');
      const body = {version: event.version, type, time: selectedTime('edit')};
      await runMutation(async () => {
        try {
          await api.request(`/api/events/${encodeURIComponent(event.id)}`, {method: 'PATCH', body, csrf: true});
        } catch (error) {
          await reportMutationError(error, 'edit');
          return;
        }
        closeEdit();
        await refreshAfterSuccess('Correzione salvata. I riepiloghi sono stati aggiornati.');
      });
    }

    async function deleteEvent(event) {
      if (state.mutating) return;
      if (windowRef && typeof windowRef.confirm === 'function'
          && !windowRef.confirm(`Eliminare l’episodio “${eventTypeLabel(event.type)}” del ${formatInstant(event.effectiveAt)}?`)) return;
      await runMutation(async () => {
        try {
          await api.request(`/api/events/${encodeURIComponent(event.id)}?version=${encodeURIComponent(event.version)}`, {
            method: 'DELETE', csrf: true
          });
        } catch (error) {
          await reportMutationError(error, 'delete');
          return;
        }
        if (state.editEvent && state.editEvent.id === event.id) closeEdit();
        await refreshAfterSuccess('Episodio eliminato. Le conferme di zero non vengono create automaticamente.');
      });
    }

    async function logout() {
      await runMutation(async () => {
        try {
          await api.request('/logout', {method: 'POST', csrf: true});
        } catch (error) {
          await reportMutationError(error, 'logout');
          return;
        }
        if (windowRef && windowRef.location && typeof windowRef.location.replace === 'function') {
          windowRef.location.replace('/login.html');
        } else {
          setStatus('Uscita completata. Puoi chiudere questa pagina.');
        }
      });
    }

    function renderZeroStatus() {
      if (!state.session || !state.insights || !state.history) return;
      const input = get('zero-date');
      const output = get('zero-status');
      if (!input || !output) return;
      const date = input.value;
      const inRange = isDateInRange(date, state.session.startDate, state.session.today);
      const member = ownMember();
      const day = member && member.days.find(item => item.date === date);
      const declaration = state.history.zeroDeclarations.find(item => item.date === date);
      const report = day ? day.smokingReport : 'MISSING';
      const resistances = day ? day.resistancePoints : 0;
      const inProgress = date === state.session.today || Boolean(day && day.inProgress);
      state.zeroCanConfirm = inRange && report !== 'SMOKED';
      state.zeroCanWithdraw = inRange && Boolean(declaration);

      if (!inRange) output.textContent = 'Scegli una data compresa nel periodo condiviso.';
      else if (report === 'SMOKED') output.textContent = 'Sono registrate sigarette in questa data: non è possibile confermare zero.';
      else if (declaration) output.textContent = `Zero sigarette confermato il ${formatInstant(declaration.confirmedAt)}.`;
      else if (resistances > 0) output.textContent = `${resistances} ${resistances === 1 ? 'resistenza registrata' : 'resistenze registrate'}; il resoconto del fumo è mancante. Le resistenze non significano zero.`;
      else output.textContent = 'Resoconto del fumo mancante: non equivale a zero sigarette.';
      if (inProgress && inRange) output.textContent += ' Oggi è una giornata in corso: anche l’eventuale conferma resta provvisoria.';
      setMutationButtons();
    }

    function renderPersonal() {
      const member = ownMember();
      const personal = state.insights.personal;
      const stats = get('personal-stats');
      if (stats) {
        stats.replaceChildren();
        const values = [
          ['Tempo dall’ultima sigaretta', personal.sinceLastSeconds === null ? 'Non disponibile: nessuna sigaretta registrata' : formatDuration(personal.sinceLastSeconds)],
          ['Media degli intervalli completati', personal.meanCompletedGapSeconds === null ? 'Non disponibile: servono almeno due sigarette' : formatDuration(personal.meanCompletedGapSeconds)],
          ['Massimo intervallo completato', personal.maxCompletedGapSeconds === null ? 'Non disponibile: servono almeno due sigarette' : formatDuration(personal.maxCompletedGapSeconds)]
        ];
        for (const [label, value] of values) {
          const wrapper = element(documentRef, 'div', 'stat');
          wrapper.append(element(documentRef, 'dt', null, label), element(documentRef, 'dd', null, value));
          stats.appendChild(wrapper);
        }
      }
      const days = member ? member.days : [];
      renderDailyBars(days);
      renderDailyTable(days);
      renderGapProgress(personal.completedGaps || []);
    }

    function renderDailyBars(days) {
      const container = get('personal-daily-chart');
      if (!container) return;
      if (!days.length) {
        showEmpty(documentRef, container, 'Non ci sono giornate da visualizzare.');
        return;
      }
      const width = Math.max(380, 54 * days.length + 52);
      const height = 220;
      const baseline = 164;
      const maximum = Math.max(1, ...days.map(day => Math.max(day.penalties || 0, day.resistancePoints || 0)));
      const scale = 112 / maximum;
      const svg = makeSvg(documentRef, 'daily-bars', 'Sigarette e resistenze per giornata',
        'Barre distinte per sigarette e resistenze. Il contorno tratteggiato con punto interrogativo indica un resoconto del fumo mancante; il cerchio con zero indica zero confermato.', width, height);
      svg.appendChild(svgElement(documentRef, 'line', {x1: 34, y1: baseline, x2: width - 12, y2: baseline, stroke: '#566174', 'stroke-width': 1}));
      days.forEach((day, index) => {
        const x = 42 + index * 54;
        const group = svgElement(documentRef, 'g', {role: 'group'});
        group.setAttribute('aria-label', `${formatDate(day.date)}: ${smokingReportLabel(day.smokingReport)}, ${day.resistancePoints} ${day.resistancePoints === 1 ? 'resistenza' : 'resistenze'}${day.inProgress ? ', giornata in corso' : ''}.`);
        if (day.smokingReport === 'MISSING') {
          group.appendChild(svgElement(documentRef, 'rect', {
            x: x - 11, y: baseline - 15, width: 12, height: 15, fill: '#fff', stroke: '#3e4859',
            'stroke-width': 2, 'stroke-dasharray': '3 2'
          }));
          group.appendChild(svgElement(documentRef, 'text', {x: x - 5, y: baseline - 20, 'text-anchor': 'middle', fill: '#202538', 'font-size': 11}, '?'));
        } else if (day.penalties === 0) {
          group.appendChild(svgElement(documentRef, 'circle', {cx: x - 5, cy: baseline - 3, r: 4, fill: '#fff', stroke: '#8b321d', 'stroke-width': 2}));
          group.appendChild(svgElement(documentRef, 'text', {x: x - 5, y: baseline - 11, 'text-anchor': 'middle', fill: '#202538', 'font-size': 10}, '0'));
        } else {
          const barHeight = Math.max(4, Number(day.penalties) * scale);
          group.appendChild(svgElement(documentRef, 'rect', {x: x - 11, y: baseline - barHeight, width: 12, height: barHeight, fill: '#8b321d'}));
          group.appendChild(svgElement(documentRef, 'text', {x: x - 5, y: baseline - barHeight - 4, 'text-anchor': 'middle', fill: '#202538', 'font-size': 10}, day.penalties));
        }
        if (day.resistancePoints > 0) {
          const barHeight = Math.max(4, Number(day.resistancePoints) * scale);
          group.appendChild(svgElement(documentRef, 'rect', {
            x: x + 3, y: baseline - barHeight, width: 12, height: barHeight, fill: '#145b78', stroke: '#0d4055',
            'stroke-width': 1, 'stroke-dasharray': '4 2'
          }));
          group.appendChild(svgElement(documentRef, 'text', {x: x + 9, y: baseline - barHeight - 4, 'text-anchor': 'middle', fill: '#202538', 'font-size': 10}, day.resistancePoints));
        }
        const shortDate = `${day.date.slice(8, 10)}/${day.date.slice(5, 7)}`;
        group.appendChild(svgElement(documentRef, 'text', {x: x + 1, y: baseline + 18, 'text-anchor': 'middle', fill: '#202538', 'font-size': 10}, shortDate));
        if (day.inProgress) group.appendChild(svgElement(documentRef, 'text', {x: x + 1, y: baseline + 32, 'text-anchor': 'middle', fill: '#202538', 'font-size': 9}, 'oggi*'));
        svg.appendChild(group);
      });
      container.replaceChildren(svg);
    }

    function renderDailyTable(days) {
      const container = get('personal-daily-table');
      if (!container) return;
      if (!days.length) {
        showEmpty(documentRef, container, 'Il riepilogo giornaliero non è disponibile.');
        return;
      }
      const rows = days.map(day => [
        formatDate(day.date),
        day.penalties === null ? 'Mancante · non è zero' : String(day.penalties),
        String(day.resistancePoints),
        `${smokingReportLabel(day.smokingReport)}${day.inProgress ? ' · in corso' : ''}`
      ]);
      container.replaceChildren(makeTable(documentRef, 'Riepilogo giornaliero personale',
        ['Giornata (Europe/Rome)', 'Sigarette / penalità', 'Resistenze', 'Stato del fumo'], rows));
    }

    function renderGapProgress(gaps) {
      const chart = get('gap-chart');
      const table = get('gap-table');
      if (!chart || !table) return;
      if (!gaps.length) {
        showEmpty(documentRef, chart, 'Nessun intervallo completato: servono almeno due sigarette registrate.');
        showEmpty(documentRef, table, 'Gli intervalli completati compariranno qui.');
        return;
      }
      const width = Math.max(380, 58 * gaps.length + 56);
      const height = 205;
      const baseline = 150;
      const maxSeconds = Math.max(1, ...gaps.map(gap => Number(gap.seconds)));
      const svg = makeSvg(documentRef, 'gap-progression', 'Progressione degli intervalli completati',
        'Ogni punto rappresenta il tempo tra due sigarette consecutive. Le resistenze non modificano questi intervalli.', width, height);
      svg.appendChild(svgElement(documentRef, 'line', {x1: 34, y1: baseline, x2: width - 12, y2: baseline, stroke: '#566174', 'stroke-width': 1}));
      gaps.forEach((gap, index) => {
        const x = 42 + index * 58;
        const y = baseline - Math.max(5, Number(gap.seconds) / maxSeconds * 105);
        svg.appendChild(svgElement(documentRef, 'line', {x1: x, y1: baseline, x2: x, y2: y, stroke: '#55447f', 'stroke-width': 2, 'stroke-dasharray': '4 3'}));
        svg.appendChild(svgElement(documentRef, 'circle', {cx: x, cy: y, r: 5, fill: '#fff', stroke: '#55447f', 'stroke-width': 3,
          'aria-label': `Intervallo ${index + 1}: ${formatDuration(gap.seconds)}`}));
        svg.appendChild(svgElement(documentRef, 'text', {x, y: y - 10, 'text-anchor': 'middle', fill: '#202538', 'font-size': 10}, formatDuration(gap.seconds)));
        svg.appendChild(svgElement(documentRef, 'text', {x, y: baseline + 17, 'text-anchor': 'middle', fill: '#202538', 'font-size': 9}, String(index + 1)));
      });
      chart.replaceChildren(svg);
      const rows = gaps.map((gap, index) => [`Intervallo ${index + 1}`, formatInstant(gap.completedAt), formatDuration(gap.seconds)]);
      table.replaceChildren(makeTable(documentRef, 'Intervalli completati tra sigarette consecutive',
        ['Intervallo', 'Sigaretta successiva (Europe/Rome)', 'Durata'], rows));
    }

    function renderHistory() {
      const eventsContainer = get('history-events');
      const zerosContainer = get('history-zeros');
      if (eventsContainer) {
        const events = state.history.events || [];
        if (!events.length) showEmpty(documentRef, eventsContainer, 'Non hai ancora registrato episodi.');
        else {
          const rows = events.map(event => {
            const actions = element(documentRef, 'div', 'row-actions');
            const edit = element(documentRef, 'button', 'button button-quiet', 'Modifica');
            edit.type = 'button';
            edit.addEventListener('click', () => openEdit(event));
            const remove = element(documentRef, 'button', 'button button-quiet', 'Elimina');
            remove.type = 'button';
            remove.setAttribute('data-mutation', '');
            remove.addEventListener('click', () => deleteEvent(event));
            actions.append(edit, remove);
            return [eventTypeLabel(event.type), formatInstant(event.effectiveAt), `Versione ${event.version}`, actions];
          });
          eventsContainer.replaceChildren(makeTable(documentRef, 'Cronologia personale degli episodi',
            ['Tipo', 'Data e ora (Europe/Rome)', 'Versione', 'Azioni'], rows));
        }
      }
      if (zerosContainer) {
        zerosContainer.replaceChildren();
        const declarations = state.history.zeroDeclarations || [];
        if (!declarations.length) zerosContainer.appendChild(element(documentRef, 'li', null, 'Nessuna conferma di zero sigarette.'));
        else declarations.forEach(declaration => {
          const label = `${formatDate(declaration.date)} · confermata ${formatInstant(declaration.confirmedAt)}${declaration.inProgress ? ' · giornata in corso' : ''}`;
          zerosContainer.appendChild(element(documentRef, 'li', null, label));
        });
      }
      renderZeroStatus();
    }

    function rankLabel(member, rankCounts) {
      if (member.rank === null || member.rank === undefined) return 'Senza rango · nessun resoconto del fumo';
      return `Rango ${member.rank}${rankCounts.get(member.rank) > 1 ? ' (condiviso)' : ''}`;
    }

    function recordedPercentage(value) {
      return value === null || value === undefined ? 'Non disponibile · nessun episodio registrato' : `${IT_NUMBER.format(value)}% degli episodi registrati`;
    }

    function renderGroup() {
      const members = state.insights.group || [];
      const rankCounts = new Map();
      for (const member of members) {
        if (member.rank !== null && member.rank !== undefined) rankCounts.set(member.rank, (rankCounts.get(member.rank) || 0) + 1);
      }
      const leaderboard = get('leaderboard-table');
      if (leaderboard) {
        if (!members.length) showEmpty(documentRef, leaderboard, 'La classifica non è disponibile.');
        else {
          const rows = members.map(member => [
            rankLabel(member, rankCounts),
            member.alias,
            member.hasSmokingReport ? String(member.penalties) : '— · nessun report del fumo',
            String(member.resistancePoints),
            `${member.missingPastDays} ${member.missingPastDays === 1 ? 'giorno passato mancante' : 'giorni passati mancanti'}`,
            member.provisional ? 'Provvisorio' : 'Completo',
            recordedPercentage(member.resistancePercentage)
          ]);
          leaderboard.replaceChildren(makeTable(documentRef, 'Classifica del gruppo per penalità da sigarette',
            ['Rango', 'Partecipante', 'Penalità / sigarette', 'Punti resistenza', 'Copertura passata', 'Stato', 'Resistenza sugli episodi registrati'], rows));
        }
      }
      renderGroupLegend('smoke-chart-legend', members, rankCounts);
      renderGroupLegend('resistance-chart-legend', members, rankCounts);
      renderCumulativeChart('smoke-cumulative-chart', members, 'smoking');
      renderCumulativeChart('resistance-cumulative-chart', members, 'resistance');
      renderGroupDailyTable(members);
    }

    function renderGroupLegend(id, members, rankCounts) {
      const legend = get(id);
      if (!legend) return;
      legend.replaceChildren();
      members.forEach((member, index) => {
        const item = element(documentRef, 'li');
        const mark = element(documentRef, 'span', `legend-mark series-mark series-mark-${index % SERIES_COLORS.length}`,
          index === 0 ? '—' : index === 1 ? '···' : '==');
        mark.setAttribute('aria-hidden', 'true');
        item.append(mark, element(documentRef, 'span', null, `${member.alias} · ${rankLabel(member, rankCounts)} · ${member.resistancePoints} resistenze`));
        legend.appendChild(item);
      });
    }

    function renderCumulativeChart(containerId, members, kind) {
      const container = get(containerId);
      if (!container) return;
      const days = members.length ? members[0].days : [];
      if (!days.length) {
        showEmpty(documentRef, container, 'Non ci sono giornate da visualizzare.');
        return;
      }
      const isSmoking = kind === 'smoking';
      const title = isSmoking ? 'Penalità cumulative e resoconti del fumo' : 'Punti resistenza cumulativi';
      const valuesFor = member => member.days.map(day => isSmoking ? day.cumulativePenalties : day.cumulativeResistancePoints);
      const allValues = members.flatMap(member => valuesFor(member).filter(value => value !== null && value !== undefined).map(Number));
      const maximum = Math.max(1, ...allValues);
      const width = Math.max(380, 47 * Math.max(1, days.length - 1) + 82);
      const height = 235;
      const left = 38;
      const right = width - 16;
      const top = 24;
      const baseline = 177;
      const plotWidth = Math.max(1, right - left);
      const plotHeight = baseline - top;
      const svg = makeSvg(documentRef, isSmoking ? 'group-smoking' : 'group-resistance', title,
        isSmoking
          ? 'Linee delle penalità cumulative per partecipante. I valori non disponibili restano assenti; la tabella seguente distingue resoconti mancanti e totali provvisori.'
          : 'Linee dei punti resistenza cumulativi per partecipante. I punti resistenza sono separati dal conteggio delle sigarette.', width, height);
      svg.appendChild(svgElement(documentRef, 'line', {x1: left, y1: baseline, x2: right, y2: baseline, stroke: '#566174', 'stroke-width': 1}));
      svg.appendChild(svgElement(documentRef, 'line', {x1: left, y1: top, x2: left, y2: baseline, stroke: '#566174', 'stroke-width': 1}));
      const xAt = index => days.length === 1 ? left : left + index * plotWidth / (days.length - 1);
      const yAt = value => baseline - Number(value) / maximum * plotHeight;

      members.forEach((member, memberIndex) => {
        const values = valuesFor(member);
        const color = SERIES_COLORS[memberIndex % SERIES_COLORS.length];
        let path = '';
        let segmentCount = 0;
        const flushPath = () => {
          if (!path) return;
          svg.appendChild(svgElement(documentRef, 'path', {
            d: path, fill: 'none', stroke: color, 'stroke-width': 3,
            'stroke-dasharray': SERIES_DASHES[memberIndex % SERIES_DASHES.length]
          }));
          path = '';
          segmentCount = 0;
        };
        values.forEach((value, index) => {
          if (value === null || value === undefined) {
            flushPath();
            return;
          }
          const x = xAt(index);
          const y = yAt(value);
          path += `${segmentCount === 0 ? 'M' : 'L'}${x},${y} `;
          segmentCount++;
          const daily = member.days[index];
          const provisional = isSmoking && Boolean(daily.cumulativeProvisional);
          const point = svgElement(documentRef, 'circle', {
            cx: x, cy: y, r: 4, fill: provisional ? '#fff' : color, stroke: color, 'stroke-width': 2,
            'aria-label': `${member.alias}, ${formatDate(daily.date)}: ${value}${provisional ? ', provvisorio' : ''}`
          });
          svg.appendChild(point);
        });
        flushPath();
      });
      svg.appendChild(svgElement(documentRef, 'text', {x: left, y: baseline + 20, fill: '#202538', 'font-size': 10}, days[0].date.slice(8, 10) + '/' + days[0].date.slice(5, 7)));
      const finalDay = days[days.length - 1];
      svg.appendChild(svgElement(documentRef, 'text', {x: right, y: baseline + 20, fill: '#202538', 'font-size': 10, 'text-anchor': 'end'}, finalDay.date.slice(8, 10) + '/' + finalDay.date.slice(5, 7) + (finalDay.inProgress ? ' · oggi*' : '')));
      container.replaceChildren(svg);
    }

    function renderGroupDailyTable(members) {
      const container = get('group-daily-table');
      if (!container) return;
      if (!members.length) {
        showEmpty(documentRef, container, 'La copertura giornaliera del gruppo non è disponibile.');
        return;
      }
      const rows = [];
      for (const member of members) {
        for (const day of member.days) {
          const smoking = day.cumulativePenalties === null || day.cumulativePenalties === undefined
            ? 'Mancante · totale non disponibile'
            : `${day.cumulativePenalties}${day.cumulativeProvisional ? ' · provvisorio' : ''}`;
          const status = `${smokingReportLabel(day.smokingReport)}${day.inProgress ? ' · giornata in corso' : ''}; ${day.cumulativeMissingPastDays} ${day.cumulativeMissingPastDays === 1 ? 'giorno passato mancante' : 'giorni passati mancanti'}`;
          rows.push([formatDate(day.date), member.alias, smoking, String(day.cumulativeResistancePoints), status]);
        }
      }
      container.replaceChildren(makeTable(documentRef, 'Copertura e valori cumulativi giorno per giorno',
        ['Giornata (Europe/Rome)', 'Partecipante', 'Sigarette cumulative', 'Resistenze cumulative', 'Copertura del fumo'], rows));
    }

    function renderAll() {
      if (!state.insights || !state.history) return;
      renderPersonal();
      renderHistory();
      renderGroup();
      setMutationButtons();
    }

    function reportStartupError(error) {
      if (error instanceof ApiError && error.status === 401) {
        const prompt = get('login-prompt');
        if (prompt) prompt.hidden = false;
        setError('Per visualizzare il percorso è necessario accedere.');
      } else if (error instanceof NetworkError) {
        setError('Il servizio non è raggiungibile. Verifica la connessione e ricarica la pagina.');
      } else {
        setError('Non è stato possibile caricare il percorso. Ricarica la pagina e riprova.');
      }
    }

    function listen(id, type, handler) {
      const node = get(id);
      if (node) node.addEventListener(type, handler);
    }

    function installHandlers() {
      listen('smoke-now', 'click', async () => {
        try { await createNow('SMOKED'); } catch (error) { await reportMutationError(error, 'create'); }
      });
      listen('resist-now', 'click', async () => {
        try { await createNow('RESISTED'); } catch (error) { await reportMutationError(error, 'create'); }
      });
      listen('retry-event', 'click', () => sendPendingCreate());
      for (const [id, type] of [['backdate-smoke', 'SMOKED'], ['backdate-resist', 'RESISTED']]) {
        listen(id, 'click', async () => {
          try { await createBackdated(type); } catch (error) { await reportMutationError(error, 'create'); }
        });
      }
      listen('backdate-form', 'submit', async event => {
        event.preventDefault();
        try { await createBackdated(); } catch (error) { await reportMutationError(error, 'create'); }
      });
      for (const id of ['backdate-date', 'backdate-time']) {
        listen(id, 'input', () => updateOffsetSelect('backdate'));
        listen(id, 'change', () => updateOffsetSelect('backdate'));
      }
      listen('zero-form', 'submit', async event => {
        event.preventDefault();
        try { await confirmZero(); } catch (error) { await reportMutationError(error, 'zero'); }
      });
      listen('zero-withdraw', 'click', async () => {
        try { await withdrawZero(); } catch (error) { await reportMutationError(error, 'zero'); }
      });
      listen('zero-date', 'input', renderZeroStatus);
      listen('zero-date', 'change', renderZeroStatus);
      listen('event-edit-form', 'submit', async event => {
        event.preventDefault();
        try { await saveEdit(); } catch (error) { await reportMutationError(error, 'edit'); }
      });
      for (const id of ['edit-date', 'edit-time']) {
        listen(id, 'input', () => updateOffsetSelect('edit'));
        listen(id, 'change', () => updateOffsetSelect('edit'));
      }
      listen('edit-cancel', 'click', closeEdit);
      listen('logout-button', 'click', () => logout());
    }

    installHandlers();

    return {
      api,
      state,
      start: async () => {
        try { await refreshData(); }
        catch (error) { reportStartupError(error); }
      },
      refresh: refreshData,
      createNow,
      createBackdated,
      confirmZero,
      withdrawZero,
      saveEdit,
      logout
    };
  }

  const publicApi = {
    ApiClient,
    ApiError,
    NetworkError,
    ValidationError,
    buildTimeInput,
    createApp,
    formatDuration,
    romeOffsetsFor
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = publicApi;
  if (typeof document !== 'undefined' && document.getElementById('dashboard')) {
    const app = createApp();
    app.start();
  }
}());
