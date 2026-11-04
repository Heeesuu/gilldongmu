package com.gildongmu.app.gleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GleoCommandsTest {

    @Test
    fun `액션과 command 값을 명령으로 바꾼다`() {
        assertEquals(GleoCommand.LISTEN, GleoCommands.of("CONTROL_ROAD_MESSAGES", "listen"))
        assertEquals(GleoCommand.SKIP, GleoCommands.of("CONTROL_ROAD_MESSAGES", "Skip"))
        assertEquals(GleoCommand.STOP, GleoCommands.of("CONTROL_ROAD_MESSAGES", "stop"))
        assertEquals(GleoCommand.THANK, GleoCommands.of("THANK_ROAD_MESSAGE", null))
        assertEquals(GleoCommand.RECORD, GleoCommands.of("RECORD_ROAD_MESSAGE", null))
        assertNull(GleoCommands.of("CONTROL_ROAD_MESSAGES", "rewind"))
        assertNull(GleoCommands.of("PLAY_MUSIC", null))
    }

    // 스키마·매니페스트·코드가 같은 함수 이름을 쓰는지 확인한다. 하나만 바꾸면 Gleo 액션이 앱에 닿지 않는다.
    @Test
    fun `스키마의 함수는 셋 이하이고 매니페스트와 코드에 모두 있다`() {
        val schema = File("src/main/assets/schema.json").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val names = Regex("\"name\"\\s*:\\s*\"([A-Z_]+)\"").findAll(schema).map { it.groupValues[1] }.toList()
        assertTrue("함수는 셋까지만 둘 수 있다: $names", names.size in 1..3)
        assertEquals(setOf(GleoCommands.CONTROL, GleoCommands.THANK, GleoCommands.RECORD), names.toSet())
        names.forEach { assertTrue("매니페스트에 $it 액션이 없다", "android:name=\"$it\"" in manifest) }
    }
}
