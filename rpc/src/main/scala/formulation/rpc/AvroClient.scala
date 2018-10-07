package formulation.rpc
import java.net.{ConnectException, InetSocketAddress}
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.TimeUnit

import _root_.io.chrisdavenport.log4cats.Logger
import _root_.io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import _root_.io.prometheus.client._
import cats.data.NonEmptyList
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import formulation.rpc.pipes._
import formulation.{Avro, AvroDecoder, AvroEncoder, AvroSchema}
import fs2._
import fs2.concurrent.Queue
import fs2.io.tcp
import fs2.io.tcp.Socket
import scodec.bits.BitVector
import scodec.{codecs => CO}

import scala.concurrent.duration._

abstract class AvroClient[F[_]](context: AvroClientCreationContext[F])(
  implicit AG: AsynchronousChannelGroup,
  F: ConcurrentEffect[F],
  C: Clock[F],
  CS: ContextShift[F],
  T: Timer[F]
) extends Protocol
    with Closeable[F] {

  type Endpoint[I, O] = I => F[O]

  override def endpoint[I, O](name: String, request: Avro[I], response: Avro[O], documentation: Option[String]): I => F[O] = {

    implicit val requestEncoder: AvroEncoder[I] = request.apply[AvroEncoder]
    implicit val requestSchema: AvroSchema[I] = request.apply[AvroSchema]
    implicit val responseDecoder: AvroDecoder[O] = response.apply[AvroDecoder]
    implicit val responseSchema: AvroSchema[O] = response.apply[AvroSchema]

    def encodeRequest(bytes: Array[Byte], correlationId: Long) =
      Envelope
        .codec(Request.codec)
        .encode(Envelope(correlationId, Request(name, BitVector.view(bytes))))

    def awaitRequest(req: I, ts: Long, promise: Deferred[F, BitVector]) =
      for {
        conn          <- context.connections.random
        correlationId <- conn.inflight.modify(_ + PendingRequest(ts + context.requestTimeout.toMillis, promise))
        envelope      <- F.fromEither(encodeRequest(formulation.encode(req), correlationId).toEither.leftMap(err => new Throwable(s"Unable to encode: $err")))
        _             <- conn.writerQueue.enqueue1(envelope)
        response      <- Concurrent.timeout(promise.get, context.requestTimeout)
        result        <- F.fromEither(formulation.decode(response.toByteArray))
      } yield result

    req => {
      for {
        ts      <- C.realTime(TimeUnit.MILLISECONDS)
        promise <- Deferred[F, BitVector]
        result  <- awaitRequest(req, ts, promise).measure(context.clientMetrics.responseTime)
      } yield result
    }
  }

  override def shutdown: F[Unit] =
    context.connections.traverse(_.killswitch) >> context.logger.info("Shutting down client")
}

final case class AvroClientCreationContext[F[_]](
  connections: NonEmptyList[Connection[F]],
  logger: Logger[F],
  requestTimeout: FiniteDuration,
  clientMetrics: ClientMetrics[F]
)

final case class Connection[F[_]](inflight: Ref[F, MonotonicMap[PendingRequest[F]]], writerQueue: Queue[F, BitVector], killswitch: F[Unit])

object AvroClient {

  def apply[F[_], C <: AvroClient[F]](
    address: InetSocketAddress = new InetSocketAddress("localhost", 10321),
    maxConnections: Int = 25,
    requestTimeout: FiniteDuration = 30.seconds,
    collectorRegistry: CollectorRegistry,
    creator: AvroClientCreationContext[F] => C
  )(implicit AG: AsynchronousChannelGroup, F: ConcurrentEffect[F], CS: ContextShift[F], C: Clock[F], T: Timer[F]): Resource[F, C] = {

    val logger = Slf4jLogger.unsafeCreate[F]

    def startSocket(index: Int, metrics: SocketMetrics[F], inflight: Ref[F, MonotonicMap[PendingRequest[F]]], writerQueue: Queue[F, BitVector], socket: Resource[F, Socket[F]]): F[Fiber[F, Unit]] = {

      def write(socket: Socket[F]) =
        writerQueue.dequeue
          .through(Pipeline.framingEncode(metrics.sentBytes, logger, s"socket$index") andThen socket.writes(timeout = None))

      def read(socket: Socket[F]) =
        socket
          .reads(256 * 1024)
          .through(Pipeline.framingDecode(metrics.receivedBytes, logger, s"socket$index"))
          .through(Pipeline.codecDecode(Envelope.codec(CO.bits), metrics.requestCounter, logger, s"socket$index"))
          .evalMap { env =>
            for {
              currentTime <- C.realTime(TimeUnit.MILLISECONDS)
              _ <- inflight.modify { r =>
                val promise = r.get(env.correlationId).map(_.promise)
                val newMap = r
                  .filterIndexNot(env.correlationId)
                  .filterValue(_.expiresAt >= currentTime)

                (
                  newMap,
                  promise
                    .map(x => CS.shift >> x.complete(env.payload))
                    .getOrElse(F.unit)
                )
              }.flatten
            } yield ()
          }

      F.start(Stream.resource(socket).flatMap(s => read(s).mergeHaltBoth(write(s))).compile.drain)
    }

    def createConnection(metrics: SocketMetrics[F])(idx: Int): F[Connection[F]] =
      for {
        inflight <- Ref.of(MonotonicMap.empty[PendingRequest[F]])
        writerQueue <- Queue.unbounded[F, BitVector]
        socketFiber <- startSocket(idx, metrics, inflight, writerQueue, tcp.client(address))
      } yield Connection[F](inflight, writerQueue, socketFiber.cancel)

    def creation: F[C] =
      for {
        _                   <- logger.info("Starting client")
        clientMetrics       <- ClientMetrics.prom(collectorRegistry)
        clientSocketMetrics <- SocketMetrics.prom(collectorRegistry)
        connections         <- List.tabulate(maxConnections)(identity).traverse(createConnection(clientSocketMetrics))
      } yield
        creator(
          AvroClientCreationContext(
            NonEmptyList.fromListUnsafe(connections),
            logger,
            requestTimeout,
            clientMetrics
          )
        )

    Resource.make[F, C](creation)(_.shutdown)
  }
}
