/*
 * Copyright © 2020 Stefan Niederhauser (nidin@gmx.ch)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package guru.nidi.prok

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class StreamClosedException : RuntimeException()

class Communicator(private val s: Socket) : Closeable {
    constructor(port: Int) : this(Socket().apply {
        connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500)
    })

    init {
        s.soTimeout = 500
    }

    private val inp = s.getInputStream()
    private val out = s.getOutputStream()

    fun writeCommand(c: Command) {
        out.write(c.ordinal)
    }

    fun readCommand() = readByte().let { b -> Command.values().first { it.ordinal == b } }

    fun writeInfo(info: ServerInfo) {
        writeInt(info.sampleRate)
        writeInt(info.sampleSize)
        writeInt(info.channels)
    }

    fun readInfo(): ServerInfo = ServerInfo(readInt(), readInt(), readInt())

    fun writeInt(i: Int) {
        out.write(i shr 24)
        out.write(i shr 16)
        out.write(i shr 8)
        out.write(i)
    }

    fun readInt(): Int = (readByte() shl 24) + (readByte() shl 16) + (readByte() shl 8) + readByte()

    fun read(buf: ByteArray, len: Int) = inp.read(buf, 0, len).also { if (it < 0) throw StreamClosedException() }

    fun write(buf: ByteArray, len: Int) = out.write(buf, 0, len)

    private fun readByte() = inp.read().also { if (it < 0) throw StreamClosedException() }

    override fun close() {
        inp.close()
        out.close()
        s.close()
    }
}
