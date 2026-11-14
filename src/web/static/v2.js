// Calendar Sync — пробный интерфейс (/v2). Те же API, что и у основного.
const SYS = {
    google: { name: 'Google', mono: 'G' },
    domino: { name: 'Notes', mono: 'N' },
    teams: { name: 'Teams', mono: 'T' },
};
const ORDER = ['domino', 'google', 'teams'];
const STORE = { routes: 'calendar-sync:v2:routes', fwd: 'calendar-sync:v2:fwd', last: 'calendar-sync:v2:last' };
const DEFAULT_ROUTES = ['domino>google', 'google>domino'];

const $ = (id) => document.getElementById(id);

function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) {
        if (value == null || value === false) continue;
        if (key === 'class') node.className = value;
        else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
        else node.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat()) {
        if (child == null || child === false) continue;
        node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
}

function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'ic');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#i-${name}`);
    svg.append(use);
    return svg;
}

const badge = (sys, xs = false) => el('span', { class: `badge b-${sys} ${xs ? 'xs' : ''}`, 'aria-hidden': 'true' }, SYS[sys].mono);

function plural(n, one, few, many) {
    const m10 = n % 10, m100 = n % 100;
    if (m10 === 1 && m100 !== 11) return one;
    if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
    return many;
}

function load(key, fallback) {
    try { const v = JSON.parse(localStorage.getItem(key)); return v ?? fallback; } catch (e) { return fallback; }
}
function save(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* хранилище недоступно */ }
}

// replaceChildren превращает null в текст «null»: пропускаем пустые части, как el()
function fill(node, ...children) {
    node.replaceChildren(...children.filter((c) => c != null && c !== false));
}

async function request(url, options = {}) {
    const response = await fetch(url, options);
    let data = {};
    try { data = await response.json(); } catch (e) { /* пустой ответ */ }
    const ok = response.ok && data.status !== 'error';
    return { ok, data, error: data.message || data.detail || `HTTP ${response.status}` };
}
const postJSON = (url, body) => request(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
});

// ---------- Состояние ----------

const state = {
    routes: new Set(load(STORE.routes, DEFAULT_ROUTES)),
    fwd: load(STORE.fwd, 30),
    config: null,
    preview: null,      // { actions, warnings, error, loading }
    on: new Set(),      // ключи отмеченных действий
    open: new Set(),    // развёрнутые серии
    tab: 'all',
};

function systemReady(sys) {
    const c = state.config && state.config[sys];
    if (!c) return false;
    if (sys === 'google') return !!c.has_token;
    if (sys === 'domino') return !!(c.server_url && c.has_password);
    return !!(c.client_id && c.has_secret && c.has_token);
}

// ---------- Навигация ----------

const VIEWS = ['overview', 'calendars', 'preview', 'result'];
function showView() {
    let name = location.hash.slice(1);
    if (!VIEWS.includes(name)) name = 'overview';
    if (name === 'preview' && !state.preview) name = 'overview';
    for (const v of VIEWS) $(`view-${v}`).hidden = v !== name;
    if (name === 'overview') renderOverview();
    if (name === 'calendars') renderMatrix();
}

// ---------- Обзор ----------

function periodText() {
    const now = Date.now();
    const f = (d) => d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
    return `${f(new Date(now - 864e5))} – ${f(new Date(now + state.fwd * 864e5))}`;
}

function renderOverview() {
    $('period-text').textContent = periodText();
    $('period').querySelectorAll('button').forEach((b) => b.setAttribute('aria-pressed', String(+b.dataset.fwd === state.fwd)));

    // Предупреждения: только про системы, которые участвуют в маршрутах
    const alerts = [];
    if (state.config) {
        const used = new Set([...state.routes].flatMap((r) => r.split('>')));
        for (const sys of ORDER) {
            if (!used.has(sys) || systemReady(sys)) continue;
            const text = {
                google: 'Google: нужен вход — откройте настройки и нажмите «Проверить»',
                domino: 'Notes: не указан сервер или пароль',
                teams: 'Teams: нужен вход через Microsoft',
            }[sys];
            alerts.push(el('div', { class: 'banner' }, icon('alert'), el('span', { class: 'grow' }, text), el('a', { href: '#calendars' }, 'Открыть')));
        }
    }
    fill($('ov-alerts'), ...alerts);
    $('ov-alerts').hidden = !alerts.length;

    const routes = ORDER.flatMap((from) => ORDER.filter((to) => state.routes.has(`${from}>${to}`)).map((to) => [from, to]));
    fill($('ov-routes'), ...(routes.length ? routes.map(([from, to]) => {
        const ready = !state.config || (systemReady(from) && systemReady(to));
        return el('li', {},
            el('span', { class: 'route-badges' }, badge(from, true), icon('arrow'), badge(to, true)),
            el('span', { class: 'grow strong' }, `${SYS[from].name} → ${SYS[to].name}`),
            ready ? null : el('span', { class: 'pill warn' }, 'Пауза'));
    }) : [el('li', { class: 'muted small' }, 'Маршруты не выбраны')]));

    const last = load(STORE.last, null);
    $('ov-dot').className = `dot ${last ? (last.errors ? 'bad' : 'ok') : ''}`;
    if (last) {
        const at = new Date(last.at);
        const when = at.toDateString() === new Date().toDateString()
            ? `сегодня в ${at.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`
            : at.toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
        $('ov-status').textContent = `Последняя синхронизация ${when}`;
        fill($('ov-last'), el('ul', { class: 'list' },
            el('li', {}, el('span', { class: 'grow' }, el('div', { class: 'strong' }, totalsText(last)),
                el('div', { class: 'muted small' }, last.summary || '')),
            el('time', { class: 'muted small' }, when)),
            last.errors ? el('li', { class: 'small' }, icon('alert'), `Ошибок: ${last.errors}`) : null));
    }
    $('ov-check').disabled = !routes.length;
}

