package app.gcaltolotus.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Экраны приложения. Четыре раздела в нижней панели и один вложенный экран (выбор календаря
 * Google внутри «Подключений»). Экранов мало и они не передают друг другу аргументов, поэтому
 * библиотека навигации не нужна.
 *
 * @property parent раздел, в который ведёт «Назад»; `null` — это сам раздел.
 */
enum class Screen(val parent: Screen? = null) {
    STATUS,
    SYNC,
    CONNECTIONS,
    SETTINGS,
    GOOGLE_CALENDAR(parent = CONNECTIONS),
    ;

    /** Раздел нижней панели, к которому относится экран. */
    val tab: Screen get() = parent ?: this

    companion object {
        val TABS: List<Screen> = listOf(STATUS, SYNC, CONNECTIONS, SETTINGS)
    }
}

/** Текущий экран. */
class Navigator(start: Screen = Screen.STATUS) {
    private val current = MutableStateFlow(start)
    val screen: StateFlow<Screen> = current.asStateFlow()

    fun open(screen: Screen) {
        current.value = screen
    }

    /**
     * «Назад»: из вложенного экрана — в его раздел, из остальных разделов — на «Состояние»
     * (главный экран). `false` — уже на «Состоянии», назад некуда (приложение закрывает платформа).
     */
    fun back(): Boolean {
        val screen = current.value
        if (screen == Screen.STATUS) return false
        current.value = screen.parent ?: Screen.STATUS
        return true
    }
}
