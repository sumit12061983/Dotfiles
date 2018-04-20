
/*bin/mkdir -p /tmp/.java/classes 2> /dev/null

# Compile the program.
#
javac -d /tmp/.java/classes $0

# Run the compiled program only if compilation succeeds.
#
[[ $? -eq 0 ]] && java \
  -cp /tmp/.java/classes $(basename ${0%.*}) "$@"
exit
*/
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * HTTP Server that serves Javadoc found on local host.
 * 
 * <p>
 * This server requires at least Java 1.8 to run.
 * 
 * <p>
 * The server assumes that a properly installed JDK is available locally and Maven. 
 * It looks up the Javadoc zip file from the following locations:
 * 
 * <ul>
 * <li>{@code $JAVA_HOME/jdk-docs-all.zip}
 * <li>{@code $HOME/.m2/repository}
 * </ul>
 */
class JavadocServer {

  private static final int DEFAULT_HTTP_PORT = 8084;

  private static final Logger LOGGER = Logger.getLogger(JavadocServer.class.getName());

  private static final String SYSPROP_FILE_SEP = System.getProperty("file.separator");

  private static final String SYSPROP_JAVA_HOME = System.getProperty("java.home");

  private static final String SYSPROP_USER_HOME = System.getProperty("user.home");

  private final HttpServer httpServer;

  private final HttpHandler httpHandler;

  /**
   * The main takes 1 command line argument that signifies the port to run the server on.
   */
  public static void main(String... args) {
    
    int argIdx = 0;
    int port = DEFAULT_HTTP_PORT;
    
    while (argIdx < args.length) {
      String arg = args[argIdx];
      switch (arg) {
        case "-h":
        case "--help":
          showUsageAndExit(2);
          break;
        default:
          try {
            port = Integer.valueOf(arg);
          } catch (NumberFormatException e) {
            LOGGER.config(String.format("%s is not a valid port number, defaulting to %d%n", args[0], DEFAULT_HTTP_PORT));
            port = DEFAULT_HTTP_PORT;
          }
          break;
      }
      argIdx++;
    }

    final JavadocServer server;

    try {
      String jdkDocs = "jdk-docs-all.zip";
      server = new JavadocServer(port, String.format("%1$s%2$s.m2%2$srepository", SYSPROP_USER_HOME, SYSPROP_FILE_SEP),
          String.format("%1$s%2$s%3$s", SYSPROP_JAVA_HOME, SYSPROP_FILE_SEP, jdkDocs));

      Runtime.getRuntime().addShutdownHook(new Thread() {
        @Override
        public void run() {
          server.shutdown();
        }
      });

      server.serve();
    } catch (Exception e) {
      System.err.printf("%s", e.getMessage());
      System.exit(1);
    }
  }

  private static void showUsageAndExit(int status) {
    showUsage();
    System.exit(status);
  }

  private static void showUsage() {
    System.err.printf("Usage: %s [port]%n", JavadocServer.class.getName());
    System.err.println();
    System.err.println("Serves content from javadoc jars stored locally in zip/jar files.");
    System.err.println("Documentation archives by default are the local JDK documentation");
    System.err.println("in $JAVA_HOME/jdk-docs-all.zip and in the local Maven repository in");
    System.err.println("${user.home}/.m2/repository");
    System.err.println();
    System.err.println("Arguments:");
    System.err.println();
    System.err.println(" port          port the server will listen on (default is " + DEFAULT_HTTP_PORT + ")");
    System.err.println();
    System.err.println("Options:");
    System.err.println();
    System.err.println(" -h, --help    show this help and exit");
  }

  JavadocServer(int port, String mavenRepoDirname, String javaApiArchivename) throws IOException {
    httpServer = HttpServer.create(new InetSocketAddress(port), 0);
    httpHandler = new JavadocHttpHandler(mavenRepoDirname, javaApiArchivename);
  }

  void serve() {
    LOGGER.fine("Starting HTTP server...");
    httpServer.createContext("/", httpHandler);
    httpServer.createContext("/jdk", httpHandler);
    httpServer.createContext("/m2", httpHandler);
    httpServer.start();
  }

  void shutdown() {
    LOGGER.warning("Stopping HTTP server...");
    httpServer.stop(0);
  }

  /**
   * HttpHandler that serves Javadoc from the JDK documentation zip file and files in the
   * local Maven repository.
   */
  private static class JavadocHttpHandler implements HttpHandler {

    private static final int BUF_SIZE = 0x1000;

    private static final String ERROR_HTML_TEMPLATE = "<html><head><title>%1$d %2$s</title></head><body><h1>%2$s</h1><p>%3$s</p></body></html>";

    private static final String HTML_LIST_ITEM_TEMPLATE = "<li><a href=\"/m2/%1$s/%2$s/%3$s/index.html\">%1$s:%2$s:%3$s</a></li>";

