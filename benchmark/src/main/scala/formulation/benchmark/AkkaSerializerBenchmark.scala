package formulation.benchmark

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.implicits._

import formulation._
import formulation.akkaserializer.FormulationAkkaSerializer
import formulation.benchmark.util.{PredefinedSchemaRegistryClient, SchemaEntry}
import formulation.schemaregistry.SchemaRegistry
import org.openjdk.jmh.annotations._

import scala.util.Try

//sbt "benchmark/jmh:run -i 10 -wi 10 -f 2 -t 1 formulation.benchmark.AkkaSerializerBenchmark"

//@State(Scope.Thread)
//@BenchmarkMode(Array(Mode.Throughput))
//@OutputTimeUnit(TimeUnit.SECONDS)
//class AkkaSerializerBenchmark {
//
//  val predefinedClient = new PredefinedSchemaRegistryClient[Try](
//    List(
//      SchemaEntry(1, "event.Completed", schema[Event.Completed]),
//      SchemaEntry(2, "event.Failed", schema[Event.Failed]),
//      SchemaEntry(3, "event.Started", schema[Event.Started])
//    )
//  )
//
//  class EventSerializer extends FormulationAkkaSerializer[Event](SchemaRegistry(predefinedClient))
//
//  val serializer = new EventSerializer
//  val bytes: Array[Byte] = serializer.toBinary(Event.Failed(Instant.now()))
//
//  @Benchmark
//  def encodeFormulation: Unit = {
//    serializer.toBinary(Event.Completed(Instant.now()))
//  }
//
//  @Benchmark
//  def decodeFormulation: Unit = {
//    serializer.fromBinary(bytes, None)
//  }
//}
//
//sealed trait Event
//
//object Event {
//  case class Completed(at: Instant) extends Event
//  case class Failed(at: Instant) extends Event
//  case class Started(at: Instant) extends Event
//
//  implicit val completed: Avro[Completed] = record1("event", "Completed")(Completed.apply)("at" -> member(instant, _.at))
//  implicit val failed: Avro[Failed] = record1("event", "Failed")(Failed.apply)("at" -> member(instant, _.at))
//  implicit val started: Avro[Started] = record1("event", "Started")(Started.apply)("at" -> member(instant, _.at))
//
//  implicit val codec: Avro[Event] = (completed | failed | started).as[Event]
//}