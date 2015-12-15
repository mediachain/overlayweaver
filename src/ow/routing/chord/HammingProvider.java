package ow.routing.chord;

import ow.routing.RoutingAlgorithm;
import ow.routing.RoutingAlgorithmConfiguration;
import ow.routing.RoutingAlgorithmProvider;
import ow.routing.RoutingService;

import java.security.InvalidAlgorithmParameterException;

/**
 * Created by yusef on 12/15/15.
 */
public class HammingProvider implements RoutingAlgorithmProvider {
  private final static String ALGORITHM_NAME = "Hamming";

  public String getName() { return ALGORITHM_NAME; }

  public RoutingAlgorithmConfiguration getDefaultConfiguration() {
    return new ChordConfiguration();
  }

  @Override
  public RoutingAlgorithm initializeAlgorithmInstance(RoutingAlgorithmConfiguration conf, RoutingService routingSvc) throws InvalidAlgorithmParameterException {
    return new Hamming(conf, routingSvc);
  }
}
