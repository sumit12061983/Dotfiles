/*bin/mkdir -p /tmp/.java/classes 2> /dev/null

# Compile the program.
#
javac -d /tmp/.java/classes $0

# Run the compiled program only if compilation succeeds.
#
[[ $? -eq 0 ]] && java -cp /tmp/.java/classes $(basename ${0%.*}) "$@"
exit
*/
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;

/**
 * Dumps a secret key as a raw string from a Java JCEKS keystore.
 */
class DumpSecretKey {

  /**
   * Main function.
   * 
   * <p>
   * Command-line arguments match those of the keytool to communicate intent.
   */
  public static void main(String... args) {

    if (args.length == 0) {
      showUsageAndExit(2);
    }
    
    int argIdx = 0;
    String alias = null;
    String filename = null;
    char[] keypass = null;
    String keystorename = null;
    boolean raw = false;
    char[] storepass = null;
    String storetype = "jceks";

    while (argIdx < args.length) {
      String arg = args[argIdx];
      switch (arg) {
        case "-alias":
          alias = args[++argIdx];
          break;
        case "-file":
          filename = args[++argIdx];
          break;
        case "-help":
          showUsageAndExit(2);
          break;
        case "-keypass":
          keypass = args[++argIdx].toCharArray();
          break;
        case "-keystore":
          keystorename = args[++argIdx];
          break;
        case "-raw":
          raw = true;
          break;
        case "-storepass":
          storepass = args[++argIdx].toCharArray();
          break;
        case "-storetype":
          storetype = args[++argIdx].toUpperCase();
          break;
        default:
          System.err.printf("Unknown option: %s%n");
          showUsageAndExit(1);
          break;
      }
      argIdx++;
    }

    if (isNullOrEmpty(alias)) {
      System.err.println("Must specify a key alias");
      showUsageAndExit(1);
    }

    if (isNullOrEmpty(keystorename)) {
      System.err.println("Must specify a keystore");
      showUsageAndExit(1);
    }

    if (isNullOrEmpty(storepass)) {
      System.err.println("Must specify a store password");
      showUsageAndExit(1);
    }

    if (isNullOrEmpty(keypass)) {
      keypass = storepass;
    }

    FileOutputStream outstream = null;
    try (FileInputStream keystorein = new FileInputStream(new File(keystorename))) {
      PrintStream out = System.out;
      if (!isNullOrEmpty(filename)) {
        outstream = new FileOutputStream(filename);
        out = new PrintStream(outstream);
      }
      KeyStore keystore = KeyStore.getInstance(storetype);
      keystore.load(keystorein, storepass);
      KeyStore.ProtectionParameter protection = new KeyStore.PasswordProtection(keypass);
      KeyStore.SecretKeyEntry secretkey =
          (KeyStore.SecretKeyEntry) keystore.getEntry(alias, protection);

      byte[] encoded = new byte[0];
      if (secretkey != null) {
        encoded = secretkey.getSecretKey().getEncoded();
      } else {
        System.err.printf("Alias %s not found in %s!", alias, keystorename);
        System.exit(1);
      }

      String key = raw ? new String(encoded) : 
        new String(Base64.getEncoder().encode(encoded));
      out.print(key);
    } catch (IOException | GeneralSecurityException e) {
      System.err.printf("Failure! %s%n", e.getMessage());
      System.exit(1);
    } finally {
      if (outstream != null) {
        try {
          outstream.close();
        } catch (IOException e) {
          System.err.println("Failed to close output file!");
        }
      }
    }
  }

  private static void showUsageAndExit(int code) {
    showUsage();
    System.exit(code);
  }

  private static void showUsage() {
    System.err.printf("Usage: %s [OPTION]%n", DumpSecretKey.class.getName());
    System.err.println();
    System.err.println("Dumps base64 encoded (or raw if specified) secret key "
    + "from a keystore; useful");
    System.err.println("for dumping passwords saved in a keystore added by the keytool command");
    System.err.println("-importpass");
    System.err.println();
    System.err.println("Options:");
    System.err.println();
    System.err.println(" -alias <alias>        alias name of the entry to process");
    System.err.println(" -file <filename>      output file name (default is write to stdout)");
    System.err.println(" -help                 show this message and exit");
    System.err.println(" -keypass <arg>        key password");
    System.err.println(" -keystore <keystore>  keystore name");
    System.err.println(" -raw                  dump the raw value (useful for passwords)");
    System.err.println(" -storepass <arg>      keystore password");
    System.err.println(" -storetype <arg>      keystore type");
  }

  private static boolean isNullOrEmpty(String value) {
    return (value == null || (value != null && value.isEmpty()));
  }

  private static boolean isNullOrEmpty(char[] value) {
    return (value == null || (value !=null && value.length == 0));
  }
}
