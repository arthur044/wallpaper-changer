package io.github.arthur044.wallpaperchanger.core.render

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop

/**
 * The canvases that call for a new drawing: a foldable opened or closed,
 * another display, a new display size (zoom). The first value is the screen
 * as it was, so it is skipped.
 *
 * The canvas size and the density count: the text is sized in dp, so a new
 * zoom changes it even when the pixels stay the same. Rotating a phone moves
 * the system bars and so the safe area, but the portrait image already drawn
 * stays right for it.
 */
fun Flow<CanvasSpec>.resizes(): Flow<CanvasSpec> =
    distinctUntilChangedBy { Triple(it.canvasWidth, it.canvasHeight, it.density) }.drop(1)