$('period').addEventListener('click', (e) => {
    const b = e.target.closest('button');
    if (!b) return;
    state.fwd = +b.dataset.fwd;
    save(STORE.fwd, state.fwd);
    renderOverview();
});

// ---------- Маршруты ----------

function renderMatrix() {
    const head = el('div', { class: 'mrow head', role: 'row' },
        el('span', { role: 'columnheader' }, 'из ↓ · в →'),
        ...ORDER.map((s) => el('span', { role: 'columnheader' }, SYS[s].name)));
    const rows = ORDER.map((from) => el('div', { class: 'mrow', role: 'row' },
        el('span', { role: 'rowheader' }, badge(from, true), SYS[from].name),
        ...ORDER.map((to) => {
            if (from === to) return el('span', { class: 'mcell', role: 'cell' }, el('span', { class: 'na', 'aria-label': 'Нельзя копировать в ту же систему' }, '—'));
            const key = `${from}>${to}`;
            const on = state.routes.has(key);
            return el('span', { class: 'mcell', role: 'cell' },
                el('button', {
                    type: 'button', class: 'cell-btn', role: 'checkbox', 'aria-checked': String(on),
                    'aria-label': `Из ${SYS[from].name} в ${SYS[to].name}`,
                    onclick: () => {
                        if (on) state.routes.delete(key); else state.routes.add(key);
                        save(STORE.routes, [...state.routes]);
                        renderMatrix();
                    },
                }, el('span', { class: 'box' }, icon('check'))));
        })));
    fill($('matrix'), head, ...rows);
    const n = state.routes.size;
    const waiting = state.config ? [...state.routes].filter((r) => r.split('>').some((s) => !systemReady(s))).length : 0;
    $('matrix-summary').textContent = `Включено маршрутов: ${n}` + (waiting ? ` · ${waiting} заработают после настройки подключения` : '');
}

// ---------- Подключения ----------

function setPill(sys, text, tone) {
    const p = $(`${sys}-pill`);
    p.textContent = text;
    p.className = `pill ${tone || ''}`;
}

function showNote(sys, text, tone = '') {
    const n = $(`${sys}-status`);
    n.textContent = text;
    n.className = `note ${tone}`;
    n.hidden = false;
}

async function loadConfig() {
    const { ok, data } = await request('/api/config');
    if (!ok) return;
    state.config = data;
    const g = data.google || {}, d = data.domino || {}, t = data.teams || {};

    $('google-credentials').value = g.credentials_file || '';
    gcal.current = g.calendar_id || 'primary';
    renderGoogleLogin();
    setPill('google', g.has_token ? 'Подключён' : 'Нужен вход', g.has_token ? 'ok' : 'warn');

    $('domino-server').value = d.server_url || '';
    $('domino-username').value = d.username || '';
    $('domino-verify-ssl').checked = !!d.verify_ssl;
    let host = '';
    try { host = d.server_url ? new URL(d.server_url).host : ''; } catch (e) { host = d.server_url; }
    $('domino-login').textContent = [d.username, host].filter(Boolean).join(' · ') || 'Не настроен';
    $('domino-password').required = !d.has_password;
    if (d.has_password) {
        $('domino-password').placeholder = '••••••••';
        $('domino-password-hint').textContent = 'Пароль сохранён. Оставьте пустым, чтобы не менять';
    }
    setPill('domino', systemReady('domino') ? 'Настроен' : 'Не настроен', systemReady('domino') ? 'ok' : 'warn');

    $('teams-client-id').value = t.client_id || '';
    $('teams-client-secret').required = !t.has_secret;
    if (t.has_secret) $('teams-client-secret').placeholder = '••••••••';
    $('teams-login').textContent = t.client_id ? `Приложение ${t.client_id.slice(0, 8)}…` : 'Не настроен';
    setPill('teams', t.has_token ? 'Подключён' : 'Нужен вход', t.has_token ? 'ok' : 'warn');

    showView();
}

