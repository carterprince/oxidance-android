package io.github.oxidance.core

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element

class SyncException(message: String) : IOException(message)

/** Minimal WebDAV client for syncing with an rclone `serve webdav` server, matching the desktop app. */
class Dav(val url: HttpUrl, val username: String, private val password: String) {
    companion object {
        const val SONGS = "songs/"
        private const val LISTING_LIFETIME_MS = 60_000L
        private val XML = "application/xml".toMediaType()

        // Audio transfers can take minutes, so only small requests get an overall timeout.
        private val transfers = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
        private val requests = transfers.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()

        fun normalizeUrl(value: String): HttpUrl {
            val trimmed = value.trim()
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = (withScheme.trimEnd('/') + "/").toHttpUrlOrNull() ?: throw SyncException("Enter a valid server URL.")
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null
                || !withScheme.substringBefore("://").lowercase().let { it == "http" || it == "https" })
                throw SyncException("Use an HTTP or HTTPS URL without credentials, query, or fragment.")
            return url
        }

        /** Connects to `url`, or to its `oxidance/` folder when `url` is just a domain. */
        fun connect(url: String, username: String, password: String): Dav {
            val normalized = normalizeUrl(url)
            val candidates = listOfNotNull(normalized, normalized.resolve("oxidance/")?.takeIf { normalized.encodedPath == "/" })
            var error: SyncException? = null
            for (candidate in candidates) {
                val dav = Dav(candidate, username, password)
                try { dav.probe(); return dav } catch (failure: SyncException) { error = failure }
            }
            throw error!!
        }

        fun parseListing(body: ByteArray): Map<String, Long> {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val document = try { factory.newDocumentBuilder().parse(body.inputStream()) }
                catch (error: Exception) { throw SyncException("Invalid server listing: ${error.message}") }
            val entries = mutableMapOf<String, Long>()
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            for (index in 0 until responses.length) {
                val response = responses.item(index) as Element
                if (response.getElementsByTagNameNS("DAV:", "collection").length > 0) continue
                val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent?.trim() ?: continue
                val length = response.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0
                val name = href.trimEnd('/').substringAfterLast('/')
                if (name.isNotEmpty()) entries[name] = length
            }
            return entries
        }
    }

    private var listing: Pair<Long, Map<String, Long>>? = null

    private fun request(path: String) = Request.Builder().url(url.resolve(path)!!)
        .header("Authorization", Credentials.basic(username, password))

    private fun call(client: OkHttpClient, request: Request): Response = try {
        client.newCall(request).execute()
    } catch (error: IOException) {
        throw SyncException("Could not reach the sync server: ${error.message}")
    }

    private fun check(response: Response, action: String): Response {
        if (response.isSuccessful) return response
        response.close()
        if (response.code == 401 || response.code == 403) throw SyncException("The sync server rejected the username or password.")
        throw SyncException("$action failed: HTTP ${response.code}")
    }

    private fun send(builder: Request.Builder, action: String, client: OkHttpClient = requests) {
        check(call(client, builder.build()), action).close()
    }

    /** Confirms the URL is a WebDAV folder and the credentials are accepted. */
    fun probe() {
        check(call(requests, request("").header("Depth", "0").method("PROPFIND", null).build()), "Connecting").use { response ->
            if (response.code != 207) throw SyncException("This address is not a WebDAV folder. Check the server URL.")
        }
    }

    fun get(path: String): ByteArray? {
        val response = call(requests, request(path).get().build())
        if (response.code == 404) { response.close(); return null }
        return check(response, "Downloading $path").use { it.body.bytes() }
    }

    fun put(path: String, bytes: ByteArray, lock: String? = null) {
        val builder = request(path).put(bytes.toRequestBody())
        if (lock != null) builder.header("If", "($lock)")
        send(builder, "Uploading $path")
    }

    fun mkcol(path: String) {
        val response = call(requests, request(path).method("MKCOL", null).build())
        // 405 means the collection already exists.
        if (response.code == 405) { response.close(); return }
        check(response, "Creating $path").close()
    }

    /** Takes an exclusive write lock, waiting while another device holds it. */
    fun lock(path: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?><D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner>oxidance</D:owner></D:lockinfo>"""
        repeat(10) {
            val response = call(requests, request(path).header("Timeout", "Second-120").header("Depth", "0")
                .method("LOCK", body.toRequestBody(XML)).build())
            if (response.code == 423) { response.close(); Thread.sleep(2000); return@repeat }
            return check(response, "Locking $path").use { it.header("Lock-Token") }
                ?: throw SyncException("The sync server did not return a lock token.")
        }
        throw SyncException("Another device is still syncing. Try again shortly.")
    }

    fun unlock(path: String, token: String) = send(request(path).header("Lock-Token", token).method("UNLOCK", null), "Unlocking $path")

    /** Lists file names and sizes directly inside a collection; a missing collection is empty. */
    fun list(path: String): Map<String, Long> {
        val body = """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getcontentlength/></D:prop></D:propfind>"""
        val response = call(requests, request(path).header("Depth", "1").method("PROPFIND", body.toRequestBody(XML)).build())
        if (response.code == 404) { response.close(); return emptyMap() }
        return check(response, "Listing ${path.ifEmpty { "server" }}").use {
            if (it.code != 207) throw SyncException("The sync server did not return a WebDAV listing. Check the server URL.")
            parseListing(it.body.bytes())
        }
    }

    /** The `songs/` listing, cached briefly so many queued downloads share one request. */
    @Synchronized
    fun songs(refresh: Boolean): Map<String, Long> {
        val cached = listing
        if (!refresh && cached != null && System.currentTimeMillis() - cached.first < LISTING_LIFETIME_MS) return cached.second
        return list(SONGS).also { listing = System.currentTimeMillis() to it }
    }

    /** Streams a remote file to `destination` through a temporary file. */
    fun download(path: String, destination: File, cancelled: AtomicBoolean) {
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            check(call(transfers, request(path).get().build()), "Downloading $path").use { response ->
                temporary.outputStream().use { output ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled.get()) throw IOException("Download cancelled")
                        val read = try { input.read(buffer) } catch (error: IOException) {
                            throw SyncException("Download from sync server interrupted: ${error.message}")
                        }
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (!temporary.renameTo(destination)) throw IOException("Could not save ${destination.name}")
        } catch (error: IOException) {
            temporary.delete()
            throw error
        }
    }

    /** Uploads a local file under a temporary name, then moves it into place so other devices never see a partial file. */
    fun upload(path: String, source: File) {
        val temporary = "$path.part"
        send(request(temporary).put(source.asRequestBody()), "Uploading $path", transfers)
        send(request(temporary).header("Destination", url.resolve(path).toString()).header("Overwrite", "T")
            .method("MOVE", null), "Uploading $path")
        val name = path.removePrefix(SONGS)
        synchronized(this) {
            val cached = listing
            if (name != path && cached != null) listing = cached.first to (cached.second + (name to source.length()))
        }
    }
}
