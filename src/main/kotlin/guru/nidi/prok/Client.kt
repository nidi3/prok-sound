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

import guru.nidi.prok.Command.*
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ClientContext(private val c: Communicator, private val info: ServerInfo) {
    fun toFrame(sec: Double) = (sec * info.sampleRate).toInt()
    fun period(freq: Double) = info.sampleRate / freq
    fun forever(func: (from: Int, len: Int) -> ByteArray) {
        while (true) {
            val pos = c.readInt()
            val len = c.readInt()
            val buf = func(pos, len)
            c.writeInt(len)
            c.write(buf, len)
        }
    }
}

fun startClientProcess(port: Int = PORT, action: ClientContext.() -> Unit) {
    startServerProcess(port)
    try {
        val info = Communicator(port).use { c ->
            c.writeCommand(CONNECT)
            c.readInfo()
        }
        Communicator(port + 1).use { c ->
            action(ClientContext(c, info))
            c.writeInt(0)
        }
    } catch (e: StreamClosedException) {
        //ignore
    }
}
