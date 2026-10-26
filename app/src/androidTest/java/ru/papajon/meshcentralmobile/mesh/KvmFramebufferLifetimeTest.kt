package ru.papajon.meshcentralmobile.mesh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KvmFramebufferLifetimeTest {
    @Test fun decodedTilesReleasePixelsAfterEveryDrawAcrossReconnectsAndResizes() {
        // Observe real BitmapFactory allocations rather than replacing Android's decoder.
        val tiles = mutableListOf<Bitmap>()
        val framebuffer = KvmFramebuffer { bytes, options ->
            assertNull("A decoded tile must not alias a published frame", options.inBitmap)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also(tiles::add)
        }
        val png = png(Color.MAGENTA)
        val frames = mutableListOf<Bitmap>()
        try {
            repeat(32) { cycle ->
                framebuffer.clear()
                assertNull(framebuffer.snapshot())
                val side = if (cycle % 2 == 0) 8 else 12
                val frame = requireNotNull(framebuffer.apply(KvmPacket.ScreenSize(side, side))?.bitmap)
                frames.add(frame)
                repeat(16) { index ->
                    val x = index % 4
                    val y = index / 4
                    val update = requireNotNull(framebuffer.apply(KvmPacket.Tile(x, y, png)))
                    assertSame(frame, update.bitmap)
                    assertNotSame("The decoded tile must be privately owned", frame, tiles.last())
                    assertEquals(Color.MAGENTA, frame.getPixel(x, y))
                    assertFalse("Only the private tile may be recycled", frame.isRecycled)
                    assertTrue("Decoded tile still owns native pixels after apply returned", tiles.last().isRecycled)
                }
                assertEquals("Unreleased decoded tiles must stay bounded at zero", 0, tiles.count { !it.isRecycled })
            }
            assertEquals(512, tiles.size)
            // Even a consumer that keeps an older published frame must remain safe.
            assertTrue(frames.all { !it.isRecycled && it.getPixel(0, 0) == Color.MAGENTA })
        } finally {
            framebuffer.clear()
            tiles.forEach { if (!it.isRecycled) it.recycle() }
            frames.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    @Test fun resizeAndClearDoNotRecycleFramesStillHeldByTheRenderer() {
        val framebuffer = KvmFramebuffer()
        val first = requireNotNull(framebuffer.apply(KvmPacket.ScreenSize(8, 8))?.bitmap)
        var second: Bitmap? = null
        try {
            framebuffer.apply(KvmPacket.Tile(0, 0, png(Color.BLUE)))
            assertSame(first, framebuffer.apply(KvmPacket.ScreenSize(8, 8))?.bitmap)
            second = requireNotNull(framebuffer.apply(KvmPacket.ScreenSize(12, 12))?.bitmap)
            assertNotSame(first, second)
            assertEquals(Color.BLUE, first.getPixel(0, 0))
            framebuffer.clear()
            assertNull(framebuffer.snapshot())
            assertFalse(first.isRecycled)
            assertFalse(second.isRecycled)
            assertEquals(Color.BLUE, first.getPixel(0, 0))
        } finally {
            framebuffer.clear()
            first.recycle()
            second?.recycle()
        }
    }

    @Test fun malformedAndRejectedTilesLeaveThePublishedFrameUsable() {
        var calls = 0
        val framebuffer = KvmFramebuffer { bytes, options ->
            calls++
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        val valid = png(Color.GREEN)
        assertNull(framebuffer.apply(KvmPacket.Tile(0, 0, valid)))
        assertEquals(0, calls)
        val frame = requireNotNull(framebuffer.apply(KvmPacket.ScreenSize(8, 8))?.bitmap)
        try {
            assertNull(framebuffer.apply(KvmPacket.Tile(0, 0, byteArrayOf())))
            assertNull(framebuffer.apply(KvmPacket.Tile(8, 0, valid)))
            assertEquals(0, calls)
            assertNull(framebuffer.apply(KvmPacket.Tile(0, 0, byteArrayOf(1, 2, 3))))
            assertSame(frame, framebuffer.apply(KvmPacket.Tile(0, 0, valid))?.bitmap)
            assertEquals(Color.GREEN, frame.getPixel(0, 0))
            assertFalse(frame.isRecycled)
        } finally {
            framebuffer.clear()
            frame.recycle()
        }
    }

    @Test fun overlappingCopyUsesTheComposedPixelsAfterDecodedTileRelease() {
        val framebuffer = KvmFramebuffer()
        val frame = requireNotNull(framebuffer.apply(KvmPacket.ScreenSize(8, 8))?.bitmap)
        try {
            framebuffer.apply(KvmPacket.Tile(0, 0, png(Color.RED)))
            framebuffer.apply(KvmPacket.CopyRect(sourceX = 0, sourceY = 0, targetX = 1, targetY = 1, width = 2, height = 2))
            assertEquals(Color.RED, frame.getPixel(2, 2))
            assertFalse(frame.isRecycled)
        } finally {
            framebuffer.clear()
            frame.recycle()
        }
    }

    private fun png(color: Int): ByteArray {
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            image.eraseColor(color)
            ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            image.recycle()
        }
    }
}
