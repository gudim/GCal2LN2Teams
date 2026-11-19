package app.gcaltolotus.sync

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/*
 * Движок синхронизации. Порт `src/sync/planner.py`.
 *
 * collect() читает календари (I/O), plan() решает, что делать (без I/O), select() оставляет
 * выбранные пользователем действия, apply() их выполняет. Dry run = collect() + plan().
 *
 * Правила (двойники, обновления по снимку, несимметричные удаления) описаны в GLOSSARY.md
 * и docs/adr/0001-conflicts-and-deletes.md. Поведение — включая порядок действий — сверяется
 * с эталонами spec/golden.
 */

/** Направление из UI → пары систем, синхронизируемые в обе стороны. */
val DIRECTIONS: Map<String, List<Set<System>>> = mapOf(
    "all" to listOf(
        setOf(System.GOOGLE, System.DOMINO),
        setOf(System.GOOGLE, System.TEAMS),
        setOf(System.DOMINO, System.TEAMS),
    ),
    "google-domino" to listOf(setOf(System.GOOGLE, System.DOMINO)),
    "google-teams" to listOf(setOf(System.GOOGLE, System.TEAMS)),
    "domino-teams" to listOf(setOf(System.DOMINO, System.TEAMS)),
)

/** Защита от массового удаления: больше стольких исчезнувших оригиналов за запуск. */
const val MASS_DELETE_ORIGINALS: Int = 5

/** ...или у системы исчезла большая доля связей (строго больше). */
const val MASS_DELETE_SHARE: Double = 0.3

/** Долю считаем, только если у системы не меньше стольких связей. */
const val MASS_DELETE_MIN_LINKS: Int = 10

/** Синхронизируемые поля группами: начало и конец меняются вместе. */
internal val UNITS: List<List<String>> =
    listOf(listOf("title"), listOf("start", "end"), listOf("location"), listOf("description"))

internal const val REASON_ORIGINAL_GONE = "оригинал удалён"
internal const val REASON_ORIGINAL_CANCELLED = "оригинал отменён"
internal const val REASON_COPY_GONE = "копия удалена — удалить оригинал?"
internal const val REASON_TWIN_GONE = "двойник удалён — удалить и этот?"
internal const val REASON_SAME_TITLE = "одинаковые название и начало: это одна встреча?"
internal const val REASON_SAME_TIME = "одинаковые начало и конец, разные названия: это одна встреча?"
internal const val REASON_EXTRA_COPY = "лишняя копия: в календаре есть двойник оригинала"
internal const val REASON_EXTRA_THIRD = "лишняя копия: в календаре уже есть копия двойника"
internal const val REASON_ATTENDEES = "участники получат письмо"

/**
 * Результат [collect]: [snapshot] — что вернул `list(window)` каждого календаря;
 * [found] — результат `get` для связанных событий, которых нет в окне (`null` — удалено).
 */
data class Collected(
    val snapshot: Map<System, List<CalendarEvent>>,
    val found: Map<Member, CalendarEvent?>,
)

/**
 * Прочитать календари и дозапросить связанные события, которых нет в окне.
 *
 * Связи `kept`, `distinct` и закончившиеся до окна не проверяются. Если `get` упал, события
 * нет в [Collected.found]: связь в этот раз не трогаем.
 */
