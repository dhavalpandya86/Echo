package com.dhaval.echo.understanding

import com.dhaval.echo.data.db.EntityNode
import com.dhaval.echo.data.db.EntityRelation
import com.dhaval.echo.data.db.EntityRelationship
import com.dhaval.echo.data.understanding.WorldClusterer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Phase C: the clusterer finds Worlds in the graph. Pure logic — a plain JVM test,
 * no device needed.
 */
class WorldClustererTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 7, 17, 22, 0)

    private fun entity(id: String) = EntityNode(
        id = id, userId = "u", type = "TOPIC", name = id.uppercase(),
        normalizedName = id, firstSeenAt = now, lastSeenAt = now
    )

    private fun edge(a: String, b: String, w: Int) = EntityRelationship(
        id = "$a-$b", userId = "u", sourceEntityId = a, targetEntityId = b,
        relation = EntityRelation.RELATED_TO, weight = w, confidence = 1f,
        evidence = null, firstSeenAt = now, lastSeenAt = now
    )

    /** Store edges symmetrically, the way the resolver does. */
    private fun bidir(a: String, b: String, w: Int) = listOf(edge(a, b, w), edge(b, a, w))

    @Test
    fun two_disconnected_triangles_become_two_worlds() {
        val entities = listOf("a", "b", "c", "x", "y", "z").map(::entity)
        // Cluster 1: a-b-c fully connected. Cluster 2: x-y-z fully connected.
        val edges = bidir("a", "b", 3) + bidir("b", "c", 3) + bidir("a", "c", 3) +
            bidir("x", "y", 3) + bidir("y", "z", 3) + bidir("x", "z", 3)

        val worlds = WorldClusterer().cluster(entities, edges)

        assertEquals("two separate clusters", 2, worlds.size)
        val members = worlds.map { it.entityIds.toSortedSet() }
        assertTrue(members.contains(sortedSetOf("a", "b", "c")))
        assertTrue(members.contains(sortedSetOf("x", "y", "z")))
    }

    @Test
    fun a_weak_bridge_does_not_merge_two_strong_clusters() {
        val entities = listOf("a", "b", "c", "x", "y", "z").map(::entity)
        // Two tight triangles joined by one weak edge (c—x, weight 1).
        val edges = bidir("a", "b", 5) + bidir("b", "c", 5) + bidir("a", "c", 5) +
            bidir("x", "y", 5) + bidir("y", "z", 5) + bidir("x", "z", 5) +
            bidir("c", "x", 1)

        val worlds = WorldClusterer().cluster(entities, edges)
        assertEquals("the weak bridge shouldn't collapse them into one", 2, worlds.size)
    }

    @Test
    fun a_lone_pair_is_a_world_but_a_singleton_is_not() {
        val entities = listOf("a", "b", "lonely").map(::entity)
        val edges = bidir("a", "b", 2) // "lonely" has no edges

        val worlds = WorldClusterer().cluster(entities, edges, minClusterSize = 2)
        assertEquals(1, worlds.size)
        assertEquals(sortedSetOf("a", "b"), worlds.first().entityIds.toSortedSet())
    }

    @Test
    fun title_names_the_most_connected_members() {
        val entities = listOf("hub", "a", "b").map(::entity)
        // hub connects to both; a,b connect only to hub → hub is most central.
        val edges = bidir("hub", "a", 4) + bidir("hub", "b", 4)

        val worlds = WorldClusterer().cluster(entities, edges)
        assertEquals(1, worlds.size)
        assertTrue("hub leads the title", worlds.first().title.startsWith("HUB"))
        assertEquals("hub", worlds.first().seedEntityId)
    }
}
