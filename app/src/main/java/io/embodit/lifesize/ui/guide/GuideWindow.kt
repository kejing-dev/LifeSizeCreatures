package io.embodit.lifesize.ui.guide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import io.embodit.lifesize.R
import io.embodit.lifesize.app.CreatureState
import io.embodit.lifesize.app.CreatureStore
import io.embodit.lifesize.app.Hint
import io.embodit.lifesize.app.hint
import io.embodit.lifesize.domain.Copy
import io.embodit.lifesize.domain.Lang
import io.embodit.lifesize.domain.Phase
import io.embodit.lifesize.domain.Roster
import io.embodit.lifesize.domain.Species
import io.embodit.lifesize.platform.LifeLog
import io.embodit.lifesize.ui.room.ROOM_STAGE_ID
import io.embodit.lifesize.ui.theme.LifeColors
import io.embodit.lifesize.ui.theme.LifeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The field guide (design panel `fieldGuide`): the planar window in the Shared Space, 440 x 640 dp with
 * the system glass. It stays where the user put it when the app enters the Full Space (spike
 * 2026-10-21: the window is still shown and takes pinches after `openStage`), so it is the guide in
 * both spaces. It also lends the Stage navigator to the store and closes the Stage when it goes away.
 */
@Composable
fun GuideWindow() {
    val state by CreatureStore.state.collectAsStateWithLifecycle()
    StageBridge()
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        TopBar(state)
        Spacer(Modifier.height(8.dp))
        TileGrid(state)
        Spacer(Modifier.height(8.dp))
        HintLine(state)
    }
}

/** Opens / closes the room Stage for the store; closes it when the window is destroyed (doc sample). */
@Composable
private fun StageBridge() {
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(navigator, lifecycleOwner) {
        CreatureStore.stageHost =
            object : CreatureStore.StageHost {
                override fun openStage() {
                    scope.launch(Dispatchers.Main.immediate) {
                        val result = runCatching { navigator.openStage(ROOM_STAGE_ID, StageStyle.Mixed) }
                        val r = result.getOrNull()
                        when (r) {
                            is OpenStageResult.Allowed -> CreatureStore.stageResult(true, "Allowed")
                            is OpenStageResult.NotAllowed -> CreatureStore.stageResult(false, "NotAllowed")
                            is OpenStageResult.Error -> CreatureStore.stageResult(false, "Error ${r.code} ${r.reason}")
                            null -> CreatureStore.stageResult(false, "threw ${result.exceptionOrNull()}")
                        }
                    }
                }

                override fun closeStage() {
                    scope.launch(Dispatchers.Main.immediate) { runCatching { navigator.closeStage() }.onFailure { LifeLog.w("STAGE close failed: $it") } }
                }
            }
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    LifeLog.i("GUIDE destroyed: closing the Stage")
                    scope.launch(Dispatchers.Main.immediate) { runCatching { navigator.closeStage() } }
                    CreatureStore.stageClosed()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CreatureStore.stageHost = null
        }
    }
}

private val TopBarHeight = 56.dp

@Composable
private fun TopBar(state: CreatureState) {
    val c = Copy(state.lang)
    Row(modifier = Modifier.fillMaxWidth().height(TopBarHeight), verticalAlignment = Alignment.CenterVertically) {
        if (state.inFullSpace) {
            Button(
                onClick = { CreatureStore.exitClicked() },
                size = ButtonDefaults.Small,
                colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = LifeColors.TapeInk),
            ) {
                Text(text = c.exitFull, maxLines = 1, color = LifeColors.TapeInk)
            }
        } else {
            Text(text = c.title, style = LifeType.title, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        LanguageSegment(state.lang)
        if (state.inFullSpace) {
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { CreatureStore.clearClicked() },
                enabled = state.animals.isNotEmpty(),
                size = IconButtonDefaults.iconButtonSize(width = 40.dp, height = 40.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
            ) {
                Icon(painter = painterResource(R.drawable.ic_trash), contentDescription = c.clearAll, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
            }
        }
    }
}

@Composable
private fun LanguageSegment(lang: Lang) {
    SegmentControl(modifier = Modifier.width(112.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        listOf(Lang.ZH to Copy(Lang.ZH).langZh, Lang.EN to Copy(Lang.EN).langEn).forEach { (option, label) ->
            SegmentItem(
                selected = lang == option,
                onClick = { CreatureStore.langSelected(option) },
                modifier = Modifier.width(52.dp).height(40.dp),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                // design-style: inherited-content-color SegmentItem
                title = { Text(text = label, maxLines = 1, softWrap = false) },
            )
        }
    }
}

private val TileWidth = 200.dp
private val TileHeight = 112.dp
private val TileGap = 8.dp

@Composable
private fun TileGrid(state: CreatureState) {
    Column(verticalArrangement = Arrangement.spacedBy(TileGap)) {
        Roster.all.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) { row.forEach { Tile(it, state) } }
        }
    }
}

@Composable
private fun Tile(species: Species, state: CreatureState) {
    val selected = state.placing == species.id || (state.phase == Phase.ENTERING && state.pending == species.id)
    val placed = state.animals.any { it.speciesId == species.id }
    val loading = state.animals.any { it.speciesId == species.id && it.loading } || (state.phase == Phase.ENTERING && state.pending == species.id)
    val enabled = state.tilesEnabled
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier =
            Modifier.size(TileWidth, TileHeight)
                .alpha(if (enabled || selected) 1f else DisabledAlpha)
                .then(if (selected) Modifier.border(2.dp, PicoTheme.colorScheme.interaction, shape) else Modifier)
                .background(PicoTheme.colorScheme.fillTertiary, shape)
                .then(if (enabled) Modifier.spatialHoverEffect() else Modifier)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, onClick = { CreatureStore.tileClicked(species.id) })
                .controllerHapticFeedback(interactionSource = interaction)
                .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column {
            ScaleDrawing(species, Modifier.fillMaxWidth().height(56.dp))
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = species.name(state.lang),
                    style = LifeType.tileName,
                    color = PicoTheme.colorScheme.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(text = Copy.m(species.lengthM), style = LifeType.label, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
            }
        }
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PicoTheme.colorScheme.interaction, progressSize = CircularProgressDefaults.circleProgressSize(16.dp))
                placed -> Box(Modifier.size(10.dp).background(PicoTheme.colorScheme.passable, CircleShape))
            }
        }
    }
}

