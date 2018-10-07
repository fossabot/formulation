package formulation.rpc

import formulation.Avro

trait Protocol {
  type Endpoint[I, O]

  def endpoint[I, O](name: String, request: Avro[I], response: Avro[O], documentation: Option[String] = None): Endpoint[I, O]
}