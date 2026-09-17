package top.wkbin.taixu.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.scene.Scene

/**
 * Activity-like page transitions for NavDisplay.
 *
 * Forward: B slides in from the right; A eases left slightly and dims a touch.
 * Back: A restores from that slight offset; B slides out to the right.
 *
 * Keeps both pages mostly covering the host (only ~10% parallax), so gaps rarely flash.
 */
internal object ActivityStylePageTransitions {
    const val DurationMs = 320

    private val NavEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    private fun <T> animSpec() = tween<T>(durationMillis = DurationMs, easing = NavEasing)

    fun <T : Any> forward(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
        // B enters from the right.
        slideInHorizontally(
            animationSpec = animSpec(),
            initialOffsetX = { fullWidth -> fullWidth },
        ) togetherWith (
            // A shifts left slightly and dims a little — stays mostly on screen.
            slideOutHorizontally(
                animationSpec = animSpec(),
                targetOffsetX = { fullWidth -> -fullWidth / 10 },
            ) + fadeOut(
                animationSpec = animSpec(),
                targetAlpha = 0.92f,
            )
        )
    }

    fun <T : Any> pop(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
        // A restores position (already near-normal brightness).
        slideInHorizontally(
            animationSpec = animSpec(),
            initialOffsetX = { fullWidth -> -fullWidth / 10 },
        ) togetherWith (
            // B exits to the right.
            slideOutHorizontally(
                animationSpec = animSpec(),
                targetOffsetX = { fullWidth -> fullWidth },
            )
        )
    }

    fun <T : Any> predictivePop():
        AnimatedContentTransitionScope<Scene<T>>.(swipeEdge: Int) -> ContentTransform = {
        pop<T>().invoke(this)
    }
}
