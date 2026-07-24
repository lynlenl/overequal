package dev.overequal.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Member identity is the user ID, and the label is the user's latest username —
 * so a rename doesn't split one person into two members on any chart.
 */
class IdentityTest {
    private fun msg(
        ts: String,
        authorId: String?,
        authorName: String,
        mentions: List<RawUser> = emptyList(),
        isBot: Boolean = false,
        content: String = "hi",
    ) = RawMessage(
        timestamp = ts,
        content = content,
        author = RawUser(id = authorId, name = authorName, isBot = isBot),
        mentions = mentions,
    )

    private fun build(
        raws: List<RawMessage>,
        options: RenderOptions = RenderOptions(),
    ) = DatasetLoader.build(raws, "guild", options)

    @Test
    fun `renamed author counts as one member under the latest name`() {
        val ds =
            build(
                listOf(
                    msg("2024-01-01T00:00:00Z", "111", "oldname"),
                    msg("2024-02-01T00:00:00Z", "111", "oldname"),
                    msg("2024-03-01T00:00:00Z", "111", "newname"),
                ),
            )
        val counts = ds.messages.groupingBy { it.authorName }.eachCount()
        assertEquals(mapOf("newname" to 3), counts)
    }

    @Test
    fun `latest name wins regardless of corpus ordering`() {
        val ds =
            build(
                listOf(
                    msg("2024-03-01T00:00:00Z", "111", "newname"),
                    msg("2024-01-01T00:00:00Z", "111", "oldname"),
                ),
            )
        assertTrue(ds.messages.all { it.authorName == "newname" })
    }

    @Test
    fun `mentions of a renamed user resolve to the latest name`() {
        val ds =
            build(
                listOf(
                    msg("2024-01-01T00:00:00Z", "222", "bob", mentions = listOf(RawUser(id = "111", name = "oldname"))),
                    msg("2024-03-01T00:00:00Z", "111", "newname"),
                ),
            )
        assertEquals(
            listOf("newname"),
            ds.messages
                .first()
                .mentions
                .map { it.name },
        )
        assertEquals("newname", ds.userNamesById["111"])
    }

    @Test
    fun `a mention can carry a newer name than any message the user authored`() {
        val ds =
            build(
                listOf(
                    msg("2024-01-01T00:00:00Z", "111", "oldname"),
                    msg("2024-05-01T00:00:00Z", "222", "bob", mentions = listOf(RawUser(id = "111", name = "newname"))),
                ),
            )
        assertEquals("newname", ds.messages.first { it.authorId == "111" }.authorName)
    }

    @Test
    fun `distinct users sharing a display name stay distinct`() {
        val ds =
            build(
                listOf(
                    msg("2024-01-01T00:00:00Z", "111111114821", "alice"),
                    msg("2024-01-02T00:00:00Z", "222222227700", "alice"),
                ),
            )
        val labels = ds.messages.map { it.authorName }.toSet()
        assertEquals(setOf("alice (4821)", "alice (7700)"), labels)
    }

    @Test
    fun `records without ids still group by name`() {
        val ds =
            build(
                listOf(
                    msg("2024-01-01T00:00:00Z", null, "legacy"),
                    msg("2024-01-02T00:00:00Z", null, "legacy"),
                ),
            )
        assertEquals(mapOf("legacy" to 2), ds.messages.groupingBy { it.authorName }.eachCount())
    }

    @Test
    fun `bot exclusion follows the id across a rename`() {
        val ds =
            build(
                listOf(
                    msg("2024-01-01T00:00:00Z", "999", "botold", isBot = true),
                    msg("2024-02-01T00:00:00Z", "999", "botnew", isBot = true),
                    msg("2024-03-01T00:00:00Z", "111", "alice", mentions = listOf(RawUser(id = "999", name = "botold", isBot = true))),
                ),
                RenderOptions(excludeBots = true),
            )
        assertEquals(1, ds.count)
        assertEquals(emptyList(), ds.messages.first().mentions)
    }

    @Test
    fun `redaction gives a renamed member a single pseudonym`() {
        val ds =
            build(
                listOf(
                    msg("2024-01-01T00:00:00Z", "111", "oldname"),
                    msg("2024-02-01T00:00:00Z", "111", "newname"),
                    msg("2024-03-01T00:00:00Z", "222", "bob", mentions = listOf(RawUser(id = "111", name = "oldname"))),
                ),
                RenderOptions(redactNames = true),
            )
        val alias =
            ds.messages
                .filter { it.authorId == "111" }
                .map { it.authorName }
                .toSet()
        assertEquals(1, alias.size)
        assertEquals("member_001", alias.single())
        assertEquals(
            listOf("member_001"),
            ds.messages
                .first { it.authorId == "222" }
                .mentions
                .map { it.name },
        )
    }
}
