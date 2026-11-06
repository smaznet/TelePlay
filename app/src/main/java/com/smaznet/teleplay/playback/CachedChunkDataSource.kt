package com.smaznet.teleplay.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.smaznet.teleplay.data.cache.ChunkCache
import com.smaznet.teleplay.data.telegram.TelegramGateway
import com.smaznet.teleplay.domain.Video
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** Media3 data source serving a Telegram video from [ChunkCache], fetching missing chunks on demand. */
class CachedChunkDataSource(
    videoRef: AtomicReference<Video>,
    cache: ChunkCache,
    gateway: TelegramGateway,
    scope: CoroutineScope,
) : BaseDataSource(/* isNetwork = */ true) {
    private val reader = ChunkReader(videoRef, cache, gateway, scope)
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val available = try {
            reader.open(dataSpec.position, dataSpec.length)
        } catch (e: IOException) {
            if (dataSpec.position > 0 && e.message == "position out of range") {
                throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            }
            throw e
        }
        remaining = available
        opened = true
        transferStarted(dataSpec)
        return available
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = reader.read(buffer, offset, length)
        if (n < 0) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader.close()
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(
        video: Video,
        private val cache: ChunkCache,
        private val gateway: TelegramGateway,
        private val scope: CoroutineScope,
    ) : DataSource.Factory {
        /** Shared so a refreshed file reference is reused by later opens. */
        val videoRef = AtomicReference(video)
        override fun createDataSource(): DataSource = CachedChunkDataSource(videoRef, cache, gateway, scope)
    }
}
