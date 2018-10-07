package formulation.benchmark

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

import cats.Id
import com.sksamuel.avro4s.AvroOutputStream
import formulation.AvroEncodeContext
import org.openjdk.jmh.annotations._
import io.circe.generic.auto._
import io.circe.syntax._

// sbt "benchmark/jmh:run -i 10 -wi 10 -f 2 -t 1 formulation.benchmark.*"

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class SingleEncodeBenchmark extends BenchSuite {
  def user = UserV1(1, "Mark", "mark@vectos.net", "so-secret")
  val userEncoder = formulation.kleisliEncode[Id, UserV1]()

  @Benchmark
  def benchAvro4s(): Unit = {
    val baos = new ByteArrayOutputStream()
    val output = AvroOutputStream.binary[UserV1](baos)
    output.write(user)
    output.close()
    baos.toByteArray
  }
  @Benchmark
  def benchFormulation(): Unit = formulation.encode(user)

  @Benchmark
  def benchFormulationKleisli(): Unit = userEncoder.run(AvroEncodeContext(user, None))

  @Benchmark
  def benchCirce(): Unit = user.asJson.noSpaces
}
