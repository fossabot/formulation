package formulation.benchmark
import formulation.rpc.pipes._
import java.util.concurrent.TimeUnit

import cats.effect.IO
import formulation.rpc.Envelope
import org.openjdk.jmh.annotations._
import fs2.{Chunk, Stream}
import scodec.bits._
import scodec.{codecs => C}

// sbt "benchmark/jmh:run -i 5 -wi 5 -f 2 -t 1 formulation.benchmark.Fs2PipeBenchmark"

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class Fs2PipeBenchmark {

  val frame = hex"000f000002d7000900000000000000e03f".toBitVector
  val payload = hex"000002d7000900000000000000e03f".toBitVector
  val envelope = Envelope(1337l, hex"0204".toBitVector)
  val frameByteArray = frame.toByteArray

  private val elements = 1000

  @Benchmark
  def framingDecode(): Unit =
    Stream(frame).repeat.take(elements).through(Framing.decode[IO]).compile.drain.unsafeRunSync()

  @Benchmark
  def framingEncode(): Unit =
    Stream(payload).repeat.take(elements).through(Framing.encode[IO]).compile.drain.unsafeRunSync()

  @Benchmark
  def byteToBitVector(): Unit =
    Stream.chunk(Chunk.array(frameByteArray)).repeat.take(elements).through(Bytes.byteToBitVector[IO]).compile.drain.unsafeRunSync()

  @Benchmark
  def bitVectorToByte(): Unit =
    Stream(frame).repeat.take(elements).through(Bytes.bitVectorToByte[IO]).compile.drain.unsafeRunSync()

  @Benchmark
  def envelopeEncode(): Unit =
    Stream(envelope).repeat.take(elements).through(Codec.encode[IO, Envelope[BitVector]](Envelope.codec(C.bits))).compile.drain.unsafeRunSync()

  @Benchmark
  def envelopeDecode(): Unit =
    Stream(payload).repeat.take(elements).through(Codec.decode[IO, Envelope[BitVector]](Envelope.codec(C.bits))).compile.drain.unsafeRunSync()

}
