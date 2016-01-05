package ow.routing.chord;

import ow.id.ID;
import ow.id.IDAddressPair;
import ow.messaging.MessageHandler;
import ow.routing.*;
import ow.routing.linearwalker.LinearWalkerRoutingContext;
import ow.routing.linearwalker.message.ReqSuccAndPredMessage;
import ow.util.Timer;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.util.logging.Level;


/**
 * Chord implementation which orders its successor ring according to the reflected binary
 * Gray Code, to assist in similarity searches using a locality-sensitive hash.
 * See "Hamming DHT: An Indexing System for Similarity Search" by
 * Rodolfo da Silva Villaca, et al.
 *
 * http://ce-resd.facom.ufms.br/sbrc/2012/ST4_2.pdf
 */

public class HammingChord extends Chord {

  protected HammingChord(RoutingAlgorithmConfiguration config, RoutingService routingService)
      throws InvalidAlgorithmParameterException {
    super(config, routingService);
  }



  @Override
  public BigInteger distance(ID to, ID from) {
    BigInteger dist = GrayCode.distance(to.toBigInteger(), from.toBigInteger());
    if (dist.compareTo(BigInteger.ZERO) <= 0) {
      return dist.add(this.sizeOfIDSpace);
    }

    return dist;
  }

}
