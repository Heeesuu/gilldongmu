package com.gildongmu.app

import android.app.Application

/**
 * 앱 하나에 [Core] 하나를 둔다. 포그라운드 서비스와 화면이 같은 Core를 쓴다.
 * 화면을 닫아도(뒤로 가기, 최근 앱에서 지우기) 서비스가 살아 있으면 Core도 그대로다.
 */
class GildongmuApp : Application() {

    var core: Core? = null
        private set

    /** Core가 없으면 만들고 시작한다. */
    fun core(): Core = core ?: Core(this).also {
        core = it
        it.start()
    }

    /** 알림의 "끄기": Core를 정리한다. 다음에 앱을 열면 새로 만든다. */
    fun stopCore() {
        core?.release()
        core = null
    }
}
