package com.bajajauto.roadsense.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class EventBookmarkTest {

    @Test
    fun deadzoneBelowThresholdResolvesToPlainEvent() {
        val thresholdPx = 100f
        assertEquals(SwipeDirection.CENTER, EventBookmarkGestureResolver.resolveDirection(0f, 0f, thresholdPx))
        assertEquals(SwipeDirection.CENTER, EventBookmarkGestureResolver.resolveDirection(35f, -35f, thresholdPx))
        assertEquals(SwipeDirection.CENTER, EventBookmarkGestureResolver.resolveDirection(-70f, 70f, thresholdPx))
        assertEquals(EventBookmarkType.EVENT, EventBookmarkGestureResolver.resolve(50f, 50f, thresholdPx))
    }

    @Test
    fun fourCardinalSwipesAboveThresholdResolveCorrectTriggers() {
        val thresholdPx = 100f
        assertEquals(EventBookmarkType.CUT_IN, EventBookmarkGestureResolver.resolve(10f, -120f, thresholdPx))
        assertEquals(EventBookmarkType.BRAKE, EventBookmarkGestureResolver.resolve(-15f, 130f, thresholdPx))
        assertEquals(EventBookmarkType.FALSE_ALARM, EventBookmarkGestureResolver.resolve(-140f, 20f, thresholdPx))
        assertEquals(EventBookmarkType.BUMP, EventBookmarkGestureResolver.resolve(115f, -10f, thresholdPx))
    }

    @Test
    fun exactThresholdBoundaryActivatesDominantAxis() {
        val thresholdPx = 90f
        assertEquals(SwipeDirection.UP, EventBookmarkGestureResolver.resolveDirection(0f, -90f, thresholdPx))
        assertEquals(SwipeDirection.DOWN, EventBookmarkGestureResolver.resolveDirection(0f, 90f, thresholdPx))
        assertEquals(SwipeDirection.LEFT, EventBookmarkGestureResolver.resolveDirection(-90f, 0f, thresholdPx))
        assertEquals(SwipeDirection.RIGHT, EventBookmarkGestureResolver.resolveDirection(90f, 0f, thresholdPx))
    }
}
