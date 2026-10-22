package io.embodit.lifesize.ui.room

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.SpatialSoundEffect
import io.embodit.lifesize.app.CreatureStore
import io.embodit.lifesize.platform.LifeLog
import io.embodit.lifesize.ui.room.debug.Probe

/** Id of the secondary Stage (declared in Main.kt, opened from the guide window with style Mixed). */
const val ROOM_STAGE_ID = "RoomStage"

/**
 * The Full Space room: one full-size SpatialView that carries the 3D scene and the panel pool (size
 * tags, tape readings, the "you" label, the info card). Every panel is declared here once with a fixed
 * size; [RoomScene] parents each under its holder and shows or hides the holder.
 *
 * Lifecycle (design container.lifecycle): the scene starts plane, head and controller tracking when the
 * Stage opens; leaving the Stage (exit key, system exit, background) stops them, destroys every entity
 * and puts the room state back to the Shared Space.
 */
@Composable
fun RoomScreen() {
    val context = LocalContext.current
    val state by CreatureStore.state.collectAsStateWithLifecycle()
    val scene = remember { RoomScene() }
    val audio = LocalAudioEffectPlayer.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }

    DisposableEffect(scene) {
        registerSystem<RoomFrameSystem>()
        // I13: the call of the animal. No CC0 call files are bundled yet: the system cue plays (deviation)
        scene.onCall = { species ->
            LifeLog.i("SOUND call ${species.id}")
            runCatching { audio.playSystem(SpatialSoundEffect.StateOn) }
        }
        LifeLog.i("STAGE enter")
        onDispose {
            unregisterSystem<RoomFrameSystem>()
            scene.detach()
            CreatureStore.stageClosed()
            LifeLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier.fillMaxSize()
                // tap and drag live in separate pointerInput blocks (Spatial Ruler finding)
                .pointerInput(Unit) { detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { scene.onSpatialTap(it) } }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { _, value -> scene.onSpatialDrag("start", value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", value) },
                    ) { value ->
                        scene.onSpatialDrag("move", value)
                    }
                },
        attachments = {
            for (i in 0 until 2) {
                AttachmentPanel(PanelIds.tag(i)) { SizeTag(state = if (scene.panelShown[PanelIds.tag(i)] == true) state else null, p = state.animals.getOrNull(i)) }
                AttachmentPanel(PanelIds.read(i)) { TapeReading(p = if (scene.panelShown[PanelIds.read(i)] == true) state.animals.getOrNull(i) else null) }
            }
            AttachmentPanel(PanelIds.YOU) { YouLabel(state = if (scene.panelShown[PanelIds.YOU] == true) state else null) }
            AttachmentPanel(PanelIds.CARD) { InfoCard(state = if (scene.panelShown[PanelIds.CARD] == true) state else null) }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}
