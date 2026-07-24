package dev.overequal.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A mentioned user (subset of author fields the mention visualizations need). */
data class Mention(
    val id: String?,
    val name: String,
)

/** A reaction on a message: [key] is the display label (`:shortcode:` or raw char), [count] the tally. */
data class Reaction(
    val key: String,
    val count: Int,
)

/** Analysis-ready, parsed message. Compact on purpose — corpora reach ~1M rows. */
data class Message(
    val timestamp: Instant,
    val authorId: String,
    val authorName: String,
    val isBot: Boolean,
    val content: String,
    val mentions: List<Mention>,
    val channel: String,
    val reactions: List<Reaction> = emptyList(),
)

/**
 * Options that shape how a corpus is turned into an analysis [Dataset] and
 * rendered. Server-agnostic: no hardcoded user/bot names anywhere.
 */
data class RenderOptions(
    val redactNames: Boolean = false,
    val redactContent: Boolean = false,
    val excludeBots: Boolean = false,
    val topN: Int = 30,
)

/**
 * An in-memory corpus plus the derived facts every chart needs: the time span it
 * covers and basic totals. Visualizations read [messages] and stamp
 * [periodLabel] into their footer so output always reflects the data's period.
 */
class Dataset(
    val messages: List<Message>,
    val guildName: String,
    val options: RenderOptions,
) {
    val count: Int = messages.size
    val start: Instant? = messages.minOfOrNull { it.timestamp }
    val end: Instant? = messages.maxOfOrNull { it.timestamp }

    /**
     * Snowflake user-ID → display name, gathered from every author and mention.
     * Lets word charts turn raw `<@123…>` mention tokens (which survive
     * tokenization as `@123…`) into `@name`. Names here are already canonicalized
     * to each user's latest username by `DatasetLoader`, and reflect redaction.
     */
    val userNamesById: Map<String, String> by lazy {
        val byId = HashMap<String, String>()
        for (m in messages) {
            byId[m.authorId] = m.authorName
            for (mn in m.mentions) if (mn.id != null) byId[mn.id] = mn.name
        }
        byId
    }

    val contentRedacted: Boolean get() = options.redactContent
    val namesRedacted: Boolean get() = options.redactNames

    private val dayFmt = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneOffset.UTC)

    /** e.g. "Apr 13, 2023 – Jun 17, 2025 · 832,104 messages". */
    fun periodLabel(): String {
        val span =
            if (start != null && end != null) {
                "${dayFmt.format(start)} – ${dayFmt.format(end)}"
            } else {
                "no data"
            }
        return "$span · ${"%,d".format(count)} messages"
    }

    fun subtitle(): String {
        val flags =
            buildList {
                if (options.excludeBots) add("bots excluded")
                if (namesRedacted) add("names redacted")
                if (contentRedacted) add("content redacted")
            }
        val base = "$guildName  ·  ${periodLabel()}"
        return if (flags.isEmpty()) base else "$base  ·  ${flags.joinToString(", ")}"
    }
}
