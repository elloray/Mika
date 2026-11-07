package com.wangzan101.mika.capsule

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleWindowVisibilityTest {
    @Test fun finishingCloseHidesTheCardRegardlessOfAnimationProgress() {
        assertEquals(
            CapsuleWindowVisibility(capsule = true, card = false),
            capsuleWindowVisibility(expanded = false, cardOut = false, closing = false),
        )
    }

    @Test fun capsuleStaysVisibleUntilTheCardStartsOpening() {
        assertEquals(
            CapsuleWindowVisibility(capsule = true, card = true),
            capsuleWindowVisibility(expanded = true, cardOut = false, closing = false),
        )
        assertEquals(
            CapsuleWindowVisibility(capsule = false, card = true),
            capsuleWindowVisibility(expanded = true, cardOut = true, closing = false),
        )
    }

    @Test fun capsuleReturnsWhileTheCardIsClosing() {
        assertEquals(
            CapsuleWindowVisibility(capsule = true, card = true),
            capsuleWindowVisibility(expanded = true, cardOut = true, closing = true),
        )
    }
}
