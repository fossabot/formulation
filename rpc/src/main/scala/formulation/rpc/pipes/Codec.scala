package formulation.rpc.pipes
import cats.effect.Sync
import formulation.rpc.CodecException
import fs2.{Pipe, Stream}
import scodec._
import scodec.bits.BitVector

object Codec {
  def encode[F[_] : Sync, A](codec: Encoder[A]): Pipe[F, A, BitVector] =
    _.flatMap(entry => codec.encode(entry).fold(err => Stream.raiseError(CodecException(err)), Stream.emit[F, BitVector](_)))

  def decode[F[_] : Sync, A](codec: Decoder[A]): Pipe[F, BitVector, A] =
    _.flatMap(entry => codec.decodeValue(entry).fold(err => Stream.raiseError(CodecException(err)), Stream.emit[F, A](_)))
}
