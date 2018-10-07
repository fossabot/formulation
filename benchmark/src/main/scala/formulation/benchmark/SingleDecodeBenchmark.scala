package formulation.benchmark

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

import cats.Id
import com.sksamuel.avro4s.AvroInputStream
import formulation.AvroDecodeContext
import io.circe.generic.auto._
import io.circe.syntax._
import org.openjdk.jmh.annotations._

// sbt "benchmark/jmh:run -i 10 -wi 10 -f 2 -t 1 formulation.benchmark.*"

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class SingleDecodeBenchmark extends BenchSuite {
  val user: UserV1 = UserV1(1, "Mark", "mark@vectos.net", "so-secret")
  val json: String = user.asJson.noSpaces
  val avroBytes: Array[Byte] = formulation.encode(user)
  val userDecoder = formulation.kleisliDecode[Id, UserV1]()

  @Benchmark
  def benchAvro4s(): Unit = {
    val in = new ByteArrayInputStream(avroBytes)
    val input = AvroInputStream.binary[UserV1](in)
    input.iterator.toSeq
  }

  @Benchmark
  def benchFormulation(): Unit = formulation.decode[UserV1](avroBytes)

  @Benchmark
  def benchFormulationKleisli(): Unit = userDecoder.run(AvroDecodeContext(avroBytes, None))

  @Benchmark
  def benchCirce(): Unit = io.circe.parser.decode[UserV1](json)
}