    private static final String INDEX_HTML_TEMPLATE = "<html><head><title>Available Local Javadoc</title></head><body><h1>Avaliable Local Javadoc</h1>"
        + "<h2>JDK API</h2><ul><li><a href=\"jdk/docs/api/index.html\">Java API</a></li></ul>"
        + "<h2>Maven Repository</h2><ul>%s</ul></body></html>";

    private static final Map<String, String> TYPES;

    private final Path mavenRepoDir;

    private final String javadocArchivename;

    static {
      Map<String, String> types = new HashMap<>();
      types.put("css", "text/css");
      types.put("gif", "image/gif");
      types.put("html", "text/html");
      types.put("jpg", "image/jpeg");
      types.put("js", "application/javascript");
      types.put("png", "image/png");
      types.put("svg", "image/svg+xml");
      types.put("woff", "application/x-font-woff");
      types.put("eot", "application/vnd.ms-fontobject");
      types.put("ttf", "application/octet-stream");
      types.put("otf", "application/octet-stream");
      types.put("zip", "application/zip");
      TYPES = Collections.unmodifiableMap(types);
    }

    private JavadocHttpHandler(String mavenRepoDirname, String javadocArchivename) {
      String[] baseDirnameComponents = mavenRepoDirname.split(SYSPROP_FILE_SEP);
      if (!baseDirnameComponents[0].startsWith(SYSPROP_FILE_SEP)) {
        baseDirnameComponents[0] = SYSPROP_FILE_SEP + baseDirnameComponents[0];
      }
      this.mavenRepoDir = FileSystems.getDefault().getPath(baseDirnameComponents[0],
          Arrays.copyOfRange(baseDirnameComponents, 1, baseDirnameComponents.length));
      this.javadocArchivename = javadocArchivename;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      byte[] content = new byte[0];
      int status = 200;
      String contentType = TYPES.get("html");

      if (!exchange.getRequestMethod().equals("GET")) {
        status = 405;
        content = getErrorHtml(status, "Method not allowed", exchange.getRequestMethod()).getBytes();
      } else {
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath.endsWith("/")) {
          requestPath = requestPath.substring(0, requestPath.length() - 1);
        }
        List<String> pathElements = Arrays.asList(requestPath.split(SYSPROP_FILE_SEP)).stream().filter(pe -> {
          return pe != null && !pe.isEmpty();
        }).collect(Collectors.toList());
        if (exchange.getRequestURI().getPath().equals(exchange.getHttpContext().getPath())) {
          content = getIndexHtml(toHtmlListElements(getMavenJavadocArtifactDirectoryNames())).getBytes();
        } else if (pathElements.size() > 0) {
          String repoType = pathElements.get(0);
          if (repoType.equals("m2")) {
            if (pathElements.size() < 5) {
              status = 400;
              content = getErrorHtml(status, "Method Not Allowed",
                  String.format("Method %s not supported", exchange.getRequestMethod())).getBytes();
            } else {
              String javadocJarname = toMavenCoordinates(mavenRepoDir.toString(),
                  pathElements.subList(2, pathElements.size()));
              LOGGER.finer(String.format("javadocJarname = %s", javadocJarname));

              StringJoiner fpJoiner = new StringJoiner(SYSPROP_FILE_SEP);
              pathElements.subList(4, pathElements.size()).stream().forEach(pe -> {
                fpJoiner.add(pe);
              });
              String docPath = fpJoiner.toString();
              content = getContentFromZip(javadocJarname, docPath);
              contentType = TYPES.get(getFileExtension(docPath));
            }
          } else if (repoType.equals("jdk")) {
            StringJoiner fpJoiner = new StringJoiner(SYSPROP_FILE_SEP);
            pathElements.subList(1, pathElements.size()).stream().forEach(pe -> {
              fpJoiner.add(pe);
            });
            String docPath = fpJoiner.toString();
            content = getContentFromZip(javadocArchivename, docPath);
            contentType = TYPES.get(getFileExtension(docPath));
          } else {
            status = 404;
            content = getErrorHtml(status, "Not Found",
                String.format("The requested URL %s was not found on this server", exchange.getRequestURI().getPath()))
                    .getBytes();
          }
        }
      }

      Headers h = exchange.getResponseHeaders();
      h.add("Content-Type", contentType);
      h.add("Server", String.format("%s/Java %s", JavadocServer.class.getName(), 
        System.getProperty("java.version")));
      int contentLength = content.length;
      if (content.length == 0) {
        contentLength = -1;
      }

      LOGGER.fine(String.format("[status = %d, contentLength = %d]", status, contentLength));
      exchange.sendResponseHeaders(status, contentLength);

      if (contentLength > 0) {
        try (OutputStream out = exchange.getResponseBody()) {

          out.write(content);
          out.flush();
          exchange.close();
        } catch (IOException e) {
          LOGGER.log(Level.WARNING, "Problem sending response", e);
        }
      }
    }

