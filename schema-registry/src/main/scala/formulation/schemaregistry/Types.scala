package formulation.schemaregistry

import java.nio.ByteBuffer

import cats._
import cats.data.Kleisli
import cats.implicits._
import formulation.{Avro, AvroDecodeContext, AvroDecodeFailure, AvroDecoder, AvroEncodeContext, AvroEncodeResult, AvroEncoder, AvroSchema, AvroSchemaCompatibility}
import org.apache.avro.Schema

import scala.collection.JavaConverters._

final class SchemaRegistry[F[_]] private(client: SchemaRegistryClient[F])(implicit M: MonadError[F, Throwable]) {

  def kleisliEncode[A : AvroSchema : AvroEncoder]: Kleisli[F, AvroEncodeContext[A], AvroEncodeContext[AvroEncodeResult]] = {
    def format(identifier: Int, payload: Array[Byte]): Array[Byte] = {
      val byteBuffer = ByteBuffer.allocate(5)
      byteBuffer.put(0.toByte)
      byteBuffer.putInt(identifier)
      byteBuffer.array() ++ payload
    }

    def confluentEnvelope = Kleisli[F, AvroEncodeContext[AvroEncodeResult], AvroEncodeContext[AvroEncodeResult]] { ctx =>
      for {
        identifier <- client.getIdBySchema(ctx.entity.usedSchema)
        payload <- identifier match {
          case Some(id) => M.pure(format(id, ctx.entity.payload))
          case None => M.raiseError[Array[Byte]](new Throwable(s"There was no schema registered for ${ctx.entity.usedSchema.getFullName}"))
        }
      } yield AvroEncodeContext(AvroEncodeResult(ctx.entity.usedSchema, payload), ctx.binaryEncoder)
    }

    formulation.kleisliEncode[F, A](true) andThen confluentEnvelope
  }

  /**
    * Encodes the a entity according the confluent format: https://docs.confluent.io/current/schema-registry/docs/serializer-formatter.html (see wire format)
    *
    * @param value The entity which has a implicit Avro[A] definition available in scope
    * @tparam A The entity we want to decode to
    * @return The payload as a array of bytes
    */
  def encode[A: AvroSchema : AvroEncoder](value: A): F[Array[Byte]] = M.map(kleisliEncode.run(AvroEncodeContext(value, None)))(_.entity.payload)


  def kleisliDecode[A: AvroSchema : AvroDecoder]: Kleisli[F, AvroDecodeContext[Array[Byte]], AvroDecodeContext[Either[AvroDecodeFailure, A]]] = {
    def confluentEnvelope = Kleisli[F, AvroDecodeContext[Array[Byte]], AvroDecodeContext[(Schema, Array[Byte])]] { ctx =>
      val bb = ByteBuffer.wrap(ctx.entity)
      def prg: F[AvroDecodeContext[(Schema, Array[Byte])]] = for {
        _ <- if (bb.get(0) == 0.toByte) M.pure(()) else M.raiseError(new Throwable("First byte was not the magic byte (0x0)"))
        identifier = bb.getInt(1)
        optSchema <- client.getSchemaById(identifier)
        schema <- optSchema match {
          case Some(s) => M.pure(s)
          case None => M.raiseError[Schema](new Throwable(s"There was no schema in the registry for identifier $identifier"))
        }
      } yield AvroDecodeContext(schema -> bb.getByteArray(5), ctx.binaryDecoder)

      prg
    }

    confluentEnvelope.flatMapF[AvroDecodeContext[Either[AvroDecodeFailure, A]]] { case AvroDecodeContext((schema, bytes), decoder) =>
      formulation.kleisliDecode[F, A](writerSchema = Some(schema)).run(AvroDecodeContext(bytes, decoder))
    }
  }

