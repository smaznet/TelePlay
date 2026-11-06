package com.smaznet.teleplay.domain.update

private val CODE_LINE = Regex("""^\s*code:\s*(\d{1,9})\s*$""", RegexOption.MULTILINE)
private val RELEASE_LINK = Regex("""https://t\.me/TelePlayUpdate/(\d+)""", RegexOption.IGNORE_CASE)

/**
 * Parses the announcement: a `code: N` line and the first link to a post of the TelePlay update
 * channel. Null unless both are present and greater than zero.
 */
fun Announcement.Companion.parse(text: String): Announcement? {
    val code = CODE_LINE.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val post = RELEASE_LINK.findAll(text)
        .mapNotNull { it.groupValues[1].toIntOrNull()?.takeIf { id -> id > 0 } }
        .firstOrNull() ?: return null
    return Announcement(code, post)
}