suspend fun collect(
    calendars: Map<System, Calendar>,
    window: Window,
    links: Collection<Link>,
): Collected {
    val snapshot = LinkedHashMap<System, List<CalendarEvent>>()
    for ((system, calendar) in calendars) snapshot[system] = calendar.list(window)
    val listed = snapshot.flatMap { (s, events) -> events.map { Member(s, it.id) } }.toSet()
    val found = LinkedHashMap<Member, CalendarEvent?>()
    for (link in links) {
        if (link.kept || link.kind == LinkKind.DISTINCT || endedBefore(link, window.start)) continue
        for (m in ends(link)) {
            val calendar = calendars[m.system]
            if (calendar == null || m.id.isNullOrEmpty()) continue
            if (m in listed || m in found) continue
            try {
                found[m] = calendar.get(m.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Не знаем, есть ли событие: связь пропускаем.
            }
        }
    }
    return Collected(snapshot, found)
}

private fun ends(link: Link): List<Member> =
    listOf(Member(link.sourceSystem, link.sourceId), Member(link.targetSystem, link.targetId))

/** Обе стороны по снимку закончились до начала окна: прошлое не проверяем. */
private fun endedBefore(link: Link, moment: Instant): Boolean {
    val snaps = listOf(link.sourceFields, link.targetFields)
    if (snaps.any { it == null }) return false
    for (snap in snaps) {
        when (val end = When.parse(snap!!.getValue("end"))) {
            is When.AllDay -> if (end.date > moment.toLocalDateTime(TimeZone.UTC).date) return false
            is When.Timed -> if (end.instant > moment) return false
        }
    }
    return true
}

/** Union-find как в Python: без сжатия путей, `find` добавляет участника. */
private class UnionFind {
    val parent = LinkedHashMap<Member, Member>()

    fun find(m0: Member): Member {
        var m = m0
        parent.getOrPut(m) { m }
        while (parent.getValue(m) != m) m = parent.getValue(m)
        return m
    }

    fun union(a: Member, b: Member) {
        // Python `parent[find(a)] = find(b)` вычисляет правую часть первой: порядок вставки
        // в parent (а от него — порядок групп) зависит от этого.
        val rb = find(b)
        val ra = find(a)
        parent[ra] = rb
    }

    fun groups(): List<List<Member>> {
        val result = LinkedHashMap<Member, MutableList<Member>>()
        for (m in parent.keys.toList()) result.getOrPut(find(m)) { mutableListOf() }.add(m)
        return result.values.map { it.sortedWith(MEMBER_ORDER) }
    }

    fun members(m: Member): List<Member> {
        val root = find(m)
        return parent.keys.toList().filter { find(it) == root }.sortedWith(MEMBER_ORDER)
    }
}

private fun orderIndex(s: System): Int = System.ORDER.indexOf(s)

private val MEMBER_ORDER: Comparator<Member> = Comparator { a, b ->
    val c = orderIndex(a.system).compareTo(orderIndex(b.system))
    if (c != 0) c else pyCompare(a.id ?: "", b.id ?: "")
}

/** Сравнение строк как в Python: по кодовым точкам Unicode (а не по UTF-16). */
internal fun pyCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = codePoint(a, i)
        val cb = codePoint(b, j)
        if (ca != cb) return ca.compareTo(cb)
        i += if (ca > 0xFFFF) 2 else 1
        j += if (cb > 0xFFFF) 2 else 1
    }
    return (a.length - i).compareTo(b.length - j)
}

private fun codePoint(s: String, i: Int): Int {
    val c = s[i]
    if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
        return 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
    }
    return c.code
}

/** Порядок пар: по отсортированным индексам систем (google, domino, teams). */
private val PAIR_ORDER: Comparator<Set<System>> = Comparator { a, b ->
    val ia = a.map(::orderIndex).sorted()
    val ib = b.map(::orderIndex).sorted()
    for (k in 0 until minOf(ia.size, ib.size)) {
        if (ia[k] != ib[k]) return@Comparator ia[k].compareTo(ib[k])
    }
    ia.size.compareTo(ib.size)
}

private fun inOrder(systems: Collection<System>): List<System> = systems.sortedBy(::orderIndex)

/** Ответ [plan]-овского `lookup`: событие, удалено или неизвестно. */
private sealed interface Lookup {
    data class Alive(val event: CalendarEvent) : Lookup

    data object Gone : Lookup
}

private data class Edge(val link: Link, val src: CalendarEvent, val tgt: CalendarEvent)

private typealias Copied = Triple<System, String?, System>

/**
 * Решить, что создать, обновить, удалить и связать. Без I/O.
 *
 * Порядок действий значим (`apply` выполняет их по очереди, `dependsOn` ссылается на уже
 * выполненные) и совпадает с Python: вопросы о двойниках; удаления и служебные действия по
 * существующим связям; связи двойников по UID; удаления лишних копий; новые копии;
 * обновления и снимки.
 *
 * @param snapshot события каждого прочитанного календаря (порядок систем и событий значим).
 * @param pairs пары систем; участвуют только пары, обе системы которых есть в [snapshot].
 * @param links связи из хранилища (порядок значим).
 * @param found результат [collect] для связанных событий вне окна.
 * @param unavailable системы, не прочитанные в этом запуске из-за сбоя (а не выключенные
 *   направлением или маршрутами): всё, что связано с ними связями, заморожено (см. [frozen]).
 */
