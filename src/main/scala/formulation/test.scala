package formulation

import java.time.LocalDateTime

sealed abstract class Color(val repr: String)

object Color {
  case object Black extends Color("black")
  case object White extends Color("white")
  case object Orange extends Color("orange")

  val all: Set[Color] = Set(Black, White, Orange)

  implicit val enum: Enum[Color] = Enum(all)(_.repr)
}

trait Enum[A] {
  val allValues: Set[A]
  def asString(value: A): String
}

object Enum {
  def apply[A](values: Set[A])(stringify: A => String): Enum[A] = new Enum[A] {
    override val allValues: Set[A] = values
    override def asString(value: A): String = stringify(value)
  }
}

case class Address(street: String, houseNumber: Int, countries: List[String], more: Set[BigDecimal])
case class Person(name: String, favoriteColor: Color, address: Address, city: Option[String], shit: Vector[Address], date: LocalDateTime)

object Main extends App {

  def enum[A](implicit E: Enum[A]) =
    string.pmap(str => E.allValues.find(x => E.asString(x) == str).fold[Attempt[A]](Attempt.error(s"Value $str not found"))(Attempt.Success.apply))(E.asString)

  implicit val address: Avro[Address] = record4("forma", "Address")(Address.apply)(
    "street" -> Member(string, _.street),
    "houseNumber" -> Member(int, _.houseNumber),
    "countries" -> Member(list(string), _.countries, Some(Nil)),
    "more" -> Member(set(bigDecimal(2, 7)), _.more)
  )
  implicit val person: Avro[Person] = record6("forma", "Person")(Person.apply)(
    "name" -> Member(string, _.name),
    "favoriteColor" -> Member(enum[Color], _.favoriteColor),
    "address" -> Member(address, _.address),
    "city" -> Member(option(string), _.city),
    "shit" -> Member(vector(address), _.shit),
    "date" -> Member(localDateTime, _.date)
  )

  val schema = AvroSchema[Person]

  println(schema.generateSchema.toString(true))

  println(decode[Person](encode(Person("Mark", Color.Orange, Address("Scalastreet", 4, List("Netherlands", "Belgium"), Set(11, 22)), Some("Utrecht"), Vector(Address("test", 11, Nil, Set())), LocalDateTime.now()))))

}