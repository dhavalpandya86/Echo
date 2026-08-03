package com.dhaval.echo.understanding

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.EchoDatabase
import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.understanding.EntityCorrectionService
import com.dhaval.echo.data.understanding.EntityGraphMaintainer
import com.dhaval.echo.data.understanding.KnownEntityAnalyzer
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Phase B acceptance: the corrections loop. The user has the final say — they can
 * rename, alias, merge, and archive entities, and the graph and counts stay right.
 */
@RunWith(AndroidJUnit4::class)
class EntityCorrectionTest {

    private lateinit var db: EchoDatabase
    private lateinit var service: RealMemoryUnderstandingService
    private lateinit var corrections: EntityCorrectionService

    private val userId = "test_user"
    private val capturedAt: LocalDateTime = LocalDateTime.of(2026, 7, 17, 22, 0)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, EchoDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val dao = db.understandingDao()
        val maintainer = EntityGraphMaintainer(dao)
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
            resolver = LocalEntityResolver(dao, maintainer)
        )
        corrections = EntityCorrectionService(dao, maintainer)
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
    fun rename_keeps_the_old_name_as_an_alias() = runBlocking {
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        val dao = db.understandingDao()
        val raj = dao.getEntitiesByType(userId, EntityType.PERSON).first { it.name == "Raj" }

        corrections.rename(raj.id, "Rajesh")

        val renamed = dao.getEntityById(raj.id)!!
        assertEquals("Rajesh", renamed.name)
        assertEquals("rajesh", renamed.normalizedName)
        assertTrue("old name kept as alias", renamed.aliases.contains("Raj"))
    }

    @Test
    fun archived_entities_drop_out_of_browsing_but_still_resolve() = runBlocking {
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        val dao = db.understandingDao()
        val raj = dao.getEntitiesByType(userId, EntityType.PERSON).first { it.name == "Raj" }

        corrections.setArchived(raj.id, true)

        assertTrue(
            "archived entity is hidden from the browser",
            dao.getAllEntities(userId).first().none { it.id == raj.id }
        )
        // Re-mentioning must not spawn a duplicate — resolution still finds it.
        understand("m2", "Had coffee with Raj again.")
        assertEquals(
            "still exactly one Raj",
            1, dao.getEntitiesByType(userId, EntityType.PERSON).count { it.name == "Raj" }
        )
    }

    @Test
    fun merge_repoints_memories_and_rebuilds_the_graph() = runBlocking {
        // Echo mis-split one person into two: "Raj" and "Rajesh".
        understand("m1", "Tomorrow I need to call Raj about the Oceanis logo.")
        understand("m2", "Tomorrow I need to call Rajesh about the Oceanis packaging.")
        val dao = db.understandingDao()

        val raj = dao.getEntitiesByType(userId, EntityType.PERSON).first { it.name == "Raj" }
        val rajesh = dao.getEntitiesByType(userId, EntityType.PERSON).first { it.name == "Rajesh" }
        val oceanis = dao.getEntitiesByType(userId, EntityType.PROJECT).first { it.name == "Oceanis" }

        // Before merge: Oceanis links to two separate people, each edge weight 1.
        assertEquals(2, dao.getRelatedEntitiesOnce(oceanis.id).count())

        corrections.merge(sourceId = rajesh.id, targetId = raj.id)

        // Rajesh is gone; Raj absorbed the alias and both memories.
        assertNull("source entity deleted", dao.getEntityById(rajesh.id))
        val survivor = dao.getEntityById(raj.id)!!
        assertTrue("source name folded into aliases", survivor.aliases.contains("Rajesh"))
        assertEquals("Raj now spans both memories", 2, survivor.memoryCount)

        // Graph healed: Oceanis has ONE neighbour (Raj) at weight 2.
        val oceanisNeighbours = dao.getRelatedEntitiesOnce(oceanis.id)
        assertEquals("one merged neighbour, not two", 1, oceanisNeighbours.size)
        assertEquals(raj.id, oceanisNeighbours.first().entityId)
        assertEquals("weight is the two shared memories", 2, oceanisNeighbours.first().weight)
    }
}