fun plan(
    snapshot: Map<System, List<CalendarEvent>>,
    pairs: Collection<Set<System>>,
    links: Collection<Link> = emptyList(),
    found: Map<Member, CalendarEvent?> = emptyMap(),
    unavailable: Set<System> = emptySet(),
): Plan {
    val frozen = frozen(links, unavailable)
    val active: Set<Set<System>> = pairs.filter { snapshot.keys.containsAll(it) }.toSet()
    val sortedActive = active.sortedWith(PAIR_ORDER)

    fun paired(a: System, b: System): Boolean = setOf(a, b) in active

    val listed = LinkedHashMap<Member, CalendarEvent>()
    for ((s, events) in snapshot) for (e in events) if (e.id != null) listed[Member(s, e.id)] = e

    /** Событие, Gone или null (неизвестно). */
    fun lookup(m: Member): Lookup? {
        if (m.id.isNullOrEmpty()) return null
        val event = when {
            m in listed -> listed.getValue(m)
            m in found -> found[m] ?: return Lookup.Gone
            else -> return null
        }
        return if (event.ignored && event.source == null) Lookup.Gone else Lookup.Alive(event)
    }

    val actions = mutableListOf<Action>()
    val copied = mutableSetOf<Copied>() // (система, id, куда) — копировать не нужно
    val refusals = LinkedHashMap<Copied, Link>() // пользователь отказался копировать
    val twinned = mutableSetOf<Member>()
    val distinct = mutableSetOf<Set<Member>>()
    val confirmed = UnionFind() // двойники по связи и UID
    val candidates = UnionFind() // то же + кандидаты по названию и началу
    val edges = mutableListOf<Edge>() // живые связи
    val goneOriginals = mutableSetOf<Member>()
    val linkCount = LinkedHashMap<System, Int>()
    val goneCount = LinkedHashMap<System, Int>()

    // 1. Существующие связи: удаления, вопросы, живые связи
    for (link in links) {
        val a = Member(link.sourceSystem, link.sourceId)
        val b = Member(link.targetSystem, link.targetId)
        if (link.kind == LinkKind.DISTINCT) {
            distinct += setOf(a, b)
            continue
        }
        if (link.isRefusal) {
            refusals[Copied(link.sourceSystem, link.sourceId, link.targetSystem)] = link
            continue
        }
        if (link.targetId == null) continue
        copied += Copied(link.sourceSystem, link.sourceId, link.targetSystem)
        if (link.kind == LinkKind.TWIN) {
            copied += Copied(link.targetSystem, link.targetId, link.sourceSystem)
            twinned += a
            twinned += b
        }
        // Замороженная связь не считается и для защиты от массового удаления
        if (link.kept || !paired(link.sourceSystem, link.targetSystem) || a in frozen) continue
        val src = lookup(a)
        val tgt = lookup(b)
        if (src == null || tgt == null) continue // неизвестно, есть ли событие: не трогаем
        val originals = if (link.kind == LinkKind.COPY) listOf(a) else listOf(a, b)
        for (o in originals) linkCount[o.system] = (linkCount[o.system] ?: 0) + 1
        if (src is Lookup.Gone && tgt is Lookup.Gone) {
            actions += Action.bookkeeping(ActionKind.UNLINK, link)
            continue
        }
        if (src is Lookup.Alive && tgt is Lookup.Alive) {
            edges += Edge(link, src.event, tgt.event)
            if (link.kind == LinkKind.TWIN) {
                confirmed.union(a, b)
                candidates.union(a, b)
            }
            continue
        }
        val gone = if (src is Lookup.Gone) a else b
        val alive = ((if (src is Lookup.Gone) tgt else src) as Lookup.Alive).event
        if (link.kind == LinkKind.COPY && src is Lookup.Gone) {
            goneOriginals += gone
            goneCount[gone.system] = (goneCount[gone.system] ?: 0) + 1
            actions += Action(
                ActionKind.DELETE, link.targetSystem, alive, link.sourceSystem,
                reason = REASON_ORIGINAL_GONE, link = link, silent = true, subject = DeleteSubject.COPY,
            )
            continue
        }
        if (link.kind == LinkKind.TWIN) {
            goneOriginals += gone
            goneCount[gone.system] = (goneCount[gone.system] ?: 0) + 1
        }
        val kept = Action.bookkeeping(ActionKind.LINK, link.copy(kept = true))
        if (!alive.organizerIsMe) {
            actions += kept // чужую встречу не трогаем: просто запоминаем
            continue
        }
        val question = if (link.kind == LinkKind.COPY) REASON_COPY_GONE else REASON_TWIN_GONE
        val targetSystem = if (src !is Lookup.Gone) link.sourceSystem else link.targetSystem
        actions += Action(
            ActionKind.DELETE, targetSystem, alive, gone.system,
            selected = false, reason = question, link = link, fallback = listOf(kept),
            subject = if (link.kind == LinkKind.COPY) DeleteSubject.ORIGINAL else DeleteSubject.TWIN,
        )
    }

    // 2. Новые двойники: по UID — сразу, по названию и началу — вопрос с галочкой,
    // по началу и концу при разных названиях — вопрос без галочки (ADR 0003).
    // Неоднозначность (несколько кандидатов) — первый с первым, как у названия.
    val originals = LinkedHashMap<System, List<CalendarEvent>>()
    for ((s, events) in snapshot) originals[s] = events.filter { it.source == null && !it.ignored }
    val twinActions = mutableListOf<Action>()
    val candidateKeys = LinkedHashMap<Member, String>() // участник кандидатов -> ключ вопроса
    val keyFunctions: List<Pair<(CalendarEvent) -> Any?, TwinMode>> = listOf(
        { e: CalendarEvent -> e.uidKey() } to TwinMode.UID,
        { e: CalendarEvent -> e.twinKey() } to TwinMode.TITLE,
        { e: CalendarEvent -> e.timeKey() } to TwinMode.TIME,
    )
    for (pair in sortedActive) {
        val (s1, s2) = inOrder(pair)
        val used = mutableSetOf<Member>()
        for ((keyOf, mode) in keyFunctions) {
            val pool = LinkedHashMap<Any, MutableList<CalendarEvent>>()
            for (e in originals.getValue(s2)) {
                val k = keyOf(e)
                if (e.id != null && Member(s2, e.id) !in twinned && k != null) {
                    pool.getOrPut(k) { mutableListOf() }.add(e)
                }
            }
            for (e in originals.getValue(s1)) {
                val m1 = Member(s1, e.id)
                val k = keyOf(e)
                if (e.id == null || m1 in twinned || m1 in used || k == null) continue
                val match = pool[k].orEmpty().firstOrNull {
                    Member(s2, it.id) !in used && setOf(m1, Member(s2, it.id)) !in distinct &&
                        (mode != TwinMode.TIME || it.twinKey() != e.twinKey())
                } ?: continue
                val m2 = Member(s2, match.id)
                used += m1
                used += m2
                if (m1 in frozen || m2 in frozen) {
                    // Кандидат замороженного ждёт вместе с ним
                    frozen += m1
                    frozen += m2
                    continue
                }
                val link = Link(s1, e.id, s2, match.id, LinkKind.TWIN, e.fields(), match.fields())
                candidates.union(m1, m2)
                if (mode == TwinMode.UID) {
                    confirmed.union(m1, m2)
                    actions += Action.bookkeeping(ActionKind.LINK, link)
                } else {
                    val action = Action(
                        ActionKind.TWIN, s2, match, s1,
                        selected = mode == TwinMode.TITLE,
                        reason = if (mode == TwinMode.TITLE) REASON_SAME_TITLE else REASON_SAME_TIME,
                        link = link,
                    )
                    twinActions += action
                    candidateKeys[m1] = action.key
                    candidateKeys[m2] = action.key
                }
            }
        }
    }

    fun clusterKeys(m: Member): List<String> {
        val root = candidates.find(m)
        return candidateKeys.filter { (x, _) -> candidates.find(x) == root }.values.toSet()
            .sortedWith(::pyCompare)
    }

    // 3. Лишние копии: копия в системе, где есть двойник её оригинала
    val matchIndex = LinkedHashMap<System, Set<Pair<System, EventKey>>>()
    for ((s, events) in snapshot) {
        matchIndex[s] = events.filter { it.source != null && !it.id.isNullOrEmpty() }
            .map { it.source!! to it.matchKey() }.toSet()
    }
    // Все события, у которых есть связь: копию без связи (создана прежней установкой, связи
    // потерялись) узнаём по метке, названию и началу и «усыновляем» или удаляем (ADR 0005).
    val linkedMembers = links.flatMap { link ->
        listOfNotNull(Member(link.sourceSystem, link.sourceId), link.targetId?.let { Member(link.targetSystem, it) })
    }.toSet()
    val copyOf = LinkedHashMap<Member, Pair<Member, Link>>()
    for (link in links) {
        if (link.kind == LinkKind.COPY && !link.targetId.isNullOrEmpty() && !link.kept) {
            copyOf[Member(link.targetSystem, link.targetId)] = Member(link.sourceSystem, link.sourceId) to link
        }
    }
    val leftovers = mutableSetOf<Member>()
    // Копии членов одной группы двойников в системе, где у группы нет своего события:
    // (система, корень группы) -> [(копия, оригинал, связь)] в порядке календаря
    val third = LinkedHashMap<Pair<System, Member>, MutableList<Triple<CalendarEvent, Member, Link?>>>()
    for ((s, events) in snapshot) {
        for (c in events) {
            val cSource = c.source
            if (cSource == null || c.id == null || !paired(s, cSource)) continue
            if (Member(s, c.id) in frozen) continue
            val entry = copyOf[Member(s, c.id)]
            var origin = entry?.first
            val link = entry?.second
            if (origin == null) {
                val o = originals[cSource].orEmpty().firstOrNull { it.matchKey() == c.matchKey() }
                origin = o?.let { Member(cSource, it.id) }
            }
            if (origin == null && entry == null && Member(s, c.id) !in linkedMembers) {
                // Копия без связи, а живого оригинала нет: если он отменён (в списке, но ignored),
                // удаляем копию, как удалили бы связанную
                val cancelled = snapshot[cSource].orEmpty().firstOrNull {
                    it.ignored && it.source == null && !it.id.isNullOrEmpty() && it.matchKey() == c.matchKey()
                }
                if (cancelled != null) {
                    leftovers += Member(s, c.id)
                    goneOriginals += Member(cSource, cancelled.id)
                    actions += Action(
                        ActionKind.DELETE, s, c, cSource,
                        reason = REASON_ORIGINAL_CANCELLED, silent = true, subject = DeleteSubject.COPY,
                    )
                }
                continue
            }
            if (origin == null || origin in frozen) continue
            val group = candidates.members(origin)
            if (group.none { it.system == s }) {
                if (group.size > 1) {
                    third.getOrPut(s to candidates.find(origin)) { mutableListOf() }.add(Triple(c, origin, link))
                }
                continue
            }
            leftovers += Member(s, c.id)
            val inConfirmed = confirmed.members(origin).any { it.system == s }
            val depends = if (inConfirmed) emptyList() else clusterKeys(origin)
            actions += Action(
                ActionKind.DELETE, s, c, cSource, reason = REASON_EXTRA_COPY,
                link = link, dependsOn = depends, subject = DeleteSubject.EXTRA,
            )
        }
    }
    // Группе двойников в третьей системе хватает одной копии: оставляем копию того, кто
    // копирует (как в covered), а если её нет — первую; копии других членов группы лишние
    for ((place, groupCopies) in third) {
        val (s, root) = place
        val copier = candidates.members(root).first { paired(it.system, s) }
        val keeper = groupCopies.firstOrNull { it.second == copier }?.second ?: groupCopies.first().second
        for ((c, origin, link) in groupCopies) {
            if (origin == keeper) continue
            leftovers += Member(s, c.id)
            val together = confirmed.find(origin) == confirmed.find(keeper)
            val depends = if (together) emptyList() else clusterKeys(origin)
            actions += Action(
                ActionKind.DELETE, s, c, checkNotNull(c.source), reason = REASON_EXTRA_THIRD,
                link = link, dependsOn = depends, subject = DeleteSubject.EXTRA,
            )
        }
    }

    // 4. Новые копии (у двойников — одна на группу)
    val fallbacks = LinkedHashMap<String, MutableList<Action>>()
    val refused = mutableListOf<Action>()
    val adopted = mutableSetOf<Member>()
    for (pair in sortedActive) {
        val (p1, p2) = inOrder(pair)
        for ((originSystem, target) in listOf(p1 to p2, p2 to p1)) {
            val index = matchIndex.getValue(target)
            for (e in originals.getValue(originSystem)) {
                val m = Member(originSystem, e.id)
                if (m in frozen || Copied(originSystem, e.id, target) in copied) continue
                if ((originSystem to e.matchKey()) in index) {
                    adopt(e, originSystem, target, snapshot, linkedMembers, leftovers, adopted)?.let { link ->
                        copied += Copied(originSystem, e.id, target)
                        actions += Action.bookkeeping(ActionKind.LINK, link)
                    }
                    continue
                }
                val create = Action(ActionKind.CREATE, target, e.copy(source = originSystem), originSystem)
                if (covered(m, target, confirmed, copied, index, listed, active)) continue
                val refusedLink = refusals[Copied(originSystem, e.id, target)]
                if (refusedLink != null) {
                    refused += create.copy(link = refusedLink)
                    continue
                }
                if (covered(m, target, candidates, copied, index, listed, active)) {
                    fallbacks.getOrPut(clusterKeys(m).first()) { mutableListOf() }.add(create)
                    continue
                }
                // Снятая галочка при ручном применении — отказ от копирования
                actions += if (e.id != null) {
                    create.copy(fallback = listOf(Action.bookkeeping(ActionKind.LINK, Link.refusal(originSystem, e.id, target))))
                } else {
                    create
                }
            }
        }
    }

    // 5. Обновления по снимку
    planUpdates(edges, leftovers, actions)

    // Вопросы о двойниках — первыми: от них зависят удаления лишних копий.
    // Если пользователь откажется, копируем как обычно и запоминаем отказ.
    val questions = twinActions.map { twin ->
        val link = checkNotNull(twin.link)
        val distinctLink = link.copy(kind = LinkKind.DISTINCT, sourceFields = null, targetFields = null)
        twin.copy(fallback = fallbacks[twin.key].orEmpty() + Action.bookkeeping(ActionKind.LINK, distinctLink))
    }
    var result: List<Action> = questions + actions

    // 6. Защита от массового удаления
    val warnings = mutableListOf<String>()
    val heavy = linkCount.filter { (s, count) ->
        count >= MASS_DELETE_MIN_LINKS && (goneCount[s] ?: 0).toDouble() / count > MASS_DELETE_SHARE
    }
    if (goneOriginals.size > MASS_DELETE_ORIGINALS || heavy.isNotEmpty()) {
        warnings += "Исчезло много событий (${goneOriginals.size}): удаления не выбраны, проверьте их"
        result = result.map { if (it.kind == ActionKind.DELETE) it.copy(selected = false, silent = false) else it }
    }
    return Plan(result, warnings, refused)
}

