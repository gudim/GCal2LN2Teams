// Calendar Sync — web UI (Material 3)
const API_URL = '';

const SYSTEMS = {
    google: { name: 'Google', mono: 'GC' },
    domino: { name: 'Domino', mono: 'LN' },
    teams: { name: 'Teams', mono: 'MT' },
};
const PAIR_ORDER = [
    'google_to_domino', 'domino_to_google',
    'google_to_teams', 'teams_to_google',
    'domino_to_teams', 'teams_to_domino',
];

const $ = (id) => document.getElementById(id);

function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) {
        if (key === 'class') node.className = value;
        else node.setAttribute(key, value);
    }
    for (const child of children) {
        if (child == null) continue;
        node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
}

function icon(name, extraClass = '') {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', `ic ${extraClass}`.trim());
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#i-${name}`);
    svg.append(use);
    return svg;
}

async function request(url, options = {}) {
    const response = await fetch(`${API_URL}${url}`, options);
    let data = {};
    try { data = await response.json(); } catch (e) { /* пустой ответ */ }
    const ok = response.ok && data.status !== 'error';
    return { ok, data, error: data.message || data.detail || `HTTP ${response.status}` };
}

function postJSON(url, body) {
    return request(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
}

// ---------- Navigation ----------

const VIEWS = ['settings', 'sync', 'logs'];

function showView() {
    const name = VIEWS.includes(location.hash.slice(1)) ? location.hash.slice(1) : 'settings';
    for (const view of VIEWS) $(`view-${view}`).hidden = view !== name;
    document.querySelectorAll('.dest').forEach((link) => {
        if (link.dataset.view === name) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    });
    if (name === 'logs') loadLogs();
}

// ---------- Status notes and chips ----------

const noteTimers = {};

function showStatus(id, message, type = 'info', autoHide = true) {
    const note = $(id);
    note.textContent = message;
    note.className = `note ${type}`;
    note.hidden = false;
    clearTimeout(noteTimers[id]);
    if (autoHide && type !== 'error') {
        noteTimers[id] = setTimeout(() => { note.hidden = true; }, 10000);
    }
}

function setChip(id, text, tone) {
    const chip = $(id);
    chip.className = `chip-status ${tone || ''}`.trim();
    chip.replaceChildren(icon(tone === 'bad' ? 'alert' : 'check', 'ic-sm'), text);
    chip.hidden = false;
}

// ---------- Controls ----------

function isOn(button) {
    return button.getAttribute('aria-checked') === 'true' || button.getAttribute('aria-pressed') === 'true';
}

function setOn(button, on) {
    button.setAttribute(button.getAttribute('role') === 'switch' ? 'aria-checked' : 'aria-pressed', on ? 'true' : 'false');
}

document.querySelectorAll('[role="switch"]').forEach((sw) => {
    sw.addEventListener('click', () => { setOn(sw, !isOn(sw)); sw.dispatchEvent(new Event('change')); });
});

document.querySelectorAll('[data-reveal]').forEach((button) => {
    button.addEventListener('click', () => {
        const input = $(button.dataset.reveal);
        const show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        button.setAttribute('aria-pressed', show ? 'true' : 'false');
    });
});

// ---------- Settings ----------

async function loadServerStatus() {
    const chip = $('server-chip');
    try {
        const { ok, data } = await request('/api/status');
        chip.className = `server-chip ${ok ? 'ok' : 'down'}`;
        $('server-chip-text').textContent = ok ? `Сервер работает · v${data.version}` : 'Сервер не отвечает';
    } catch (e) {
        chip.className = 'server-chip down';
        $('server-chip-text').textContent = 'Сервер не отвечает';
    }
}

async function loadConfig() {
    try {
        const { data: config } = await request('/api/config');

        if (config.google) {
            $('google-credentials').value = config.google.credentials_file || '';
            $('google-calendar-id').value = config.google.calendar_id || 'primary';
            setChip('google-chip', config.google.has_token ? 'Вход выполнен' : 'Нужен вход', config.google.has_token ? '' : 'bad');
        }
        if (config.domino) {
            $('domino-server').value = config.domino.server_url || '';
            $('domino-username').value = config.domino.username || '';
            setOn($('domino-verify-ssl'), !!config.domino.verify_ssl);
            updateSslWarning();
            if (!config.domino.verify_ssl) $('domino-advanced').open = true;
            const configured = config.domino.server_url && config.domino.has_password;
            setChip('domino-chip', configured ? 'Настроен' : 'Не настроен', configured ? '' : 'bad');
            $('domino-password').required = !config.domino.has_password;
            if (config.domino.has_password) {
                $('domino-password').placeholder = '••••••••';
                $('domino-password-support').textContent = 'Пароль сохранён. Оставьте поле пустым, чтобы его не менять';
            }
        }
        if (config.teams) {
            $('teams-client-id').value = config.teams.client_id || '';
            $('teams-client-secret').required = !config.teams.has_secret;
            if (config.teams.has_secret) $('teams-client-secret').placeholder = '••••••••';
            setChip('teams-chip', config.teams.has_token ? 'Вход выполнен' : 'Нужен вход', config.teams.has_token ? '' : 'bad');
        }
    } catch (error) {
        console.error('Error loading config:', error);
    }
}

function updateSslWarning() {
    $('ssl-warning').hidden = isOn($('domino-verify-ssl'));
}
$('domino-verify-ssl').addEventListener('change', updateSslWarning);

function bindSave(formId, statusId, url, collect) {
    $(formId).addEventListener('submit', async (e) => {
        e.preventDefault();
        try {
            const { ok, data, error } = await postJSON(url, collect());
            showStatus(statusId, ok ? data.message : `Ошибка сохранения: ${error}`, ok ? 'success' : 'error');
            if (ok) {
                $(formId).querySelectorAll('input[type="password"]').forEach((input) => { input.value = ''; });
                loadConfig();
            }
        } catch (error) {
            showStatus(statusId, `Ошибка сохранения: ${error.message}`, 'error');
        }
    });
}

bindSave('google-form', 'google-status', '/api/config/google', () => ({
    credentials_file: $('google-credentials').value,
    calendar_id: $('google-calendar-id').value,
}));

bindSave('domino-form', 'domino-status', '/api/config/domino', () => ({
    server_url: $('domino-server').value,
    username: $('domino-username').value,
    password: $('domino-password').value,
    verify_ssl: isOn($('domino-verify-ssl')),
}));

bindSave('teams-form', 'teams-status', '/api/config/teams', () => ({
    client_id: $('teams-client-id').value,
    client_secret: $('teams-client-secret').value,
}));

async function runTest(name, url, describe, body) {
    showStatus(`${name}-status`, 'Проверяем подключение…', 'info', false);
    try {
        const { ok, data, error } = await postJSON(url, body);
        if (ok) {
            showStatus(`${name}-status`, `${data.message}\n${describe(data.info || {})}`, 'success');
            setChip(`${name}-chip`, 'Подключено', 'ok');
        } else {
            const hint = /\b401\b|Unauthorized/.test(error)
                ? '\n\nСервер отклонил имя пользователя или пароль. Введите текущий пароль и снова нажмите «Проверить».' +
                  (name === 'domino' ? '\nЕсли не помогло, попробуйте другой формат имени: «Имя Фамилия/ORG», e-mail или короткое имя.' : '')
                : '';
            showStatus(`${name}-status`, error + hint, 'error');
            setChip(`${name}-chip`, 'Ошибка подключения', 'bad');
        }
    } catch (error) {
        showStatus(`${name}-status`, `Ошибка подключения: ${error.message}`, 'error');
    }
}

function testGoogle() {
    return runTest('google', '/api/test/google', (i) => `Календарь: ${i.calendar_name}\nЧасовой пояс: ${i.timezone}`);
}

function testDomino() {
    // Проверяем то, что сейчас в форме: новый пароль можно проверить до сохранения
    return runTest('domino', '/api/test/domino', (i) => `Пользователь: ${i.username}\nСервер: ${i.server}`, {
        server_url: $('domino-server').value || null,
        username: $('domino-username').value || null,
        password: $('domino-password').value || null,
        verify_ssl: isOn($('domino-verify-ssl')),
    });
}

function testTeams() {
    return runTest('teams', '/api/test/teams', (i) => `Пользователь: ${i.user}\nИмя: ${i.display_name}\nКалендарь: ${i.calendar_name}`);
}

async function reauthorizeGoogle() {
    if (!confirm('Удалить токен Google и авторизоваться заново?')) return;
    try {
        const { ok, error } = await postJSON('/api/google/reauth');
        showStatus('google-status', ok ? 'Вход в Google сброшен. Нажмите «Проверить», чтобы войти заново.' : error, ok ? 'info' : 'error', false);
        if (ok) setChip('google-chip', 'Нужен вход', 'bad');
    } catch (error) {
        showStatus('google-status', `Ошибка: ${error.message}`, 'error');
    }
}

async function authorizeTeams() {
    showStatus('teams-status', 'Получаем ссылку для входа…', 'info', false);
    try {
        const { ok, data, error } = await request('/api/teams/authorize');
        if (ok && data.authorization_url) {
            window.open(data.authorization_url, 'teams_auth', 'width=600,height=700');
            showStatus('teams-status',
                'Войдите в учётную запись Microsoft в открывшемся окне и разрешите доступ к календарю.\n' +
                'Затем нажмите «Проверить».', 'info', false);
        } else {
            showStatus('teams-status', `Ошибка: ${error}`, 'error');
        }
    } catch (error) {
        showStatus('teams-status', `Ошибка: ${error.message}`, 'error');
    }
}

// ---------- Sync ----------

const syncSources = ['google', 'domino', 'teams'];
const LAST_RESULT_KEY = 'calendar-sync:last-result';

function formatDay(date) {
    return date.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' });
}

function syncParams() {
    return {
        back: Math.max(0, Math.min(365, parseInt($('sync-days-back').value, 10) || 0)),
        fwd: Math.max(1, Math.min(365, parseInt($('sync-days-forward').value, 10) || 1)),
    };
}

function updateSyncForm() {
    const { back, fwd } = syncParams();
    const now = Date.now();
    $('period-text').textContent = `${formatDay(new Date(now - back * 864e5))} – ${formatDay(new Date(now + fwd * 864e5))}`;
    $('period-presets').querySelectorAll('.seg').forEach((b) => setOn(b, +b.dataset.back === back && +b.dataset.fwd === fwd));

    const enabled = syncSources.filter((s) => isOn($(`enable-${s}`))).length;
    $('src-error').hidden = enabled >= 2;
    $('src-hint').hidden = enabled < 2;

    const running = $('sync-form').dataset.running === 'true';
    $('sync-run').disabled = running || enabled < 2;
    $('sync-preview').disabled = running || enabled < 2;
    $('sync-run-label').textContent = running ? 'Выполняется…' : 'Синхронизировать';
}

syncSources.forEach((s) => {
    $(`enable-${s}`).addEventListener('click', () => { setOn($(`enable-${s}`), !isOn($(`enable-${s}`))); updateSyncForm(); });
});
['sync-days-back', 'sync-days-forward'].forEach((id) => $(id).addEventListener('input', updateSyncForm));
$('period-presets').addEventListener('click', (e) => {
    const preset = e.target.closest('.seg');
    if (!preset) return;
    $('sync-days-back').value = preset.dataset.back;
    $('sync-days-forward').value = preset.dataset.fwd;
    updateSyncForm();
});

function renderRunning() {
    $('res-sub').textContent = 'Выполняется…';
    $('sync-results').replaceChildren(
        el('div', { class: 'progress', role: 'progressbar', 'aria-label': 'Синхронизация выполняется' }, el('div')),
        el('div', { class: 'support-inline' }, 'Читаем календари и сверяем копии…'),
    );
}

function renderError(message) {
    $('res-sub').textContent = 'Синхронизация не выполнена';
    const blocks = [el('div', { class: 'note error' }, message)];
    if (/авторизац|credentials|настройки|не заполнены|токен/i.test(message)) {
        blocks.push(el('div', { class: 'results-actions' },
            el('a', { class: 'btn btn-tonal sl', href: '#settings' }, 'Открыть настройки')));
    }
    $('sync-results').replaceChildren(...blocks);
}

function renderResults(result, at = new Date()) {
    const sameDay = at.toDateString() === new Date().toDateString();
    const when = sameDay
        ? `Сегодня, ${at.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}`
        : at.toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
    const win = result.sync_window || {};
    $('res-sub').textContent = `${when} · ${win.start || '?'} – ${win.end || '?'}`;

    const pairs = PAIR_ORDER.filter((key) => result[key]).map((key) => {
        const [from, to] = key.split('_to_');
        return { from, to, created: result[key].created || 0, errors: result[key].errors || [] };
    });
    const created = pairs.reduce((n, p) => n + p.created, 0);
    const errors = pairs.reduce((n, p) => n + p.errors.length, 0);

    const blocks = [];
    if (result.dry_run) {
        blocks.push(el('div', { class: 'note info' }, 'Предпросмотр: в календарях ничего не изменено. Ниже — что будет создано.'));
    }
    blocks.push(el('div', { class: 'tiles' },
        el('div', { class: 'tile' },
            el('div', { class: 'tile-num' }, created),
            el('div', { class: 'tile-label' }, result.dry_run ? 'Будет создано' : 'Создано событий')),
        el('div', { class: `tile ${errors ? 'bad' : ''}` },
            el('div', { class: 'tile-num' }, errors),
            el('div', { class: 'tile-label' }, 'Ошибок')),
    ));

    if (pairs.length === 0) {
        blocks.push(el('div', { class: 'support-inline' }, 'Копировать нечего: все события уже синхронизированы.'));
    } else {
        blocks.push(el('ul', { class: 'pairs' }, ...pairs.map((p) => el('li', { class: `pair ${p.errors.length ? 'bad' : ''}` },
            el('div', { class: 'pair-main' },
                el('span', { class: `mono-sm mono-${p.from}`, 'aria-hidden': 'true' }, SYSTEMS[p.from].mono),
                icon('arrow', 'pair-arrow'),
                el('span', { class: `mono-sm mono-${p.to}`, 'aria-hidden': 'true' }, SYSTEMS[p.to].mono),
                el('span', { class: 'pair-label' }, `${SYSTEMS[p.from].name} → ${SYSTEMS[p.to].name}`),
                el('span', { class: 'pair-count' }, p.created)),
            p.errors.length
                ? el('ul', { class: 'pair-errors' }, ...p.errors.map((msg) => el('li', {}, icon('alert', 'ic-sm'), el('span', {}, msg))))
                : null,
        ))));
    }
    if (result.dry_run && created > 0 && result.request) {
        const createButton = el('button', { type: 'button', class: 'btn btn-filled btn-lg sl' }, icon('sync'), `Создать ${created} ${plural(created, 'событие', 'события', 'событий')}`);
        createButton.addEventListener('click', () => runSync({ ...result.request, dry_run: false }));
        blocks.push(el('div', { class: 'results-actions' }, createButton));
    }
    $('sync-results').replaceChildren(...blocks);
}

function plural(n, one, few, many) {
    const m10 = n % 10, m100 = n % 100;
    if (m10 === 1 && m100 !== 11) return one;
    if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
    return many;
}

function saveLastResult(result) {
    try { localStorage.setItem(LAST_RESULT_KEY, JSON.stringify({ at: new Date().toISOString(), result })); } catch (e) { /* хранилище недоступно */ }
}

function restoreLastResult() {
    try {
        const saved = JSON.parse(localStorage.getItem(LAST_RESULT_KEY) || 'null');
        if (saved && saved.result) renderResults(saved.result, new Date(saved.at));
    } catch (e) { /* хранилище недоступно или повреждено */ }
}

async function runSync(body) {
    const form = $('sync-form');
    form.dataset.running = 'true';
    updateSyncForm();
    renderRunning();
    if (window.matchMedia('(max-width: 839px)').matches) {
        $('results-card').scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
    try {
        const { ok, data, error } = await postJSON('/api/sync', body);
        if (ok) {
            const result = { ...data, request: body };
            renderResults(result);
            saveLastResult(result);
        } else {
            renderError(`Ошибка: ${error}`);
        }
    } catch (error) {
        renderError(`Ошибка запуска: ${error.message}`);
    } finally {
        form.dataset.running = 'false';
        updateSyncForm();
    }
}

function syncRequest(dryRun) {
    const { back, fwd } = syncParams();
    return {
        direction: 'all',
        days_back: back,
        days_forward: fwd,
        dry_run: dryRun,
        enable_google: isOn($('enable-google')),
        enable_domino: isOn($('enable-domino')),
        enable_teams: isOn($('enable-teams')),
    };
}

$('sync-form').addEventListener('submit', (e) => {
    e.preventDefault();
    runSync(syncRequest(false));
});
$('sync-preview').addEventListener('click', () => runSync(syncRequest(true)));

// ---------- Logs ----------

const LOG_LINE = /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) - (\S+) - (DEBUG|INFO|WARNING|ERROR|CRITICAL) - (.*)$/;
let logEntries = [];
let logLevel = 'all';

function parseLogs(lines) {
    const entries = [];
    for (const line of lines) {
        const m = LOG_LINE.exec(line);
        if (m) {
            entries.push({ time: m[1], logger: m[2], level: m[3], msg: m[4] });
        } else if (entries.length) {
            entries[entries.length - 1].msg += `\n${line}`;  // traceback и многострочные сообщения
        } else {
            entries.push({ time: '', logger: '', level: 'INFO', msg: line });
        }
    }
    return entries;
}

function renderLogs() {
    const container = $('logs-container');
    const keep = (level) => logLevel === 'all'
        || (logLevel === 'warn' ? level !== 'INFO' && level !== 'DEBUG' : level === 'ERROR' || level === 'CRITICAL');
    const shown = logEntries.filter((e) => keep(e.level));
    if (!shown.length) {
        container.replaceChildren(el('p', { class: 'support-inline' }, logEntries.length ? 'Нет записей этого уровня' : 'Логи пусты'));
        return;
    }
    container.replaceChildren(el('ol', { class: 'log-list' }, ...shown.map((e) => {
        const tone = e.level === 'ERROR' || e.level === 'CRITICAL' ? 'error' : e.level === 'WARNING' ? 'warning' : '';
        return el('li', { class: `log ${tone}` },
            el('span', { class: 'log-time' }, e.time),
            el('span', { class: `badge ${tone}` }, e.level),
            el('span', { class: 'log-logger' }, e.logger),
            el('span', { class: 'log-msg' }, e.msg));
    })));
    container.scrollTop = container.scrollHeight;
}

async function loadLogs() {
    const container = $('logs-container');
    container.replaceChildren(el('p', { class: 'support-inline' }, 'Загрузка логов…'));
    try {
        const { data } = await request('/api/logs');
        logEntries = parseLogs(data.logs || []);
        renderLogs();
    } catch (error) {
        container.replaceChildren(el('p', { class: 'note error' }, `Ошибка загрузки логов: ${error.message}`));
    }
}

$('log-filters').addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (!chip) return;
    logLevel = chip.dataset.level;
    $('log-filters').querySelectorAll('.chip').forEach((c) => setOn(c, c === chip));
    renderLogs();
});

// ---------- Start ----------

window.addEventListener('hashchange', showView);
window.addEventListener('DOMContentLoaded', () => {
    showView();
    updateSyncForm();
    restoreLastResult();
    loadServerStatus();
    loadConfig();
});
