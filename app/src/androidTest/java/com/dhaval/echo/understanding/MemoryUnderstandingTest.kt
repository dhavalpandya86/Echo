package com.dhaval.echo.understanding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.db.EchoDatabase
import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.db.ItemKind
import com.dhaval.echo.data.understanding.KnownEntityAnalyzer
import com.dhaval.echo.data.understanding.LocalEntityResolver
import com.dhaval.echo.data.understanding.LocalMoodAnalyzer
import com.dhaval.echo.data.understanding.LocalPersonAnalyzer
import com.dhaval.echo.data.understanding.LocalProjectAnalyzer
import com.dhaval.echo.data.understanding.LocalReminderAnalyzer
import com.dhaval.echo.data.understanding.LocalTaskAnalyzer
import com.dhaval.echo.data.understanding.RealMemoryUnderstandingService
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * MU-0 acceptance: the vision's own example flows through the local pipeline.
 *
 * "Tomorrow I need to call Raj about the Oceanis logo."
 *   → person Raj, project Oceanis, a task with tomorrow's date, links with
 *     evidence — and a second memory mentioning Raj links to the SAME entity
 *     (identity, not tagging).
 *
 * Uses an in-memory DB: no Hilt, no app-data wipe.
 */
@RunWith(AndroidJUnit4::class)
class MemoryUnderstandingTest {

    private lateinit var db: EchoDatabase
    private lateinit var service: RealMemoryUnderstandingService

    private val userId = "test_user"
    private val capturedAt: LocalDateTime = LocalDateTime.of(2026, 7, 17, 22, 0)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val dao = db.understandingDao()
        service = RealMemoryUnderstandingService(
            analyzers = setOf(
                LocalPersonAnalyzer(),
                LocalProjectAnalyzer(),
                LocalTaskAnalyzer(),
                LocalReminderAnalyzer(),
                LocalMoodAnalyzer(),
                KnownEntityAnalyzer(dao)
            ),
            resolver = LocalEntityResolver(dao)
        )
    }

    @After
    fun tearDown() = db.close()

    /** Links have an FK to diary_entries, so memories must exist first. */
    private fun insertMemory(id: String, text: String) = runBlocking {
        db.diaryEntryDao().insertEntry(
            DiaryEntry(
                id = id, userId = userId, title = "t", audioPath = "",
                createdAt = capturedAt, updatedAt = capturedAt, duration = 0,
                textContent = text
            )
        )
    }

    private fun understand(memoryId: String, text: String) = runBlocking {
        insertMemory(memoryId, text)
        service.understand(
            NormalizedContent(
                memoryId = memoryId, userId = userId, text = text,
                sourceKinds = setOf(SourceKind.TEXT), capturedAt = capturedAt
            )
        )
    }

    @Test
    fun vision_example_builds_the_evidence_board_and_graph() = runBlocking {
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        val dao = db.understandingDao()

        // Entity graph: Raj is a PERSON, Oceanis a PROJECT — each exactly once.
        val people = dao.getEntitiesByType(userId, EntityType.PERSON)
        val projects = dao.getEntitiesByType(userId, EntityType.PROJECT)
        assertEquals("expected exactly one person", 1, people.size)
        assertEquals("Raj", people.first().name)
        assertEquals("expected exactly one project", 1, projects.size)
        assertEquals("Oceanis", projects.first().name)

        // Memory graph: links carry evidence text.
        val links = dao.getLinkedEntities("m1").first()
        assertTrue("expected links to both entities", links.size >= 2)
        assertTrue("no link may be marked inferred at MU-0", links.none { it.inferred })

        // Evidence board: a task, dated tomorrow.
        val items = dao.getItemsForMemory("m1").first()
        val task = items.firstOrNull { it.kind == ItemKind.TASK }
        assertNotNull("expected a TASK item", task)
        assertTrue("task should mention calling Raj", task!!.value.contains("call raj", ignoreCase = true))
        assertNotNull("task should carry a due date", task.dueAtMillis)
        val due = java.time.Instant.ofEpochMilli(task.dueAtMillis!!).atZone(ZoneId.systemDefault())
        assertEquals("due date should resolve to tomorrow", capturedAt.plusDays(1).dayOfMonth, due.dayOfMonth)
        assertNotNull("task must carry evidence text", task.evidence)
    }

    @Test
    fun same_person_across_memories_is_one_entity() = runBlocking {
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        understand("m2", "Had a great meeting with Raj today about packaging.")
        val dao = db.understandingDao()

        // Identity: still exactly one Raj…
        val people = dao.getEntitiesByType(userId, EntityType.PERSON)
        assertEquals("second mention must not create a duplicate", 1, people.size)
        val raj = people.first()

        // …linked from both memories, with an accurate memory count.
        val rajLinks = dao.getLinksForEntity(raj.id)
        assertEquals("Raj should be linked from both memories", 2, rajLinks.map { it.memoryId }.toSet().size)
        assertEquals("memoryCount should be refreshed", 2, dao.getEntityById(raj.id)!!.memoryCount)
    }

    @Test
    fun known_entity_recall_links_without_fresh_heuristic_signal() = runBlocking {
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        // "Oceanis" appears bare here — no project-noun context for the
        // heuristic. Only graph recall (KnownEntityAnalyzer) can catch it.
        understand("m2", "Thinking more about Oceanis while walking.")
        val dao = db.understandingDao()

        val oceanis = dao.getEntitiesByType(userId, EntityType.PROJECT).first()
        val linkedMemories = dao.getLinksForEntity(oceanis.id).map { it.memoryId }.toSet()
        assertTrue("graph recall should link m2 to the existing Oceanis", "m2" in linkedMemories)
    }

    @Test
    fun reprocessing_a_memory_is_idempotent() = runBlocking {
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        // Same memory processed again (e.g. worker retry) — nothing duplicates.
        runBlocking {
            service.understand(
                NormalizedContent(
                    memoryId = "m1", userId = userId,
                    text = "Tomorrow I need to call Raj about the Oceanis logo.",
                    sourceKinds = setOf(SourceKind.TEXT), capturedAt = capturedAt
                )
            )
        }
        val dao = db.understandingDao()
        assertEquals(1, dao.getEntitiesByType(userId, EntityType.PERSON).size)
        val raj = dao.getEntitiesByType(userId, EntityType.PERSON).first()
        assertEquals("re-run must not inflate memoryCount", 1, dao.getEntityById(raj.id)!!.memoryCount)
        val items = dao.getItemsForMemory("m1").first()
        assertEquals("re-run must not duplicate tasks", 1, items.count { it.kind == ItemKind.TASK })
    }

    @Test
    fun mood_is_detected_when_stated_and_absent_when_not() = runBlocking {
        understand("m1", "I feel really excited about the new design direction!")
        val withMood = db.understandingDao().getItemsForMemory("m1").first()
        assertEquals("Excited", withMood.firstOrNull { it.kind == ItemKind.MOOD }?.value)

        understand("m2", "Bought three bags of rice for the shipment.")
        val withoutMood = db.understandingDao().getItemsForMemory("m2").first()
        assertTrue(
            "no mood signal must mean no mood claim — never a fabricated Neutral",
            withoutMood.none { it.kind == ItemKind.MOOD }
        )
    }
}