/**
 * События, связанные с недоступной системой: в этом запуске их не трогаем.
 *
 * Группа — события, соединённые связями копий (в любую сторону) и двойников, транзитивно.
 * Если в группе есть событие недоступной системы, заморожена вся группа: ни созданий,
 * ни обновлений, ни удалений, ни вопросов. Связи kept (вторая сторона уже удалена),
 * distinct и отказы событий не соединяют.
 */
private fun frozen(links: Collection<Link>, unavailable: Set<System>): MutableSet<Member> {
    if (unavailable.isEmpty()) return mutableSetOf()
    val uf = UnionFind()
    for (link in links) {
        if (link.kind == LinkKind.DISTINCT || link.kept || link.targetId.isNullOrEmpty()) continue
        uf.union(Member(link.sourceSystem, link.sourceId), Member(link.targetSystem, link.targetId))
    }
    val members = uf.parent.keys.toList()
    val roots = members.filter { it.system in unavailable }.map { uf.find(it) }.toSet()
    return members.filter { uf.find(it) in roots }.toMutableSet()
}

/**
 * Связь для копии оригинала [e] в [target], найденной по метке, названию и началу, если у копии
 * ещё нет связи (ADR 0005). Снимки — текущие поля обеих сторон: дальше правки синхронизируются
 * как у обычной копии. `null` — копия уже связана, будет удалена как лишняя или усыновлена другим.
 */
