package com.dhaval.echo.understanding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.EchoDatabase
import com.dhaval.echo.data.understanding.LocalEntityResolver
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * MU-4 Stage-6 (memory expansion). Fed crafted evidence directly so co-occurrence
 * is controlled: entities that repeatedly appear together let a later memory that
 * names only one of them gain an *inferred* link to the other — marked, capped,
 * and never counted as a real mention.
 */
@RunWith(AndroidJUnit4::class)
class MemoryExpansionTest {

    private lateinit var db: EchoDatabase
    private lateinit var resolver: LocalEntityResolver
    private val userId = "u"
    private val at = LocalDateTime.of(2026, 7, 18, 10, 0)

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, EchoDatabase::class.java).allowMainThreadQueries().build()
        resolver = LocalEntityResolver(db.understandingDao())
    }

    @After fun tearDown() = db.close()

    private fun resolve(id: String, evidence: List<Evidence>) = runBlocking {
        db.diaryEntryDao().insertEntry(
            DiaryEntry(id = id, userId = userId, title = "t", audioPath = "",
                createdAt = at, updatedAt = at, duration = 0, textContent = "x")
        )
        resolver.resolve(
            NormalizedContent(id, userId, "x", setOf(SourceKind.TEXT), at), evidence
        )
    }

    private fun project(name: String) = Evidence(EvidenceKind.PROJECT, name, "q", 0.9f)
    private fun topic(name: String) = Evidence(EvidenceKind.TOPIC, name, "q", 0.9f)
    private fun person(name: String) = Evidence(EvidenceKind.PERSON, name, "q", 0.9f)

    @Test
    fun co_occurrence_infers_a_link_the_user_did_not_state() = runBlocking {
        // Oceanis + Logo stated together twice → an association forms.
        resolve("m1", listOf(project("Oceanis"), topic("Logo")))
        resolve("m2", listOf(project("Oceanis"), topic("Logo")))
        // m3 mentions only Logo — Oceanis should be inferred.
        resolve("m3", listOf(topic("Logo")))

        val dao = db.understandingDao()
        val links = dao.getLinkedEntities("m3").first()
        val oceanis = dao.getEntitiesByType(userId, com.dhaval.echo.data.db.EntityType.PROJECT).first()

        val inferred = links.firstOrNull { it.entityId == oceanis.id }
        assertTrue("expected an inferred Oceanis link on m3", inferred != null && inferred.inferred)
        assertTrue("inferred confidence must be capped low", inferred!!.confidence <= 0.5f)
        assertFalse("the stated Logo link must not be inferred",
            links.first { it.name == "Logo" }.inferred)

        // Inferred mentions must NOT inflate the real memory count (m1, m2 only).
        assertEquals("Oceanis stated in 2 memories", 2, dao.getEntityById(oceanis.id)!!.memoryCount)
    }

    @Test
    fun no_inference_without_a_co_occurrence_path() = runBlocking {
        resolve("m1", listOf(project("Oceanis"), topic("Logo")))
        resolve("m2", listOf(project("Oceanis"), topic("Logo")))
        // Priya has never co-occurred with anything.
        resolve("m3", listOf(person("Priya")))

        val links = db.understandingDao().getLinkedEntities("m3").first()
        assertEquals("only the stated Priya, nothing inferred", 1, links.size)
        assertFalse(links.single().inferred)
    }

    @Test
    fun single_co_occurrence_is_below_threshold() = runBlocking {
        // Co-occur only once → not enough to infer (guards against noise).
        resolve("m1", listOf(project("Oceanis"), topic("Logo")))
        resolve("m2", listOf(topic("Logo")))

        val links = db.understandingDao().getLinkedEntities("m2").first()
        assertTrue("one shared memory is below MIN_SHARED — no inference",
            links.none { it.inferred })
    }

    @Test
    fun reprocessing_does_not_duplicate_inferred_links() = runBlocking {
        resolve("m1", listOf(project("Oceanis"), topic("Logo")))
        resolve("m2", listOf(project("Oceanis"), topic("Logo")))
        resolve("m3", listOf(topic("Logo")))
        // process m3 again — idempotent
        resolve("m3", listOf(topic("Logo")))

        val links = db.understandingDao().getLinkedEntities("m3").first()
        assertEquals("Logo stated + Oceanis inferred, no duplicates", 2, links.size)
        assertEquals("exactly one inferred link", 1, links.count { it.inferred })
    }
}
