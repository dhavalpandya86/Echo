package com.dhaval.echo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Echo's color language (Constitution §4): color is never decorative — it
 * carries meaning, so a person recognizes what a mark is about instantly.
 * Tones are muted to stay within Echo's calm Paper/Indigo palette rather than
 * shouting. Reused by Story fingerprints, Worlds, entity chips, mood, etc.
 *
 * Never assign a random color to memory content — resolve it through [Meaning].
 */
enum class Meaning(val color: Color, val label: String) {
    Understanding(Color(0xFF7A6FF0), "Understanding"),  // purple — Echo's own insight
    People(Color(0xFF4F7CD1), "People"),                // blue
    Commitments(Color(0xFF3E7C4F), "Commitments"),      // green
    Places(Color(0xFFC08A3E), "Places"),                // orange/ochre
    Family(Color(0xFFC77598), "Family"),                // pink
    Ideas(Color(0xFFC9A23E), "Ideas"),                  // yellow
    Health(Color(0xFF3E9E97), "Health"),                // teal
    Important(Color(0xFFC4402F), "Important"),          // red
    Archived(Color(0xFF9A96A0), "Archived");            // gray

    companion object {
        /** Maps an EntityType (data/db/EntityType) to its meaning color. */
        fun forEntityType(type: String): Meaning = when (type.uppercase()) {
            "PERSON" -> People
            "PLACE" -> Places
            "PROJECT", "TOPIC", "PRODUCT", "ORG" -> Ideas
            else -> Understanding
        }
    }
}