private fun adopt(
    e: CalendarEvent,
    origin: System,
    target: System,
    snapshot: Map<System, List<CalendarEvent>>,
    linked: Set<Member>,
    leftovers: Set<Member>,
    adopted: MutableSet<Member>,
): Link? {
    val originId = e.id ?: return null
    val copy = snapshot[target].orEmpty().firstOrNull { c ->
        val m = Member(target, c.id)
        c.source == origin && !c.id.isNullOrEmpty() && c.matchKey() == e.matchKey() &&
            m !in linked && m !in leftovers && m !in adopted
    } ?: return null
    adopted += Member(target, copy.id)
    return Link(origin, originId, target, copy.id, sourceFields = e.fields(), targetFields = copy.fields())
}

/** Вид совпадения при поиске двойников: UID — связать сразу; название, время — вопрос. */
private enum class TwinMode { UID, TITLE, TIME }

/** Группа двойников уже есть в target (событием или копией) или копию делает другой. */
private fun covered(
    m: Member,
    target: System,
    uf: UnionFind,
    copied: Set<Copied>,
    copiesInTarget: Set<Pair<System, EventKey>>,
    listed: Map<Member, CalendarEvent>,
    active: Set<Set<System>>,
): Boolean {
    val group = uf.members(m)
    for (x in group) {
        if (x.system == target || Copied(x.system, x.id, target) in copied) return true
        val event = listed[x]
        if (event != null && (x.system to event.matchKey()) in copiesInTarget) return true
    }
    val copier = group.firstOrNull { setOf(it.system, target) in active } ?: m
    return copier != m
}

