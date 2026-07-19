package com.dhaval.echo.understanding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.EchoDatabase
import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.understanding.KnownEntityAnalyzer
import com.dhaval.echo.data.understanding.EntityGraphMaintainer
import com.dhaval.echo.data.understanding.LocalEntityResolver
import com.dhaval.echo.data.understanding.LocalMoodAnalyzer
import com.dhaval.echo.data.understanding.LocalPersonAnalyzer
import com.dhaval.echo.data.understanding.LocalProjectAnalyzer
import com.dhaval.echo.data.understanding.LocalReminderAnalyzer
import com.dhaval.echo.data.understanding.LocalTaskAnalyzer
import com.dhaval.echo.data.understanding.RealMemoryUnderstandingService
import com.dhaval.echo.domain.understanding.MemoryAnalyzerProvider
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Phase A acceptance: the weighted entity↔entity graph.
 *
 * When two entities keep showing up in the same memories, an edge forms between
 * them whose weight is the number of shared memories — and the edge is symmetric
 * (Raj→Oceanis and Oceanis→Raj carry the same weight). This is the traversable
 * graph the whole Experience layer stands on.
 */
@RunWith(AndroidJUnit4::class)
class EntityGraphTest {

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
        val localAnalyzers = listOf(
            LocalPersonAnalyzer(),
            LocalProjectAnalyzer(),
            LocalTaskAnalyzer(),
            LocalReminderAnalyzer(),
            LocalMoodAnalyzer(),
            KnownEntityAnalyzer(dao)
        )
        service = RealMemoryUnderstandingService(
            analyzerProvider = MemoryAnalyzerProvider { localAnalyzers },
            resolver = LocalEntityResolver(dao, EntityGraphMaintainer(dao))
        )
    }

    @After
    fun tearDown() = db.close()

    private fun understand(memoryId: String, text: String) = runBlocking {
        db.diaryEntryDao().insertEntry(
            DiaryEntry(
                id = memoryId, userId = userId, title = "t", audioPath = "",
                createdAt = capturedAt, updatedAt = capturedAt, duration = 0,
                textContent = text
            )
        )
        service.understand(
            NormalizedContent(
                memoryId = memoryId, userId = userId, text = text,
                sourceKinds = setOf(SourceKind.TEXT), capturedAt = capturedAt
            )
        )
    }

    @Test
    fun co_occurrence_forms_a_symmetric_weighted_edge() = runBlocking {
        // Raj and Oceanis co-occur in one memory → an edge of weight 1 each way.
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        val dao = db.understandingDao()

        val raj = dao.getEntitiesByType(userId, EntityType.PERSON).first { it.name == "Raj" }
        val oceanis = dao.getEntitiesByType(userId, EntityType.PROJECT).first { it.name == "Oceanis" }

        val fromRaj = dao.getRelatedEntitiesOnce(raj.id)
        val fromOceanis = dao.getRelatedEntitiesOnce(oceanis.id)

        assertEquals("Raj should connect to exactly one entity", 1, fromRaj.size)
        assertEquals(oceanis.id, fromRaj.first().entityId)
        assertEquals("edge weight is shared-memory count", 1, fromRaj.first().weight)

        assertEquals("the edge is symmetric", 1, fromOceanis.size)
        assertEquals(raj.id, fromOceanis.first().entityId)
        assertEquals(1, fromOceanis.first().weight)
    }

    @Test
    fun repeated_co_occurrence_increases_the_edge_weight() = runBlocking {
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        understand("m2", "Met Raj to review the Oceanis packaging.")
        val dao = db.understandingDao()

        val raj = dao.getEntitiesByType(userId, EntityType.PERSON).first { it.name == "Raj" }
        val related = dao.getRelatedEntitiesOnce(raj.id)

        assertEquals("still one neighbour, not a duplicate edge", 1, related.size)
        assertEquals("two shared memories → weight 2", 2, related.first().weight)
    }

    @Test
    fun a_feeling_joins_the_graph_and_connects_to_what_co_occurs() = runBlocking {
        // Phase B: feelings are entities, so "what was I doing when I felt excited?"
        // is answerable by graph traversal.
        understand("m1", "I feel really excited about the Oceanis launch!")
        val dao = db.understandingDao()

        val excited = dao.getEntitiesByType(userId, EntityType.FEELING).first { it.name == "Excited" }
        val neighbours = dao.getRelatedEntitiesOnce(excited.id).map { it.name }.toSet()
        assertTrue("the feeling connects to Oceanis", neighbours.contains("Oceanis"))
    }

    @Test
    fun edges_are_deduplicated_and_traversable_across_a_hub() = runBlocking {
        // Oceanis is a hub touched with Raj, then with Meera.
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        understand("m2", "Tomorrow I need to call Meera about the Oceanis packaging.")
        val dao = db.understandingDao()

        val oceanis = dao.getEntitiesByType(userId, EntityType.PROJECT).first { it.name == "Oceanis" }
        val neighbours = dao.getRelatedEntitiesOnce(oceanis.id).map { it.name }.toSet()

        assertTrue("Oceanis connects to Raj", neighbours.contains("Raj"))
        assertTrue("Oceanis connects to Meera", neighbours.contains("Meera"))
        // Raj and Meera never shared a memory → no direct edge between them.
        val raj = dao.getEntitiesByType(userId, EntityType.PERSON).first { it.name == "Raj" }
        assertTrue(
            "Raj and Meera don't co-occur, so no direct edge",
            dao.getRelatedEntitiesOnce(raj.id).none { it.name == "Meera" }
        )
    }
}
