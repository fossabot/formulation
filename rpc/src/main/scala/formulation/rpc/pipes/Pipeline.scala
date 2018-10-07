package formulation.rpc.pipes
import io.chrisdavenport.log4cats.Logger
import _root_.fs2._
import cats.Show
import cats.effect.{Concurrent, ConcurrentEffect, Effect, Timer}
import formulation.rpc.MeasureCount
import scodec.bits.BitVector
import scodec.{Decoder, Encoder}

object Pipeline {

  def framingEncode[F[_] : ConcurrentEffect : Timer](sentBytes: MeasureCount[F], logger: Logger[F], labels: String*): Pipe[F, BitVector, Byte] =
      Framing.encode andThen
      Logging.trace(logger, "writing frame") andThen
      Metrics.countPipe(sentBytes, _.size, labels:_*) andThen
      Bytes.bitVectorToByte

  def framingDecode[F[_] : Effect](receivedBytes: MeasureCount[F], logger: Logger[F], labels: String*): Pipe[F, Byte, BitVector] =
    Bytes.byteToBitVector andThen
      Logging.trace(logger, "reading frame") andThen
      Metrics.countPipe(receivedBytes, _.size, labels:_*) andThen
      Framing.decode


  def codecDecode[F[_] : Effect, A : Show](decoder: Decoder[A], counter: MeasureCount[F], logger: Logger[F], labels: String*): Pipe[F, BitVector, A] =
    _.through(
      Codec.decode(decoder) andThen
      Metrics.incPipe(counter, labels:_*) andThen
      Logging.trace(logger, "decoded entity")
    ).handleErrorWith(err => Stream.eval(logger.error(err)("Decode error occurred")) >> Stream.empty)

  def codecEncode[F[_] : Effect, A : Show](encoder: Encoder[A], counter: MeasureCount[F], logger: Logger[F], labels: String*): Pipe[F, A, BitVector] =
    _.through(
      Codec.encode(encoder) andThen
      Metrics.incPipe(counter, labels:_*)
    ).handleErrorWith(err => Stream.eval(logger.error(err)("Encode error occurred")) >> Stream.empty)
}
