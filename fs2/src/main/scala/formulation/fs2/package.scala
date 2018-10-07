package formulation

import cats.effect.Sync
import _root_.fs2._
import cats.Id
import org.apache.avro.Schema

package object fs2 {

  def decode[F[_] : Sync, A : Avro](writerSchema: Option[Schema] = None, readerSchema: Option[Schema] = None): Pipe[F, Array[Byte], A] =
    bytes => bytes.scan(AvroDecodeContext[Either[AvroDecodeFailure, A]](Left(AvroDecodeFailure.Skip(AvroDecodeSkipReason.NoReason)), None)) { case (ctx, b) =>
      kleisliDecode[Id, A](writerSchema, readerSchema).run(AvroDecodeContext(b, ctx.binaryDecoder))
    } flatMap {
      case AvroDecodeContext(Right(value), _) => Stream.emit(value)
      case AvroDecodeContext(Left(err), _) => Stream.raiseError[F](err)
    }

}
