package guru.nidi.prok

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun main() {
    startClientProcess {
        forever { pos, len ->
            mix(pos, len,
                    seq(
                            (play2(::sinus, 880.0) * .3 * adsr(.5, attack = .1, decay = .1)).repeat(5, .2),
                            (play2(::sinus, 440.0) * .5 * adsr(.5, attack = .1, decay = .1))))
        }
    }
}

typealias Frame = Int
typealias Seconds = Double

//0..1 -> -1..1
typealias WaveForm = (pos: Double) -> Double

//frame -> -1..1
abstract class Framer(val ctx: ClientContext) {
    abstract val len: Int
    abstract fun at(pos: Frame): Double
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

fun ClientContext.play2(waveform: WaveForm, freq: Double): Framer {
    return object : Framer(this) {
        override fun at(pos: Frame) = waveform((pos / period(freq)) % 1)
        override val len = Int.MAX_VALUE
    }
}

fun ClientContext.adsr(sustain: Seconds, attack: Seconds = 0.0, decay: Seconds = 0.0, release: Seconds = 0.0, sustainLevel: Double = 1.0): Framer {
    fun adsr(sustain: Int, attack: Int = 0, decay: Int = 0, release: Int = 0, sustainLevel: Double = 1.0): Framer {
        return object : Framer(this) {
            override fun at(pos: Frame) = when {
                pos < 0 -> 0.0
                pos < attack -> (pos) / attack.toDouble()
                pos < attack + decay -> 1 - (pos - attack) / decay.toDouble() * (1 - sustainLevel)
                pos < attack + decay + sustain -> sustainLevel
                pos < attack + decay + sustain + release -> sustainLevel * (1 - (pos - attack - decay - sustain) / release.toDouble())
                else -> 0.0
            }

            override val len = attack + decay + sustain + release
        }
    }

    return adsr(toFrame(sustain), toFrame(attack), toFrame(decay), toFrame(release), sustainLevel)
}

infix operator fun Framer.times(volume: Double): Framer = object : Framer(ctx) {
    override fun at(pos: Frame) = this@times.at(pos) * volume
    override val len = this@times.len
}

infix operator fun Framer.times(volume: Framer): Framer = object : Framer(ctx) {
    override fun at(pos: Frame) = this@times.at(pos) * volume.at(pos)
    override val len = min(this@times.len, volume.len)
}

infix operator fun Framer.plus(other: Framer): Framer = object : Framer(ctx) {
    override fun at(pos: Frame) = this@plus.at(pos) + other.at(pos)
    override val len = max(this@plus.len, other.len)
}

fun Framer.repeat(times: Int, delay: Seconds = 0.0): Framer = object : Framer(ctx) {
    override fun at(pos: Frame): Double {
        val period = this@repeat.len + ctx.toFrame(delay)
        val rep = pos / period
        if (rep >= times) {
            return 0.0
        }
        val per = pos % period
        if (per > this@repeat.len) {
            return 0.0
        }
        return this@repeat.at(pos - rep * period)
    }

    override val len = (this@repeat.len + ctx.toFrame(delay)) * times
}

fun ClientContext.pause(len: Seconds) = adsr(len) * 0.0

fun ClientContext.seq(vararg framers: Framer): Framer = object : Framer(this) {
    override fun at(pos: Frame): Double {
        //TODO inefficient
        var end = 0
        for (framer in framers) {
            if (pos < end + framer.len) {
                return framer.at(pos - end)
            }
            end += framer.len
        }
        return 0.0
    }

    override val len = framers.sumBy { it.len }
}

fun mix(from: Int, len: Int, vararg framers: Framer): ByteArray {
    val buf = ByteArray(len)
    val pos = from % framers.maxBy { it.len }!!.len
    for (i in buf.indices) {
        var sum = 0.0
        for (f in framers) {
            sum += f.at(pos + i)
        }
        buf[i] = (sum * 127).toClampByte()
    }
    return buf
}

fun Double.toClampByte(): Byte {
    return (if (this > 127) 127.0 else if (this < -127) -127.0 else this).toByte()
}
