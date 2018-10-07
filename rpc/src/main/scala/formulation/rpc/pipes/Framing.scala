package formulation.rpc.pipes
import _root_.fs2._
import cats.effect.Effect
import scodec.bits.BitVector
import scodec.stream.decode.StreamDecoder
import scodec.stream.encode.StreamEncoder
import scodec.stream.{decode => D, encode => E}
import scodec.{Codec, codecs => C}
import shapeless.Lazy

object Framing {

  private val frameCodec: Lazy[Codec[BitVector]] =
    C.variableSizeBytes(C.uint16, C.bits)
  private val decoder: StreamDecoder[BitVector] =
    D.once(frameCodec).many
  private val encoder: StreamEncoder[BitVector] =
    E.once(frameCodec).many

  def decode[F[_] : Effect]: Pipe[F, BitVector, BitVector] =
    _.flatMap(decoder.decode(_)(Effect[F]))

  def encode[F[_] : Effect]: Pipe[F, BitVector, BitVector] =
    _.through(encoder.encode)
}