    private String getFileExtension(String filename) {
      int lastDot = filename.lastIndexOf('.');
      return lastDot > 0 ? filename.substring(lastDot + 1) : "";
    }

    private String toMavenCoordinates(String baseDirname, List<String> components) {
      String group = components.get(0).replace(".", SYSPROP_FILE_SEP);
      String artifactId = components.get(1);
      String version = components.get(2);

      StringJoiner dashJoiner = new StringJoiner("-");
      dashJoiner.add(artifactId).add(version).add("javadoc.jar");
      String artifactName = dashJoiner.toString();

      StringJoiner fpJoiner = new StringJoiner(SYSPROP_FILE_SEP);
      fpJoiner.add(baseDirname).add(group).add(artifactId).add(version).add(artifactName);
      return fpJoiner.toString();
    }

    private byte[] getContentFromZip(String zipFilename, String filename) throws IOException {
      byte[] content = new byte[0];
      try (ZipFile zf = new ZipFile(zipFilename)) {
        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
          ZipEntry zipEntry = (ZipEntry) entries.nextElement();
          if (zipEntry.getName().equals(filename)) {
            content = toByteArray(zf.getInputStream(zipEntry));
            break;
          }
        }
      }
      return content;
    }

    /**
     * Convert the InputStream to a byte array and returns it.
     * 
     * @param from InputStream to convert.
     * 
     * <p>
     * Shamelessly copied and adapted from com.google.common.io.ByteStreams.copy() and 
     * com.google.common.io.ByteStreams.toByteArray().
     */
    private static byte[] toByteArray(InputStream from) throws IOException {
      ByteArrayOutputStream to = new ByteArrayOutputStream();
      byte[] buf = new byte[BUF_SIZE];
      while (true) {
        int read = from.read(buf);
        if (read == -1) {
          break;
        }
        to.write(buf, 0, read);
      }
      return to.toByteArray();
    }

    private Collection<String> getMavenJavadocArtifactDirectoryNames() throws IOException {
      Collection<String> dirnames = new ArrayList<>();
      if (mavenRepoDir.toFile().exists()) {
        JavadocArchiveFinder finder = new JavadocArchiveFinder(mavenRepoDir);
        Files.walkFileTree(mavenRepoDir, finder);
        dirnames.addAll(finder.getJavadocArtifactDirectoryNames());
      }
      return dirnames;
    }

    private Collection<String> toHtmlListElements(Collection<String> javadocArchiveNames) {
      List<String> listElements = new ArrayList<>();

      for (String javadocArchivename : javadocArchiveNames) {
        List<String> parts = Arrays.asList(javadocArchivename.split(SYSPROP_FILE_SEP));
        StringJoiner dotJoiner = new StringJoiner(".");
        parts.subList(0, parts.size() - 2).stream().forEach(part -> {
          dotJoiner.add(part);
        });
        String listElement = String.format(HTML_LIST_ITEM_TEMPLATE, dotJoiner.toString(), parts.get(parts.size() - 2),
            parts.get(parts.size() - 1));
        listElements.add(listElement);
      }
      return listElements;
    }

    private String getIndexHtml(Collection<String> javadocArchiveNames) {
      StringJoiner emptyJoiner = new StringJoiner("");
      javadocArchiveNames.stream().forEach(jarname -> {
        emptyJoiner.add(jarname);
      });
      String indexHtml = String.format(INDEX_HTML_TEMPLATE, emptyJoiner.toString());
      return indexHtml;
    }

    private String getErrorHtml(int status, String error, String text) {
      return String.format(ERROR_HTML_TEMPLATE, status, error, text);
    }
  }

  /**
   * FileVisitor that looks for directories containing jar files matching {@code *-javadoc.jar}.
   */
  private static class JavadocArchiveFinder extends SimpleFileVisitor<Path> {

    private static final Logger LOGGER = Logger.getLogger(JavadocArchiveFinder.class.getName());

    private final List<String> javadocArtifactDirectoryNames = new ArrayList<>();

    private final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("regex:.*-javadoc.jar$");

    private final Path basePath;

    private JavadocArchiveFinder(Path basePath) {
      this.basePath = basePath;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
      LOGGER.finest(String.format("Scanning %s", dir.toString()));
      return super.preVisitDirectory(dir, attrs);
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      Path name = file.getFileName();
      if (name != null && matcher.matches(name)) {
        Path subpath = file.subpath(basePath.getNameCount(), file.getNameCount() - 1);
        LOGGER.finest(String.format("Adding %s", subpath.toString()));
        javadocArtifactDirectoryNames.add(subpath.toString());
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      LOGGER.log(Level.WARNING, String.format("Problem with %s", file.toString()), exc);
      return super.visitFileFailed(file, exc);
    }

    Collection<String> getJavadocArtifactDirectoryNames() {
      return javadocArtifactDirectoryNames;
    }
  }
}