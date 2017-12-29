package formulation

import java.time.{LocalDate, LocalDateTime}
import java.time.format.DateTimeFormatter
import java.util.UUID

import cats._

import scala.util.control.NonFatal

trait Avro[A] {
  def apply[F[_] : AvroAlgebra]: F[A]
}

object Avro extends AvroDslRecordN {

  val int: Avro[Int] = new Avro[Int] {
    override def apply[F[_] : AvroAlgebra]: F[Int] = implicitly[AvroAlgebra[F]].int
  }

  val string: Avro[String] = new Avro[String] {
    override def apply[F[_] : AvroAlgebra]: F[String] = implicitly[AvroAlgebra[F]].string
  }

  def imap[A, B](fa: Avro[A])(f: A => B)(g: B => A): Avro[B] = new Avro[B] {
    override def apply[F[_] : AvroAlgebra]: F[B] = implicitly[AvroAlgebra[F]].imap(fa.apply[F])(f)(g)
  }

  def pmap[A, B](fa: Avro[A])(f: A => Either[String, B])(g: B => A): Avro[B] = new Avro[B] {
    override def apply[F[_] : AvroAlgebra]: F[B] = implicitly[AvroAlgebra[F]].pmap(fa.apply[F])(f)(g)
  }

  val uuid: Avro[UUID] =
    pmapUnsafe(string)(UUID.fromString)(_.toString)

  val localDate: Avro[LocalDate] =
    pmapUnsafe(string)(LocalDate.parse)(_.format(DateTimeFormatter.ISO_LOCAL_DATE))

  val localDateTime: Avro[LocalDateTime] =
    pmapUnsafe(string)(LocalDateTime.parse)(_.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))

  def pmapUnsafe[A, B](fa: Avro[A])(f: A => B)(g: B => A): Avro[B] =
    pmap(fa)(a => try Right(f(a)) catch { case NonFatal(ex) => Left(ex.getMessage)})(g)

  def option[A](value: Avro[A]): Avro[Option[A]] = new Avro[Option[A]] {
    override def apply[F[_] : AvroAlgebra]: F[Option[A]] = implicitly[AvroAlgebra[F]].option(value.apply[F])
  }

  def list[A](of: Avro[A]): Avro[List[A]] = new Avro[List[A]] {
    override def apply[F[_] : AvroAlgebra]: F[List[A]] = implicitly[AvroAlgebra[F]].list(of.apply[F])
  }

  implicit val invariant: Invariant[Avro] = new Invariant[Avro] {
    override def imap[A, B](fa: Avro[A])(f: A => B)(g: B => A): Avro[B] = Avro.imap(fa)(f)(g)
  }
}