function bindSave(sys, url, collect) {
    $(`${sys}-form`).addEventListener('submit', async (e) => {
        e.preventDefault();
        const { ok, data, error } = await postJSON(url, collect());
        showNote(sys, ok ? data.message : `Ошибка сохранения: ${error}`, ok ? 'success' : 'error');
        if (ok) {
            $(`${sys}-form`).querySelectorAll('input[type="password"]').forEach((i) => { i.value = ''; });
            loadConfig();
        }
    });
}
bindSave('domino', '/api/config/domino', () => ({
    server_url: $('domino-server').value, username: $('domino-username').value,
    password: $('domino-password').value, verify_ssl: $('domino-verify-ssl').checked,
}));
bindSave('teams', '/api/config/teams', () => ({ client_id: $('teams-client-id').value, client_secret: $('teams-client-secret').value }));

// ---------- Календарь Google ----------

// Список календарей грузим лениво: при открытии настроек Google или по кнопке «Обновить»
const gcal = { current: 'primary', list: null, loading: false, error: null, pendingSwitch: null };

const calLabel = (c) => c.summary || c.id;

function renderGoogleLogin() {
    const c = gcal.list && gcal.list.find((x) => x.id === gcal.current || (gcal.current === 'primary' && x.primary));
    const name = c ? calLabel(c) : (gcal.current === 'primary' ? 'основной' : gcal.current);
    $('google-login').textContent = `Календарь: ${name}`;
}

function setCalHint(text, isError = false) {
    const hint = $('google-cal-hint');
    hint.textContent = text;
    hint.className = isError ? 'err-text' : '';
}

function renderCalendars(selectId) {
    const select = $('google-calendar');
    const keep = selectId || select.value || gcal.current;
    if (gcal.loading) {
        fill(select, el('option', {}, 'Загружаем…'));
        select.disabled = true;
    } else if (!gcal.list) {
        fill(select, el('option', {}, 'Список недоступен'));
        select.disabled = true;
    } else {
        // В «primary» из настроек может прийти и псевдоним, и настоящий ID основного календаря
        const isCurrent = (c) => c.id === gcal.current || (gcal.current === 'primary' && c.primary);
        const options = gcal.list.map((c) => {
            const marks = [c.primary ? 'основной' : null, c.writable ? null : 'только чтение'].filter(Boolean);
            return el('option', { value: c.id, disabled: !c.writable && !isCurrent(c) },
                calLabel(c) + (marks.length ? ` · ${marks.join(', ')}` : ''));
        });
        if (!gcal.list.some(isCurrent)) {
            options.unshift(el('option', { value: gcal.current }, `${gcal.current} · не найден`));
        }
        fill(select, ...options);
        const wanted = gcal.list.find((c) => c.id === keep) || gcal.list.find(isCurrent);
        select.value = wanted ? wanted.id : gcal.current;
        select.disabled = false;
    }
    if (gcal.loading) setCalHint('Загружаем календари…');
    else if (gcal.error) {
        setCalHint(`Не удалось загрузить календари: ${gcal.error}. Нажмите «Проверить», чтобы войти в Google, или «Войти заново».`, true);
    } else if (gcal.list) {
        const ro = gcal.list.filter((c) => !c.writable).length;
        setCalHint('Сюда записываются копии событий из Notes и Teams' + (ro ? '. Календари только для чтения выбрать нельзя' : ''));
    }
    $('google-cal-refresh').disabled = gcal.loading;
    $('google-cal-refresh').classList.toggle('spin', gcal.loading);
    renderGoogleLogin();
}

async function loadCalendars(selectId) {
    if (gcal.loading) return;
    gcal.loading = true;
    gcal.error = null;
    renderCalendars();
    try {
        const { ok, data, error } = await request('/api/google/calendars');
        if (ok) {
            gcal.list = data.calendars || [];
            if (data.current) gcal.current = data.current;
        } else {
            gcal.list = null;
            gcal.error = error;
        }
    } catch (e) {
        gcal.list = null;
        gcal.error = e.message;
    }
    gcal.loading = false;
    renderCalendars(selectId);
}

$('google-details').addEventListener('toggle', () => {
    if ($('google-details').open && !gcal.list && !gcal.loading) loadCalendars();
});
$('google-cal-refresh').addEventListener('click', () => loadCalendars());
$('google-calendar').addEventListener('change', () => { $('google-switch').hidden = true; });

function toggleCreate(show) {
    $('google-cal-create').hidden = !show;
    $('google-cal-new').setAttribute('aria-expanded', String(show));
    if (show) $('google-cal-name').focus();
    else $('google-cal-new').focus();
}
$('google-cal-new').addEventListener('click', () => toggleCreate($('google-cal-create').hidden));
$('google-cal-cancel').addEventListener('click', () => toggleCreate(false));

