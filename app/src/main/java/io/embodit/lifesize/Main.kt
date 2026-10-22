package io.embodit.lifesize

import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import io.embodit.lifesize.ui.guide.GuideWindow
import io.embodit.lifesize.ui.room.ROOM_STAGE_ID
import io.embodit.lifesize.ui.room.RoomScreen
import io.embodit.lifesize.ui.theme.LifeTheme

/**
 * App entry (design container `ON_PLAIN + STAGE_MIXED`, skeleton B): the field guide is the default
 * planar window in the Shared Space (AndroidManifest `FieldGuide`, 440 x 640 dp, system glass); the room
 * is a secondary Stage the guide opens with style Mixed when an animal is chosen.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { LifeTheme { GuideWindow() } }
        Stage(id = ROOM_STAGE_ID) { LifeTheme { RoomScreen() } }
    }
