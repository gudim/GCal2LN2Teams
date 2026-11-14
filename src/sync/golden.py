"""
Эталонные планы: сценарии, по которым Kotlin-движок должен выдавать то же, что Python.

Каждый сценарий — состояние календарей и связей перед запуском. run_input() читает вход
только из JSON (как это будет делать Kotlin), прогоняет collect -> plan -> select -> apply
на календарях в памяти и ещё раз collect -> plan на результате. Формат — spec/README.md,
файлы — spec/golden/<сценарий>.json, генератор — scripts/export_golden_plans.py.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field, replace
from datetime import date, datetime, timedelta, timezone
from typing import Any, Collection, Dict, Iterable, List, Optional, Sequence, Tuple
from zoneinfo import ZoneInfo

from .calendar import InMemoryCalendar
from .event import CalendarEvent, DateRange, System
from .planner import DIRECTIONS, ORDER, Link, apply, collect, plan, select
from .serialize import (
    Json, event_from_json, event_to_json, link_from_json, links_to_json, plan_to_json,
    window_from_json, window_to_json,
)

G, D, T = System.GOOGLE, System.DOMINO, System.TEAMS
UTC = timezone.utc
WINDOW = DateRange(datetime(2026, 1, 1, tzinfo=UTC), datetime(2027, 1, 1, tzinfo=UTC))
AT = datetime(2026, 11, 13, 10, tzinfo=UTC)
FAILURE = "сбой"


# --- Календарь и хранилище связей для прогона ---

class GoldenCalendar(InMemoryCalendar):
    """InMemoryCalendar, в котором id новой копии назначает календарь, как настоящий сервер.

    create: id = "<система>-new-<n>", n — номер вызова create в этом календаре (с 1);
    остальные поля события сохраняются как есть. failures — (операция, название), на которых
    create/update/delete падают с ошибкой "сбой".
    """

    def __init__(
        self, system: System, events: Iterable[CalendarEvent] = (),
        failures: Collection[Tuple[str, str]] = (),
    ) -> None:
        super().__init__(system)
        self.events = list(events)
        self.failures = set(failures)
        self.created = 0

    def _check(self, operation: str, title: str) -> None:
        if (operation, title) in self.failures:
            raise RuntimeError(FAILURE)

    def create(self, event: CalendarEvent) -> str:
        self._check("create", event.title)
        self.created += 1
        event_id = f"{self.system.value}-new-{self.created}"
        self.events.append(replace(event, id=event_id))
        return event_id

    def update(self, event_id: str, event: CalendarEvent) -> None:
        self._check("update", event.title)
        super().update(event_id, event)

    def delete(self, event_id: str) -> None:
        current = self.get(event_id)
        if current is not None:
            self._check("delete", current.title)
        super().delete(event_id)


class MemoryLinkStore:
    """Связи по ключу Link.key(): save заменяет связь с тем же ключом, drop удаляет."""

    def __init__(self, links: Iterable[Link] = ()) -> None:
        self.rows = {link.key(): link for link in links}

    def save(self, link: Link) -> None:
        self.rows[link.key()] = link

    def drop(self, link: Link) -> None:
        self.rows.pop(link.key(), None)

    @property
    def links(self) -> List[Link]:
        return list(self.rows.values())


# --- Сценарий -> вход -> ожидаемый результат ---

@dataclass
class Scenario:
    name: str
    description: str
    direction: str
    calendars: Dict[System, List[CalendarEvent]]
    links: Sequence[Link] = ()
    keys: Optional[Sequence[str]] = None  # ключи предпросмотра; None = выбор по умолчанию
    routes: Optional[Sequence[Tuple[System, System]]] = None
    failures: Sequence[Tuple[System, str, str]] = ()  # (система, операция, название)
    window: DateRange = field(default=WINDOW)
    # Системы, не прочитанные из-за сбоя; их календарей во входе нет
    unavailable: Sequence[System] = ()


def _by_id(events: Iterable[CalendarEvent]) -> List[CalendarEvent]:
    return sorted(events, key=lambda e: e.id or "")


def _calendars_json(calendars: Dict[System, List[CalendarEvent]]) -> Json:
    return {s.value: [event_to_json(e) for e in _by_id(events)] for s, events in calendars.items()}


def scenario_input(s: Scenario) -> Json:
    pairs = sorted(DIRECTIONS[s.direction], key=lambda p: sorted(ORDER.index(x) for x in p))
    assert not set(s.unavailable) & set(s.calendars), f"{s.name}: недоступный календарь прочитан"
    data: Json = {
        "window": window_to_json(s.window),
        "direction": s.direction,
        "pairs": [[x.value for x in sorted(p, key=ORDER.index)] for p in pairs],
        "calendars": _calendars_json(s.calendars),
        "links": links_to_json(list(s.links)),
        "select": {
            "keys": sorted(s.keys) if s.keys is not None else None,
            "routes": sorted([o.value, t.value] for o, t in s.routes) if s.routes is not None
            else None,
        },
        "failures": sorted(
            ({"system": sys.value, "operation": op, "title": title}
             for sys, op, title in s.failures),
            key=lambda f: (f["system"], f["operation"], f["title"]),
        ),
    }
    if s.unavailable:  # поле необязательное: старые файлы остаются прежними
        data["unavailable"] = [x.value for x in ORDER if x in s.unavailable]
    return data


def _load_calendars(data: Json, failures: Sequence[Json] = ()) -> Dict[System, GoldenCalendar]:
    """Календари в порядке ORDER (google, domino, teams): от него зависит порядок действий."""
    calendars = {}
    for system in ORDER:
        if system.value in data:
            fails = [(f["operation"], f["title"]) for f in failures if f["system"] == system.value]
            events = [event_from_json(e) for e in data[system.value]]
            calendars[system] = GoldenCalendar(system, events, fails)
    return calendars


def _collect_json(snapshot: Dict[System, List[CalendarEvent]], found: Dict[Any, Any]) -> Json:
    fetched = sorted(found.items(), key=lambda kv: (kv[0][0].value, kv[0][1]))
    return {
        "listed": {s.value: [e.id for e in events] for s, events in snapshot.items()},
        "fetched": [
            {"system": s.value, "id": event_id,
             "event": event_to_json(event) if event is not None else None}
            for (s, event_id), event in fetched
        ],
    }


def run_input(data: Json) -> Json:
    """Прогнать сценарий, зная только его JSON-вход."""
    window = window_from_json(data["window"])
    pairs = [frozenset(System(x) for x in p) for p in data["pairs"]]
    calendars = _load_calendars(data["calendars"], data["failures"])
    links = [link_from_json(link) for link in data["links"]]
    unavailable = frozenset(System(x) for x in data.get("unavailable", []))

    snapshot, found = collect(calendars, window, links)  # type: ignore[arg-type]
    result = plan(snapshot, pairs, links, found, unavailable)
    keys = data["select"]["keys"]
    routes = data["select"]["routes"]
    chosen = select(
        result.actions,
        {(System(o), System(t)) for o, t in routes} if routes is not None else None,
        keys,
        result.refused,
    )
    store = MemoryLinkStore(links)
    report = apply(chosen, calendars, store)  # type: ignore[arg-type]

    after = {s.value: c.events for s, c in calendars.items()}
    after_json = {s: [event_to_json(e) for e in _by_id(events)] for s, events in after.items()}
    after_links = links_to_json(store.links)

    # Повторный запуск на результате: обычно пустой план (нет эха)
    again = _load_calendars(after_json)
    again_links = [link_from_json(link) for link in after_links]
    snapshot2, found2 = collect(again, window, again_links)  # type: ignore[arg-type]
    replan = plan(snapshot2, pairs, again_links, found2, unavailable)

    return {
        "collect": _collect_json(snapshot, found),
        "plan": plan_to_json(result),
        "select": [a.key for a in chosen],
        "apply": {
            "report": [
                {"origin": o.value, "target": t.value, "created": r.created, "updated": r.updated,
                 "deleted": r.deleted, "linked": r.linked, "errors": list(r.errors)}
                for (o, t), r in sorted(report.pairs.items(), key=lambda kv: (kv[0][0].value,
                                                                              kv[0][1].value))
                if r.created or r.updated or r.deleted or r.linked or r.errors
            ],
            "calendars": after_json,
            "links": after_links,
        },
        "replan": plan_to_json(replan),
    }


def build(s: Scenario) -> Json:
    data = scenario_input(s)
    return {
        "name": s.name,
        "description": s.description,
        "input": data,
        "expected": run_input(json.loads(json.dumps(data))),
    }


def dumps(doc: Json) -> str:
    return json.dumps(doc, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


# --- Строители событий ---

def ev(
    event_id: str, title: str, start: datetime = AT, hours: float = 1, **kw: Any
) -> CalendarEvent:
    end = start + timedelta(hours=hours)
    return CalendarEvent(title=title, start=start, end=end, id=event_id, **kw)


def day(event_id: str, title: str, start: date, days: int = 1, **kw: Any) -> CalendarEvent:
    end = start + timedelta(days=days)
    return CalendarEvent(title=title, start=start, end=end, id=event_id, **kw)


def copy_of(
    origin: System, original: CalendarEvent, target: System, copy_id: str
) -> Tuple[CalendarEvent, Link]:
    """Копия после прошлой синхронизации и связь со снимком обеих сторон."""
    copy = CalendarEvent(
        title=original.title, start=original.start, end=original.end, location=original.location,
        description=original.description, source=origin, id=copy_id, private=original.private,
    )
    assert original.id is not None
    link = Link(origin, original.id, target, copy_id,
                source_fields=original.fields(), target_fields=copy.fields())
    return copy, link


def twin_link(a: System, ea: CalendarEvent, b: System, eb: CalendarEvent) -> Link:
    assert ea.id is not None and eb.id is not None
    return Link(a, ea.id, b, eb.id, "twin", ea.fields(), eb.fields())


def moved(event: CalendarEvent, delta: timedelta, **kw: Any) -> CalendarEvent:
    start, end = event.start + delta, event.end + delta  # type: ignore[operator]
    return replace(event, start=start, end=end, **kw)


# --- Сценарии ---

def _scenarios() -> List[Scenario]:
    s: List[Scenario] = []
    add = s.append

    # Создание
    add(Scenario(
        "create_all_directions",
        "Оригинал в каждой системе копируется в две другие: шесть маршрутов. "
        "Частное событие копируется со всеми полями.",
        "all",
        {G: [ev("g1", "Планёрка", location="Комната 1", description="Повестка\nпункт 1",
                private=True)],
         D: [ev("d1", "Обед с командой", AT + timedelta(hours=2))],
         T: [ev("t1", "Ретро", AT + timedelta(hours=4))]},
    ))
    add(Scenario(
        "direction_limits_pairs",
        "Направление google-teams: Domino не участвует, хотя его календарь прочитан. Все три "
        "события в одно время: у Google и Teams вопрос по времени без галочки (без ключей "
        "ничего не копируется), у Domino — нет.",
        "google-teams",
        {G: [ev("g1", "G")], D: [ev("d1", "D")], T: [ev("t1", "T")]},
    ))
    add(Scenario(
        "copies_are_not_copied_further",
        "Копия ([GC] в Lotus) не копируется дальше в Teams.",
        "domino-teams",
        {D: [ev("d1", "Из Google", source=G)], T: []},
    ))

    # Уже скопировано
    review = ev("g1", "Обзор")
    review_copy, review_link = copy_of(G, review, D, "d1")
    add(Scenario(
        "already_linked",
        "Связь со снимком, обе стороны не менялись: ничего не делать.",
        "google-domino",
        {G: [review], D: [review_copy]},
        [review_link],
    ))
    add(Scenario(
        "old_link_without_snapshot",
        "Старая связь без снимка: копия не создаётся заново и не обновляется, "
        "связь получает снимок текущих полей (служебное действие link).",
        "google-domino",
        {G: [review], D: [moved(review_copy, timedelta(hours=5), location="X")]},
        [review_link._replace(source_fields=None, target_fields=None)],
    ))
    late = ev("g1", "Релиз", datetime(2026, 11, 13, 22, tzinfo=UTC))
    add(Scenario(
        "legacy_match_by_marker",
        "Копии без связи (сделаны до появления связей) узнаются по source + название + начало "
        "(в UTC, 01:00 МСК = 22:00 UTC). Ни копий, ни связей не создаётся.",
        "google-domino",
        {G: [late, ev("g-copy", "Созвон", source=D)],
         D: [ev("d-copy", "Релиз", late.start, source=G), ev("d1", "Созвон")]},
    ))
    add(Scenario(
        "legacy_match_needs_same_start",
        "Копия без связи с другим началом не считается копией: оригинал копируется.",
        "google-domino",
        {G: [ev("g1", "Демо")], D: [ev("d-copy", "Демо", AT + timedelta(hours=1), source=G)]},
    ))

    # Обновления
    add(Scenario(
        "update_original_change",
        "Изменили название оригинала: обновляется копия, затем снимок связи.",
        "google-domino",
        {G: [replace(review, title="Обзор v2")], D: [review_copy]},
        [review_link],
    ))
    review_copy_t, review_link_t = copy_of(G, review, T, "t1")
    add(Scenario(
        "update_with_old_link_listed_first",
        "У оригинала две связи: старая без снимка (идёт первой) и со снимком. Изменили "
        "название оригинала: изменение видно по снимку второй связи, обе копии обновляются, "
        "старая связь получает снимок.",
        "all",
        {G: [replace(review, title="Обзор v2")], D: [review_copy], T: [review_copy_t]},
        [review_link._replace(source_fields=None, target_fields=None), review_link_t],
    ))
    add(Scenario(
        "update_copy_change_to_original",
        "Изменили место в копии: правка уходит в оригинал без участников.",
        "google-domino",
        {G: [review], D: [replace(review_copy, location="Комната 5")]},
        [review_link],
    ))
    add(Scenario(
        "update_merge_different_fields",
        "Разные поля изменены с двух сторон: оба изменения сливаются в обе стороны.",
        "google-domino",
        {G: [replace(review, title="Обзор v2")], D: [replace(review_copy, location="Комната 5")]},
        [review_link],
    ))
    add(Scenario(
        "conflict_later_modified_wins",
        "Одно поле изменено с двух сторон: побеждает более позднее modified.",
        "google-domino",
        {G: [replace(review, title="Из Google", modified=AT)],
         D: [replace(review_copy, title="Из Lotus", modified=AT + timedelta(minutes=1))]},
        [review_link],
    ))
    add(Scenario(
        "conflict_tie_goes_to_original",
        "Одно поле изменено с двух сторон, у одной стороны нет modified: побеждает оригинал.",
        "google-domino",
        {G: [replace(review, title="G2")], D: [replace(review_copy, title="D2", modified=AT)]},
        [review_link],
    ))
    meeting = replace(review, has_attendees=True)
    add(Scenario(
        "update_original_with_attendees",
        "Правка копии должна уйти в оригинал с участниками: вопрос без галочки "
        "«участники получат письмо». Без ключей не выполняется и задаётся снова.",
        "google-domino",
        {G: [meeting], D: [replace(review_copy, location="Комната 5")]},
        [review_link],
    ))
    add(Scenario(
        "update_original_with_attendees_confirmed",
        "То же, но пользователь отметил обновление в предпросмотре.",
        "google-domino",
        {G: [meeting], D: [replace(review_copy, location="Комната 5")]},
        [review_link],
        keys=["update:google:g1"],
    ))
    invite = replace(review, organizer_is_me=False)
    add(Scenario(
        "foreign_meeting_copy_change_ignored",
        "Чужая встреча не меняется: правка копии только попадает в снимок связи.",
        "google-domino",
        {G: [invite], D: [replace(review_copy, location="Комната 5")]},
        [review_link],
    ))
    add(Scenario(
        "foreign_meeting_change_wins",
        "Чужая встреча и копия изменены: изменения берутся только из чужой встречи, "
        "даже если копия изменена позже.",
        "google-domino",
        {G: [replace(invite, title="Перенесено", modified=AT)],
         D: [replace(review_copy, title="Моя правка", modified=AT + timedelta(hours=1))]},
        [review_link],
    ))
    teams_copy, teams_link = copy_of(G, review, T, "t1")
    add(Scenario(
        "copy_change_propagates_to_other_copies",
        "Копию в Lotus перенесли: обновляется оригинал, а после него — копия в Teams.",
        "all",
        {G: [review], D: [moved(review_copy, timedelta(hours=2))], T: [teams_copy]},
        [review_link, teams_link],
    ))
    add(Scenario(
        "event_moved_out_of_window",
        "Оригинал перенесли за окно: get() его находит, копия обновляется, а не удаляется.",
        "google-domino",
        {G: [moved(review, timedelta(days=400))], D: [review_copy]},
        [review_link],
    ))
    add(Scenario(
        "twins_sync_fields",
        "Двойники синхронизируют поля так же, как оригинал и копия.",
        "google-domino",
        {G: [ev("g1", "Синк", uid="U1")], D: [ev("d1", "Синк", uid="U1", location="B")]},
        [twin_link(G, ev("g1", "Синк", uid="U1"), D, ev("d1", "Синк", uid="U1", location="A"))],
    ))
    add(Scenario(
        "apply_failure_skips_dependent",
        "Обновление копии упало: снимок связи не обновляется (depends_on), "
        "в следующий раз обновление планируется снова.",
        "google-domino",
        {G: [replace(review, title="Обзор v2")], D: [review_copy]},
        [review_link],
        failures=[(D, "update", "Обзор v2")],
    ))

    # Удаления
    add(Scenario(
        "delete_copy_when_original_gone",
        "Оригинал удалён: копия удаляется без вопроса (silent), связь убирается.",
        "google-domino",
        {G: [], D: [review_copy]},
        [review_link],
    ))
    add(Scenario(
        "delete_copy_of_ignored_original",
        "Оригинал стал служебным/отклонённым (ignored): копия удаляется; "
        "ignored-событие без копии не копируется.",
        "google-domino",
        {G: [replace(review, ignored=True), ev("g2", "Отклонено", ignored=True)], D: [review_copy]},
        [review_link],
    ))
    add(Scenario(
        "copy_gone_asks_to_delete_original",
        "Копию удалили: вопрос «удалить оригинал?» без галочки. Без ключей ничего не "
        "делается, вопрос задаётся снова.",
        "google-domino",
        {G: [review], D: []},
        [review_link],
    ))
    add(Scenario(
        "copy_gone_original_delete_confirmed",
        "Копию удалили, пользователь отметил удаление оригинала.",
        "google-domino",
        {G: [review], D: []},
        [review_link],
        keys=["delete:google:g1"],
    ))
    add(Scenario(
        "copy_gone_declined_is_kept",
        "Копию удалили, пользователь не отметил удаление: связь запоминается как kept.",
        "google-domino",
        {G: [review], D: []},
        [review_link],
        keys=[],
    ))
    add(Scenario(
        "kept_link_not_recreated",
        "Связь kept: копия не воссоздаётся и вопрос не задаётся.",
        "google-domino",
        {G: [review], D: []},
        [review_link._replace(kept=True)],
    ))
    add(Scenario(
        "copy_of_foreign_original_gone_kept_silently",
        "Удалили копию чужой встречи: оригинал не трогаем, связь молча становится kept.",
        "google-domino",
        {G: [invite], D: []},
        [review_link],
    ))
    sync_g, sync_d = ev("g1", "Синк", uid="U1"), ev("d1", "Синк", uid="U1")
    inv_g = ev("g2", "Приглашение", AT + timedelta(hours=2), uid="U2", organizer_is_me=False)
    inv_d = ev("d2", "Приглашение", AT + timedelta(hours=2), uid="U2")
    add(Scenario(
        "twin_gone_asks_and_foreign_twin_never",
        "Двойники удалены в Lotus: вопрос об удалении своего двойника в Google, чужой не "
        "трогаем. Пользователь отказался: обе связи kept.",
        "google-domino",
        {G: [sync_g, inv_g], D: []},
        [twin_link(G, sync_g, D, sync_d), twin_link(G, inv_g, D, inv_d)],
        keys=[],
    ))

    def bulk(n: int, gone: int) -> Tuple[List[CalendarEvent], List[CalendarEvent], List[Link]]:
        """n копий со связями; первые gone оригиналов удалены. n <= 10: id сортируются."""
        originals, copies, links = [], [], []
        for i in range(n):
            o = ev(f"g{i}", f"Событие {i}", AT + timedelta(days=i))
            c, link = copy_of(G, o, D, f"d{i}")
            if i >= gone:
                originals.append(o)
            copies.append(c)
            links.append(link)
        return originals, copies, links

    g6, d6, l6 = bulk(6, 6)
    add(Scenario(
        "mass_delete_by_count",
        "Исчезло больше 5 оригиналов: все удаления без галочек и видимы, предупреждение.",
        "google-domino",
        {G: g6, D: d6}, l6,
    ))
    g10, d10, l10 = bulk(10, 4)
    add(Scenario(
        "mass_delete_by_share",
        "Исчезло 4 оригинала из 10 связей (40% > 30%): защита срабатывает.",
        "google-domino",
        {G: g10, D: d10}, l10,
    ))
    g10b, d10b, l10b = bulk(10, 3)
    add(Scenario(
        "mass_delete_below_threshold",
        "Исчезло ровно 30% (3 из 10): защита не срабатывает, копии удаляются молча.",
        "google-domino",
        {G: g10b, D: d10b}, l10b,
    ))
    g5, d5, l5 = bulk(5, 5)
    add(Scenario(
        "mass_delete_few_links",
        "Исчезли все 5 оригиналов, но связей меньше 10 и оригиналов не больше 5: "
        "защита не срабатывает.",
        "google-domino",
        {G: g5, D: d5}, l5,
    ))

    # Двойники
    add(Scenario(
        "twins_by_uid",
        "Один UID в двух системах: двойники связываются сразу, копии не создаются.",
        "all",
        {G: [sync_g], D: [replace(sync_d, title="Синк (Lotus)")]},
    ))
    add(Scenario(
        "twin_question_by_title_and_start",
        "Совпали название (без учёта регистра) и начало: вопрос «одна встреча?» с галочкой.",
        "google-domino",
        {G: [ev("g1", "Обед")], D: [ev("d1", "обед")]},
    ))
    add(Scenario(
        "twin_question_declined",
        "Пользователь снял галочку с вопроса о двойниках: копии в обе стороны и связь distinct.",
        "google-domino",
        {G: [ev("g1", "Обед")], D: [ev("d1", "Обед")]},
        keys=[],
    ))
    add(Scenario(
        "distinct_link_no_question",
        "Связь distinct: вопрос о двойниках не задаётся, события копируются как обычно.",
        "google-domino",
        {G: [ev("g1", "Обед")], D: [ev("d1", "Обед")]},
        [Link(G, "g1", D, "d1", "distinct")],
    ))
    add(Scenario(
        "twins_ambiguous_first_to_first",
        "Два кандидата в Google на одного в Lotus: пара — первый с первым, второй копируется.",
        "google-domino",
        {G: [ev("g1", "Обед"), ev("g2", "Обед")], D: [ev("d1", "Обед")]},
    ))
    stale_copy, stale_link = copy_of(G, sync_g, D, "d2")
    add(Scenario(
        "redundant_copy_next_to_twin",
        "Лишняя копия: в Lotus есть двойник оригинала по UID, старая копия удаляется.",
        "google-domino",
        {G: [sync_g], D: [sync_d, stale_copy]},
        [stale_link],
    ))
    plain_g, plain_d = ev("g1", "Синк"), ev("d1", "Синк")
    plain_copy, plain_link = copy_of(G, plain_g, D, "d2")
    add(Scenario(
        "redundant_copy_depends_on_twin_question",
        "Лишняя копия рядом с кандидатом в двойники по названию: удаление зависит от ответа "
        "на вопрос о двойниках.",
        "google-domino",
        {G: [plain_g], D: [plain_d, plain_copy]},
        [plain_link],
    ))
    add(Scenario(
        "twins_copied_to_third_system_once",
        "Двойники Google и Lotus копируются в Teams один раз (из Google).",
        "all",
        {G: [sync_g], D: [sync_d], T: []},
    ))
    later = AT + timedelta(days=7)
    add(Scenario(
        "recurring_instances_twin_per_instance",
        "Экземпляры серии — двойники по UID + исходному началу, даже если экземпляр перенесён.",
        "google-domino",
        {G: [ev("g1", "Еженедельная", uid="S", original_start=AT),
             ev("g2", "Еженедельная", later, uid="S", original_start=later)],
         D: [ev("d1", "Еженедельная", uid="S", original_start=AT),
             ev("d2", "Еженедельная", later + timedelta(hours=1), uid="S", original_start=later)]},
    ))

    # Перевод часов. Серия «по понедельникам в 10:00 по Берлину»: 25.10.2026 Европа переходит
    # на зимнее время, поэтому экземпляр 19.10 начинается в 08:00 UTC, а 26.10 — в 09:00 UTC.
    # Сервер-источник разворачивает серию сам; движок видит экземпляры как моменты в UTC.
    berlin = ZoneInfo("Europe/Berlin")
    before_dst = datetime(2026, 10, 19, 10, tzinfo=berlin).astimezone(UTC)
    after_dst = datetime(2026, 10, 26, 10, tzinfo=berlin).astimezone(UTC)
    weekly = [ev("g-w1", "Планёрка Берлин", before_dst, uid="W", original_start=before_dst),
              ev("g-w2", "Планёрка Берлин", after_dst, uid="W", original_start=after_dst)]
    add(Scenario(
        "series_across_dst_copied_per_instance",
        "Серия в поясе с переводом часов: каждый экземпляр копируется отдельным событием со "
        "своим моментом в UTC (08:00 UTC до перевода, 09:00 UTC после) — в обоих случаях "
        "10:00 по Берлину.",
        "google-domino",
        {G: weekly, D: []},
    ))
    weekly_copies = [copy_of(G, e, D, "d-" + str(e.id)) for e in weekly]
    add(Scenario(
        "series_across_dst_nothing_to_update",
        "Перевод часов прошёл, серия не менялась: моменты экземпляров в UTC те же, что в "
        "снимках, — ни обновлений, ни эха.",
        "google-domino",
        {G: weekly, D: [c for c, _ in weekly_copies]},
        [link for _, link in weekly_copies],
    ))
    add(Scenario(
        "update_original_date_and_time",
        "Оригинал перенесли на другой день и час: копия находится по связи (а не по названию и "
        "началу) и получает новые начало и конец.",
        "google-domino",
        {G: [moved(review, timedelta(days=1, hours=3))], D: [review_copy]},
        [review_link],
    ))
    add(Scenario(
        "update_copy_time_to_original",
        "В копии сдвинули время: у оригинала нет участников, перенос уходит в оригинал.",
        "google-domino",
        {G: [review], D: [moved(review_copy, timedelta(hours=2))]},
        [review_link],
    ))

    # Окно и события на весь день
    add(Scenario(
        "all_day_exclusive_end",
        "Весь день: конец исключён. Событие, кончающееся в день начала окна, не читается; "
        "пересекающее начало окна — читается и копируется датами.",
        "google-domino",
        {G: [day("g-holiday", "Праздник", date(2026, 11, 13)),
             day("g-old", "Старое", date(2025, 12, 31)),
             day("g-straddle", "Новый год", date(2025, 12, 31), days=2),
             ev("g-night", "Ночь", datetime(2025, 12, 31, 23, tzinfo=UTC))],
         D: []},
    ))
    old = ev("g-old", "Давно", datetime(2025, 12, 20, 10, tzinfo=UTC))
    old_day = day("g-oldday", "Давно весь день", date(2025, 12, 31))
    recent = ev("g-recent", "Недавно")
    add(Scenario(
        "links_ended_before_window",
        "Связи, у которых обе стороны по снимку кончились до окна, не проверяются (get не "
        "вызывается). Недавняя связь с удалёнными сторонами убирается (unlink).",
        "google-domino",
        {G: [], D: []},
        [copy_of(G, old, D, "d-old")[1], copy_of(G, old_day, D, "d-oldday")[1],
         copy_of(G, recent, D, "d-recent")[1]],
    ))

    # Выбор
    add(Scenario(
        "select_routes",
        "select() с маршрутами: выполняется только domino>google.",
        "google-domino",
        {G: [ev("g1", "Стендап")], D: [ev("d1", "Ревью", AT + timedelta(hours=2))]},
        routes=[(D, G)],
    ))

    # Двойники по времени (docs/adr/0003-time-twins-and-refusals.md). Реальный случай: своя
    # встреча в Lotus и чужая в Teams с другим названием в то же время, и уже скопированы
    # друг в друга и обе в Google.
    lotus = ev("d1", "AI Agent Platform: status update (incl. demo of progress)")
    foreign = ev("t1", "AI Agent Platform: status update (дублирую в тимсе ...)",
                 organizer_is_me=False)
    ln_in_teams, ln_teams_link = copy_of(D, lotus, T, "t-ln")
    ln_in_google, ln_google_link = copy_of(D, lotus, G, "g-ln")
    mt_in_lotus, mt_lotus_link = copy_of(T, foreign, D, "d-mt")
    mt_in_google, mt_google_link = copy_of(T, foreign, G, "g-mt")
    live = {G: [ln_in_google, mt_in_google], D: [lotus, mt_in_lotus], T: [foreign, ln_in_teams]}
    live_links = [ln_teams_link, ln_google_link, mt_lotus_link, mt_google_link]
    question = "twin:twin:domino:d1->teams:t1"
    add(Scenario(
        "time_twin_question_unticked",
        "Своя встреча в Lotus и чужая в Teams: одинаковые начало и конец, разные названия. "
        "Вопрос «одна встреча?» без галочки. Пока он открыт, копии между ними — ответ «нет» "
        "(fallback), поэтому без ключей (как фон) ничего не создаётся и вопрос задаётся снова; "
        "в Google копируется только встреча из Lotus (она копирует за группу).",
        "all",
        {G: [], D: [lotus], T: [foreign]},
    ))
    add(Scenario(
        "time_twin_confirmed_removes_redundant_copies",
        "Живой случай: копии уже есть ([LN] в Teams, [MT] в Lotus, обе в Google). Пользователь "
        "отметил вопрос по времени: оригиналы связаны как двойники, лишние копии удалены — "
        "между ними и вторая копия в Google (остаётся копия из Lotus: Lotus копирует за "
        "группу). Удаления зависят от вопроса.",
        "all",
        live, live_links,
        keys=[question, "delete:domino:d-mt", "delete:google:g-mt", "delete:teams:t-ln"],
    ))
    add(Scenario(
        "time_twin_declined_is_distinct",
        "Тот же живой случай, пользователь не отметил вопрос: запоминается distinct, копии "
        "не удаляются, вопрос больше не задаётся.",
        "all",
        live, live_links,
        keys=[],
    ))
    add(Scenario(
        "time_twin_background_keeps_copies",
        "Тот же живой случай без ключей (фон): вопрос без галочки не выполняется, удаления "
        "лишних копий от него зависят и тоже не выполняются; ничего не меняется.",
        "all",
        live, live_links,
    ))
    add(Scenario(
        "time_twin_not_for_all_day_or_copies",
        "Вопроса по времени нет: у событий на весь день (праздники совпадают часто), у копии "
        "и оригинала (копии не оригиналы), при разном конце.",
        "domino-teams",
        {D: [day("d-day", "Праздник", date(2026, 11, 13)),
             ev("d-copy", "Из Google", AT + timedelta(hours=3), source=G),
             ev("d-long", "Длинная", AT + timedelta(hours=6), hours=2)],
         T: [day("t-day", "Holiday", date(2026, 11, 13)),
             ev("t1", "Своя", AT + timedelta(hours=3)),
             ev("t-short", "Короткая", AT + timedelta(hours=6))]},
    ))
    add(Scenario(
        "time_twins_ambiguous_first_to_first",
        "Два кандидата по времени в Lotus на одну встречу в Teams: вопрос — первый с первым "
        "(как у названия), второй копируется как обычно.",
        "domino-teams",
        {D: [ev("d1", "Планёрка"), ev("d2", "Созвон")], T: [ev("t1", "Standup")]},
    ))

    # Отказ от копирования
    add(Scenario(
        "create_unticked_is_refused",
        "Ручное применение: создание g1 не отмечено — запоминается отказ (связь copy, kept, "
        "без target_id). Повторный план g1 не копирует и показывает его в refused.",
        "google-domino",
        {G: [ev("g1", "Личное"), ev("g2", "Рабочее", AT + timedelta(hours=2))], D: []},
        keys=["google:g2>domino"],
    ))
    add(Scenario(
        "background_creates_no_refusal",
        "Без ключей (фон) отказы не запоминаются: создание вне маршрута просто не выполняется "
        "и в следующий раз предлагается снова.",
        "google-domino",
        {G: [ev("g1", "Личное")], D: [ev("d1", "Рабочее", AT + timedelta(hours=2))]},
        routes=[(D, G)],
    ))
    refused_g1 = Link(G, "g1", D, None, kept=True)
    add(Scenario(
        "refusal_not_proposed",
        "Отказ: ни ручной, ни фоновый запуск не предлагает копию; она в refused плана. "
        "Связь не проверяется (kept) и не меняется.",
        "google-domino",
        {G: [ev("g1", "Личное")], D: []},
        [refused_g1],
    ))
    add(Scenario(
        "refusal_reticked_creates_copy",
        "Отмена отказа в предпросмотре: ключ отказа из refused передан в keys — копия "
        "создаётся, связь копии заменяет отказ (тот же ключ).",
        "google-domino",
        {G: [ev("g1", "Личное")], D: []},
        [refused_g1],
        keys=["google:g1>domino"],
    ))

    # Недоступная система: всё, что с ней связано, в этом запуске заморожено
    lotus = ev("d1", "Встреча")
    meeting = ev("t1", "Встреча (Teams)", organizer_is_me=False)
    ln_in_google, ln_link = copy_of(D, lotus, G, "g-ln")
    meeting_twins = twin_link(D, lotus, T, meeting)
    add(Scenario(
        "unavailable_freezes_twin_group",
        "Фон, Lotus недоступен (сбой, а не направление). t1 — двойник оригинала Lotus, в Google "
        "уже есть [LN]-копия группы: t1 не копируется в Google. Несвязанный t2 копируется.",
        "all",
        {G: [ln_in_google], T: [meeting, ev("t2", "Ретро", AT + timedelta(hours=3))]},
        [ln_link, meeting_twins],
        unavailable=[D],
    ))
    standup = ev("d1", "Планёрка")
    standup_in_teams, standup_link = copy_of(D, standup, T, "t-ln")
    standup_in_google, standup_google = copy_of(D, standup, G, "g-ln")
    add(Scenario(
        "unavailable_keeps_copies_of_its_originals",
        "Lotus недоступен, связи d1 -> t-ln и d1 -> g-ln есть. Копии не удаляются (оригинал "
        "не «исчез»), правка копии в Teams не уходит в Google, вопросов нет.",
        "all",
        {G: [standup_in_google], T: [replace(standup_in_teams, title="Планёрка 2")]},
        [standup_link, standup_google],
        unavailable=[D],
    ))
    review_t = ev("t3", "Обзор", AT + timedelta(days=1))
    review_in_google, review_link_t = copy_of(T, review_t, G, "g-mt")
    add(Scenario(
        "unavailable_unrelated_group_updates",
        "Lotus недоступен. Оригинал Teams t3 связан только с Google: его правка обновляет копию "
        "как обычно, а замороженная группа (двойник Lotus) не трогается.",
        "all",
        {G: [ln_in_google, review_in_google],
         T: [meeting, replace(review_t, title="Обзор v2")]},
        [ln_link, meeting_twins, review_link_t],
        unavailable=[D],
    ))
    demo = ev("g1", "Демо")
    demo_in_lotus, demo_link = copy_of(G, demo, D, "d-gc")
    add(Scenario(
        "unavailable_candidate_twin_waits",
        "Lotus недоступен, g1 связан с Lotus (заморожен). Новый t1 совпал с g1 по названию и "
        "началу: вопроса нет, t1 ждёт вместе с g1 и не копируется.",
        "all",
        {G: [demo], T: [ev("t1", "Демо")]},
        [demo_link],
        unavailable=[D],
    ))
    gone = [ev(f"g{i}", f"E{i}", AT + timedelta(days=i)) for i in range(7)]
    gone_links: List[Link] = []
    gone_copies = []
    for e in gone:
        c, link = copy_of(G, e, T, f"t-{e.id}")
        gone_copies.append(c)
        gone_links.append(link)
    gone_links += [copy_of(G, e, D, f"d-{e.id}")[1] for e in gone[:6]]
    add(Scenario(
        "unavailable_frozen_not_counted_as_gone",
        "Lotus недоступен. Из Google исчезли 7 оригиналов, у 6 есть ещё копия в Lotus: они "
        "заморожены и защиту от массового удаления не включают. Копия g6 удаляется молча.",
        "google-teams",
        {G: [], T: gone_copies},
        gone_links,
        unavailable=[D],
    ))
    add(Scenario(
        "direction_excluding_lotus_is_not_outage",
        "Направление google-teams выключает Lotus, но он не недоступен: поведение прежнее — "
        "двойник Lotus t1 копируется в Google, хотя там есть [LN]-копия группы.",
        "google-teams",
        {G: [ln_in_google], D: [lotus], T: [meeting]},
        [ln_link, meeting_twins],
    ))
    return s


SCENARIOS: List[Scenario] = _scenarios()
