import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import cats.{Applicative, Id}
import cats.data.Kleisli
import cats.implicits._
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericDatumReader, GenericDatumWriter, GenericRecord}
import org.apache.avro.io.{DecoderFactory, EncoderFactory}

import scala.util.control.NonFatal
import scala.collection.JavaConverters._

package object formulation extends AvroDsl {

  /**
    * Constructs a member for a record
    *
    * @param avro          One of the combinators of `Avro[A]`, see primitives supported
    * @param getter        A function which gives access to the the field in the case class
    * @param defaultValue  The default value, use this if you want to introduce new fields and maintain compatibility
    * @param documentation Documentation for this member, it doesn't break compatibility (https://avro.apache.org/docs/1.8.1/spec.html#Parsing+Canonical+Form+for+Schemas)
    * @param aliases       Aliases for this member, it doesn't break compatibility (https://avro.apache.org/docs/1.8.1/spec.html#Parsing+Canonical+Form+for+Schemas)
    * @tparam A The member type of the record
    * @tparam B The type of the record it self
    * @return A fully constructed `Member[Avro, A, B]`
    */
  def member[A, B](avro: Avro[A], getter: B => A, defaultValue: Option[A] = None, documentation: Option[String] = None, aliases: Seq[String] = Seq.empty): Member[Avro, A, B] =
    Member[Avro, A, B](avro, getter, aliases, defaultValue.map(avro.apply[AvroDefaultValuePrinter].print), documentation)

  /**
    * Encodes a value of type `A` which has a `AvroEncoder` and `AvroSchema` (gained by having a implicit `Avro[A]` in scope).
    *
    * @param R The AvroEncoder for this type
    * @param S The AvroSchema for this type
    * @tparam A The type of the value to encode
    * @return A `AvroEncodeResult` which also contains the used `Schema`, useful when want to store it for example.
    */
  def kleisliEncode[F[_], A](dropUnionSchema: Boolean = false)(implicit F: Applicative[F], R: AvroEncoder[A], S: AvroSchema[A]): Kleisli[F, AvroEncodeContext[A], AvroEncodeContext[AvroEncodeResult]] =
    Kleisli { ctx =>
      val os = new ByteArrayOutputStream()

      try {
        val schema = S.schema
        val (usedSchema, record: GenericRecord) = R.encode(schema, ctx.entity)
        val dataWriter = new GenericDatumWriter[GenericRecord](if(dropUnionSchema) usedSchema else schema)
        val encoder = EncoderFactory.get().directBinaryEncoder(os, ctx.binaryEncoder.orNull)

        dataWriter.write(record, encoder)

        encoder.flush()

        F.pure(AvroEncodeContext(AvroEncodeResult(usedSchema, os.toByteArray), Some(encoder)))
      }
      finally {
        os.close()
      }
    }

  /**
    * Encodes a value of type `A` which has a `AvroEncoder` and `AvroSchema` (gained by having a implicit `Avro[A]` in scope).
    *
    * @param value The value to encode
    * @param R     The AvroEncoder for this type
    * @param S     The AvroSchema for this type
    * @tparam A The type of the value to encode
    * @return A array of bytes
    */
  def encode[A](value: A, dropUnionSchema: Boolean = false)(implicit R: AvroEncoder[A], S: AvroSchema[A]): Array[Byte] =
    kleisliEncode[Id, A](dropUnionSchema).run(AvroEncodeContext(value, None)).entity.payload


  /**
    * Decodes a array of bytes in to a entity
    *
    * @param writerSchema The Schema which was used to write this payload
    * @param readerSchema The Schema which will be used to read this payload
    * @param R            The AvroDecoder for this type
    * @param S            The AvroSchema for this type
    * @tparam A The type of the value to decode
    * @return Either a error or the decode value.
    */
  def kleisliDecode[F[_], A](writerSchema: Option[Schema] = None, readerSchema: Option[Schema] = None)
                            (implicit F: Applicative[F], R: AvroDecoder[A], S: AvroSchema[A]): Kleisli[F, AvroDecodeContext[Array[Byte]], AvroDecodeContext[Either[AvroDecodeFailure, A]]] =
    Kleisli[F, AvroDecodeContext[Array[Byte]], AvroDecodeContext[Either[AvroDecodeFailure, A]]] { ctx =>
      val in = new ByteArrayInputStream(ctx.entity)
      val binDecoder = DecoderFactory.get().directBinaryDecoder(in, ctx.binaryDecoder.orNull)

      def doDecode(wSchema: Schema, rSchema: Schema): F[AvroDecodeContext[Either[AvroDecodeFailure, A]]] = {
        val datumReader = new GenericDatumReader[GenericRecord](wSchema, rSchema)
        val record = datumReader.read(null, binDecoder)

        F.pure {
          R.decode(JsonPointer(), rSchema, record).toEither match {
            case Left(errs) => AvroDecodeContext(Left(AvroDecodeFailure.Errors(record, errs)), Some(binDecoder))
            case Right(value) => AvroDecodeContext(Right(value), Some(binDecoder))
          }
        }
      }

      try {
        def schema = S.schema

        val wSchema = writerSchema.getOrElse(schema)
        val rSchema = readerSchema.getOrElse(schema)

        (wSchema.getType, rSchema.getType) match {
          case (Schema.Type.RECORD, Schema.Type.UNION) =>
            if(rSchema.getTypes.asScala.exists(_.getFullName == wSchema.getFullName)) doDecode(wSchema, rSchema)
            else F.pure(AvroDecodeContext(Left(AvroDecodeFailure.Skip(AvroDecodeSkipReason.NotMemberOfUnion(wSchema, rSchema))), Some(binDecoder)))

          case _ =>
            doDecode(wSchema, rSchema)
        }


      }
      catch {
        case NonFatal(ex) =>
          F.pure(AvroDecodeContext(Left(AvroDecodeFailure.Exception(ex)), ctx.binaryDecoder))
      }
      finally {
        in.close()
      }
    }

  /**
    * Decodes a array of bytes in to a entity
    *
    * @param bytes        The Avro payload
    * @param writerSchema The Schema which was used to write this payload
    * @param readerSchema The Schema which will be used to read this payload
    * @param R            The AvroDecoder for this type
    * @param S            The AvroSchema for this type
    * @tparam A The type of the value to decode
    * @return Either a error or the decode value.
    */
  def decode[A](bytes: Array[Byte], writerSchema: Option[Schema] = None, readerSchema: Option[Schema] = None)
               (implicit R: AvroDecoder[A], S: AvroSchema[A]): Either[AvroDecodeFailure, A] =
    kleisliDecode[Id, A](writerSchema, readerSchema).run(AvroDecodeContext(bytes, None)).map(_.entity)

  /**
    * Returns the Avro Schema for a type
    *
    * @param A A implicit Avro available
    * @tparam A The type of the value to get the Schema from
    * @return An Avro Schema
    */
  def schema[A](implicit A: Avro[A]): Schema = A.apply[AvroSchema].schema
}
