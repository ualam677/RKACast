package com.rkacast.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.rkacast.remote.SamsungRemote
import kotlin.math.abs

/**
 * Laptop-style touchpad that drives the TV pointer:
 * - drag: move pointer · tap: click · two-finger tap: right-click
 * - two-finger drag: scroll up/down and left/right
 * - press & hold, then drag: drag-and-hold (button stays pressed until you lift your finger)
 */
fun Modifier.tvTouchpad(sensitivity: Float, haptics: HapticFeedback, onDragHold: (Boolean) -> Unit = {}): Modifier =
    pointerInput(sensitivity) {
        val slop = viewConfiguration.touchSlop
        val holdMs = 450L
        val scrollStep = 45f
        awaitEachGesture {
            awaitFirstDown()
            val start = System.currentTimeMillis()
            var travelled = 0f
            var maxPointers = 1
            var scrollX = 0f
            var scrollY = 0f
            var holding = false

            // Wait to see whether this becomes a long-press (finger still, single pointer).
            val earlyEvent = withTimeoutOrNull(holdMs) {
                var e = awaitPointerEvent()
                while (e.changes.any { it.pressed } && e.changes.count { it.pressed } == 1 &&
                    e.changes.sumOf { (abs(it.positionChange().x) + abs(it.positionChange().y)).toDouble() } < 1.0
                ) {
                    e.changes.forEach { it.consume() }
                    e = awaitPointerEvent()
                }
                e
            }
            if (earlyEvent == null) {
                holding = true
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                SamsungRemote.press("KEY_ENTER")
                onDragHold(true)
            }

            var event = earlyEvent ?: awaitPointerEvent()
            while (true) {
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                maxPointers = maxOf(maxPointers, pressed.size)
                if (pressed.size >= 2 && !holding) {
                    val dx = pressed.map { it.positionChange().x }.average().toFloat()
                    val dy = pressed.map { it.positionChange().y }.average().toFloat()
                    travelled += abs(dx) + abs(dy)
                    // Natural scrolling: fingers up => page moves down.
                    if (abs(dy) >= abs(dx)) scrollY += dy else scrollX += dx
                    while (scrollY > scrollStep) { SamsungRemote.key("KEY_UP"); scrollY -= scrollStep }
                    while (scrollY < -scrollStep) { SamsungRemote.key("KEY_DOWN"); scrollY += scrollStep }
                    while (scrollX > scrollStep) { SamsungRemote.key("KEY_LEFT"); scrollX -= scrollStep }
                    while (scrollX < -scrollStep) { SamsungRemote.key("KEY_RIGHT"); scrollX += scrollStep }
                } else if (pressed.size == 1 && (maxPointers == 1 || holding)) {
                    val d = pressed[0].positionChange()
                    travelled += abs(d.x) + abs(d.y)
                    if (travelled > slop || holding) SamsungRemote.moveMouse(d.x * sensitivity, d.y * sensitivity)
                }
                event.changes.forEach { it.consume() }
                event = awaitPointerEvent()
            }

            if (holding) {
                SamsungRemote.release("KEY_ENTER")
                onDragHold(false)
            } else if (System.currentTimeMillis() - start < 350 && travelled <= slop * 2) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (maxPointers >= 2) SamsungRemote.rightClick() else SamsungRemote.leftClick()
            }
        }
    }
