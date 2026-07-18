package com.aiforum.images

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Local-disk blob store for image attachments. NOT an IO seam we fake (it's plain filesystem IO,
 * exercised at Tier 2 against a temp dir); the genuinely-fakeable boundary is [ImageDescriber].
 *
 * Bytes are content-addressed: the sha256 of the (post-sanitisation) bytes IS the filename, sharded two
 * levels deep (images/ab/cd/<sha>.<ext>). That gives automatic dedup and an immutable, cache-forever URL,
 * and — crucially — the on-disk path NEVER derives from a user-supplied filename, so there is no path
 * traversal surface. The serve endpoint looks an attachment up by id and reads [storagePath]; it never
 * takes a path from the client.
 *
 * Ingest sanitises: the type is decided by MAGIC BYTES (not the multipart Content-Type or the filename),
 * non-images are rejected, an oversize file is rejected, and jpeg/png are re-encoded through ImageIO to
 * strip EXIF/metadata (privacy). gif/webp are stored as-is (re-encoding would drop animation, and stock
 * ImageIO can't write webp).
 */
@Component
class ImageStore(
    // ${user.home} is resolved by Spring before this sees it (same mechanism as the datasource URL), so a
    // literal `~` never reaches the filesystem. Tier-2 tests construct this directly with a temp dir.
    @Value("\${aiforum.images.dir:\${user.home}/.ai_forum/data/images}") dir: String,
    @Value("\${aiforum.images.max-bytes:10485760}") private val maxBytes: Long,
) {
    private val root: Path = Path.of(dir).toAbsolutePath()

    init {
        Files.createDirectories(root)
    }

    /** A sanitised, stored image — the metadata the attachment row needs. */
    data class Stored(
        val sha256: String,
        val storagePath: String,
        val mimeType: String,
        val byteSize: Long,
    )

    /** A rejected upload (not an image, or too big). The controller maps this to a 4xx / skip. */
    class RejectedException(message: String) : RuntimeException(message)

    /**
     * Validate, sanitise and store [bytes]. Returns the content-addressed metadata. Throws
     * [RejectedException] for a non-image or an oversize file. Idempotent: the same bytes resolve to the
     * same path, so a re-upload is a no-op write.
     */
    fun store(bytes: ByteArray): Stored {
        if (bytes.size > maxBytes) {
            throw RejectedException("image is ${bytes.size} bytes, over the ${maxBytes}-byte limit")
        }
        val type = sniff(bytes) ?: throw RejectedException("not a recognised image (png/jpeg/gif/webp)")
        val sanitised = sanitise(bytes, type)
        val sha = sha256Hex(sanitised)
        val storagePath = "${sha.substring(0, 2)}/${sha.substring(2, 4)}/$sha.${type.ext}"
        val target = root.resolve(storagePath)
        if (!Files.exists(target)) {
            Files.createDirectories(target.parent)
            // Write to a temp sibling then atomically move, so a concurrent reader never sees a partial file.
            val tmp = Files.createTempFile(target.parent, "img", ".tmp")
            Files.write(tmp, sanitised)
            runCatching { Files.move(tmp, target) }.onFailure { Files.deleteIfExists(tmp) }
        }
        return Stored(sha, storagePath, type.mime, sanitised.size.toLong())
    }

    /** Absolute path of a stored blob, for the serve endpoint to stream. */
    fun resolve(storagePath: String): Path = root.resolve(storagePath)

    fun bytes(storagePath: String): ByteArray = Files.readAllBytes(resolve(storagePath))

    // --- internals --------------------------------------------------------------------------------

    private enum class ImageType(val mime: String, val ext: String) {
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpg"),
        GIF("image/gif", "gif"),
        WEBP("image/webp", "webp"),
    }

    /** Decide the type from the leading magic bytes — never trust the declared Content-Type / extension. */
    private fun sniff(b: ByteArray): ImageType? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> ImageType.PNG
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> ImageType.JPEG
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[3] == '8'.code.toByte() -> ImageType.GIF
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() &&
            b[3] == 'F'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte() -> ImageType.WEBP
        else -> null
    }

    /**
     * Strip metadata (EXIF, GPS, etc.) by decoding then re-encoding through ImageIO — only for jpeg/png,
     * where ImageIO round-trips reliably. gif/webp are returned untouched (re-encode would drop animation
     * / isn't writable by stock ImageIO). Any decode/encode hiccup falls back to the original bytes, so a
     * quirky-but-valid image is never lost to sanitisation.
     */
    private fun sanitise(bytes: ByteArray, type: ImageType): ByteArray {
        if (type != ImageType.PNG && type != ImageType.JPEG) return bytes
        return runCatching {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
            val out = ByteArrayOutputStream()
            val ok = ImageIO.write(img, if (type == ImageType.JPEG) "jpg" else "png", out)
            if (ok && out.size() > 0) out.toByteArray() else bytes
        }.getOrDefault(bytes)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