async function createCalendar() {
    const summary = $('google-cal-name').value.trim();
    if (!summary) { showNote('google', 'Введите название календаря', 'error'); $('google-cal-name').focus(); return; }
    const button = $('google-cal-add');
    button.disabled = true;
    showNote('google', `Создаём календарь «${summary}»…`);
    try {
        const { ok, data, error } = await postJSON('/api/google/calendars', { summary });
        if (ok && data.calendar) {
            gcal.list = [...(gcal.list || []).filter((c) => c.id !== data.calendar.id), data.calendar];
            gcal.error = null;
            renderCalendars(data.calendar.id);
            toggleCreate(false);
            showNote('google', `Календарь «${calLabel(data.calendar)}» создан и выбран. Нажмите «Сохранить», чтобы копии шли в него.`, 'success');
        } else {
            showNote('google', `Не удалось создать календарь: ${error}`, 'error');
        }
    } catch (e) {
        showNote('google', `Не удалось создать календарь: ${e.message}`, 'error');
    } finally {
        button.disabled = false;
    }
}
$('google-cal-add').addEventListener('click', createCalendar);
// Enter в поле названия создаёт календарь, а не отправляет всю форму
$('google-cal-name').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); createCalendar(); }
    if (e.key === 'Escape') { e.preventDefault(); toggleCreate(false); }
});

function selectedCalendar() {
    const select = $('google-calendar');
    if (select.disabled || !gcal.list) return gcal.current;
    const c = gcal.list.find((x) => x.id === select.value);
    // Основной календарь, сохранённый как «primary», не считаем сменой
    if (c && c.primary && gcal.current === 'primary') return gcal.current;
    return select.value || gcal.current;
}

async function doSwitch(calendarId, deleteOld) {
    $('google-switch').hidden = true;
    showNote('google', 'Переключаем календарь…');
    try {
        const { ok, data, error } = await postJSON('/api/google/switch', { calendar_id: calendarId, delete_old: deleteOld });
        if (!ok) { showNote('google', `Не удалось переключить календарь: ${error}`, 'error'); return; }
        const c = gcal.list && gcal.list.find((x) => x.id === calendarId);
        const errors = data.errors || [];
        let text = `Календарь переключён на «${c ? calLabel(c) : calendarId}».`;
        if (deleteOld) text += `\nУдалено копий из старого: ${data.deleted || 0}.`;
        if (errors.length) text += `\nНе удалось удалить: ${errors.length}\n${errors.join('\n')}`;
        showNote('google', text, errors.length ? 'error' : 'success');
        gcal.current = calendarId;
        renderCalendars(calendarId);
        loadConfig();
    } catch (e) {
        showNote('google', `Не удалось переключить календарь: ${e.message}`, 'error');
    }
}

$('google-switch-delete').addEventListener('click', () => doSwitch(gcal.pendingSwitch, true));
$('google-switch-keep').addEventListener('click', () => doSwitch(gcal.pendingSwitch, false));
$('google-switch-cancel').addEventListener('click', () => {
    $('google-switch').hidden = true;
    showNote('google', 'Календарь не изменён');
    $('google-calendar').focus();
});

$('google-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('google-switch').hidden = true;
    // Файл credentials сохраняем отдельно; календарь меняет только /api/google/switch
    const { ok, data, error } = await postJSON('/api/config/google', {
        credentials_file: $('google-credentials').value, calendar_id: gcal.current,
    });
    if (!ok) { showNote('google', `Ошибка сохранения: ${error}`, 'error'); return; }
    const target = selectedCalendar();
    if (target === gcal.current) {
        showNote('google', data.message, 'success');
        loadConfig();
        return;
    }
    showNote('google', 'Проверяем старый календарь…');
    const preview = await request(`/api/google/switch-preview?calendar_id=${encodeURIComponent(target)}`);
    if (!preview.ok) { showNote('google', `Не удалось проверить старый календарь: ${preview.error}`, 'error'); return; }
    const n = preview.data.old_copies || 0;
    if (n <= 0) { doSwitch(target, false); return; }
    gcal.pendingSwitch = target;
    $('google-status').hidden = true;
    $('google-switch-text').textContent = `В старом календаре ${n} ${plural(n, 'копия', 'копии', 'копий')} синхронизатора. `
        + 'Удалить их? В новом календаре они будут созданы заново при следующей синхронизации.';
    $('google-switch').hidden = false;
    $('google-switch-delete').focus();
});

const TESTS = {
    google: { url: '/api/test/google', body: () => undefined, info: (i) => `Календарь: ${i.calendar_name}\nЧасовой пояс: ${i.timezone}` },
    // Проверяем значения из формы: новый пароль можно проверить до сохранения
    domino: {
        url: '/api/test/domino',
        body: () => ({
            server_url: $('domino-server').value || null, username: $('domino-username').value || null,
            password: $('domino-password').value || null, verify_ssl: $('domino-verify-ssl').checked,
        }),
        info: (i) => `Пользователь: ${i.username}\nСервер: ${i.server}`,
    },
    teams: { url: '/api/test/teams', body: () => undefined, info: (i) => `Пользователь: ${i.user}\nКалендарь: ${i.calendar_name}` },
};

document.querySelectorAll('[data-test]').forEach((button) => {
    button.addEventListener('click', async () => {
        const sys = button.dataset.test;
        const t = TESTS[sys];
        showNote(sys, 'Проверяем подключение…');
        button.disabled = true;
        try {
            const { ok, data, error } = await postJSON(t.url, t.body());
            if (ok) {
                showNote(sys, `${data.message}\n${t.info(data.info || {})}`, 'success');
                setPill(sys, 'Подключён', 'ok');
                if (sys === 'google' && gcal.error) loadCalendars();
            } else {
                const hint = /\b401\b|Unauthorized/.test(error) ? '\nСервер отклонил имя пользователя или пароль.' : '';
                showNote(sys, error + hint, 'error');
                setPill(sys, 'Ошибка', 'warn');
            }
        } catch (e) {
            showNote(sys, `Ошибка подключения: ${e.message}`, 'error');
        } finally {
            button.disabled = false;
        }
    });
});

