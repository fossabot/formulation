package formulation

import akka.NotUsed
import akka.stream.scaladsl.Flow
import cats.data.Kleisli
import cats.implicits._
import org.apache.avro.Schema

import scala.concurrent.{ExecutionContext, Future}

package object akkastreams {

  /**
    * A `Flow` which translate a `I` which has a `AvroSchema` and `AvroEncoder` to a `Array[Byte]`.
    *
    * Uses the natural `formulation.kleisliEncode[Future, I]`
    *
    * @param ec A ExecutionContext
    * @tparam I The input type
    * @return A Flow which translate `I` to `Array[Byte]`
    */
  def encoder[I : AvroSchema : AvroEncoder](dropUnionSchema: Boolean = false)(implicit ec: ExecutionContext): Flow[I, Array[Byte], NotUsed] =
    kleisliEncode(formulation.kleisliEncode[Future, I](dropUnionSchema))

  /**
    * A `Flow` which translate a `Array[Byte]` which has a `AvroSchema` and `AvroDecoder` to a `O`
    *
    * Uses the natural `formulation.kleisliDecode[Future, I]` which breaks with top level unions.
    *
    * @param ec A ExecutionContext
    * @tparam O The output type
    * @return A Flow which translate `Array[Byte]` to `O`
    */
  def decoder[O : AvroSchema : AvroDecoder](implicit ec: ExecutionContext): Flow[Array[Byte], O, NotUsed] =
    kleisliDecode(formulation.kleisliDecode[Future, O]())

  /**
    * Takes a `Kleisli` which either comes from:
    *
    * - formulation
    * - formulation.schemaregistry.SchemaRegistry
    *
    * When it comes from the SchemaRegistry it will put it in a confluent envelope. This enables to decode a top level
    * union later, because we can discriminate by using the schema id.
    *
    * @param f The Kleisli
    * @tparam I The input type
    * @return A Flow which translate `I` to `Array[Byte]`
    */
  def kleisliEncode[I](f: Kleisli[Future, AvroEncodeContext[I], AvroEncodeContext[AvroEncodeResult]]): Flow[I, Array[Byte], NotUsed] =
    Flow[I].scanAsync(AvroEncodeContext(AvroEncodeResult(Schema.create(Schema.Type.NULL), Array.empty), None)) { case (ctx, entity) =>
      f.run(AvroEncodeContext(entity, ctx.binaryEncoder))
    } filter(_.entity.payload.nonEmpty) map(_.entity.payload)

  /**
    * Takes a `Kleisli` which either comes from:
    * - formulation
    * - formulation.schemaregistry.SchemaRegistry
    *
    * When it comes from the SchemaRegistry it will get it from a confluent envelope. This enables to decode a top level
    * unions, because we can discriminate by using the schema id.
    *
    * @param f The Kleisli
    * @tparam O The output type
    * @return A Flow which translate `Array[Byte]` to `O`
    */
  def kleisliDecode[O](f: Kleisli[Future, AvroDecodeContext[Array[Byte]], AvroDecodeContext[Either[AvroDecodeFailure, O]]]): Flow[Array[Byte], O, NotUsed] =
    Flow[Array[Byte]].scanAsync(AvroDecodeContext[Either[AvroDecodeFailure, O]](Left(AvroDecodeFailure.Skip(AvroDecodeSkipReason.NoReason)), None)) { case (ctx, bytes) =>
      f.run(AvroDecodeContext(bytes, ctx.binaryDecoder))
    }.map(_.entity).collect {
      case Left(AvroDecodeFailure.Exception(ex)) => throw ex
      case Left(AvroDecodeFailure.Errors(record, errors)) => throw AvroDecodeException(record, errors)
      case Right(value) => value
    }
}
