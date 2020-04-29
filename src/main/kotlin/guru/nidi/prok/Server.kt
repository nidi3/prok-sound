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

const val PORT = 10345

enum class Command {
    PING, CONNECT, STOP
}

class ServerInfo(val sampleRate: Int, val sampleSize: Int, val channels: Int) {
    override fun toString(): String {
        val channels = if (channels == 1) "mono" else "stereo"
        return "${sampleSize} bits / ${sampleRate / 1000} kHz $channels"
    }
}

class Server(private val port: Int, private val info: ServerInfo) {
    companion object {
        @JvmStatic
        fun main(vararg args: String) {
            Server(if (args.isEmpty()) PORT else args[0].toInt(), ServerInfo(40 * 1024, 8, 1)).run()
        }
    }

    fun run() {
        try {
            Communicator(port).use { c ->
                c.writeCommand(STOP)
            }
        } catch (e: ConnectException) {
            //ok
        }
        println("start on port $port, $info")
        val af = AudioFormat(info.sampleRate.toFloat(), info.sampleSize, info.channels, true, true)

        val line = AudioSystem.getSourceDataLine(af)
        line.open(af, info.sampleRate)
        line.start()
        var pos = 0
        val buf = ByteArray(info.sampleRate)
        var stop = false

        thread {
            ServerSocket(port).use { ss ->
                while (true) {
                    try {
                        Communicator(ss.accept()).use { c ->
                            when (c.readCommand()) {
                                PING -> {
                                    println("Ping")
                                    c.writeInt(1)
                                }
                                CONNECT -> {
                                    println("Connect")
                                    c.writeInfo(info)
                                    stop = true
                                }
                                STOP -> {
                                    println("Goodbye")
                                    exitProcess(1)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        //ignore
                    }
                }
            }
        }

        ServerSocket(port + 1).use { ss ->
            while (true) {
                try {
                    Communicator(ss.accept()).use { c ->
                        stop = false
                        println("Receive")
                        while (!stop) {
                            val toRead = line.available()
                            c.writeInt(pos)
                            c.writeInt(toRead)
                            val toWrite = c.readInt()
                            if (toWrite == 0) break
                            c.read(buf, toWrite)
                            line.write(buf, 0, toWrite)
                            pos += toWrite
                            Thread.sleep(50)
                        }
                        println("Disconnect")
                    }
                } catch (e: Exception) {
                    //ignore
                }
            }
        }
    }
}

fun startServerProcess(port: Int = PORT) {
    fun canConnect() = try {
        Communicator(port).use { c ->
            c.writeCommand(PING)
            c.readInt()
        }
        true
    } catch (e: ConnectException) {
        false
    }

    if (!canConnect()) {
        val isWin = System.getProperty("os.name").startsWith("Windows")
        val cmd = listOf(
                System.getProperty("java.home") + "/bin/java" + if (isWin) ".exe" else "",
                "-cp", System.getProperty("java.class.path"),
                Server::class.qualifiedName,
                port.toString())
        ProcessBuilder(cmd).inheritIO().start()
        for (i in 0..10) {
            Thread.sleep(20)
            if (canConnect()) {
                return
            }
        }
        throw IOException("Could not connect to server")
    }
}