$('google-reauth').addEventListener('click', async () => {
    if (!confirm('Удалить токен Google и войти заново?')) return;
    const { ok, error } = await postJSON('/api/google/reauth');
    showNote('google', ok ? 'Вход сброшен. Нажмите «Проверить», чтобы войти заново.' : error, ok ? '' : 'error');
    if (ok) {
        gcal.list = null;
        gcal.error = 'нужен вход в Google';
        renderCalendars();
        loadConfig();
    }
});

$('teams-auth').addEventListener('click', async () => {
    const { ok, data, error } = await request('/api/teams/authorize');
    if (ok && data.authorization_url) {
        window.open(data.authorization_url, 'teams_auth', 'width=600,height=700');
        showNote('teams', 'Войдите в Microsoft в открывшемся окне, затем нажмите «Проверить».');
    } else {
        showNote('teams', `Ошибка: ${error}`, 'error');
    }
});

// ---------- Предпросмотр ----------

function syncRequest(dryRun, keys) {
    const used = new Set([...state.routes].flatMap((r) => r.split('>')));
    return {
        direction: 'all',
        days_back: 1,
        days_forward: state.fwd,
        dry_run: dryRun,
        enable_google: used.has('google'),
        enable_domino: used.has('domino'),
        enable_teams: used.has('teams'),
        routes: [...state.routes],
        // С keys выполняется ровно отмеченное, для снятых вопросов запоминается ответ «нет»
        keys: keys === undefined ? null : keys,
    };
}

async function runPreview() {
    state.preview = { loading: true, actions: [] };
    state.on = new Set();
    state.open = new Set();
    state.tab = 'all';
    location.hash = '#preview';
    renderPreview();
    const { ok, data, error } = await postJSON('/api/sync', syncRequest(true));
    if (ok) {
        const actions = data.actions || data.events || [];
        state.preview = { actions, warnings: data.warnings || [], window: data.sync_window };
        state.on = new Set(actions.filter((a) => a.selected !== false).map((a) => a.key));
    } else {
        state.preview = { error, actions: [], warnings: [] };
    }
    renderPreview();
}
$('ov-check').addEventListener('click', runPreview);

// Что делает строка предпросмотра. Вопросы: без галочки ответ «нет» запоминается.
const TYPES = {
    create: { label: 'Создать копию', tone: '' },
    update: { label: 'Обновить', tone: 'upd' },
    'del-copy': { label: 'Удалить копию', tone: 'del' },
    'del-extra': { label: 'Удалить лишнюю копию', tone: 'del' },
    'del-original': { label: 'Удалить оригинал?', tone: 'ask', question: true, on: 'Удалим оригинал', off: 'Оставим, больше не спрашиваем' },
    'del-twin': { label: 'Удалить второго двойника?', tone: 'ask', question: true, on: 'Удалим это событие', off: 'Оставим, больше не спрашиваем' },
    twin: { label: 'Это одна встреча?', tone: 'ask', question: true, on: 'Да: связать, копию не создавать', off: 'Нет: копировать как обычно, больше не спрашиваем' },
};
const FIELD_NAMES = { title: 'название', start: 'время', end: 'время', location: 'место', description: 'описание' };
const VERBS = {
    create: ['копия', 'копии', 'копий'],
    update: ['обновление', 'обновления', 'обновлений'],
    delete: ['удаление', 'удаления', 'удалений'],
    twin: ['связь', 'связи', 'связей'],
};

function typeOf(a) {
    if (a.kind !== 'delete') return a.kind in TYPES ? a.kind : 'create';
    // Что удаляется — поле subject из планировщика: copy | extra | original | twin
    return { original: 'del-original', twin: 'del-twin', extra: 'del-extra' }[a.subject] || 'del-copy';
}
const isQuestion = (a) => !!TYPES[typeOf(a)].question;
// В блок «Нужно решение»: вопросы и всё, что по умолчанию не отмечено
const needsDecision = (a) => isQuestion(a) || a.selected === false;

function localDay(ev) {
    // all-day даты приходят как YYYY-MM-DD, timed — как ISO в UTC
    return ev.all_day ? new Date(`${ev.start.slice(0, 10)}T00:00:00`) : new Date(ev.start);
}
const hm = (iso) => new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
const shortDate = (ev) => localDay(ev).toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
const byTime = (a, b) => localDay(a) - localDay(b) || (a.all_day ? -1 : 0) - (b.all_day ? -1 : 0);

function changedText(a) {
    const names = [...new Set((a.changed || []).map((f) => FIELD_NAMES[f] || f))];
    return names.length ? `изменено: ${names.join(', ')}` : 'изменено';
}