  /**
    * Decodes a payload according to the confluent format: https://docs.confluent.io/current/schema-registry/docs/serializer-formatter.html (see wire format)
    *
    * @param bytes The payload
    * @tparam A The entity we want to decode to
    * @return Attempt[A], which might be a error or a success case
    */
  def decode[A: AvroSchema : AvroDecoder](bytes: Array[Byte]): F[Either[AvroDecodeFailure, A]] =
    M.map(kleisliDecode[A].run(AvroDecodeContext(bytes, None)))(_.entity)

  /**
    * Verifies if the schema's are compatible with the current schema's already being registerd in the registry. In case of a union (ADT), we register multiple schema's
    *
    * Each schema's fullName (namespace.name) is used as subject
    *
    * @param avro The avro definition to register
    * @tparam A The type of case class you want to register
    * @return A list of SchemaRegistryCompatibilityResult
    */
  def verifyCompatibility[A](avro: Avro[A], desired: AvroSchemaCompatibility = AvroSchemaCompatibility.Full): F[List[SchemaRegistryCompatibilityResult]] = {
    val schema = avro.apply[AvroSchema].schema

    def run(s: Schema): F[SchemaRegistryCompatibilityResult] = for {
      _ <- client.setCompatibilityLevel(s.getFullName, desired)
      result <- client.checkCompatibility(s).map(SchemaRegistryCompatibilityResult(s, _))
    } yield result

    schema.getType match {
      case Schema.Type.RECORD => run(schema).map(_ :: Nil)
      case Schema.Type.UNION =>
        schema
          .getTypes
          .asScala
          .toList
          .filterNot(_.getType == Schema.Type.NULL)
          .traverse[F, SchemaRegistryCompatibilityResult](run)
      case _ =>
        M.raiseError(new Throwable(s"We cannot verify compatibility of the type: ${schema.getType} as it has no fullname"))
    }
  }

  /**
    * Register the schema's for the specified Avro[A] type. In case it's a union (ADT), we register multiple schema's.
    *
    * Each schema's fullName (namespace.name) is used as subject
    *
    * @param avro The avro definition to register
    * @tparam A The type of case class you want to register
    * @return A list of SchemaRegistryRegisterResult
    */
  def registerSchemas[A](avro: Avro[A]): F[List[SchemaRegistryRegisterResult]] = {
    val schema = avro.apply[AvroSchema].schema

    schema.getType match {
      case Schema.Type.RECORD =>
        client.registerSchema(schema).map(compat => List(SchemaRegistryRegisterResult(schema, compat)))
      case Schema.Type.UNION =>
        schema
          .getTypes
          .asScala
          .toList
          .filterNot(_.getType == Schema.Type.NULL)
          .traverse[F, SchemaRegistryRegisterResult](s => client.registerSchema(s).map(id => SchemaRegistryRegisterResult(s, id)))
      case _ =>
        M.raiseError(new Throwable(s"We cannot register the type: ${schema.getType} as it has no fullname"))
    }
  }

  private implicit class RichByteBuffer(bb: ByteBuffer) {
    def getByteArray(offset: Int): Array[Byte] = {
      bb.array().slice(offset, bb.array().length)
    }
  }

}

object SchemaRegistry {
  def apply[F[_]](client: SchemaRegistryClient[F])(implicit F: MonadError[F, Throwable]): SchemaRegistry[F] =
    new SchemaRegistry[F](client)
}

final case class SchemaRegistryCompatibilityResult(schema: Schema, compatible: Boolean)

final case class SchemaRegistryRegisterResult(schema: Schema, identifier: Int)

trait SchemaRegistryClient[F[_]] {
  def getSchemaById(id: Int): F[Option[Schema]]

  def getIdBySchema(schema: Schema): F[Option[Int]]

  def registerSchema(schema: Schema): F[Int]

  def checkCompatibility(schema: Schema): F[Boolean]

  def getCompatibilityLevel(subject: String): F[Option[AvroSchemaCompatibility]]

  def setCompatibilityLevel(subject: String, desired: AvroSchemaCompatibility): F[AvroSchemaCompatibility]
}