package tachiyomi.source.local.filter

import android.content.Context
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.SManga
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR

/** Matched case-insensitively against the author in a local entry's ComicInfo.xml or legacy details.json. */
class AuthorFilter(context: Context) : Filter.Text(context.stringResource(MR.strings.author))

/** Matched case-insensitively against the artist in a local entry's ComicInfo.xml or legacy details.json. */
class ArtistFilter(context: Context) : Filter.Text(context.stringResource(MR.strings.artist))

/** Comma-separated genres; an entry has to match every one of them. */
class GenreFilter(context: Context) : Filter.Text(context.stringResource(SYMR.strings.genre))

/** Restricts results to local entries with the selected publishing status. */
class StatusFilter(context: Context) : Filter.Select<String>(
    context.stringResource(MR.strings.status),
    arrayOf(
        context.stringResource(MR.strings.all),
        context.stringResource(MR.strings.unknown),
        context.stringResource(MR.strings.ongoing),
        context.stringResource(MR.strings.completed),
        context.stringResource(MR.strings.licensed),
        context.stringResource(MR.strings.publishing_finished),
        context.stringResource(MR.strings.cancelled),
        context.stringResource(MR.strings.on_hiatus),
    ),
) {
    companion object {
        /** No status restriction: the "All" option. */
        const val ANY = Int.MIN_VALUE

        // Dropdown index -> SManga status.
        private val STATUSES = intArrayOf(
            ANY,
            SManga.UNKNOWN,
            SManga.ONGOING,
            SManga.COMPLETED,
            SManga.LICENSED,
            SManga.PUBLISHING_FINISHED,
            SManga.CANCELLED,
            SManga.ON_HIATUS,
        )

        fun statusFor(selectedIndex: Int): Int = STATUSES.getOrElse(selectedIndex) { ANY }
    }
}
