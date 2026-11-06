package com.smaznet.teleplay.domain.proxy

/** What a bulk import did, in numbers only (contracts/commands.md). */
data class ImportCounts(val added: Int, val duplicate: Int, val invalid: Int, val skipped: Int) {
    val total: Int get() = added + duplicate + invalid + skipped
}

/**
 * Bulk import of proxy links. It only saves: it never switches the proxy on and never changes the selected
 * proxy (clarification, FR-009). At most [MAX_LINKS] links per message are processed.
 */
class ProxyImport(private val repository: ProxyRepository) {
    fun import(text: String): ImportCounts {
        val links = ProxyLinkParser.extractLinks(text)
        var added = 0; var duplicate = 0; var invalid = 0
        var skipped = (links.size - MAX_LINKS).coerceAtLeast(0)
        for (link in links.take(MAX_LINKS)) {
            when (val p = ProxyLinkParser.parse(link)) {
                is ParsedLink.Invalid -> invalid++
                is ParsedLink.Valid -> when (repository.add(p.draft)) {
                    is AddResult.Added -> added++
                    is AddResult.Duplicate -> duplicate++
                    AddResult.LimitReached -> skipped++
                    is AddResult.Invalid -> invalid++
                }
            }
        }
        return ImportCounts(added, duplicate, invalid, skipped)
    }

    companion object { const val MAX_LINKS = 100 }
}
