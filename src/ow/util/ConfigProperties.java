package ow.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;

/**
 * Created by yusef on 12/16/15.
 */
public class ConfigProperties {
  private static final String FILENAME = "config.properties";
  private static HashMap<String, String> props = null;

  private static void init()
      throws IOException {
    if (props != null) {
      return;
    }

    props = new HashMap<>();
    Properties p = new Properties();
    InputStream is = ConfigProperties.class.getClassLoader().getResourceAsStream(FILENAME);

    if (is == null) {
      throw new FileNotFoundException("Can't load " + FILENAME + " from resources.");
    }
    p.load(is);
    Set<String> keys = p.stringPropertyNames();
    for (String k : keys) {
      String val = p.getProperty(k);
      if (val != null) {
        props.put(k, val);
      }
    }
    is.close();
  }

  public static String getString(String key)
    throws IOException
  {
    init();
    return props.get(key);
  }

  public static long getLong(String key)
      throws IOException, NumberFormatException {
    return Long.parseLong(getString(key));
  }

  public static int getInt(String key)
    throws IOException, NumberFormatException {
    return Integer.parseInt(getString(key));
  }

  public static int getInt(String key, int defaultVal) {
    try {
      return getInt(key);
    } catch (Exception e) {
      System.err.println("Error getting int value for property with key "
          + key + " returning default value " + defaultVal);
      return defaultVal;
    }
  }
}
