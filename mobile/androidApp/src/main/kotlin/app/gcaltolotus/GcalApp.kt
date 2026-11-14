package app.gcaltolotus

import android.app.Application

/** Приложение: держит [AppGraph] и при старте планирует фоновую синхронизацию по настройкам. */
class GcalApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }
}
