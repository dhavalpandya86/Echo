package com.dhaval.echo.ai

import com.dhaval.echo.data.ai.LocalTagSuggestionService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real, content-derived tags — not the old fixed placeholder trio. */
class LocalTagSuggestionServiceTest {

    private fun tags(text: String): List<String> = runBlocking {
        LocalTagSuggestionService().suggestTags(text).first()
    }

    @Test
    fun tags_reflect_the_memory_content() {
        val t = tags("Planning the Goa trip with my family this weekend.")
        assertTrue("topical Travel", t.contains("Travel"))
        assertTrue("topical Family", t.contains("Family"))
        assertFalse("no placeholder", t.contains("Reflection"))
    }

    @Test
    fun salient_keywords_become_tags() {
        val t = tags("The budget meeting ran long. Budget, budget, budget was all we discussed.")
        assertTrue("frequent keyword surfaces", t.any { it.equals("Budget", ignoreCase = true) })
    }

    @Test
    fun empty_or_meaningless_input_gets_no_fabricated_tags() {
        assertTrue("blank → none", tags("   ").isEmpty())
        // a filler test recording earns nothing rather than fake tags
        assertTrue("filler → none", tags("hello hello hello").isEmpty())
    }

    @Test
    fun tag_count_is_capped() {
        val t = tags("business startup revenue market client health workout gym family kids travel trip money budget idea work office shopping buy food lunch")
        assertTrue("at most five tags", t.size <= 5)
    }
}
