package ow.id.lsh;


import ow.id.ID;
import ow.util.ConfigProperties;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;


/**
 * Created by yusef on 12/16/15.
 */
public class RandomHyperplaneIDGenerator {
  public static long randomSeed;
  public static int idBitLength;
  public static int contentVectorBitLength;
  public static int contentVectorDimensions;

  private static BigInteger[][] randomProjections;

  private static void init() {
    try {
      randomSeed = ConfigProperties.getLong("ow.lsh.random-seed");
      idBitLength = ConfigProperties.getInt("ow.lsh.rhh-bit-length", 160);
      contentVectorBitLength = ConfigProperties.getInt("ow.lsh.content-vector-bit-length", 160);
      contentVectorDimensions = ConfigProperties.getInt("ow.lsh.content-vector-dimensions", 1);
      randomProjections = new BigInteger[idBitLength][contentVectorDimensions];
    } catch (Exception e) {
      throw new IllegalStateException("Can't read LSH seed info from config properties", e);
    }

    System.out.println("Config values: " + "seed: " + randomSeed + " id-len: " + idBitLength +
     " content-len: " + contentVectorBitLength + " content dim: " + contentVectorDimensions);

    Random rand = new Random();
    rand.setSeed(randomSeed);

    // Generate `m` random vectors of `n` dimensions,
    // Where `m` == `idBitLength` and `n` == contentVectorDimensions
    // Each dimension has a range `r` of 2^`contentVectorBitLength`
    // Random values are obtained by scaling `Random.nextGaussian` by `r`

    for (int i = 0; i < idBitLength; i++) {
      for (int j = 0; j < contentVectorDimensions; j++) {
        BigDecimal gaussian = BigDecimal.valueOf(rand.nextGaussian());
        BigDecimal scalar = BigDecimal.valueOf(contentVectorBitLength).pow(2);
        BigDecimal projection = gaussian.multiply(scalar);
        randomProjections[i][j] = projection.toBigInteger();
      }
    }
  }

  static {
    init();
  }

  private static BigInteger dotProduct(BigInteger[] v1, BigInteger[] v2) {
    if (v1.length != v2.length) {
      throw new IllegalArgumentException("Can't compute dot product of vectors with different dimensions");
    }

    BigInteger sum = BigInteger.ZERO;
    for (int i = 0; i < v1.length; i++) {
      sum = sum.add(v1[i].multiply(v2[i]));
    }
    return sum;
  }

  public static ID generateIDByHashingVector(BigInteger[] inputVector) {
    BigInteger result = BigInteger.ZERO;
    for (int i = 0; i < idBitLength; i++) {
      BigInteger dot = dotProduct(inputVector, randomProjections[i]);
      if (dot.compareTo(BigInteger.ZERO) >= 0) {
        result = result.flipBit(i);
      }
    }
    return ID.getID(result, idBitLength / 8);
  }

  public static ID generateIDByHashingHash(BigInteger inputHash) {
    if (contentVectorDimensions != 1) {
      throw new IllegalStateException("generateIDByHashingHash() should only be used when " +
          "content vectors are of a single dimension");
    }

    BigInteger[] v = new BigInteger[1];
    v[0] = inputHash;
    return generateIDByHashingVector(v);
  }

  public static ID generateIDByHashingByteArray(byte[] bytes) {
    if (contentVectorDimensions != 1) {
      throw new IllegalStateException("generateIDByHashingByteArray() should only be used when " +
        "content vectors are of a single dimension");
    }
    BigInteger v[] = new BigInteger[1];
    v[0] = new BigInteger(bytes);
    return generateIDByHashingVector(v);
  }
}
