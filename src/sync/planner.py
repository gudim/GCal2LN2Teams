"""
Движок синхронизации.

collect() читает календари (I/O), plan() решает, что делать (без I/O), select() оставляет
выбранные пользователем действия, apply() их выполняет. Dry run = collect() + plan().

Правила (двойники, обновления по снимку, несимметричные удаления) описаны в GLOSSARY.md
и docs/adr/0001-conflicts-and-deletes.md, двойники по времени и отказы — в docs/adr/0003.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field, replace
from datetime import date, datetime
from itertools import permutations
from typing import (
    Collection, Dict, FrozenSet, Iterable, List, Mapping, NamedTuple, Optional, Sequence, Set,
    Tuple, Union,
)

try:
    from typing import Protocol
except ImportError:  # Python 3.7
    from typing_extensions import Protocol  # type: ignore

from ..utils.logger import get_logger
from .calendar import Calendar
from .event import CalendarEvent, DateRange, System

logger = get_logger(__name__)

G, D, T = System.GOOGLE, System.DOMINO, System.TEAMS
ORDER = [G, D, T]

# Направление из UI -> пары систем, синхронизируемые в обе стороны
DIRECTIONS: Dict[str, List[FrozenSet[System]]] = {
    "all": [frozenset({G, D}), frozenset({G, T}), frozenset({D, T})],
    "google-domino": [frozenset({G, D})],
    "google-teams": [frozenset({G, T})],
    "domino-teams": [frozenset({D, T})],
}

# Защита от массового удаления
MASS_DELETE_ORIGINALS = 5
MASS_DELETE_SHARE = 0.3
MASS_DELETE_MIN_LINKS = 10  # долю считаем, только если у системы достаточно связей

# Синхронизируемые поля группами: начало и конец меняются вместе
UNITS: List[Tuple[str, ...]] = [("title",), ("start", "end"), ("location",), ("description",)]

# Вопрос «это одна встреча?» по виду совпадения
TWIN_REASONS = {
    "title": "одинаковые название и начало: это одна встреча?",
    "time": "одинаковые начало и конец, разные названия: это одна встреча?",
}

Fields = Dict[str, str]
Member = Tuple[System, str]


class Link(NamedTuple):
    """Связь.

    kind: "copy" — оригинал (source) и его копия (target); "twin" — двойники;
    "distinct" — пользователь отказался считать события двойниками.
    source_fields/target_fields — снимок полей сторон после прошлой синхронизации.
    kept — одна сторона удалена, а другую пользователь оставил: больше не спрашивать
    и не воссоздавать.
    Отказ от копирования — связь "copy" с kept и без target_id: копию не создавать
    (см. refusal(), docs/adr/0003-time-twins-and-refusals.md).
    """

    source_system: System
    source_id: str
    target_system: System
    target_id: Optional[str] = None
    kind: str = "copy"
    source_fields: Optional[Fields] = None
    target_fields: Optional[Fields] = None
    kept: bool = False

    def key(self) -> str:
        base = f"{self.source_system.value}:{self.source_id}->{self.target_system.value}"
        return base if self.kind == "copy" else f"{self.kind}:{base}:{self.target_id}"

    @property
    def is_refusal(self) -> bool:
        """Отказ: пользователь снял галочку с создания копии, копию не создавать."""
        return self.kind == "copy" and self.kept and self.target_id is None


def refusal(origin: System, origin_id: str, target: System) -> Link:
    """Связь-отказ: оригинал (origin, origin_id) не копировать в target.

    Ключ тот же, что у будущей связи копии, поэтому созданная позже копия её заменяет.
    """
    return Link(origin, origin_id, target, None, kept=True)


@dataclass(frozen=True)
class Action:
    """Одно действие синхронизации.

    kind: create | update | delete | twin (показываются пользователю),
          link | unlink (служебные: сохранить/удалить связь).
    create: event — будущая копия (source = система оригинала, id = id оригинала).
    update: event — новое содержимое события с id цели; changed — изменённые поля.
    delete: event — удаляемое событие; link — связь, которую убрать после удаления.
    twin: event — двойник в target; link — связь двойников.
    """

    kind: str
    target: System
    event: CalendarEvent
    origin: System
    selected: bool = True
    reason: str = ""
    changed: Tuple[str, ...] = ()
    link: Optional[Link] = None
    depends_on: Tuple[str, ...] = ()  # выполнять, только если эти действия выполнены
    fallback: Tuple["Action", ...] = ()  # выполнить вместо этого, если пользователь снял галочку
    silent: bool = False  # не требует подтверждения и не показывается в предпросмотре
    # что удаляется (только для delete): copy | extra (лишняя копия) | original | twin
    subject: str = ""

    @property
    def key(self) -> str:
        """Стабильный ключ между предпросмотром и запуском."""
        if self.kind == "create":
            ident = self.event.id if self.event.id is not None else (
                f"{self.event.title}@{self.event.start.isoformat()}"
            )
            return f"{self.origin.value}:{ident}>{self.target.value}"
        if self.kind in ("link", "unlink", "twin"):
            assert self.link is not None
            return f"{self.kind}:{self.link.key()}"
        return f"{self.kind}:{self.target.value}:{self.event.id}"


@dataclass
class Plan:
    """План.

    refused — отказы в окне: создания копий, которые план предложил бы, если бы не отказ.
    У каждого link — связь-отказ. Их ключи можно передать в select(keys=..., refused=...),
    чтобы создать копию (отказ заменится связью копии), или убрать отказ undo_refusal().
    """

    actions: List[Action]
    warnings: List[str] = field(default_factory=list)
    refused: List[Action] = field(default_factory=list)


class _Gone:
    pass


GONE = _Gone()  # событие удалено (get вернул None) или игнорируется


def collect(
    calendars: Mapping[System, Calendar], window: DateRange, links: Collection[Link]
) -> Tuple[Dict[System, List[CalendarEvent]], Dict[Member, Optional[CalendarEvent]]]:
    """Прочитать календари и дозапросить связанные события, которых нет в окне.

    Возвращает (snapshot, found): found[(система, id)] — результат get() (None = удалено).
    Если get() упал, события нет в found: связь в этот раз не трогаем.
    """
    snapshot = {system: calendar.list(window) for system, calendar in calendars.items()}
    listed = {(system, e.id) for system, events in snapshot.items() for e in events}
    found: Dict[Member, Optional[CalendarEvent]] = {}
    for link in links:
        if link.kept or link.kind == "distinct" or _ended_before(link, window.start):
            continue
        for system, event_id in _ends(link):
            if system not in calendars or not event_id:
                continue
            if (system, event_id) in listed or (system, event_id) in found:
                continue
            try:
                found[(system, event_id)] = calendars[system].get(event_id)
            except Exception as e:  # noqa: BLE001 - не знаем, есть ли событие: связь пропускаем
                logger.warning(f"⚠️ {system.value}: не удалось получить {event_id}: {e}")
    return snapshot, found


def _ends(link: Link) -> List[Tuple[System, Optional[str]]]:
    return [(link.source_system, link.source_id), (link.target_system, link.target_id)]


def _ended_before(link: Link, moment: datetime) -> bool:
    """Обе стороны по снимку закончились до начала окна: прошлое не проверяем."""
    snaps = [link.source_fields, link.target_fields]
    if any(s is None for s in snaps):
        return False
    for snap in snaps:
        end = snap["end"]  # type: ignore[index]
        if len(end) == 10:
            if date.fromisoformat(end) > moment.date():
                return False
        elif datetime.fromisoformat(end) > moment:
            return False
    return True


class _UnionFind:
    def __init__(self) -> None:
        self.parent: Dict[Member, Member] = {}

    def find(self, m: Member) -> Member:
        self.parent.setdefault(m, m)
        while self.parent[m] != m:
            m = self.parent[m]
        return m

    def union(self, a: Member, b: Member) -> None:
        self.parent[self.find(a)] = self.find(b)

    def groups(self) -> List[List[Member]]:
        result: Dict[Member, List[Member]] = defaultdict(list)
        for m in self.parent:
            result[self.find(m)].append(m)
        return [sorted(g, key=_order) for g in result.values()]


def _order(m: Member) -> Tuple[int, str]:
    return (ORDER.index(m[0]), m[1])


def plan(
    snapshot: Mapping[System, Sequence[CalendarEvent]],
    pairs: Iterable[FrozenSet[System]],
    links: Collection[Link] = (),
    found: Optional[Mapping[Member, Optional[CalendarEvent]]] = None,
    unavailable: Collection[System] = frozenset(),
) -> Plan:
    """Решить, что создать, обновить, удалить и связать. Без I/O.

    unavailable — системы, которые в этом запуске не прочитаны из-за сбоя (а не выключены
    направлением или маршрутами). Всё, что связано с ними связями, заморожено (см. _frozen()).
    """
    found = found or {}
    frozen = _frozen(links, unavailable)
    active = {p for p in pairs if p <= set(snapshot)}

    def paired(a: System, b: System) -> bool:
        return frozenset({a, b}) in active

    listed: Dict[Member, CalendarEvent] = {
        (s, e.id): e for s, events in snapshot.items() for e in events if e.id is not None
    }

    def lookup(s: System, event_id: Optional[str]) -> Union[CalendarEvent, _Gone, None]:
        """Событие, GONE или None (неизвестно)."""
        if not event_id:
            return None
        if (s, event_id) in listed:
            event: Optional[CalendarEvent] = listed[(s, event_id)]
        elif (s, event_id) in found:
            event = found[(s, event_id)]
            if event is None:
                return GONE
        else:
            return None
        assert event is not None
        return GONE if event.ignored and event.source is None else event

    actions: List[Action] = []
    copied: Set[Tuple[System, str, System]] = set()  # (система, id, куда) — копировать не нужно
    refusals: Dict[Tuple[System, str, System], Link] = {}  # пользователь отказался копировать
    twinned: Set[Member] = set()
    distinct: Set[FrozenSet[Member]] = set()
    confirmed = _UnionFind()  # двойники по связи и UID
    candidates = _UnionFind()  # то же + кандидаты по названию и началу
    edges: List[Tuple[Link, CalendarEvent, CalendarEvent]] = []  # живые связи
    gone_originals: Set[Member] = set()
    link_count: Dict[System, int] = defaultdict(int)
    gone_count: Dict[System, int] = defaultdict(int)

    # 1. Существующие связи: удаления, вопросы, живые связи
    for link in links:
        a, b = (link.source_system, link.source_id), (link.target_system, link.target_id)
        if link.kind == "distinct":
            distinct.add(frozenset({a, b}))  # type: ignore[arg-type]
            continue
        if link.is_refusal:
            refusals[(link.source_system, link.source_id, link.target_system)] = link
            continue
        if link.target_id is None:
            continue
        copied.add((link.source_system, link.source_id, link.target_system))
        if link.kind == "twin":
            copied.add((link.target_system, link.target_id, link.source_system))
            twinned.update([a, b])  # type: ignore[list-item]
        if link.kept or not paired(link.source_system, link.target_system) or a in frozen:
            continue  # замороженная связь не считается и для защиты от массового удаления
        src, tgt = lookup(*a), lookup(*b)  # type: ignore[arg-type]
        if src is None or tgt is None:
            continue  # неизвестно, есть ли событие: в этот раз не трогаем
        originals = [a] if link.kind == "copy" else [a, b]
        for o in originals:
            link_count[o[0]] += 1  # type: ignore[index]
        if src is GONE and tgt is GONE:
            actions.append(_bookkeeping("unlink", link))
            continue
        if isinstance(src, CalendarEvent) and isinstance(tgt, CalendarEvent):
            edges.append((link, src, tgt))
            if link.kind == "twin":
                confirmed.union(a, b)  # type: ignore[arg-type]
                candidates.union(a, b)  # type: ignore[arg-type]
            continue
        gone, alive = (a, tgt) if src is GONE else (b, src)
        assert isinstance(alive, CalendarEvent)
        if link.kind == "copy" and src is GONE:
            gone_originals.add(gone)  # type: ignore[arg-type]
            gone_count[gone[0]] += 1  # type: ignore[index]
            actions.append(Action(
                "delete", link.target_system, alive, link.source_system,
                reason="оригинал удалён", link=link, silent=True, subject="copy",
            ))
            continue
        if link.kind == "twin":
            gone_originals.add(gone)  # type: ignore[arg-type]
            gone_count[gone[0]] += 1  # type: ignore[index]
        kept = _bookkeeping("link", link._replace(kept=True))
        if not alive.organizer_is_me:
            actions.append(kept)  # чужую встречу не трогаем: просто запоминаем
            continue
        question = "копия удалена — удалить оригинал?" if link.kind == "copy" else (
            "двойник удалён — удалить и этот?"
        )
        assert alive.id is not None
        target_system = link.source_system if src is not GONE else link.target_system
        actions.append(Action(
            "delete", target_system, alive, gone[0],  # type: ignore[arg-type]
            selected=False, reason=question, link=link, fallback=(kept,),
            subject="original" if link.kind == "copy" else "twin",
        ))

    # 2. Новые двойники: по UID — сразу, по названию и началу — вопрос с галочкой,
    # по началу и концу при разных названиях — вопрос без галочки (ADR 0003).
    # Неоднозначность (несколько кандидатов) — первый с первым, как у названия.
    originals: Dict[System, List[CalendarEvent]] = {
        s: [e for e in events if e.source is None and not e.ignored]
        for s, events in snapshot.items()
    }
    twin_actions: List[Action] = []
    candidate_keys: Dict[Member, str] = {}  # участник кандидатов -> ключ вопроса
    for s1, s2 in (sorted(p, key=ORDER.index) for p in sorted(active, key=_pair_order)):
        used: Set[Member] = set()
        for key_of, mode in ((CalendarEvent.uid_key, "uid"), (CalendarEvent.twin_key, "title"),
                             (CalendarEvent.time_key, "time")):
            pool: Dict[object, List[CalendarEvent]] = defaultdict(list)
            for e in originals[s2]:
                if e.id is not None and (s2, e.id) not in twinned and key_of(e) is not None:
                    pool[key_of(e)].append(e)
            for e in originals[s1]:
                m1: Member = (s1, e.id)  # type: ignore[assignment]
                if e.id is None or m1 in twinned or m1 in used or key_of(e) is None:
                    continue
                match = next((x for x in pool[key_of(e)] if (s2, x.id) not in used
                              and frozenset({m1, (s2, x.id)}) not in distinct
                              and (mode != "time" or x.twin_key() != e.twin_key())), None)
                if match is None:
                    continue
                m2: Member = (s2, match.id)  # type: ignore[assignment]
                used.update([m1, m2])
                if m1 in frozen or m2 in frozen:
                    frozen.update([m1, m2])  # кандидат замороженного ждёт вместе с ним
                    continue
                link = Link(s1, m1[1], s2, m2[1], "twin", e.fields(), match.fields())
                candidates.union(m1, m2)
                if mode == "uid":
                    confirmed.union(m1, m2)
                    actions.append(_bookkeeping("link", link))
                else:
                    action = Action("twin", s2, match, s1, reason=TWIN_REASONS[mode], link=link,
                                    selected=mode == "title")
                    twin_actions.append(action)
                    candidate_keys[m1] = candidate_keys[m2] = action.key

    def cluster_keys(m: Member) -> Tuple[str, ...]:
        root = candidates.find(m)
        return tuple(sorted({k for x, k in candidate_keys.items() if candidates.find(x) == root}))

    # 3. Лишние копии: копия в системе, где есть двойник её оригинала
    match_index: Dict[System, Dict[Tuple[System, object], str]] = {
        s: {(e.source, e.match_key()): e.id for e in events if e.source is not None and e.id}
        for s, events in snapshot.items()
    }
    copy_of: Dict[Member, Tuple[Member, Optional[Link]]] = {}
    for link in links:
        if link.kind == "copy" and link.target_id and not link.kept:
            original = (link.source_system, link.source_id)
            copy_of[(link.target_system, link.target_id)] = (original, link)
    leftovers: Set[Member] = set()
    # Копии членов одной группы двойников в системе, где у группы нет своего события:
    # (система, корень группы) -> [(копия, оригинал, связь)] в порядке календаря
    third: Dict[Tuple[System, Member], List[Tuple[CalendarEvent, Member, Optional[Link]]]] = (
        defaultdict(list)
    )
    for s, events in snapshot.items():
        for c in events:
            if c.source is None or c.id is None or not paired(s, c.source):
                continue
            if (s, c.id) in frozen:
                continue
            origin, link = copy_of.get((s, c.id), (None, None))
            if origin is None:
                o = next((x for x in originals.get(c.source, [])
                          if x.match_key() == c.match_key()), None)
                origin = (c.source, o.id) if o is not None else None  # type: ignore[assignment]
            if origin is None or origin in frozen:
                continue
            group = _members(candidates, origin)
            if not any(m[0] == s for m in group):
                if len(group) > 1:
                    third[(s, candidates.find(origin))].append((c, origin, link))
                continue
            leftovers.add((s, c.id))
            in_confirmed = any(m[0] == s for m in _members(confirmed, origin))
            depends = () if in_confirmed else cluster_keys(origin)
            actions.append(Action(
                "delete", s, c, c.source, reason="лишняя копия: в календаре есть двойник оригинала",
                link=link, depends_on=depends, subject="extra",
            ))
    # Группе двойников в третьей системе хватает одной копии: оставляем копию того, кто
    # копирует (как в _covered), а если её нет — первую; копии других членов группы лишние
    for (s, root), group_copies in third.items():
        copier = next(m for m in _members(candidates, root) if paired(m[0], s))
        keeper = next((x[1] for x in group_copies if x[1] == copier), group_copies[0][1])
        for c, origin, link in group_copies:
            if origin == keeper:
                continue
            leftovers.add((s, c.id))  # type: ignore[arg-type]
            together = confirmed.find(origin) == confirmed.find(keeper)
            depends = () if together else cluster_keys(origin)
            actions.append(Action(
                "delete", s, c, c.source,  # type: ignore[arg-type]
                reason="лишняя копия: в календаре уже есть копия двойника",
                link=link, depends_on=depends, subject="extra",
            ))

    # 4. Новые копии (у двойников — одна на группу)
    fallbacks: Dict[str, List[Action]] = defaultdict(list)
    refused: List[Action] = []
    for pair in sorted(active, key=_pair_order):
        for origin_system, target in permutations(sorted(pair, key=ORDER.index)):
            for e in originals[origin_system]:
                m: Member = (origin_system, e.id)  # type: ignore[assignment]
                if m in frozen or (origin_system, e.id, target) in copied:
                    continue
                if (origin_system, e.match_key()) in match_index[target]:
                    continue
                create = Action("create", target, replace(e, source=origin_system), origin_system)
                index = match_index[target]
                if _covered(m, target, confirmed, copied, index, listed, active):
                    continue
                refused_link = refusals.get((origin_system, e.id, target))  # type: ignore[arg-type]
                if refused_link is not None:
                    refused.append(replace(create, link=refused_link))
                    continue
                if _covered(m, target, candidates, copied, index, listed, active):
                    fallbacks[cluster_keys(m)[0]].append(create)
                    continue
                if e.id is not None:  # снятая галочка при ручном применении — отказ
                    no = _bookkeeping("link", refusal(origin_system, e.id, target))
                    create = replace(create, fallback=(no,))
                actions.append(create)

    # 5. Обновления по снимку
    _plan_updates(edges, leftovers, actions)

    # Вопросы о двойниках — первыми: от них зависят удаления лишних копий.
    # Если пользователь откажется, копируем как обычно и запоминаем отказ.
    questions = []
    for twin in twin_actions:
        assert twin.link is not None
        distinct_link = twin.link._replace(kind="distinct", source_fields=None, target_fields=None)
        twin_fallback = tuple(fallbacks[twin.key]) + (_bookkeeping("link", distinct_link),)
        questions.append(replace(twin, fallback=twin_fallback))
    actions[:0] = questions

    # 6. Защита от массового удаления
    warnings: List[str] = []
    heavy = [s for s in link_count if link_count[s] >= MASS_DELETE_MIN_LINKS
             and gone_count[s] / link_count[s] > MASS_DELETE_SHARE]
    if len(gone_originals) > MASS_DELETE_ORIGINALS or heavy:
        warnings.append(
            f"Исчезло много событий ({len(gone_originals)}): удаления не выбраны, проверьте их"
        )
        actions = [
            replace(a, selected=False, silent=False) if a.kind == "delete" else a
            for a in actions
        ]
    return Plan(actions, warnings, refused)


def _frozen(links: Collection[Link], unavailable: Collection[System]) -> Set[Member]:
    """События, связанные с недоступной системой: в этом запуске их не трогаем.

    Группа — события, соединённые связями копий (в любую сторону) и двойников, транзитивно.
    Если в группе есть событие недоступной системы, заморожена вся группа: ни созданий,
    ни обновлений, ни удалений, ни вопросов. Связи kept (вторая сторона уже удалена),
    distinct и отказы событий не соединяют.
    """
    if not unavailable:
        return set()
    uf = _UnionFind()
    for link in links:
        if link.kind == "distinct" or link.kept or not link.target_id:
            continue
        uf.union((link.source_system, link.source_id), (link.target_system, link.target_id))
    roots = {uf.find(m) for m in uf.parent if m[0] in unavailable}
    return {m for m in uf.parent if uf.find(m) in roots}


def _pair_order(pair: FrozenSet[System]) -> List[int]:
    return sorted(ORDER.index(s) for s in pair)


def _members(uf: _UnionFind, m: Member) -> List[Member]:
    root = uf.find(m)
    return sorted([x for x in uf.parent if uf.find(x) == root], key=_order)


def _covered(
    m: Member,
    target: System,
    uf: _UnionFind,
    copied: Set[Tuple[System, str, System]],
    copies_in_target: Mapping[Tuple[System, object], str],
    listed: Mapping[Member, CalendarEvent],
    active: Set[FrozenSet[System]],
) -> bool:
    """Группа двойников уже есть в target (событием или копией) или копию делает другой."""
    group = _members(uf, m)
    for x in group:
        if x[0] == target or (x[0], x[1], target) in copied:
            return True
        event = listed.get(x)
        if event is not None and (x[0], event.match_key()) in copies_in_target:
            return True
    copier = next((x for x in group if frozenset({x[0], target}) in active), m)
    return copier != m


def _bookkeeping(kind: str, link: Link) -> Action:
    event = CalendarEvent(title="", start=date.min, end=date.min)
    return Action(kind, link.target_system, event, link.source_system, link=link, silent=True)


def _plan_updates(
    edges: List[Tuple[Link, CalendarEvent, CalendarEvent]],
    leftovers: Set[Member],
    actions: List[Action],
) -> None:
    """Слить изменения внутри каждой группы связанных событий и записать их во все стороны."""
    uf = _UnionFind()
    events: Dict[Member, CalendarEvent] = {}
    live = []
    for link, src, tgt in edges:
        a, b = (link.source_system, link.source_id), (link.target_system, link.target_id)
        if a in leftovers or b in leftovers:
            continue
        live.append((link, a, b))
        events[a], events[b] = src, tgt  # type: ignore[index]
        uf.union(a, b)  # type: ignore[arg-type]

    for group in uf.groups():
        group_set = set(group)
        group_edges = [(l, a, b) for l, a, b in live if a in group_set]
        # Оригиналы раньше копий, корень — первый оригинал
        ordered = sorted(group, key=lambda m: (events[m].source is not None, _order(m)))
        base: Dict[Member, Optional[Fields]] = {}
        parent: Dict[Member, Member] = {}
        for link, a, b in group_edges:
            # Снимок берём из первой связи, где он есть: старая связь без снимка
            # не должна скрывать снимок из другой связи того же события
            if base.get(a) is None:
                base[a] = link.source_fields  # type: ignore[index, assignment]
            if base.get(b) is None:
                base[b] = link.target_fields  # type: ignore[index, assignment]
            if link.kind == "copy":
                parent[b] = a  # type: ignore[index, assignment]
        current = {m: events[m].fields() for m in group}

        # Если в группе есть чужая встреча, изменения берём только из неё
        authority = [m for m in ordered
                     if events[m].source is None and not events[m].organizer_is_me]
        changers = authority or ordered
        winners: Dict[Tuple[str, ...], Member] = {}
        for unit in UNITS:
            winner: Optional[Member] = None
            for m in changers:
                snap = base.get(m)
                if snap is None or all(current[m][f] == snap[f] for f in unit):
                    continue
                if winner is None or _later(events[m], events[winner]):
                    winner = m
            if winner is not None:
                winners[unit] = winner

        updates: List[Action] = []
        original_keys: List[str] = []  # копии обновляем только после оригиналов
        new_snap: Dict[Member, Fields] = {}
        for m in ordered:
            event = events[m]
            units = [u for u, w in winners.items()
                     if any(current[m][f] != current[w][f] for f in u)]
            is_original = event.source is None
            if not units or (is_original and not event.organizer_is_me):
                new_snap[m] = current[m]
                continue
            values = {f: getattr(events[winners[u]], f) for u in units for f in u}
            new_event = replace(event, **values)
            new_snap[m] = new_event.fields()
            changed = tuple(f for u in units for f in u if current[m][f] != new_snap[m][f])
            if is_original:
                origin = winners[units[0]][0]
            else:
                origin = parent.get(m, m)[0]
            selected = not (is_original and event.has_attendees)
            updates.append(Action(
                "update", m[0], new_event, origin, selected=selected,
                reason="" if selected else "участники получат письмо",
                changed=changed, depends_on=() if is_original else tuple(original_keys),
            ))
            if is_original:
                original_keys.append(updates[-1].key)
        for action in updates:
            actions.append(action)
        update_keys = tuple(a.key for a in updates)
        for link, a, b in group_edges:
            fresh = link._replace(source_fields=new_snap[a], target_fields=new_snap[b])
            if fresh != link:
                actions.append(replace(_bookkeeping("link", fresh), depends_on=update_keys))


def _later(candidate: CalendarEvent, current: CalendarEvent) -> bool:
    """Изменение candidate новее? Если время неизвестно или равно — побеждает текущий."""
    if candidate.modified is None or current.modified is None:
        return False
    return candidate.modified > current.modified


class LinkStore(Protocol):
    """Куда apply() сохраняет связи сразу после каждого действия."""

    def save(self, link: Link) -> None:
        ...

    def drop(self, link: Link) -> None:
        ...


@dataclass
class PairResult:
    created: int = 0
    updated: int = 0
    deleted: int = 0
    linked: int = 0
    errors: List[str] = field(default_factory=list)


@dataclass
class SyncReport:
    pairs: Dict[Tuple[System, System], PairResult] = field(default_factory=dict)

    def pair(self, origin: System, target: System) -> PairResult:
        return self.pairs.setdefault((origin, target), PairResult())


COUNTERS = {"create": "created", "update": "updated", "delete": "deleted", "twin": "linked"}


def apply(
    actions: Iterable[Action], calendars: Mapping[System, Calendar], store: LinkStore
) -> SyncReport:
    """Выполнить действия по порядку. Ошибка одного действия не останавливает остальные."""
    report = SyncReport()
    done: Set[str] = set()
    for action in actions:
        if any(key not in done for key in action.depends_on):
            logger.info(f"⏭️ {action.kind} {action.event.title}: не выполнено предыдущее действие")
            continue
        result = report.pair(action.origin, action.target)
        try:
            _execute(action, calendars, store)
        except Exception as e:  # noqa: BLE001 - фиксируем и идём дальше
            logger.error(f"❌ {action.kind} {action.origin.value} → {action.target.value}: "
                         f"{action.event.title}: {e}")
            result.errors.append(f"{action.event.title}: {e}")
            continue
        done.add(action.key)
        if action.kind in COUNTERS:
            logger.info(f"✅ {action.kind} {action.origin.value} → {action.target.value}: "
                        f"{action.event.title}")
            counter = COUNTERS[action.kind]
            setattr(result, counter, getattr(result, counter) + 1)
    return report


def _execute(action: Action, calendars: Mapping[System, Calendar], store: LinkStore) -> None:
    calendar = calendars.get(action.target)
    if action.kind == "create":
        assert calendar is not None
        target_id = calendar.create(action.event)
        if action.event.id is not None:
            snap = action.event.fields()
            store.save(Link(action.origin, action.event.id, action.target, target_id,
                            source_fields=snap, target_fields=snap))
    elif action.kind == "update":
        assert calendar is not None and action.event.id is not None
        calendar.update(action.event.id, action.event)
    elif action.kind == "delete":
        assert calendar is not None and action.event.id is not None
        calendar.delete(action.event.id)
        if action.link is not None:
            store.drop(action.link)
    elif action.kind in ("twin", "link"):
        assert action.link is not None
        store.save(action.link)
    elif action.kind == "unlink":
        assert action.link is not None
        store.drop(action.link)
    else:
        raise ValueError(f"Неизвестное действие: {action.kind}")


def copy_key(action: Action) -> str:
    """Ключ действия (старое имя для создания копии)."""
    return action.key


def select(
    actions: Iterable[Action],
    routes: Optional[Collection[Tuple[System, System]]] = None,
    keys: Optional[Collection[str]] = None,
    refused: Iterable[Action] = (),
) -> List[Action]:
    """Оставить действия, которые нужно выполнить.

    Служебные связи выполняются всегда, "тихие" — в пределах маршрутов (origin, target).
    Остальные: без keys — выбранные по умолчанию, с keys — ровно эти ключи. Если keys
    переданы и действие в них не попало, выполняется его fallback (запомнить отказ:
    связь kept/distinct или отказ от копирования; скопировать недвойников).
    refused — Plan.refused: отказы, чьи ключи есть в keys, выполняются в конце (копия
    создаётся, отказ заменяется связью копии). Без keys отказы не трогаются.
    """
    def in_route(a: Action) -> bool:
        if routes is None or a.kind in ("link", "unlink"):
            return True
        if a.kind == "twin":
            return (a.origin, a.target) in routes or (a.target, a.origin) in routes
        return (a.origin, a.target) in routes

    chosen: List[Action] = []
    for action in actions:
        if not in_route(action):
            continue
        if action.kind in ("link", "unlink") or action.silent:
            chosen.append(action)
        elif action.selected if keys is None else action.key in keys:
            chosen.append(action)
        elif keys is not None:
            chosen.extend(f for f in action.fallback if in_route(f))
    if keys is not None:
        chosen.extend(a for a in refused if a.key in keys and in_route(a))
    return chosen


def undo_refusal(link: Link, store: LinkStore) -> None:
    """Отменить отказ от копирования: следующий план снова предложит создать копию."""
    if not link.is_refusal:
        raise ValueError(f"Связь {link.key()} — не отказ от копирования")
    store.drop(link)
