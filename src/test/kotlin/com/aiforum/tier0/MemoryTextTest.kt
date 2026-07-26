package com.aiforum.tier0

import com.aiforum.persona.MemoryText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier-0: [MemoryText], the ONE validator/cleaner for memory bodies (plan_docs/persona-memory.md
 * §2.15/D15). What these tests pin is I5 — "the value compared is the value stored" — held by
 * construction: the cleaner is idempotent, the validator refuses anything that is not already a
 * fixed point of it (never re-cleans), and length is measured in code points, which is exactly what
 * SQLite `length()` counts (the S4b review class, 4b §10.3 item 3, dodged rather than patched).
 *
 * The "one function serves parse and owner form" claim is structural (one symbol); its behavioural
 * shadow — the parse's refusal reasons being BYTE-IDENTICAL to this object's — is pinned in
 * [ScribeAnswerTest], and the SQLite side of the code-point agreement is a real-DB fact pinned in
 * `PersonaMemoryRepositoryTest`'s scoped-CHECK test (a 300-code-point emoji body inserts; 301 trips).
 */
@Tag("tier0")
class MemoryTextTest {

    @Test
    fun `clean trims and collapses whitespace runs`() {
        assertEquals("a tidy memory", MemoryText.clean("  a \t tidy\n\nmemory  "))
        assertEquals("", MemoryText.clean("   \n\t "))
        assertEquals("already clean", MemoryText.clean("already clean"))
    }

    @Test
    fun `clean is idempotent on every input shape it changes`() {
        // The property that makes the fixed-point refusal a caller-discipline check rather than a
        // packaging unwrap: unlike Interests.clean (quote-stripping, deliberately non-idempotent),
        // one application of this cleaner IS the canonical form.
        listOf(
            "  edges  ", "runs\t\tinside", "line\nbreaks\nhere", " mixed \t all\n over ",
            "", "single", "café  🙂  space",
        ).forEach { raw ->
            val once = MemoryText.clean(raw)
            assertEquals(once, MemoryText.clean(once), "clean must be a fixed point after one pass: <$raw>")
        }
    }

    @Test
    fun `validate refuses a candidate that is not a fixed point of clean, and never re-cleans it`() {
        // The refusal IS the guarantee: had validate cleaned and passed this, the caller would store
        // a string that was never the one validated (the S4b double-clean defect shape).
        val reason = MemoryText.validate("spaced  out  memory")
        assertNotNull(reason, "a non-fixed-point candidate must be refused")
        assertEquals("a memory must arrive already cleaned; it is refused, never re-cleaned", reason)
        // The cleaned twin of the same text is welcome — proof the refusal is about the FORM
        // arriving dirty, not about the words.
        assertNull(MemoryText.validate(MemoryText.clean("spaced  out  memory")))
    }

    @Test
    fun `validate refuses blank ahead of the fixed-point rule`() {
        // "   " is also a non-fixed-point, but "cannot be blank" is the true diagnosis — a
        // fixed-point complaint about whitespace would send the owner hunting for spacing in an
        // empty field.
        assertEquals("a memory cannot be blank", MemoryText.validate(""))
        assertEquals("a memory cannot be blank", MemoryText.validate("   "))
    }

    @Test
    fun `validate admits exactly 300 code points and refuses 301`() {
        assertNull(MemoryText.validate("e".repeat(300)))
        assertEquals(
            "a memory must be at most 300 characters",
            MemoryText.validate("e".repeat(301)),
        )
    }

    @Test
    fun `code points are counted as SQLite length() counts them, not as UTF-16 units`() {
        // Multi-byte BMP: é is one code point (SQLite length('café') = 4).
        assertEquals(4, MemoryText.codePoints("café"))
        // Surrogate pair: one emoji is 2 to String.length and 1 to SQLite — the whole reason this
        // helper exists (I5). A UTF-16 measure here would let a body pass validation and then
        // disagree with the V28 CHECK.
        assertEquals(1, MemoryText.codePoints("🙂"))
        assertEquals(2, "🙂".length, "the trap this guards against: UTF-16 sees two units")
        // The bound is therefore a code-point bound: 300 emoji validate, 301 refuse.
        assertNull(MemoryText.validate("🙂".repeat(300)))
        assertNotNull(MemoryText.validate("🙂".repeat(301)))
    }

    @Test
    fun `validate refuses a NUL-bearing body, wherever the NUL sits`() {
        // U+0000 is the ONE character every other check passes through — not Kotlin whitespace
        // (so trim/isBlank/\s+ keep it) and one honest code point to codePointCount — while
        // SQLite length() stops counting at it. Unrefused, a leading-NUL body reads as length 0
        // to the V28 CHECK (length(body) > 0) and a mid-NUL one undercounts against BETWEEN 1
        // AND 300: the validated string and the stored string part company, and the owner form
        // 500s on an uncaught driver exception (the close-out audit's medium, §10.3 item 1).
        val nul = Char(0).toString()
        val leading = MemoryText.validate(nul + "hello")
        assertNotNull(leading, "a leading-NUL body must be refused at the door")
        val mid = MemoryText.validate("a" + nul + "b")
        assertNotNull(mid, "a mid-NUL body must be refused at the door")
        // The reason is owner-readable, never a bare rejection: both callers log it.
        assertTrue(leading!!.isNotBlank(), "the NUL refusal must carry a readable reason")
        assertEquals(leading, mid, "one reason for the one defect class, wherever the NUL sits")
    }

    @Test
    fun `fold is the canonical Unicode-aware case fold`() {
        // §2.5: duplicate comparison is case-insensitive AS IMPLEMENTED BY THIS FOLD — Kotlin's
        // Unicode-aware lowercase, never SQLite's ASCII-only NOCASE. The non-ASCII case is the one
        // NOCASE would get wrong.
        assertEquals("storage engines", MemoryText.fold("Storage Engines"))
        assertEquals("école", MemoryText.fold("ÉCOLE"))
    }
}
