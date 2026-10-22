package io.embodit.lifesize.ui.room

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.lifesize.R
import io.embodit.lifesize.app.CreatureState
import io.embodit.lifesize.app.CreatureStore
import io.embodit.lifesize.domain.Copy
import io.embodit.lifesize.domain.Placed
import io.embodit.lifesize.ui.theme.LifeColors
import io.embodit.lifesize.ui.theme.LifeType

// Panel sizes (design panels[].sizeDp; 1 dp = 0.8 mm at scale 1). Fixed: the pool declares them once.
val SizeTagWidth = 320.dp
val SizeTagHeight = 104.dp
val ReadingWidth = 120.dp
val ReadingHeight = 48.dp
val YouLabelWidth = 160.dp
val YouLabelHeight = 40.dp
val InfoCardWidth = 480.dp
val InfoCardHeight = 420.dp

/**
 * Room panels are an opaque slate, not the glass of the window: a `backgroundMaterial` on a Stage
 * attachment panel draws black bands on the 6.1 emulator (Qianliyan QA q1-1 / q2-1). Logged as a
 * within-tolerance deviation.
 */
private val PanelShape = RoundedCornerShape(20.dp)

@Composable
private fun Slate(width: Dp, height: Dp, content: @Composable () -> Unit) {
    Box(modifier = Modifier.size(width, height).background(LifeColors.PanelSlate, PanelShape)) { content() }
}

/** Design panel `sizeTag`: name, length and height, and the room line or the 1:n badge. */
@Composable
fun SizeTag(state: CreatureState?, p: Placed?) {
    if (state == null || p == null) return Box(Modifier.size(SizeTagWidth, SizeTagHeight))
    val c = Copy(state.lang)
    val s = p.species
    val fit = state.fit(p.copy(scaleN = 1))
    Slate(SizeTagWidth, SizeTagHeight) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = s.name(state.lang), style = LifeType.title, color = PicoTheme.colorScheme.labelPrimaryLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = c.lengthAndHeight(s), style = LifeType.body, color = PicoTheme.colorScheme.labelPrimaryLight, maxLines = 1)
            when {
                p.scaleN > 1 -> Pill(c.scaledBadge(p.scaleN))
                fit.overHighM != null -> Text(text = c.tallerThanRoom(fit.overHighM), style = LifeType.body, color = PicoTheme.colorScheme.alert, maxLines = 1)
                fit.overLongM != null -> Text(text = c.longerThanRoom(fit.overLongM), style = LifeType.body, color = PicoTheme.colorScheme.alert, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Box(modifier = Modifier.background(PicoTheme.colorScheme.alert, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 2.dp)) {
        Text(text = text, style = LifeType.label, color = LifeColors.TapeInk, maxLines = 1)
    }
}

/** The tape's reading plate at the middle of the tape: the true length, also at 1:n. */
@Composable
fun TapeReading(p: Placed?) {
    if (p == null) return Box(Modifier.size(ReadingWidth, ReadingHeight))
    Box(modifier = Modifier.size(ReadingWidth, ReadingHeight).background(LifeColors.TapeYellow, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(text = Copy.m(p.species.lengthM), style = LifeType.reading, color = LifeColors.TapeInk, maxLines = 1)
    }
}

/** "你 1.68 m" at the feet of the figure. */
@Composable
fun YouLabel(state: CreatureState?) {
    if (state == null) return Box(Modifier.size(YouLabelWidth, YouLabelHeight))
    Box(modifier = Modifier.size(YouLabelWidth, YouLabelHeight).background(LifeColors.PanelSlate, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(text = Copy(state.lang).you(state.youHeightM), style = LifeType.label, color = LifeColors.YouCyan, maxLines = 1)
    }
}

/** Design panel `infoCard`: names, three figures, habitat, one fact, the scale segment (too big only), turn / send back. */
@Composable
fun InfoCard(state: CreatureState?) {
    val p = state?.animal(state.cardUid) ?: return Box(Modifier.size(InfoCardWidth, InfoCardHeight))
    val c = Copy(state.lang)
    val s = p.species
    val fit = state.fit(p.copy(scaleN = 1))
    val ink = PicoTheme.colorScheme.labelPrimaryLight
    val soft = ink.copy(alpha = 0.7f)
    Slate(InfoCardWidth, InfoCardHeight) {
        Column(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "${s.zh}  ${s.en}", style = LifeType.cardTitle, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(text = s.latin, style = LifeType.label.copy(fontStyle = FontStyle.Italic), color = soft, maxLines = 1)
                }
                CardIcon(R.drawable.ic_close, c.close) { CreatureStore.cardClosed() }
            }
            Spacer(Modifier.height(10.dp))
            DataRow(c.rowLength, "${Copy.m(s.lengthM)}  (${c.lengthNote(s)})")
            DataRow(c.rowHeight, "${Copy.m(s.heightM)}  (${c.heightWord(s)})")
            DataRow(c.rowMass, Copy.kg(s.massKg))
            Spacer(Modifier.height(8.dp))
            Text(text = s.habitat(state.lang), style = LifeType.body, color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(text = s.fact(state.lang), style = LifeType.body, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (fit.tooBig) {
                ScaleSegment(trueSize = p.scaleN == 1, n = fit.n, c = c)
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = s.model.credit, style = LifeType.label, color = ink.copy(alpha = 0.5f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                CardIcon(R.drawable.ic_rotate, c.rotate) { CreatureStore.rotateClicked() }
                Spacer(Modifier.width(8.dp))
                CardIcon(R.drawable.ic_send_back, c.sendBack) { CreatureStore.sendBackClicked() }
            }
        }
    }
}

@Composable
private fun DataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = LifeType.body, color = PicoTheme.colorScheme.labelPrimaryLight.copy(alpha = 0.7f), maxLines = 1, modifier = Modifier.width(72.dp))
        Text(text = value, style = LifeType.body, color = PicoTheme.colorScheme.labelPrimaryLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CardIcon(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        size = IconButtonDefaults.iconButtonSize(width = 48.dp, height = 48.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillPrimary, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        Icon(painter = painterResource(icon), contentDescription = description, modifier = Modifier.size(22.dp), tint = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
private fun ScaleSegment(trueSize: Boolean, n: Int, c: Copy) {
    SegmentControl(backgroundColor = PicoTheme.colorScheme.fillPrimary) {
        listOf(true to c.trueSize, false to c.fitRoom(n)).forEach { (option, label) ->
            SegmentItem(
                selected = trueSize == option,
                onClick = { CreatureStore.scaleSelected(fitRoom = !option) },
                modifier = Modifier.width(150.dp).height(44.dp),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimaryLight,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.interaction,
                        selectedItemContentColor = LifeColors.TapeInk,
                    ),
                // design-style: inherited-content-color SegmentItem
                title = { Text(text = label, maxLines = 1, softWrap = false) },
            )
        }
    }
}
