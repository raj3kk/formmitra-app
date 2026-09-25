package com.formmitra.app.agent

/**
 * WorkCategories (v28) — kaam ki categories EK JAGAH.
 *
 * P1: Home par SIRF 3 category cards — apply / track / resume.
 * Purane 5 work-cats isi me map hue:
 *   apply_track → apply, zamin_track → track(zameen), resume_create → resume,
 *   job_find → track(job), scholarship → track(scholarship).
 * P2: apply me tracking ka koi zikr nahi — sirf aavedan.
 * P7 future-proofing: nayi category yahan add karte hi Home picker,
 * Agent-tab picker, history section aur server prompts (lib/agent/categories.ts
 * me WORK_CATEGORIES) automatically us par laagu hote hain — kahin aur
 * hardcoded list nahi hai.
 */
object WorkCategories {

    data class Cat(
        val key: String,
        val label: String,
        val icon: String,
        val bg: String,
        val border: String,
        val desc: String
    )

    val ALL = listOf(
        Cat(
            "apply", "Apply (आवेदन)", "📝", "#E8F0FE", "#8AB4F8",
            "Form bharna — aavedan (आवेदन)"
        ),
        Cat(
            "track", "Track (ट्रैकिंग)", "🔍", "#E6F4EA", "#81C995",
            "Tracking lagao — zameen, naukri, scheme (ट्रैकिंग)"
        ),
        Cat(
            "resume", "Resume (रिज़्यूमे)", "📄", "#FEF7E0", "#F6C343",
            "Resume banana / sudharna (रिज़्यूमे)"
        )
    )

    fun of(key: String?): Cat? = ALL.find { it.key == key }

    fun labelOf(key: String?): String = of(key)?.label ?: key.orEmpty()

    /**
     * P3: Track ke andar 8 types. Har type par tap → card select/unlock →
     * Track agent us type ke context ke saath khulta hai → agent details
     * lekar POST /api/app/trackings se tracking banata hai.
     */
    data class TrackType(
        val key: String,
        val label: String,
        val icon: String,
        val hint: String
    )

    val TRACK_TYPES = listOf(
        TrackType("zameen", "Zameen (ज़मीन)", "🌾", "Khata / khesra / mauza"),
        TrackType("job", "Naukri (नौकरी)", "💼", "Sarkari / private / gig"),
        TrackType(
            "scholarship", "Scholarship (छात्रवृत्ति)", "🎓",
            "Class / state / category"
        ),
        TrackType("document", "Document (दस्तावेज़)", "📄", "Document status"),
        TrackType(
            "certificate", "Certificate (प्रमाणपत्र)", "📜",
            "Certificate apply / status"
        ),
        TrackType(
            "vacancy", "Sarkari Vacancy (सरकारी भर्ती)", "🏛️",
            "Vibhag / pad / pariksha"
        ),
        TrackType("scheme", "Scheme (योजना)", "🎯", "Sarkari yojana"),
        TrackType("other", "Other (अन्य)", "📌", "Kuch aur")
    )

    fun trackTypeOf(key: String?): TrackType? =
        TRACK_TYPES.find { it.key == key }

    fun trackLabelOf(key: String?): String =
        trackTypeOf(key)?.label ?: key.orEmpty()
}
