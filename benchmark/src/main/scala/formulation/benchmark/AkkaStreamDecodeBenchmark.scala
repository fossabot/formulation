package formulation.benchmark

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import com.sksamuel.avro4s.AvroInputStream
import formulation.akkastreams._
import io.circe.generic.auto._
import io.circe.syntax._
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, _}

//sbt "benchmark/jmh:run -i 10 -wi 10 -f 2 -t 1 formulation.benchmark.AkkaStreamDecodeBenchmark"

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class AkkaStreamDecodeBenchmark extends BenchSuite {

  private implicit val system: ActorSystem = ActorSystem()
  private implicit val materializer: Materializer = ActorMaterializer()

  val user: UserV1 = UserV1(1, "Mark", "mark@vectos.net", "so-secret")
  val json: String = user.asJson.noSpaces
  val avroBytes: Array[Byte] = formulation.encode(user)

  @Param(Array("10000", "100000"))
  var size: Int = _

  @Benchmark
  def benchAvro4s(): Unit = {
    def read(bytes: Array[Byte]) = {
      val in = new ByteArrayInputStream(bytes)
      val input = AvroInputStream.binary[UserV1](in)
      input.iterator.toSeq
    }

    def prg = Source(List.fill(size)(avroBytes))
      .map(read)
      .runWith(Sink.ignore)

    Await.result(prg, Duration.Inf)
  }

  @Benchmark
  def benchFormulation(): Unit = {
    def prg = Source(List.fill(size)(avroBytes))
      .via(decoder[UserV1])
      .runWith(Sink.ignore)

    Await.result(prg, Duration.Inf)
  }

  @Benchmark
  def benchCirce(): Unit = {
    def prg = Source(List.fill(size)(json))
      .map(io.circe.parser.decode[UserV1])
      .runWith(Sink.ignore)

    Await.result(prg, Duration.Inf)
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }
}
