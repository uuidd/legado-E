package io.legado.app.web.socket

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Web counterpart of the native change-cover search.
 *
 * The public `/searchBook` socket follows the user's current search scope and
 * merges equal books. Cover replacement needs a different contract: cached
 * exact matches and the global cover rule are returned first, then every
 * enabled source with a cover search rule is queried independently.
 */
class BookCoverSearchWebSocket(handshakeRequest: NanoHTTPD.IHTTPSession) :
    NanoWSD.WebSocket(handshakeRequest), CoroutineScope by MainScope() {

    private val normalClosure = NanoWSD.WebSocketFrame.CloseCode.NormalClosure
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var searchJob: Job? = null

    override fun onOpen() {
        launch(IO) {
            runCatching {
                while (isOpen) {
                    ping("ping".toByteArray())
                    delay(30000)
                }
            }
        }
    }

    override fun onClose(
        code: NanoWSD.WebSocketFrame.CloseCode,
        reason: String,
        initiatedByRemote: Boolean
    ) {
        stopSearch()
        cancel()
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        launch(IO) {
            if (!message.textPayload.isJson()) {
                closeWithError("数据必须为Json格式")
                return@launch
            }
            val query = GSON.fromJsonObject<Map<String, String>>(message.textPayload).getOrNull()
            val name = query?.get("name")?.trim().orEmpty()
            val author = query?.get("author")?.replace(AppPattern.authorRegex, "")?.trim().orEmpty()
            if (name.isBlank()) {
                closeWithError(appCtx.getString(R.string.cannot_empty))
                return@launch
            }
            startSearch(name, author)
        }
    }

    private fun startSearch(name: String, author: String) {
        stopSearch()
        val threadCount = min(AppConfig.threadCount, AppConst.MAX_THREAD).coerceAtLeast(1)
        searchPool = Executors.newFixedThreadPool(threadCount).asCoroutineDispatcher()
        searchJob = launch(searchPool!!) {
            runCatching {
                send(GSON.toJson(mapOf("type" to "coverSearch", "state" to "started")))
                // Match the native dialog by exposing reusable cached results.
                appDb.searchBookDao.getEnableHasCover(name, author).forEach(::sendResult)

                // The configured global cover rule is the native dialog's
                // first search stage. Continue through sources afterwards so a
                // desktop search produces the complete chooser in one pass.
                runCatching { BookCover.searchCover(Book(name = name, author = author)) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        sendResult(
                            SearchBook(
                                originName = "封面规则",
                                name = name,
                                author = author,
                                coverUrl = it,
                                originOrder = -1
                            )
                        )
                    }

                flow {
                    appDb.bookSourceDao.allEnabledPart.forEach { sourcePart ->
                        sourcePart.getBookSource()?.let { source ->
                            if (!source.getSearchRule().coverUrl.isNullOrBlank()) emit(source)
                        }
                    }
                }.mapParallelSafe(threadCount) { source ->
                    val result = runCatching {
                        withTimeout(60000L) {
                            WebBook.searchBookAwait(
                                source,
                                name,
                                shouldBreak = { it > 0 }
                            ).firstOrNull()?.takeIf { item ->
                                item.name == name &&
                                    item.author == author &&
                                    !item.coverUrl.isNullOrBlank()
                            }
                        }
                    }.onFailure {
                        currentCoroutineContext().ensureActive()
                    }.getOrNull()
                    source.bookSourceName to result
                }.collect { (sourceName, result) ->
                    if (result != null) {
                        appDb.searchBookDao.insert(result)
                        sendResult(result)
                    }
                    send(GSON.toJson(mapOf("type" to "progress", "source" to sourceName)))
                }
            }.onFailure { error ->
                currentCoroutineContext().ensureActive()
                if (isOpen) send(GSON.toJson(mapOf("error" to (error.localizedMessage ?: error.toString()))))
            }
            if (isOpen) close(normalClosure, "Cover search finish", false)
        }
    }

    private fun sendResult(result: SearchBook) {
        if (isOpen) send(GSON.toJson(listOf(result)))
    }

    private fun closeWithError(message: String) {
        runCatching { send(GSON.toJson(mapOf("error" to message))) }
        close(normalClosure, message, false)
    }

    private fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        searchPool?.close()
        searchPool = null
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit

    override fun onException(exception: IOException) {
        stopSearch()
    }
}
