import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin


fun main() {
    generateTone()
}

const val SAMPLE_RATE = 40 * 1024

typealias Frame = Int
typealias Seconds = Double

fun Seconds.toFrame() = (this * SAMPLE_RATE).toInt()

//0..1 -> -1..1
typealias WaveForm = (pos: Double) -> Double

//frame -> -1..1
interface Framer {
    val len: Int
    fun play(pos: Frame): Double
}

fun sinus(pos: Double) = sin(pos * Math.PI * 2.0)

fun rect(pos: Double) = when {
    pos < .3333 -> 1.0
    pos < .6666 -> 0.0
    else -> -1.0
}

fun triangle(pos: Double) = when {
    pos < .25 -> pos * 4
    pos < .75 -> 1 - (pos - .25) * 4
    else -> (pos - .75) * 4 - 1
}

fun saw(pos: Double) = if (pos < .5) pos * 2 else pos * 2 - 2

fun play2(waveform: WaveForm, freq: Double): Framer {
    val period = SAMPLE_RATE.toDouble() / freq
    return object : Framer {
        override fun play(pos: Frame) = waveform((pos / period) % 1)
        override val len = Int.MAX_VALUE
    }
}

fun adsr(sustain: Seconds, attack: Seconds = 0.0, decay: Seconds = 0.0, release: Seconds = 0.0, sustainLevel: Double = 1.0): Framer {
    return object : Framer {
        val sf = sustain.toFrame()
        val af = attack.toFrame()
        val df = decay.toFrame()
        val rf = release.toFrame()
        override fun play(pos: Frame) = when {
            pos < 0 -> 0.0
            pos < af -> (pos) / af.toDouble()
            pos < af + df -> 1 - (pos - af) / df.toDouble() * (1 - sustainLevel)
            pos < af + df + sf -> sustainLevel
            pos < af + df + sf + rf -> sustainLevel * (1 - (pos - af - df - sf) / rf.toDouble())
            else -> 0.0
        }

        override val len = af + df + sf + rf
    }
}

infix operator fun Framer.times(volume: Double): Framer = object : Framer {
    override fun play(pos: Frame) = this@times.play(pos) * volume
    override val len = this@times.len
}

infix operator fun Framer.times(volume: Framer): Framer = object : Framer {
    override fun play(pos: Frame) = this@times.play(pos) * volume.play(pos)
    override val len = min(this@times.len, volume.len)
}

infix operator fun Framer.plus(other: Framer): Framer = object : Framer {
    override fun play(pos: Frame) = this@plus.play(pos) + other.play(pos)
    override val len = max(this@plus.len, other.len)
}

fun Framer.repeat(times: Int, delay: Seconds = 0.0): Framer = object : Framer {
    override fun play(pos: Frame): Double {
        val period = this@repeat.len + delay.toFrame()
        val rep = pos / period
        if (rep >= times) {
            return 0.0
        }
        val per = pos % period
        if (per > this@repeat.len) {
            return 0.0
        }
        return this@repeat.play(pos - rep * period)
    }

    override val len = (this@repeat.len + delay.toFrame()) * times
}

fun pause(len: Seconds) = adsr(len) * 0.0

fun seq(vararg framers: Framer): Framer = object : Framer {
    override fun play(pos: Frame): Double {
        //TODO inefficient
        var end = 0
        for (framer in framers) {
            if (pos < end + framer.len) {
                return framer.play(pos - end)
            }
            end += framer.len
        }
        return 0.0
    }

    override val len = framers.sumBy { it.len }
}

fun mix(from: Int, len: Int, vararg framers: Framer): ByteArray {
    val buf = ByteArray(len)
    for (i in buf.indices) {
        var sum = 0.0
        for (f in framers) {
            sum += f.play(from + i)
        }
        buf[i] = (sum * 127).toClampByte()
    }
    return buf
}

fun Double.toClampByte(): Byte {
    return (if (this > 127) 127.0 else if (this < -127) -127.0 else this).toByte()
}

fun generateTone() {
    val af = AudioFormat(SAMPLE_RATE.toFloat(), 8, 1, true, true)
    val line = AudioSystem.getSourceDataLine(af)
    line.open(af, SAMPLE_RATE)
    line.start()
    thread {
        var pos = 0
        do {
            val len = line.available()
            val buf = mix(pos, len,
//                    play2(::sinus, 440.0) * .3 * adsr(30000, sustainLevel = .5, attack = 5000, decay = 5000, release = 5000)
                    seq(
                            (play2(::sinus, 880.0) * .3 * adsr(.5, attack = .1, decay = .1)).repeat(3, .2),
                            (play2(::sinus, 440.0) * .5 * adsr(.5, attack = .1, decay = .1))
//                    play2(::sinus, 660.0) * .3)
                    ))
            line.write(buf, 0, len)
            pos += len
            Thread.sleep(50)
        } while (pos < 4.0.toFrame())
        while (line.bufferSize - line.available() > 3000) {
            Thread.sleep(50)
        }
        line.stop()
        line.close()
    }
}
