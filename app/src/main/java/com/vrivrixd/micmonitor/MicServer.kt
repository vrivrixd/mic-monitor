package com.vrivrixd.micmonitor

import android.content.res.AssetManager
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servidor HTTP e WebSocket embarcado.
 *
 * Serve a pagina guardada em assets/web. Normalmente aceita um ouvinte por vez, e
 * aceita varios quando a pessoa liga isso nas configuracoes. O audio vai em quadros
 * binarios de PCM cru. Os comandos vem em quadros de texto.
 */
class MicServer(
    private val assets: AssetManager,
    val port: Int,
    allowMultiple: Boolean,
    private val listener: Listener
) {

    interface Listener {
        /** Comando recebido de alguma pagina. */
        fun onCommand(command: JSONObject)

        /** Quantos ouvintes existem agora. */
        fun onClientCountChanged(count: Int)

        /** Estado atual que as paginas precisam conhecer. */
        fun configJson(): JSONObject
    }

    /** Muda junto com a preferencia, sem precisar reabrir a porta. */
    @Volatile
    var allowMultiple: Boolean = allowMultiple

    @Volatile
    private var closed = false

    private var serverSocket: ServerSocket? = null

    /** Cada ouvinte tem a fila propria, entao um computador lento nao atrasa os outros. */
    private val sessions = CopyOnWriteArrayList<Session>()

    fun clientCount(): Int = sessions.size

    fun hasClient(): Boolean = sessions.isNotEmpty()

    /** Verdadeiro quando um recem chegado seria recusado. */
    fun isBusy(): Boolean = !allowMultiple && sessions.isNotEmpty()

    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        Thread({ acceptLoop(socket) }, "MicMonitor-Accept").start()
    }

    fun stop() {
        closed = true
        for (session in sessions) session.close()
        sessions.clear()
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
    }

    /** Envia um bloco de PCM a todos os ouvintes. O quadro e montado uma vez so. */
    fun sendPcm(data: ByteArray, length: Int) {
        if (sessions.isEmpty()) return
        val packet = frame(OP_BINARY, data, length)
        for (session in sessions) session.enqueue(packet)
    }

    /** Reenvia o estado atual a todos os ouvintes. */
    fun sendConfig() {
        if (sessions.isEmpty()) return
        val text = listener.configJson().toString().toByteArray(Charsets.UTF_8)
        val packet = frame(OP_TEXT, text, text.size)
        for (session in sessions) session.enqueue(packet)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed) {
            val connection = try {
                socket.accept()
            } catch (e: IOException) {
                if (!closed) Log.w(TAG, "Fim do laco de aceitacao", e)
                return
            }
            Thread({ handle(connection) }, "MicMonitor-Conn").start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = socket.getInputStream().buffered(8192)
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(":")
                if (i > 0) {
                    headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
            }

            val upgrade = headers["upgrade"]
            if (upgrade != null && upgrade.lowercase().contains("websocket")) {
                serveWebSocket(socket, input, output, headers)
            } else {
                serveStatic(output, path)
                socket.close()
            }
        } catch (e: IOException) {
            Log.d(TAG, "Conexao encerrada", e)
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    // ---------------------------------------------------------------- estatico

    private fun serveStatic(output: OutputStream, path: String) {
        if (path == "/status") {
            // A pagina consulta isto antes de comecar, para saber se ha vaga sem
            // precisar ocupar nenhuma.
            val json = JSONObject().put("busy", isBusy()).toString().toByteArray(Charsets.UTF_8)
            writeResponse(output, "200 OK", "application/json; charset=utf-8", json)
            return
        }
        val asset = when (path) {
            "/", "/index.html" -> "web/index.html"
            "/style.css" -> "web/style.css"
            "/app.js" -> "web/app.js"
            "/i18n.js" -> "web/i18n.js"
            else -> null
        }
        if (asset == null) {
            writeResponse(output, "404 Not Found", TEXT_MIME, "Pagina nao encontrada".toByteArray())
            return
        }
        val body = try {
            assets.open(asset).use { it.readBytes() }
        } catch (e: IOException) {
            Log.e(TAG, "Falha ao ler o recurso " + asset, e)
            writeResponse(output, "500 Internal Server Error", TEXT_MIME, "Erro interno".toByteArray())
            return
        }
        writeResponse(output, "200 OK", mimeOf(asset), body)
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".html") -> "text/html; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".js") -> "application/javascript; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun writeResponse(output: OutputStream, status: String, mime: String, body: ByteArray) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(CRLF)
            .append("Content-Type: ").append(mime).append(CRLF)
            .append("Content-Length: ").append(body.size).append(CRLF)
            .append("Cache-Control: no-store").append(CRLF)
            .append("Connection: close").append(CRLF).append(CRLF)
            .toString()
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    // --------------------------------------------------------------- websocket

    private fun serveWebSocket(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val key = headers["sec-websocket-key"]
        if (key == null) {
            writeResponse(output, "400 Bad Request", TEXT_MIME, "Requisicao invalida".toByteArray())
            socket.close()
            return
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1))
        val accept = Base64.encodeToString(digest, Base64.NO_WRAP)

        val handshake = StringBuilder()
            .append("HTTP/1.1 101 Switching Protocols").append(CRLF)
            .append("Upgrade: websocket").append(CRLF)
            .append("Connection: Upgrade").append(CRLF)
            .append("Sec-WebSocket-Accept: ").append(accept).append(CRLF).append(CRLF)
            .toString()
        output.write(handshake.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val session = Session(socket, output)

        // A entrada na lista acontece de uma vez so, senao dois navegadores que
        // chegam juntos poderiam passar os dois pela conferencia da vaga.
        val accepted = synchronized(sessions) {
            if (isBusy()) {
                false
            } else {
                sessions.add(session)
                true
            }
        }
        if (!accepted) {
            val busy = JSONObject().put("type", "busy").toString().toByteArray(Charsets.UTF_8)
            session.writeAndClose(frame(OP_TEXT, busy, busy.size))
            return
        }

        listener.onClientCountChanged(sessions.size)
        session.startWriter()
        sendConfigTo(session)

        try {
            readLoop(input, session)
        } catch (e: IOException) {
            Log.d(TAG, "Leitura de um ouvinte encerrada", e)
        } finally {
            sessions.remove(session)
            session.close()
            listener.onClientCountChanged(sessions.size)
        }
    }

    /** Estado atual para um ouvinte so, logo que ele entra. */
    private fun sendConfigTo(session: Session) {
        val text = listener.configJson().toString().toByteArray(Charsets.UTF_8)
        session.enqueue(frame(OP_TEXT, text, text.size))
    }

    private fun readLoop(input: InputStream, session: Session) {
        while (!closed && !session.isClosed()) {
            val b0 = input.read()
            if (b0 < 0) return
            val b1 = input.read()
            if (b1 < 0) return

            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var length = (b1 and 0x7F).toLong()

            if (length == 126L) {
                length = ((readByte(input) shl 8) or readByte(input)).toLong()
            } else if (length == 127L) {
                length = 0L
                for (i in 0 until 8) {
                    length = (length shl 8) or readByte(input).toLong()
                }
            }
            if (length > MAX_INCOMING || length < 0) return

            val mask = ByteArray(4)
            if (masked) readFully(input, mask, 4)

            val payload = ByteArray(length.toInt())
            readFully(input, payload, payload.size)
            if (masked) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            when (opcode) {
                OP_TEXT -> handleText(String(payload, Charsets.UTF_8))
                OP_CLOSE -> return
                OP_PING -> session.enqueue(frame(OP_PONG, payload, payload.size))
                else -> Unit
            }
        }
    }

    private fun handleText(text: String) {
        try {
            listener.onCommand(JSONObject(text))
        } catch (e: Exception) {
            Log.w(TAG, "Comando invalido: " + text, e)
        }
    }

    private fun readByte(input: InputStream): Int {
        val v = input.read()
        if (v < 0) throw IOException("fim inesperado")
        return v
    }

    private fun readFully(input: InputStream, buf: ByteArray, count: Int) {
        var off = 0
        while (off < count) {
            val r = input.read(buf, off, count - off)
            if (r < 0) throw IOException("fim inesperado")
            off += r
        }
    }

    /** Monta um quadro do servidor para o cliente, sempre sem mascara. */
    private fun frame(opcode: Int, data: ByteArray, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length + 10)
        out.write(0x80 or opcode)
        if (length < 126) {
            out.write(length)
        } else if (length <= 0xFFFF) {
            out.write(126)
            out.write((length shr 8) and 0xFF)
            out.write(length and 0xFF)
        } else {
            out.write(127)
            var shift = 56
            while (shift >= 0) {
                out.write((length ushr shift) and 0xFF)
                shift -= 8
            }
        }
        out.write(data, 0, length)
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (out.size() == 0) null else out.toString("ISO-8859-1")
            }
            if (b == 10) {
                return out.toString("ISO-8859-1").trimEnd('\r')
            }
            out.write(b)
            if (out.size() > 8192) throw IOException("cabecalho longo demais")
        }
    }

    /** Um ouvinte conectado, com fila propria para nao travar a captura. */
    private inner class Session(val socket: Socket, val output: OutputStream) {

        private val queue = ArrayBlockingQueue<ByteArray>(120)
        private val closedFlag = AtomicBoolean(false)
        private var writer: Thread? = null

        fun isClosed(): Boolean = closedFlag.get()

        fun startWriter() {
            val t = Thread({
                try {
                    while (!closedFlag.get()) {
                        val chunk = queue.take()
                        if (chunk.isEmpty()) break
                        output.write(chunk)
                        output.flush()
                    }
                } catch (_: InterruptedException) {
                } catch (e: IOException) {
                    Log.d(TAG, "Escrita encerrada", e)
                } finally {
                    closeSocket()
                }
            }, "MicMonitor-Writer")
            writer = t
            t.start()
        }

        fun enqueue(data: ByteArray) {
            if (closedFlag.get()) return
            // Rede lenta descarta o audio mais antigo em vez de acumular atraso.
            while (!queue.offer(data)) {
                if (queue.poll() == null) return
            }
        }

        /**
         * Encerra sem escrever nada, entao pode vir de qualquer thread, inclusive a
         * principal. Escrever em socket fora de uma thread de rede derruba o aplicativo,
         * e fechar a conexao ja avisa o navegador do outro lado.
         */
        fun close() {
            if (!closedFlag.compareAndSet(false, true)) return
            queue.clear()
            queue.offer(ByteArray(0))
            writer?.interrupt()
            closeSocket()
        }

        /** Manda um ultimo quadro e encerra. So pode ser chamado da thread da conexao. */
        fun writeAndClose(data: ByteArray) {
            try {
                output.write(data)
                output.flush()
            } catch (_: IOException) {
            }
            close()
        }

        private fun closeSocket() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        private const val TAG = "MicServer"
        private const val CRLF = "\r\n"
        private const val TEXT_MIME = "text/plain; charset=utf-8"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_INCOMING = 64L * 1024L

        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
    }
}
