package formulation.rpc.pipes
import cats.effect.{Concurrent, Sync, Timer}
import fs2.{Chunk, Pipe, Stream}
import scodec.bits.BitVector

object Bytes {
  def byteToBitVector[F[_]: Sync]: Pipe[F, Byte, BitVector] =
    _.chunks.map(chunks => BitVector.view(chunks.toArray))

  def bitVectorToByte[F[_]: Concurrent: Timer]: Pipe[F, BitVector, Byte] =
    chunks => chunks.flatMap(x => Stream.chunk(Chunk.array(x.toByteArray)))
}
