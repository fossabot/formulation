package formulation.rpc
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.spi.AsynchronousChannelProvider
import java.util.concurrent.Executors

import cats.effect._
import cats.implicits._
import formulation._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.PushGateway
import io.prometheus.client.hotspot._
import _root_.fs2._

import scala.concurrent.duration._

case class Binary(x: Int, y: Int)

object Binary {
  val codec: Avro[Binary] =
    record2("arith", "Protocol")(Binary.apply)("x" -> member(int, _.x), "y" -> member(int, _.y))
}

sealed trait Result[+A]

object Result {

  case class Success[A](value: A) extends Result[A]
  case class Failure(message: String) extends Result[Nothing]

  def codec[A](valueCodec: Avro[A]): Avro[Result[A]] = {

    val success = record1("arith", "Success")(Success.apply[A])("value" -> member(valueCodec, _.value))
    val failure = record1("arith", "Failure")(Failure.apply)("message"  -> member(string, _.message))

    (success | failure).as[Result[A]]
  }
}

trait ArithProtocol extends Protocol {

  val add: Endpoint[Binary, Result[Int]] =
    endpoint("add", Binary.codec, Result.codec(int))
  val mult: Endpoint[Binary, Result[Int]] =
    endpoint("mult", Binary.codec, Result.codec(int))
  val div: Endpoint[Binary, Result[Double]] =
    endpoint("div", Binary.codec, Result.codec(double))

}

class ArithServer[F[_]](logger: Logger[F], collectorRegistry: CollectorRegistry)(implicit AG: AsynchronousChannelGroup, F: ConcurrentEffect[F], C: Clock[F], CS: ContextShift[F], T: Timer[F]) extends AvroServer[F](collectorRegistry) with ArithProtocol {

  val handlers =
  add.handleWith(x => Result.Success(x.x + x.y)) orElse
  div.handleWith(b => if (b.y == 0) Result.Failure("Cannot divide by zero") else Result.Success(b.x.toDouble / b.y.toDouble)) orElse
  mult.handleWith(x => Result.Success(x.x * x.y))

  val middleware: ServerMiddleware[F] = RequestHandler.logging(logger)
  def create: Resource[F, Closeable[F]] = server(requestHandler = middleware(RequestHandler.implementation(handlers)))
}

class ArithClient[F[_]](context: AvroClientCreationContext[F])(implicit AG: AsynchronousChannelGroup, F: ConcurrentEffect[F], C: Clock[F], CS: ContextShift[F], T: Timer[F]) extends AvroClient[F](context) with ArithProtocol

object ServerMain extends IOApp {

  private val logger = Slf4jLogger.unsafeFromName[IO]("Arith")

  implicit val tcpACG: AsynchronousChannelGroup = AsynchronousChannelProvider
    .provider()
    .openAsynchronousChannelGroup(Executors.newCachedThreadPool(), 30)

  def exportJob[F[_]](pushGateway: PushGateway, collectorRegistry: CollectorRegistry)(implicit F: ConcurrentEffect[F], T: Timer[F]): Resource[F, Fiber[F, Unit]] = {
    def loop: F[Unit] = T.sleep(10.second) >> F.delay(pushGateway.push(collectorRegistry, "push_job_server")) >> loop

    Resource.make(F.start(loop))(_.cancel)
  }

  def forever: IO[Nothing] = IO.never >> forever

  override def run(args: List[String]): IO[ExitCode] =
    for {
      collectorRegistry <- IO.delay(new CollectorRegistry())
      pushGateway       <- IO.delay(new PushGateway("localhost:9091"))
      server = new ArithServer[IO](logger, collectorRegistry)
      _ <- exportJob[IO](pushGateway, collectorRegistry).use(_ => server.create.use(_ => forever))
    } yield ExitCode.Success


}

object ClientMain extends IOApp {
  private val logger = Slf4jLogger.unsafeFromName[IO]("Arith")

  implicit val tcpACG: AsynchronousChannelGroup = AsynchronousChannelProvider
    .provider()
    .openAsynchronousChannelGroup(Executors.newCachedThreadPool(), 30)

  def exportJob[F[_]](pushGateway: PushGateway, collectorRegistry: CollectorRegistry)(implicit F: ConcurrentEffect[F], T: Timer[F]): Resource[F, Fiber[F, Unit]] = {
    def loop: F[Unit] = T.sleep(10.second) >> F.delay(pushGateway.push(collectorRegistry, "push_job_client")) >> loop

    Resource.make(F.start(loop))(_.cancel)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    def operations(client: Resource[IO, ArithClient[IO]]) =
      client.use(c => Stream(Binary(1, 2)).repeat.chunkN(1000).evalMap(_.toList.parTraverse(c.div)).compile.drain)

    for {
      collectorRegistry <- IO.delay(new CollectorRegistry())
      pushGateway       <- IO.delay(new PushGateway("localhost:9091"))
      client = AvroClient[IO, ArithClient[IO]](requestTimeout = 30.seconds, collectorRegistry = collectorRegistry, creator = new ArithClient(_))
      res <- exportJob[IO](pushGateway, collectorRegistry).use(_ => operations(client))
      _   <- logger.info(s"Got result: $res")
    } yield ExitCode.Success
  }
}
