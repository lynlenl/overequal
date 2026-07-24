package dev.overequal.data

import dev.overequal.redact.Redactor

/**
 * Turns a raw corpus into an analysis-ready [Dataset]: parse timestamps, apply
 * the generic bot-exclusion option, canonicalize member identity, then redaction.
 * No server-specific logic — bots are identified purely by the `isBot` flag.
 *
 * **Identity is the user ID, not the username.** Discord usernames change, and a
 * corpus scraped over years holds every name a member ever had. Keying analysis
 * off the name would split one person into several members (and split their
 * mentions, their heatmap row, their share of the pie…). So each author/mention
 * is resolved to a stable identity key — `author.id`, falling back to the name
 * for pre-ID records — and every occurrence is then relabelled with that user's
 * **most recent** name (the one attached to their latest message or mention in
 * the corpus). Downstream visualizations keep grouping by `authorName` exactly as
 * before; the difference is that the name is now a 1:1 stand-in for the ID.
 */
object DatasetLoader {
    fun build(
        raws: List<RawMessage>,
        guildName: String,
        options: RenderOptions,
    ): Dataset {
        // IDs of authors ever flagged as bots — used to also strip bot mentions.
        // Keyed by identity so a bot that renamed itself is still filtered out.
        val botIds: Set<String> =
            if (options.excludeBots) {
                raws
                    .asSequence()
                    .filter { it.author.isBot }
                    .map { identity(it.author) }
                    .toHashSet()
            } else {
                emptySet()
            }

        // identity -> (latest observation time, name at that time)
        val latestName = HashMap<String, Pair<Long, String>>()

        fun observe(
            id: String,
            name: String,
            at: Long,
        ) {
            if (name.isBlank()) return
            val prev = latestName[id]
            if (prev == null || at >= prev.first) latestName[id] = at to name
        }

        val parsed = ArrayList<Message>(raws.size)
        for (raw in raws) {
            if (options.excludeBots && raw.author.isBot) continue
            val timestamp = Time.parse(raw.timestamp)
            val at = timestamp.toEpochMilli()

            val authorId = identity(raw.author)
            observe(authorId, raw.author.name, at)

            val mentions =
                raw.mentions
                    .asSequence()
                    .filter { !options.excludeBots || (identity(it) !in botIds && !it.isBot) }
                    .map { mn ->
                        observe(identity(mn), mn.name, at)
                        Mention(mn.id, mn.name)
                    }.toList()

            parsed +=
                Message(
                    timestamp = timestamp,
                    authorId = authorId,
                    authorName = raw.author.name,
                    isBot = raw.author.isBot,
                    content = raw.content,
                    mentions = mentions,
                    channel = raw.channel?.name ?: "",
                    // Reactions carry no names/content, so they're exempt from redaction.
                    reactions =
                        raw.reactions.mapNotNull { r ->
                            r.emoji?.displayKey()?.let { Reaction(it, r.count) }
                        },
                )
        }

        val displayNames = disambiguate(latestName)
        val canonical =
            parsed.map { m ->
                m.copy(
                    authorName = displayNames[m.authorId] ?: m.authorName,
                    mentions =
                        m.mentions.map { mn ->
                            displayNames[identity(mn)]?.let { mn.copy(name = it) } ?: mn
                        },
                )
            }

        val redacted = Redactor(options.redactNames, options.redactContent).apply(canonical)
        return Dataset(redacted, guildName, options)
    }

    /**
     * The stable grouping key for a user. The snowflake when we have it; the name
     * otherwise, so corpora exported without IDs still behave the way they used to
     * (one member per distinct name).
     */
    private fun identity(user: RawUser): String = user.id ?: user.name

    private fun identity(mention: Mention): String = mention.id ?: mention.name

    /**
     * Turn `identity -> latest name` into `identity -> chart label`, guaranteeing
     * the label is unique per identity.
     *
     * Collapsing name variants onto the latest name is only safe if distinct users
     * can't land on the same label — otherwise two people who happen to share a
     * display name would merge back into one bar. Colliding identities therefore
     * get a short discriminator (`alice (4821)`, from the tail of the snowflake),
     * falling back to the full identity if even that isn't enough.
     */
    private fun disambiguate(latest: Map<String, Pair<Long, String>>): Map<String, String> {
        val byName = latest.entries.groupBy({ it.value.second }, { it.key })
        val out = HashMap<String, String>(latest.size)
        val taken = HashSet<String>(latest.size)
        for ((name, ids) in byName) {
            if (ids.size == 1) {
                out[ids[0]] = name
                taken += name
                continue
            }
            // Deterministic order so labels don't shuffle between runs.
            for (id in ids.sorted()) {
                val short = "$name (${id.takeLast(4)})"
                val label = if (taken.add(short)) short else "$name ($id)".also { taken += it }
                out[id] = label
            }
        }
        return out
    }
}