function detail(a) {
    const from = SYS[a.origin].name, to = SYS[a.target].name;
    switch (typeOf(a)) {
        case 'update': return changedText(a);
        case 'del-copy': return `в ${to} · оригинал удалён`;
        case 'del-extra': return `в ${to} · у оригинала есть двойник`;
        case 'del-original': return `в ${to} · копия в ${from} удалена`;
        case 'del-twin': return `в ${to} · двойник в ${from} удалён`;
        case 'twin': return `одинаковые название и начало в ${from} и ${to}`;
        default: return a.location || `из ${from}`;
    }
}

function chip(a) {
    const o = SYS[a.origin].mono, t = SYS[a.target].mono;
    if (a.kind === 'twin') return `${o} ↔ ${t}`;
    if (a.kind === 'delete') return `× ${t}`;
    return `${o} → ${t}`;
}

function timeCol(a, withDate) {
    if (withDate) return el('span', { class: 'ev-time' }, el('b', {}, shortDate(a)), el('span', {}, a.all_day ? 'весь день' : hm(a.start)));
    return el('span', { class: 'ev-time' }, a.all_day ? [el('b', {}, 'весь'), el('span', {}, 'день')] : [el('b', {}, hm(a.start)), el('span', {}, hm(a.end))]);
}

function toggleKeys(keys, on, focusKey) {
    keys.forEach((k) => (on ? state.on.add(k) : state.on.delete(k)));
    renderPreview();
    // Перерисовка теряет фокус — возвращаем его на ту же строку
    const node = focusKey && $('pv-body').querySelector(`[data-key="${CSS.escape(focusKey)}"]`);
    if (node) node.focus();
}

function actionRow(a, withDate = false) {
    const t = typeOf(a), T = TYPES[t];
    const on = state.on.has(a.key);
    const decision = needsDecision(a);
    // Причина (например, «участники получат письмо») — для обновлений и того, что не отмечено по умолчанию
    const reason = a.reason && (a.kind === 'update' || (a.selected === false && !T.question)) ? a.reason : '';
    return el('button', {
        type: 'button', class: `ev ${decision ? 'q' : ''}`, role: 'checkbox', 'aria-checked': String(on), 'data-key': a.key,
        onclick: () => toggleKeys([a.key], !on, a.key),
    },
    timeCol(a, withDate),
    el('span', { class: 'ev-bar', style: `background: var(--${a.target})` }),
    el('span', { class: 'ev-main' },
        el('span', { class: `kind ${T.tone}` }, T.label),
        el('span', { class: 'ev-title' }, a.title),
        el('span', { class: 'ev-sub' }, el('span', { class: 'chip' }, chip(a)), el('span', { class: 'ellipsis' }, detail(a))),
        reason ? el('span', { class: 'ev-reason' }, icon('alert'), reason) : null,
        T.question ? el('span', { class: `ev-state ${on ? 'on' : ''}` }, on ? T.on : T.off) : null),
    el('span', { class: 'box' }, icon('check')));
}

// Серия: повторяющиеся экземпляры одного события с одним действием — одна строка
function seriesRow(list) {
    const a = list[0], T = TYPES[typeOf(a)];
    const keys = list.map((x) => x.key);
    const n = keys.filter((k) => state.on.has(k)).length;
    const checked = n === list.length ? 'true' : n ? 'mixed' : 'false';
    const id = `series:${keys[0]}`;
    const open = state.open.has(id);
    const last = list[list.length - 1];
    const head = el('li', { class: 'series' },
        el('button', {
            type: 'button', class: 'ev', role: 'checkbox', 'aria-checked': checked, 'data-key': id,
            onclick: () => toggleKeys(keys, n !== list.length, id),
        },
        timeCol(a, false),
        el('span', { class: 'ev-bar', style: `background: var(--${a.target})` }),
        el('span', { class: 'ev-main' },
            el('span', { class: `kind ${T.tone}` }, `${T.label} · серия`),
            el('span', { class: 'ev-title' }, a.title),
            el('span', { class: 'ev-sub' }, el('span', { class: 'chip' }, chip(a)),
                el('span', { class: 'ellipsis' }, `${list.length} ${plural(list.length, 'раз', 'раза', 'раз')} до ${shortDate(last)}` + (a.kind === 'update' ? ` · ${changedText(a)}` : '')))),
        el('span', { class: 'box' }, checked === 'mixed' ? el('span', { class: 'dash' }) : icon('check'))),
        el('button', {
            type: 'button', class: 'expand', 'aria-expanded': String(open), 'aria-label': `${open ? 'Свернуть' : 'Показать'} ${list.length} ${plural(list.length, 'событие', 'события', 'событий')} серии «${a.title}»`,
            onclick: () => { if (open) state.open.delete(id); else state.open.add(id); renderPreview(); $('pv-body').querySelector(`[data-key="${CSS.escape(id)}"]`)?.parentElement.querySelector('.expand')?.focus(); },
        }, String(list.length), el('span', { class: 'chev', 'aria-hidden': 'true' })));
    return [head, ...(open ? list.map((x) => el('li', { class: 'sub' }, actionRow(x, true))) : [])];
}