private const val DisabledAlpha = 0.3f
private const val PERSON_M = 1.7f

/**
 * The animal and a person drawn at one scale (design: "动物剪影与一个小人剪影同比例"): the side
 * silhouette rendered from the model itself (tools/prep_models.py) and a 1.7 m figure on the same
 * ground line, the person on the left.
 */
@Composable
private fun ScaleDrawing(species: Species, modifier: Modifier) {
    val context = LocalContext.current
    val image = ImageBitmap.imageResource(context.resources, silhouetteOf(species.id))
    // the window's system glass is light on PICO OS 6.1: the silhouette takes the adaptive label colour (deviation from silhouetteInk)
    val inkColor = PicoTheme.colorScheme.labelPrimary.copy(alpha = 0.85f)
    Canvas(modifier = modifier) {
        val gap = 0.25f
        val personW = PERSON_M * 0.28f
        val worldW = personW + gap + species.fullLengthM
        val worldH = maxOf(species.topM, PERSON_M)
        val k = minOf(size.width / worldW, size.height / worldH)
        val ground = size.height
        drawPerson(Offset(0f, ground), PERSON_M * k)
        val w = species.fullLengthM * k
        val h = species.sizeM.y * k
        val left = (personW + gap) * k
        val bottom = ground - species.floatM * k
        val ink = inkColor
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(left.toInt(), (bottom - h).toInt()),
            dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
            colorFilter = ColorFilter.tint(ink),
        )
    }
}

/** A plain standing figure (head, body, legs) of height [h], feet at [feet], in the "you" colour. */
private fun DrawScope.drawPerson(feet: Offset, h: Float) {
    val color = LifeColors.YouCyan
    val head = h * 0.13f
    val bodyW = h * 0.22f
    drawCircle(color, radius = head / 2f, center = Offset(feet.x + bodyW / 2f, feet.y - h + head / 2f))
    drawRoundRect(color, topLeft = Offset(feet.x, feet.y - h + head * 1.08f), size = Size(bodyW, h * 0.42f), cornerRadius = CornerRadius(bodyW * 0.35f))
    val legW = bodyW * 0.4f
    drawRoundRect(color, topLeft = Offset(feet.x + bodyW * 0.06f, feet.y - h * 0.47f), size = Size(legW, h * 0.47f), cornerRadius = CornerRadius(legW / 2f))
    drawRoundRect(color, topLeft = Offset(feet.x + bodyW * 0.54f, feet.y - h * 0.47f), size = Size(legW, h * 0.47f), cornerRadius = CornerRadius(legW / 2f))
}

private fun silhouetteOf(id: String): Int =
    when (id) {
        "shiba" -> R.drawable.sil_shiba
        "wolf" -> R.drawable.sil_wolf
        "velociraptor" -> R.drawable.sil_velociraptor
        "stag" -> R.drawable.sil_stag
        "horse" -> R.drawable.sil_horse
        "shark" -> R.drawable.sil_shark
        "triceratops" -> R.drawable.sil_triceratops
        else -> R.drawable.sil_trex
    }

@Composable
private fun HintLine(state: CreatureState) {
    val hint = state.hint()
    val c = Copy(state.lang)
    val color =
        when (hint.kind) {
            Hint.Kind.ERROR -> PicoTheme.colorScheme.error
            else -> PicoTheme.colorScheme.labelPrimary
        }
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        when (hint.kind) {
            Hint.Kind.PROGRESS -> CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            Hint.Kind.ERROR -> Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(20.dp), tint = color)
            Hint.Kind.NORMAL -> Unit
        }
        if (hint.kind != Hint.Kind.NORMAL) Spacer(Modifier.width(8.dp))
        Text(text = hint.text, style = LifeType.body, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (state.phase == Phase.NO_FLOOR) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = { CreatureStore.retryClicked() }, size = ButtonDefaults.Small) {
                Icon(painter = painterResource(R.drawable.ic_retry), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = c.retry, maxLines = 1)
            }
        }
    }
}
