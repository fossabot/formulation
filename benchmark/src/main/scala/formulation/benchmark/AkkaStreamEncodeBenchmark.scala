package formulation.benchmark

import java.io.ByteArrayOutputStream

import formulation.akkastreams._
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import com.sksamuel.avro4s.AvroOutputStream
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import io.circe.generic.auto._
import io.circe.syntax._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

//

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class AkkaStreamEncodeBenchmark extends BenchSuite {

  private implicit val system: ActorSystem = ActorSystem()
  private implicit val materializer: Materializer = ActorMaterializer()

  @Param(Array("10000", "100000"))
  var size: Int = _

  @Benchmark
  override def benchAvro4s(): Unit = {
    def write(user: UserV1) = {
      val baos = new ByteArrayOutputStream()
      val output = AvroOutputStream.binary[UserV1](baos)
      output.write(user)
      output.close()
      baos.toByteArray
    }

    def prg = Source(List.fill(size)(UserV1(1, "Mark", "mark@vectos.net", "so-secret")))
      .map(write)
      .runWith(Sink.ignore)

    Await.result(prg, Duration.Inf)
  }

  @Benchmark
  override def benchFormulation(): Unit = {
    def prg = Source(List.fill(size)(UserV1(1, "Mark", "mark@vectos.net", "so-secret")))
      .via(encoder[UserV1]())
      .runWith(Sink.ignore)

    Await.result(prg, Duration.Inf)
  }

  @Benchmark
  override def benchCirce(): Unit = {
    def prg = Source(List.fill(size)(UserV1(1, "Mark", "mark@vectos.net", "so-secret")))
      .map(_.asJson.noSpaces)
      .runWith(Sink.ignore)

    Await.result(prg, Duration.Inf)
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }
}
