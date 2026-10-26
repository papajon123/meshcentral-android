package ru.papajon.meshcentralmobile.mesh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Composes Mesh Agent KVM tiles into a single ARGB bitmap.
 *
 * Tiles are JPEG by default (MeshCentral `ImageType` 1) but the decoder accepts anything
 * `BitmapFactory` understands (JPEG/PNG/WebP), because `cmd 5` allows switching the type.
 * Tiles are applied in arrival order, which matches the browser's `PendingOperations` queue.
 */
class KvmFramebuffer internal constructor(
    // The decoder transfers exclusive ownership of each returned tile to this framebuffer.
    private val decodeBitmap: (ByteArray, BitmapFactory.Options) -> Bitmap?,
) {
    constructor() : this({ bytes, options ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    })

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    private val destinationRect = Rect()

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var width: Int = 0
    private var height: Int = 0

    val currentWidth: Int get() = width
    val currentHeight: Int get() = height

    @Synchronized
    fun apply(packet: KvmPacket): FramebufferUpdate? {
        return when (packet) {
            is KvmPacket.ScreenSize -> resize(packet.width, packet.height)
            is KvmPacket.Tile -> drawTile(packet)
            is KvmPacket.CopyRect -> copyRect(packet)
            else -> null
        }
    }

    @Synchronized
    fun clear() {
        bitmap = null
        canvas = null
        width = 0
        height = 0
    }

    @Synchronized
    fun snapshot(): FramebufferUpdate? {
        val frame = bitmap ?: return null
        return FramebufferUpdate(bitmap = frame, width = width, height = height)
    }

    private fun resize(newWidth: Int, newHeight: Int): FramebufferUpdate? {
        if (newWidth <= 0 || newHeight <= 0) return null
        if (newWidth > MAX_DIMENSION || newHeight > MAX_DIMENSION) return null
        if (newWidth == width && newHeight == height && bitmap != null) {
            val frame = bitmap ?: return null
            return FramebufferUpdate(bitmap = frame, width = width, height = height)
        }
        val created = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        bitmap = created
        canvas = Canvas(created)
        width = newWidth
        height = newHeight
        return FramebufferUpdate(bitmap = created, width = width, height = height)
    }

    private fun drawTile(packet: KvmPacket.Tile): FramebufferUpdate? {
        val frame = bitmap ?: return null
        val target = canvas ?: return null
        if (packet.image.isEmpty()) return null
        if (packet.x >= width || packet.y >= height) return null
        val tile = decodeTile(packet.image) ?: return null
        try {
            // This bitmap-backed software Canvas copies the pixels synchronously. Only
            // the destination frame is published; the privately decoded tile never is.
            target.drawBitmap(tile, packet.x.toFloat(), packet.y.toFloat(), paint)
        } finally {
            tile.recycle()
        }
        return FramebufferUpdate(bitmap = frame, width = width, height = height)
    }

    private fun copyRect(packet: KvmPacket.CopyRect): FramebufferUpdate? {
        val frame = bitmap ?: return null
        val target = canvas ?: return null
        if (packet.width <= 0 || packet.height <= 0) return null
        if (packet.sourceX !in 0 until width || packet.sourceY !in 0 until height) return null
        if (packet.targetX !in 0 until width || packet.targetY !in 0 until height) return null
        val copyWidth = packet.width.coerceAtMost(width - packet.sourceX).coerceAtMost(width - packet.targetX)
        val copyHeight = packet.height.coerceAtMost(height - packet.sourceY).coerceAtMost(height - packet.targetY)
        if (copyWidth <= 0 || copyHeight <= 0) return null
        if (packet.sourceX == packet.targetX && packet.sourceY == packet.targetY) {
            return FramebufferUpdate(bitmap = frame, width = width, height = height)
        }
        // Copy through a small scratch bitmap: drawing a region of a bitmap onto itself is not
        // guaranteed to be correct on every Android renderer.
        val scratch = Bitmap.createBitmap(frame, packet.sourceX, packet.sourceY, copyWidth, copyHeight)
        destinationRect.set(
            packet.targetX,
            packet.targetY,
            packet.targetX + copyWidth,
            packet.targetY + copyHeight,
        )
        target.drawBitmap(scratch, null, destinationRect, paint)
        scratch.recycle()
        return FramebufferUpdate(bitmap = frame, width = width, height = height)
    }

    private fun decodeTile(bytes: ByteArray): Bitmap? {
        return runCatching { decodeBitmap(bytes, options) }.getOrNull()
    }

    private companion object {
        const val MAX_DIMENSION = 16_384
    }
}

data class FramebufferUpdate(
    val bitmap: Bitmap?,
    val width: Int,
    val height: Int,
)
