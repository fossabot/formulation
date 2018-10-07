package formulation.rpc
import cats.effect.Sync
import cats.effect.concurrent.Deferred
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import scodec.bits.BitVector
import scodec.{Codec, Err, codecs => C}

trait Closeable[F[_]] {
  def shutdown: F[Unit]
}

final case class MonotonicMap[A] private (private val map: Map[Long, A],
                                          private val idx: Long) {
  def +(item: A): (MonotonicMap[A], Long) =
    (copy(map + (idx -> item), idx + 1), idx)
  def get(index: Long): Option[A] = map.get(index)
  def filterIndexNot(index: Long): MonotonicMap[A] =
    copy(map - index, idx)
  def filterValue(f: A => Boolean): MonotonicMap[A] =
    copy(map.filter { case (_, v) => f(v) }, idx)
  def size: Int = map.size
}
object MonotonicMap {
  def empty[A]: MonotonicMap[A] = MonotonicMap[A](Map.empty, 0)
}

final case class PendingRequest[F[_]] private (
  expiresAt: Long,
  promise: Deferred[F, BitVector]
)


final case class CodecException(err: Err) extends Throwable(s"Unable to encode/decode due $err")

final case class Envelope[A](correlationId: Long, payload: A)

object Envelope {
  def codec[A](payloadCodec: Codec[A]): Codec[Envelope[A]] =
    (C.uint32 :: C.variableSizeBytes(C.uint16, payloadCodec)).as[Envelope[A]]
}

final case class Request(endpointName: String, payload: BitVector)

object Request {
  val codec: Codec[Request] =
    (C.variableSizeBytes(C.uint8, C.utf8) :: C.bits).as[Request]
}


sealed trait MeasureGauge[F[_]] {
  def inc(): F[Unit]
  def dec(): F[Unit]
}
object MeasureGauge {
  def prom[F[_]](gauge: Gauge)(implicit F: Sync[F]): MeasureGauge[F] = new MeasureGauge[F] {
    override def inc(): F[Unit] = F.delay(gauge.inc())
    override def dec(): F[Unit] = F.delay(gauge.dec())
  }
}

sealed trait MeasureHisto[F[_]] {
  def observe(amt: Double): F[Unit]
}
object MeasureHisto {
  def prom[F[_]](histogram: Histogram)(implicit F: Sync[F]) = new MeasureHisto[F] {
    override def observe(amt: Double): F[Unit] = F.delay(histogram.observe(amt))
  }
}

sealed trait MeasureCount[F[_]] {
  def inc(amt: Double, labels: Seq[String]): F[Unit]
}
object MeasureCount {
  def prom[F[_]](counter: Counter)(implicit F: Sync[F]) = new MeasureCount[F] {
    override def inc(amt: Double, labels: Seq[String]): F[Unit] = F.delay(counter.labels(labels:_*).inc(amt))
  }
}

case class ServerMetrics[F[_]](
  receivedBytes: MeasureCount[F],
  sentBytes: MeasureCount[F],
  requestCounter: MeasureCount[F],
  responseCounter: MeasureCount[F],
  abnormalTerminations: MeasureCount[F],
  activeRequests: MeasureGauge[F],
  activeClients: MeasureGauge[F],
  responseTime: MeasureHisto[F]
)


object ServerMetrics {
  def prom[F[_]](c: CollectorRegistry)(implicit F:Sync[F]): F[ServerMetrics[F]] =
    F.delay {
      ServerMetrics(
        receivedBytes = MeasureCount.prom(createCounter("server_received_bytes", "Server received bytes.", c)),
        sentBytes = MeasureCount.prom(createCounter("server_sent_bytes", "Total sent bytes.", c)),
        requestCounter = MeasureCount.prom(createCounter("server_requests", "Total requests.", c)),
        responseCounter = MeasureCount.prom(createCounter("server_responses",  "Total Responses.", c)),
        abnormalTerminations = MeasureCount.prom(createCounter("server_abnormal_terminations", "Total abnormal terminations.", c)),
        activeRequests = MeasureGauge.prom(createGauge("server_active_requests","Total Active Requests.", c)),
        activeClients = MeasureGauge.prom(createGauge("server_active_clients", "Total Active Client.", c)),
        responseTime = MeasureHisto.prom(createHisto("server_response_milliseconds", "Response duration in milliseconds.", c))
      )
    }

  private def createGauge(name: String, desc: String, c: CollectorRegistry) =
    Gauge.build().name(name).help(desc).register(c)

  private def createCounter(name: String, desc: String, c: CollectorRegistry) =
    Counter.build().name(name).help(desc).register(c)

  private def createHisto(name: String, desc: String, c: CollectorRegistry) =
    Histogram.build().name(name).help(desc).register(c)
}

case class ClientMetrics[F[_]](
  activeRequests: MeasureGauge[F],
  responseTime: MeasureHisto[F]
)
object ClientMetrics {
  def prom[F[_]](collectorRegistry: CollectorRegistry)(implicit F: Sync[F]): F[ClientMetrics[F]] = F.delay {
    ClientMetrics(
      activeRequests = MeasureGauge.prom(Gauge
        .build()
        .name("client_active_requests")
        .help("Total Active Requests.")
        .register(collectorRegistry)),
      responseTime = MeasureHisto.prom(Histogram
        .build()
        .name("client_response_milliseconds")
        .help("Response duration in milliseconds.")
        .register(collectorRegistry))
    )
  }
}

case class SocketMetrics[F[_]](
  receivedBytes: MeasureCount[F],
  sentBytes: MeasureCount[F],
  requestCounter: MeasureCount[F]
)
object SocketMetrics {
  def prom[F[_]](collectorRegistry: CollectorRegistry)(implicit F: Sync[F]): F[SocketMetrics[F]] = F.delay {
    SocketMetrics(
      receivedBytes = MeasureCount.prom(Counter
        .build()
        .name(s"client_received_bytes")
        .help("Total received bytes")
        .labelNames("socketNumber")
        .register(collectorRegistry)),
      sentBytes = MeasureCount.prom(Counter
        .build()
        .name(s"client_sent_bytes")
        .help("Total sent bytes.")
        .labelNames("socketNumber")
        .register(collectorRegistry)),
      requestCounter = MeasureCount.prom(Counter
        .build()
        .name(s"client_requests")
        .help("Total requests.")
        .labelNames("socketNumber")
        .register(collectorRegistry))
    )
  }
}