function renderPreview() {
    const p = state.preview;
    $('pv-period').textContent = periodText();
    const body = $('pv-body');
    const run = $('pv-run');
    const note = $('pv-note');
    note.hidden = true;

    if (p.loading) {
        fill($('pv-tabs'));
        fill(body,
            el('div', { class: 'progress', role: 'progressbar', 'aria-label': 'Читаем календари' }, el('div')),
            el('p', { class: 'hint' }, 'Читаем календари и сверяем копии…'),
            el('div', { class: 'skel' }), el('div', { class: 'skel' }), el('div', { class: 'skel' }));
        $('pv-summary').textContent = '';
        run.disabled = true;
        run.textContent = 'Применить';
        return;
    }
    if (p.error) {
        fill($('pv-tabs'));
        fill(body, el('div', { class: 'card pad stack errors' },
            el('h2', {}, 'Не удалось прочитать календари'),
            el('p', { class: 'small' }, p.error),
            el('a', { class: 'btn btn-ghost', href: '#calendars' }, 'Проверить подключения')));
        $('pv-summary').textContent = '';
        run.disabled = true;
        return;
    }

    const actions = p.actions;
    const counts = { all: actions.length };
    actions.forEach((a) => { counts[a.target] = (counts[a.target] || 0) + 1; });
    const tabs = ['all', ...ORDER.filter((s) => counts[s])];
    if (!tabs.includes(state.tab)) state.tab = 'all';
    fill($('pv-tabs'), ...(actions.length ? tabs.map((t) => el('button', {
        type: 'button', role: 'tab', 'aria-selected': String(state.tab === t),
        onclick: () => { state.tab = t; renderPreview(); },
    }, t === 'all' ? 'Все' : `В ${SYS[t].name}`, el('span', {}, counts[t]))) : []));

    const warnings = (p.warnings || []).map((w) => el('div', { class: 'banner', role: 'alert' }, icon('alert'), el('span', { class: 'grow' }, w)));

    if (!actions.length) {
        fill(body, ...warnings, el('div', { class: 'empty' },
            el('div', { class: 'hero-ic ok' }, icon('check')),
            el('h2', {}, 'Синхронизировать нечего'),
            el('p', { class: 'hint' }, 'Новых, изменённых и удалённых событий за период нет.')));
    } else {
        const shown = actions.filter((a) => state.tab === 'all' || a.target === state.tab).sort(byTime);
        const decide = shown.filter(needsDecision);
        const rest = shown.filter((a) => !needsDecision(a));

        const sections = [];
        if (decide.length) {
            sections.push(el('section', { class: 'day stack', 'aria-labelledby': 'pv-decide-h' },
                el('h2', { id: 'pv-decide-h' }, `Нужно решение · ${decide.length}`),
                decide.some(isQuestion) ? el('p', { class: 'hint left' }, 'Галочка — «да». Если оставить вопрос без галочки, ответ «нет» запомним и больше не спросим.') : null,
                el('ul', { class: 'card ask' }, ...decide.map((a) => el('li', {}, actionRow(a, true))))));
        }

        if (rest.length) {
            // Серии: одинаковые действие, маршрут и название, от трёх экземпляров
            const groups = new Map();
            for (const a of rest) {
                const g = `${a.kind}|${a.origin}|${a.target}|${a.title}`;
                if (!groups.has(g)) groups.set(g, []);
                groups.get(g).push(a);
            }
            const days = new Map();
            const seen = new Set();
            for (const a of rest) {
                const g = `${a.kind}|${a.origin}|${a.target}|${a.title}`;
                const list = groups.get(g);
                if (list.length >= 3 && seen.has(g)) continue;
                seen.add(g);
                const label = localDay(a).toLocaleDateString('ru-RU', { weekday: 'long', day: 'numeric', month: 'long' });
                if (!days.has(label)) days.set(label, []);
                days.get(label).push(...(list.length >= 3 ? seriesRow(list) : [el('li', {}, actionRow(a))]));
            }
            const allOn = rest.every((a) => state.on.has(a.key));
            sections.push(
                el('div', { class: 'group-actions' }, el('button', {
                    type: 'button',
                    onclick: () => toggleKeys(rest.map((a) => a.key), !allOn),
                }, allOn ? 'Снять все' : 'Выбрать все')),
                ...[...days].map(([label, items]) => el('section', { class: 'day stack' },
                    el('h2', {}, label[0].toUpperCase() + label.slice(1)),
                    el('ul', { class: 'card' }, ...items))));
        }
        fill(body, ...warnings, ...sections);
    }

    // Подвал: счётчики по видам, предупреждение о запоминаемых отказах
    const chosen = actions.filter((a) => state.on.has(a.key));
    const byKind = {};
    chosen.forEach((a) => { byKind[a.kind] = (byKind[a.kind] || 0) + 1; });
    const parts = Object.keys(VERBS).filter((k) => byKind[k]).map((k) => `${byKind[k]}\u00a0${plural(byKind[k], ...VERBS[k])}`);
    const declined = actions.filter((a) => isQuestion(a) && !state.on.has(a.key));

    $('pv-summary').textContent = actions.length ? `Выбрано ${chosen.length} из ${actions.length}` : '';
    if (declined.length) {
        const dels = declined.filter((a) => a.kind === 'delete').length;
        const twins = declined.length - dels;
        const what = [
            dels ? `${dels}\u00a0${plural(dels, 'удаление', 'удаления', 'удалений')}` : '',
            twins ? `${twins}\u00a0${plural(twins, 'вопрос', 'вопроса', 'вопросов')} о двойниках` : '',
        ].filter(Boolean).join(', ');
        fill(note, icon('alert'), el('span', {}, `Без галочки: ${what}. Ответ «нет» запомним и больше не спросим.`));
        note.hidden = false;
    }
    run.disabled = !chosen.length && !declined.length;
    run.textContent = parts.length ? `Применить: ${parts.join(', ')}`
        : declined.length ? 'Применить: только запомнить ответы' : 'Отметьте хотя бы одно действие';
}

