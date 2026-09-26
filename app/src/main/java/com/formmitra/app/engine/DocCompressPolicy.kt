package com.formmitra.app.engine

/**
 * DocCompressPolicy — POINT 16 (DOCUMENT VAULT 400KB COMPRESSION) ka
 * pure-Kotlin hissa. ZERO Android imports — JVM self-test me seedha
 * compile hota hai. Bitmap wala execution DocsStore me hai; ye file sirf
 * faisla leti hai (policy), taaki logic test ho sake.
 *
 * Usool (root):
 *  - 400KB ya usse chhoti file → HAATH NAHI (dobara compress = quality
 *    kharab, isliye bilkul nahi chhedte).
 *  - Badi photo (JPEG/PNG/WebP/BMP) → quality-ladder: pehle sirf JPEG
 *    quality ghatao (92→82→72, full size), phir halka downscale
 *    (0.85→0.7) + quality 80/75. Har step par size check — 400KB tak
 *    aate hi RUKO (zyada compress nahi).
 *  - Quality guard: downscale me chhoti side 800px se neeche NAHI
 *    (executor ye step skip karega) — chhoti photo ko aur chhota karke
 *    kharab nahi karte.
 *  - GIF (animation toot jayegi), PDF/special formats (bina kharab kiye
 *    chhote nahi ho sakte) → ORIGINAL rakho + user ko ek line batao.
 *  - Ladder se bhi 400KB na aaye → ORIGINAL rakho + ek line batao.
 *    Corrupt/over-compress KABHI nahi.
 */
object DocCompressPolicy {

    /** Vault limit: 400 KB. */
    const val MAX_BYTES: Long = 400L * 1024L

    /** Downscale me chhoti side isse neeche nahi (quality guard). */
    const val MIN_SMALL_SIDE_PX = 800

    enum class Kind { JPEG, PNG, WEBP, GIF, BMP, PDF, OTHER }

    /** Magic bytes se format pehchano (extension par bharosa nahi). */
    fun detectKind(bytes: ByteArray): Kind {
        if (bytes.size < 4) return Kind.OTHER
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> Kind.JPEG
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 -> Kind.PNG
            b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 -> {
                // RIFF....WEBP
                if (bytes.size >= 12 && bytes[8] == 0x57.toByte() &&
                    bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() &&
                    bytes[11] == 0x50.toByte()
                ) Kind.WEBP else Kind.OTHER
            }
            b0 == 0x47 && b1 == 0x49 && b2 == 0x46 -> Kind.GIF // GIF8
            b0 == 0x42 && b1 == 0x4D -> Kind.BMP // BM
            b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46 -> Kind.PDF // %PDF
            else -> Kind.OTHER
        }
    }

    /** Ladder ka ek step: is scale par JPEG me is quality se encode karo. */
    data class Step(val scale: Double, val quality: Int)

    /**
     * Compress karne layak photo formats ke liye ladder.
     * Pehle full-size quality steps, phir halke downscale steps.
     */
    val LADDER: List<Step> = listOf(
        Step(1.0, 92),
        Step(1.0, 82),
        Step(1.0, 72),
        Step(0.85, 85),
        Step(0.85, 75),
        Step(0.7, 80),
        Step(0.7, 70)
    )

    sealed class Decision {
        /** Original jaisa hai waisa rakho (koi quality loss nahi). */
        data class Keep(val note: String) : Decision()
        /** Ladder chalao; na ghuse to Keep me badlo (note ke saath). */
        data class Compress(val steps: List<Step>, val giveUpNote: String) : Decision()
    }

    /** Ek line user ko batane layak note — kaamyaab compress par. */
    fun compressedNote(): String =
        "Photo 400KB se chhoti karke save ki ✅ (quality waisi hi hai)"

    /**
     * Faisla: size + format dekh ke Keep ya Compress.
     * @param sizeBytes original file ka size
     * @param kind detectKind() se
     */
    fun decide(sizeBytes: Long, kind: Kind): Decision {
        // Chhoti file → haath nahi (dobara compress = bekaar quality loss).
        if (sizeBytes <= MAX_BYTES) return Decision.Keep("")
        return when (kind) {
            Kind.JPEG, Kind.PNG, Kind.WEBP, Kind.BMP -> Decision.Compress(
                LADDER,
                "Photo 400KB tak nahi aa payi bina quality kharab kiye — original hi rakhi hai."
            )
            Kind.GIF -> Decision.Keep(
                "Ye GIF/animation wali file hai — chhoti ki to kharab ho jayegi, isliye original rakhi hai."
            )
            Kind.PDF -> Decision.Keep(
                "Ye PDF 400KB se badi hai par bina kharab kiye chhoti nahi ho sakti — original rakhi hai."
            )
            Kind.OTHER -> Decision.Keep(
                "Ye file 400KB se badi hai par is format ko bina kharab kiye chhota nahi kar sakte — original rakhi hai."
            )
        }
    }

    /**
     * Ladder ka step chal sakta hai? (quality guard: chhoti side
     * MIN_SMALL_SIDE_PX se neeche jayegi to step SKIP — photo kharab
     * karne se achha original rakhna hai, wo give-up par hoga.)
     */
    fun stepAllowed(smallSidePx: Int, step: Step): Boolean {
        if (step.scale >= 1.0) return true
        return (smallSidePx * step.scale) >= MIN_SMALL_SIDE_PX
    }
}
