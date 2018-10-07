package formulation.rpc

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import _root_.fs2._
import _root_.fs2.io._
import _root_.fs2.io.tcp.Socket
import _root_.io.chrisdavenport.log4cats.Logger
import _root_.io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import _root_.io.prometheus.client._
import cats.data.Kleisli
import cats.effect._
import cats.implicits._
import formulation._
import formulation.rpc.pipes._
import scodec.bits.BitVector
import scodec.{codecs => C}

import scala.util.control.NonFatal

abstract class AvroServer[F[_]](c: CollectorRegistry)(implicit AG: AsynchronousChannelGroup, F: ConcurrentEffect[F], C: Clock[F], CS: ContextShift[F], T: Timer[F]) extends Protocol {

  private val logger = Slf4jLogger.unsafeCreate

  case class Endpoint[I, O](name: String)(implicit requestDecoder: AvroDecoder[I], requestSchema: AvroSchema[I], responseEncoder: AvroEncoder[O], responseSchema: AvroSchema[O]) {
    def handleWithAsync(impl: I => F[O]): PartialFunction[Request, F[BitVector]] =
      Function.unlift[Request, F[BitVector]] { env =>
        if (env.endpointName == name)
          Some(
            F.fromEither(decode[I](env.payload.toByteArray))
              .flatMap(impl(_).map(resp => BitVector.view(encode[O](resp))))
          )
        else
          None
      }

    def handleWith(impl: I => O): PartialFunction[Request, F[BitVector]] =
      handleWithAsync(i => F.pure(impl(i)))
  }

  override def endpoint[I, O](name: String, request: Avro[I], response: Avro[O], documentation: Option[String]): Endpoint[I, O] =
    Endpoint(name)(request.apply[AvroDecoder], request.apply[AvroSchema], response.apply[AvroEncoder], response.apply[AvroSchema])

  def server(
    address: InetSocketAddress = new InetSocketAddress("localhost", 10321),
    requestHandler: Kleisli[F, Envelope[Request], BitVector]
  ): Resource[F, Closeable[F]] = {

    def handlerPipe(responseTimeHisto: MeasureHisto[F]): Pipe[F, Envelope[Request], BitVector] =
      _.evalMap(x => requestHandler.run(x).measure(responseTimeHisto))

    def handleSocket(metrics: ServerMetrics[F])(socket: Resource[F, Socket[F]]): Stream[F, Unit] =
      Stream
        .resource(socket)
        .flatMap(
          s =>
            s.reads(256 * 1024)
              .through(Pipeline.framingDecode(metrics.receivedBytes, logger))
              .through(Pipeline.codecDecode(Envelope.codec(Request.codec), metrics.requestCounter, logger))
              .through(Metrics.gauge(metrics.activeRequests)(handlerPipe(metrics.responseTime)))
              .through(Pipeline.framingEncode(metrics.sentBytes, logger))
              .to(s.writes(timeout = None))
        )

    def startServer =
      for {
        _       <- logger.info("Starting server")
        metrics <- ServerMetrics.prom(c)
        fiber <- F.start {
          tcp
            .server[F](address)
            .through(Logging.trace(logger, "client connected"))
            .map(handleSocket(metrics))
            .parJoinUnbounded
            .compile
            .drain
        }
      } yield
        new Closeable[F] {
          override def shutdown: F[Unit] = logger.info("Shutting down server") >> fiber.cancel
        }

    Resource.make(startServer)(_.shutdown)
  }

}

object RequestHandler {
  def logging[F[_]: Sync](
    logger: Logger[F]
  ): ServerMiddleware[F] =
    other =>
      Kleisli { request =>
        logger.trace(s"Received request (correlationId: ${request.correlationId})") >>
        other
          .run(request)
          .onError {
            case NonFatal(ex) =>
              logger.error(ex)(
                s"An error occurred for request (correlationId: ${request.correlationId})"
              )
          }
    }

  def implementation[F[_]](
    handlers: PartialFunction[Request, F[BitVector]]
  )(implicit F: Sync[F]): Kleisli[F, Envelope[Request], BitVector] = Kleisli { env =>
    for {
      resp <- handlers.applyOrElse(
        env.payload,
        (_: Request) =>
          F.raiseError(
            new Throwable(s"No handler for `${env.payload.endpointName}`")
        )
      )
      env <- F.fromOption(
        Envelope.codec(C.bits).encode(Envelope(env.correlationId, resp)).toOption,
        new Throwable("Unable to encode response")
      )
    } yield env
  }
}
