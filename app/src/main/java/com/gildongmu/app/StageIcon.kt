package com.gildongmu.app

import com.gildongmu.app.data.Stage

/** 단계별 아이콘. 주행 중에도 보이므로 움직이지 않는 그림만 쓴다 (주행 규제: 애니메이션 금지). */
object StageIcon {
    fun of(stage: Stage) = when (stage) {
        Stage.SEED -> R.drawable.ic_stage_seed
        Stage.SPROUT -> R.drawable.ic_stage_sprout
        Stage.FLOWER -> R.drawable.ic_stage_flower
        Stage.MEMORY -> R.drawable.ic_stage_memory
    }
}
