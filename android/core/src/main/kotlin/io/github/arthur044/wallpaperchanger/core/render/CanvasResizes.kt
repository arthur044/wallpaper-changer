package io.github.arthur044.wallpaperchanger.core.render

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop

/**
 * The canvases that call for a new drawing: a foldable opened or closed,
 * another display. The first value is the screen as it was, so it is skipped.
 *
 * Only the canvas size counts. Rotating a phone moves the system bars and so
 * the safe area, but the portrait image already drawn stays right for it.
 */
fun Flow<CanvasSpec>.resizes(): Flow<CanvasSpec> =
    distinctUntilChangedBy { it.canvasWidth to it.canvasHeight }.drop(1)
