package io.embodit.lifesize.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design-package `palette`). The only file with colour literals. The four
 * semantic roles go into `PicoTheme` through [LifeTheme]; the brand tokens are used directly on domain
 * content (tape, the "you" figure, the ceiling line, tile silhouettes, the placing ghost).
 */
object LifeColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error: no floor / could not enter / load failed
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert: bigger than the room, 1:n badge
    val Passable = Color(0xFF7CF2A3) // design-style: fixed-figma-color passable: the "placed" dot on a tile
    val Interaction = Color(0xFFFFD24A) // design-style: fixed-figma-color interaction: tape yellow, selected tile ring

    // ---- brand / domain tokens outside ColorScheme
    val TapeYellow = Color(0xFFFFD24A) // design-style: fixed-figma-color tapeYellow: the floor tape
    val TapeInk = Color(0xFF2A2108) // design-style: fixed-figma-color tapeInk: tick marks, text on tape yellow
    val YouCyan = Color(0xFF7FD8FF) // design-style: fixed-figma-color youCyan: the "you" figure
    val CeilingAlert = Color(0xFFFF8A1F) // design-style: fixed-figma-color ceilingAlert: dashed ceiling line
    val SilhouetteInk = Color(0xE0FFFFFF) // design-style: fixed-figma-color silhouetteInk white 88 %: tile drawings
    val GhostFill = Color(0x2EFFD24A) // design-style: fixed-figma-color ghostFill tape yellow 18 %: placing footprint
    val PanelSlate = Color(0xE01E2226) // design-style: fixed-figma-color slate 88 %: room panels (opaque glass stand-in, see AGENTS.md)
}