$('pv-run').addEventListener('click', async () => {
    // Все отмеченные строки, во всех вкладках. «Тихие» действия сервер выполняет сам.
    const keys = state.preview.actions.filter((a) => state.on.has(a.key)).map((a) => a.key);
    const run = $('pv-run');
    run.disabled = true;
    run.textContent = 'Применяем…';
    const { ok, data, error } = await postJSON('/api/sync', syncRequest(false, keys));
    renderResult(ok ? data : null, error);
    location.hash = '#result';
});

// ---------- Результат ----------

const RESULT_KINDS = [
    ['created', 'создано'], ['updated', 'обновлено'], ['deleted', 'удалено'], ['linked', 'связано'],
];

function totalsText(t) {
    const parts = RESULT_KINDS.filter(([k]) => t[k]).map(([k, word]) => `${word}\u00a0${t[k]}`);
    if (!parts.length) return 'Ничего не изменено';
    const s = parts.join(' · ');
    return s[0].toUpperCase() + s.slice(1);
}

function renderResult(data, error) {
    const body = $('rs-body');
    if (!data) {
        fill(body, el('div', { class: 'hero' },
            el('div', { class: 'hero-ic' }, icon('alert')),
            el('h1', {}, 'Синхронизация не выполнена'),
            el('p', { class: 'muted' }, error)));
        return;
    }
    const pairs = Object.entries(data).filter(([k, v]) => k.includes('_to_') && v && typeof v === 'object')
        .map(([k, v]) => {
            const [from, to] = k.split('_to_');
            const p = { from, to, errors: v.errors || [] };
            RESULT_KINDS.forEach(([c]) => { p[c] = v[c] || 0; });
            return p;
        })
        .filter((p) => SYS[p.from] && SYS[p.to]);
    const totals = {};
    RESULT_KINDS.forEach(([c]) => { totals[c] = pairs.reduce((n, p) => n + p[c], 0); });
    const errors = pairs.flatMap((p) => p.errors.map((m) => `${SYS[p.from].name} → ${SYS[p.to].name}: ${m}`));
    save(STORE.last, {
        at: new Date().toISOString(), ...totals, errors: errors.length,
        summary: pairs.filter((p) => RESULT_KINDS.some(([c]) => p[c]))
            .map((p) => `${SYS[p.from].mono}→${SYS[p.to].mono}: ${RESULT_KINDS.filter(([c]) => p[c]).map(([c, w]) => `${w} ${p[c]}`).join(', ')}`).join(' · '),
    });

    fill(body,
        el('div', { class: 'hero' },
            el('div', { class: `hero-ic ${errors.length ? '' : 'ok'}` }, icon(errors.length ? 'alert' : 'check')),
            el('h1', {}, errors.length ? 'Почти готово' : 'Готово'),
            el('p', { class: 'muted' }, totalsText(totals))),
        pairs.length ? el('ul', { class: 'card list' }, ...pairs.map((p) => el('li', {},
            el('span', { class: 'route-badges' }, badge(p.from, true), icon('arrow'), badge(p.to, true)),
            el('span', { class: 'grow' },
                el('div', { class: 'strong' }, `${SYS[p.from].name} → ${SYS[p.to].name}`),
                el('div', { class: 'muted small' }, totalsText(p).toLowerCase(),
                    p.errors.length ? el('span', { class: 'err-text' }, ` · ошибок ${p.errors.length}`) : null))))) : null,
        errors.length ? el('section', { class: 'card pad errors' },
            el('h2', {}, `Не выполнено: ${errors.length}`),
            el('ul', {}, ...errors.map((m) => el('li', {}, m))),
            el('p', { class: 'hint left' }, 'Запустите проверку ещё раз — невыполненные действия снова появятся в списке.')) : null,
        el('p', { class: 'hint' }, 'Копии помечены [GC], [LN] или [MT]. Если удалить копию, при следующей проверке спросим, удалять ли оригинал.'));
}

// ---------- Старт ----------

window.addEventListener('hashchange', showView);
showView();
loadConfig();
