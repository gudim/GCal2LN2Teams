package app.gcaltolotus.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.gcaltolotus.MainActivity

/**
 * Возврат из браузера после входа ([LoopbackHttp.RETURN_TO_APP]). Вкладка входа (Custom Tab)
 * лежит в задаче приложения поверх [MainActivity]; запуск [MainActivity] с CLEAR_TOP закрывает
 * её, и пользователь сразу видит приложение. Сама активность ничего не показывает.
 */
class ReturnToAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
