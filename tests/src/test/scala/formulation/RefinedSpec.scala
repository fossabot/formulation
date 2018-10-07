package formulation

import eu.timepit.refined._
import org.scalatest.{Inside, Matchers, WordSpec}

class RefinedSpec extends WordSpec with Matchers with Inside {
  "Refined" should {
    "be compatible" in {
      val schemaRefined = AvroSchema[PersonRefined].schema
      val schemaUnrefined = AvroSchema[PersonUnrefined].schema

      AvroSchemaCompatibility(schemaRefined, schemaUnrefined) shouldBe AvroSchemaCompatibility.Full
    }

    "succeed decoding with valid bytes" in {
      val entity = PersonRefined(refineMV("Mark"), refineMV(1337))
      val bytes = encode(entity)

      decode[PersonRefined](bytes) shouldBe Right(entity)
    }

    "fail decoding with invalid bytes" in {
      val entity = PersonUnrefined("Mark", -1)
      val bytes = encode(entity)

      inside(decode[PersonRefined](bytes)) {
        case Left(AvroDecodeFailure.Errors(_, AvroDecodeError.Error(p, err) :: Nil)) =>
          p shouldBe JsonPointer().member("age")
          err shouldBe "Predicate failed: (-1 > 0)."
      }
    }
  }
}

