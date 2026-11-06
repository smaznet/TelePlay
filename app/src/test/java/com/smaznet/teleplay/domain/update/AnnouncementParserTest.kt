package com.smaznet.teleplay.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnouncementParserTest {
    private fun parse(s: String) = Announcement.parse(s)

    @Test fun valid() = assertEquals(Announcement(2, 3), parse("latest:\ncode: 2\nhttps://t.me/TelePlayUpdate/3"))

    @Test fun whitespaceAndExtraLines() =
        assertEquals(Announcement(12, 7), parse("hello\n  latest:\n   code:   12  \nnotes here\nhttps://t.me/TelePlayUpdate/7\nbye"))

    @Test fun hostAndChannelCaseInsensitive() =
        assertEquals(Announcement(5, 9), parse("code: 5\nHTTPS://T.ME/teleplayupdate/9"))

    @Test fun missingCode() = assertNull(parse("latest:\nhttps://t.me/TelePlayUpdate/3"))

    @Test fun nonNumericCode() = assertNull(parse("code: two\nhttps://t.me/TelePlayUpdate/3"))

    @Test fun codeZero() = assertNull(parse("code: 0\nhttps://t.me/TelePlayUpdate/3"))

    @Test fun codeTenDigits() = assertNull(parse("code: 1234567890\nhttps://t.me/TelePlayUpdate/3"))

    @Test fun codeNineDigits() = assertEquals(Announcement(123456789, 3), parse("code: 123456789\nhttps://t.me/TelePlayUpdate/3"))

    @Test fun codeMustBeOwnLine() = assertNull(parse("latest code: 2 https://t.me/TelePlayUpdate/3"))

    @Test fun missingLink() = assertNull(parse("latest:\ncode: 2"))

    @Test fun otherChannelLink() = assertNull(parse("code: 2\nhttps://t.me/OtherChannel/3"))

    @Test fun otherHostLink() = assertNull(parse("code: 2\nhttps://example.com/TelePlayUpdate/3"))

    @Test fun postIdZero() = assertNull(parse("code: 2\nhttps://t.me/TelePlayUpdate/0"))

    @Test fun firstValidLinkWins() =
        assertEquals(Announcement(2, 4), parse("code: 2\nhttps://t.me/Other/9\nhttps://t.me/TelePlayUpdate/4\nhttps://t.me/TelePlayUpdate/5"))
}
