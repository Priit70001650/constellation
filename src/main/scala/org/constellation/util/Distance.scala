package org.constellation.util

import org.constellation.primitives.Schema.Id

object Distance {
  def calculate(hash: String, id: Id): BigInt =
    BigInt(id.toPublicKey.getEncoded) ^ BigInt(hash.getBytes())

  def calculate(id1: Id, id2: Id): BigInt =
    BigInt(id1.toPublicKey.getEncoded) ^ BigInt(id2.toPublicKey.getEncoded)

}