/** Слить изменения внутри каждой группы связанных событий и записать их во все стороны. */
private fun planUpdates(edges: List<Edge>, leftovers: Set<Member>, actions: MutableList<Action>) {
    val uf = UnionFind()
    val events = LinkedHashMap<Member, CalendarEvent>()
    val live = mutableListOf<Triple<Link, Member, Member>>()
    for ((link, src, tgt) in edges) {
        val a = Member(link.sourceSystem, link.sourceId)
        val b = Member(link.targetSystem, link.targetId)
        if (a in leftovers || b in leftovers) continue
        live += Triple(link, a, b)
        events[a] = src
        events[b] = tgt
        uf.union(a, b)
    }

    for (group in uf.groups()) {
        val groupSet = group.toSet()
        val groupEdges = live.filter { it.second in groupSet }
        // Оригиналы раньше копий, корень — первый оригинал
        val ordered = group.sortedWith(
            compareBy<Member> { events.getValue(it).source != null }.then(MEMBER_ORDER),
        )
        val base = LinkedHashMap<Member, Fields?>()
        val parent = LinkedHashMap<Member, Member>()
        for ((link, a, b) in groupEdges) {
            // Снимок берём из первой связи, где он есть: старая связь без снимка
            // не должна скрывать снимок из другой связи того же события
            if (base[a] == null) base[a] = link.sourceFields
            if (base[b] == null) base[b] = link.targetFields
            if (link.kind == LinkKind.COPY) parent[b] = a
        }
        val current = group.associateWith { events.getValue(it).fields() }

        // Если в группе есть чужая встреча, изменения берём только из неё
        val authority = ordered.filter {
            val e = events.getValue(it)
            e.source == null && !e.organizerIsMe
        }
        val changers = authority.ifEmpty { ordered }
        val winners = LinkedHashMap<List<String>, Member>()
        for (unit in UNITS) {
            var winner: Member? = null
            for (m in changers) {
                val snap = base[m]
                if (snap == null || unit.all { current.getValue(m)[it] == snap[it] }) continue
                if (winner == null || later(events.getValue(m), events.getValue(winner))) winner = m
            }
            if (winner != null) winners[unit] = winner
        }

        val updates = mutableListOf<Action>()
        val originalKeys = mutableListOf<String>() // копии обновляем только после оригиналов
        val newSnap = LinkedHashMap<Member, Fields>()
        for (m in ordered) {
            val event = events.getValue(m)
            val cur = current.getValue(m)
            val units = winners.filter { (u, w) -> u.any { cur[it] != current.getValue(w)[it] } }.keys.toList()
            val isOriginal = event.source == null
            if (units.isEmpty() || (isOriginal && !event.organizerIsMe)) {
                newSnap[m] = cur
                continue
            }
            var newEvent = event
            for (u in units) newEvent = takeUnit(newEvent, events.getValue(winners.getValue(u)), u)
            val fields = newEvent.fields()
            newSnap[m] = fields
            val changed = units.flatten().filter { cur[it] != fields[it] }
            val origin = if (isOriginal) winners.getValue(units[0]).system else (parent[m] ?: m).system
            val selected = !(isOriginal && event.hasAttendees)
            val action = Action(
                ActionKind.UPDATE, m.system, newEvent, origin, selected = selected,
                reason = if (selected) "" else REASON_ATTENDEES,
                changed = changed, dependsOn = if (isOriginal) emptyList() else originalKeys.toList(),
            )
            updates += action
            if (isOriginal) originalKeys += action.key
        }
        actions += updates
        val updateKeys = updates.map { it.key }
        for ((link, a, b) in groupEdges) {
            val fresh = link.copy(sourceFields = newSnap.getValue(a), targetFields = newSnap.getValue(b))
            if (fresh != link) {
                actions += Action.bookkeeping(ActionKind.LINK, fresh).copy(dependsOn = updateKeys)
            }
        }
    }
}

