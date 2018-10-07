package formulation.rpc
import formulation.{Avro, AvroSchema}
import org.apache.avro.Schema

case class EndpointDescriptor(name: String, request: Schema, response: Schema, documentation: Option[String])

class AvroProtocolPrinter extends Protocol {
  type Endpoint[I, O] = EndpointDescriptor

  override def endpoint[I, O](name: String, request: Avro[I], response: Avro[O], documentation: Option[String]): Endpoint[I, O] =
    EndpointDescriptor(name, request.apply[AvroSchema].schema, response.apply[AvroSchema].schema, documentation)
}
object AvroProtocolPrinter {
  def create(namespace: String, protocol: String, documentation: String, endpoints: List[EndpointDescriptor]) = {

//    val distinctTypes = endpoints.flatMap(_.request)

    println(endpoints)
  }
}