/** Поля группы [unit] из события-победителя [from]. */
private fun takeUnit(event: CalendarEvent, from: CalendarEvent, unit: List<String>): CalendarEvent =
    when (unit.first()) {
        "title" -> event.copy(title = from.title)
        "start" -> event.copy(start = from.start, end = from.end)
        "location" -> event.copy(location = from.location)
        "description" -> event.copy(description = from.description)
        else -> error("Неизвестная группа полей: $unit")
    }

/** Изменение candidate новее? Если время неизвестно или равно — побеждает текущий. */
private fun later(candidate: CalendarEvent, current: CalendarEvent): Boolean {
    val c = candidate.modified ?: return false
    val w = current.modified ?: return false
    return c > w
}

/**
 * Оставить действия, которые нужно выполнить.
 *
 * Служебные связи выполняются всегда, «тихие» — в пределах маршрутов (origin, target).
 * Остальные: без [keys] — выбранные по умолчанию, с [keys] — ровно эти ключи. Если ключи
 * переданы и действие в них не попало, выполняется его fallback (запомнить отказ: связь
 * `kept`/`distinct` или отказ от копирования; скопировать недвойников). Вопрос о двойниках
 * проходит маршрут в любую сторону.
 *
 * @param routes маршруты `(origin, target)`; `null` — все.
 * @param keys ключи, отмеченные в предпросмотре; `null` — выбор по умолчанию.
 * @param refused [Plan.refused]: отказы, чьи ключи есть в [keys], выполняются в конце (копия
 *   создаётся, отказ заменяется связью копии). Без [keys] отказы не трогаются.
 */
fun select(
    actions: Iterable<Action>,
    routes: Collection<Pair<System, System>>? = null,
    keys: Collection<String>? = null,
    refused: Iterable<Action> = emptyList(),
): List<Action> {
    fun inRoute(a: Action): Boolean {
        if (routes == null || a.kind.isBookkeeping) return true
        if (a.kind == ActionKind.TWIN) return (a.origin to a.target) in routes || (a.target to a.origin) in routes
        return (a.origin to a.target) in routes
    }

    val chosen = mutableListOf<Action>()
    for (action in actions) {
        if (!inRoute(action)) continue
        if (action.kind.isBookkeeping || action.silent) {
            chosen += action
        } else if (if (keys == null) action.selected else action.key in keys) {
            chosen += action
        } else if (keys != null) {
            chosen += action.fallback.filter(::inRoute)
        }
    }
    if (keys != null) chosen += refused.filter { it.key in keys && inRoute(it) }
    return chosen
}

/** Отменить отказ от копирования (`undo_refusal`): следующий план снова предложит копию. */
fun undoRefusal(link: Link, store: LinkStore) {
    require(link.isRefusal) { "Связь ${link.key()} — не отказ от копирования" }
    store.drop(link)
}
