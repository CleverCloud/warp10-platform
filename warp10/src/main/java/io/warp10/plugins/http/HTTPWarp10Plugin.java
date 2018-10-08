package io.warp10.plugins.http;

import com.google.common.base.Charsets;
import io.warp10.script.MemoryWarpScriptStack;
import io.warp10.script.WarpScriptStack.Macro;
import io.warp10.warp.sdk.AbstractWarp10Plugin;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

public class HTTPWarp10Plugin extends AbstractWarp10Plugin implements Runnable {

  private static final Logger LOG = LoggerFactory.getLogger(HTTPWarp10Plugin.class);

  private static final String PARAM_PATH = "path";
  private static final String PARAM_MACRO = "macro";
  private static final String PARAM_PREFIX = "prefix";
  private static final String PARAM_PARSE_PAYLOAD = "parsePayload";

  private static final String CONF_HTTP_HOST = "http.host";
  private static final String CONF_HTTP_PORT = "http.port";

  /**
   * Directory where spec files are located
   */
  private static final String CONF_HTTP_DIR = "http.dir";

  /**
   * Period at which to scan the spec directory
   */
  private static final String CONF_HTTP_PERIOD = "http.period";

  private static final String CONF_HTTP_ACCEPTORS = "http.acceptors";
  private static final String CONF_HTTP_SELECTORS = "http.selectors";
  private static final String CONF_HTTP_MAXTHREADS = "http.maxthreads";
  private static final String CONF_HTTP_IDLE_TIMEOUT = "http.idle.timeout";
  private static final String CONF_HTTP_QUEUESIZE = "http.queuesize";


  /**
   * Default scanning period in ms
   */
  private static final long DEFAULT_PERIOD = 60000L;

  private String dir;
  private long period;
  private String host;
  private int port;
  private int acceptors = 2;
  private int selectors = 4;
  private int maxthreads = 1 + acceptors + acceptors * selectors;
  private long idleTimeout = 30000;
  private BlockingQueue<Runnable> queue = null;

  /**
   * Map of uri to macros
   */
  private Map<String, Macro> macros = new HashMap<String, Macro>();

  /**
   * Map of uri to parse payloads
   */
  private Map<String, Boolean> parsePayloads = new HashMap<String, Boolean>();

  /**
   * Map of filename to uri
   */
  private Map<String, String> uris = new HashMap<String, String>();

  /**
   * Sorted set of prefixes
   */
  private TreeSet<String> prefixes = new TreeSet<String>();

  /**
   * Map of filename to size
   */
  private Map<String, Integer> sizes = new HashMap<String, Integer>();

  public HTTPWarp10Plugin() {
    super();
  }

  @Override
  public void run() {

    //
    // Start Jetty server
    //

    Server server = new Server(new QueuedThreadPool(maxthreads, 8, (int) idleTimeout, queue));

    ServerConnector connector = new ServerConnector(server, acceptors, selectors);
    connector.setIdleTimeout(idleTimeout);
    connector.setPort(port);
    connector.setHost(host);
    connector.setName("Warp 10 HTTP Plugin Jetty Connector");

    server.addConnector(connector);

    server.setHandler(new WarpScriptHandler(this));

    try {
      server.start();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    while (true) {
      try {
        Iterator<Path> iter = Files.walk(new File(dir).toPath(), FileVisitOption.FOLLOW_LINKS)
            //.filter(path -> path.toString().endsWith(".mc2"))
            .filter(new Predicate<Path>() {
              @Override
              public boolean test(Path t) {
                return t.toString().endsWith(".mc2");
              }
            })
            .iterator();

        Set<String> specs = new HashSet<String>();

        while (iter.hasNext()) {
          Path p = iter.next();

          boolean load = false;

          if (this.sizes.containsKey(p.toString())) {
            if (this.sizes.get(p.toString()) != p.toFile().length()) {
              load = true;
            }
          } else {
            // This is a new spec
            load = true;
          }

          if (load) {
            load(p);
          }

          specs.add(p.toString());
        }

        //
        // Clean the uris which disappeared
        //

        Set<String> removed = new HashSet<String>(this.sizes.keySet());
        removed.removeAll(specs);

        for (String spec: removed) {
          String uri = uris.remove(spec);
          this.macros.remove(uri);
          this.parsePayloads.remove(uri);
          this.sizes.remove(spec);
          this.prefixes.remove(uri);
        }

        Set<String> inactiveURIs = new HashSet<String>(this.macros.keySet());
        inactiveURIs.removeAll(this.uris.values());

        for (String uri: inactiveURIs) {
          this.macros.remove(uri);
          this.parsePayloads.remove(uri);
          this.prefixes.remove(uri);
        }
      } catch (Throwable t) {
        t.printStackTrace();
      }

      LockSupport.parkNanos(this.period * 1000000L);
    }
  }

  /**
   * Load a spec file
   *
   * @param p
   */
  private void load(Path p) {
    try {
      //
      // Read content of mc2 file
      //

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      InputStream in = new FileInputStream(p.toFile());
      byte[] buf = new byte[8192];

      try {
        while (true) {
          int len = in.read(buf);
          if (len < 0) {
            break;
          }
          baos.write(buf, 0, len);
        }
      } finally {
        in.close();
      }


      String warpscript = new String(baos.toByteArray(), Charsets.UTF_8);
      MemoryWarpScriptStack stack = new MemoryWarpScriptStack(getExposedStoreClient(), getExposedDirectoryClient(), new Properties());
      stack.maxLimits();

      stack.execMulti(warpscript);

      Object top = stack.pop();

      if (!(top instanceof Map)) {
        throw new RuntimeException("HTTP consumer spec must leave a configuration map on top of the stack.");
      }

      Map<Object, Object> config = (Map<Object, Object>) top;

      this.sizes.put(p.toString(), baos.size());
      String oldpath = this.uris.put(p.toString(), String.valueOf(config.get(PARAM_PATH)));
      if (null != oldpath) {
        this.macros.remove(oldpath);
        this.parsePayloads.remove(oldpath);
        this.prefixes.remove(oldpath);
      }
      this.macros.put(String.valueOf(config.get(PARAM_PATH)), (Macro) config.get(PARAM_MACRO));
      this.parsePayloads.put(String.valueOf(config.get(PARAM_PATH)), (Boolean) config.getOrDefault(PARAM_PARSE_PAYLOAD, true));
      if (Boolean.TRUE.equals(config.get(PARAM_PREFIX))) {
        prefixes.add(String.valueOf(config.get(PARAM_PATH)));
      }
    } catch (Exception e) {
      e.printStackTrace();
      LOG.error("Caught exception while loading '" + p.getFileName() + "'.", e);
    }
  }

  @Override
  public void init(Properties properties) {
    this.dir = properties.getProperty(CONF_HTTP_DIR);

    if (null == this.dir) {
      throw new RuntimeException("Missing '" + CONF_HTTP_DIR + "' configuration.");
    }

    this.period = Long.parseLong(properties.getProperty(CONF_HTTP_PERIOD, Long.toString(DEFAULT_PERIOD)));
    if (!properties.containsKey(CONF_HTTP_HOST)) {
      throw new RuntimeException("Missing " + CONF_HTTP_HOST + " configuration.");
    }
    this.host = properties.getProperty(CONF_HTTP_HOST);
    if (!properties.containsKey(CONF_HTTP_PORT)) {
      throw new RuntimeException("Missing " + CONF_HTTP_PORT + " configuration.");
    }
    this.port = Integer.parseInt(properties.getProperty(CONF_HTTP_PORT));

    acceptors = Integer.valueOf(properties.getProperty(CONF_HTTP_ACCEPTORS, String.valueOf(acceptors)));
    selectors = Integer.valueOf(properties.getProperty(CONF_HTTP_SELECTORS, String.valueOf(selectors)));
    maxthreads = Integer.valueOf(properties.getProperty(CONF_HTTP_MAXTHREADS, String.valueOf(maxthreads)));
    idleTimeout = Long.parseLong(properties.getProperty(CONF_HTTP_IDLE_TIMEOUT, String.valueOf(idleTimeout)));

    if (properties.containsKey(CONF_HTTP_QUEUESIZE)) {
      queue = new BlockingArrayQueue<>(Integer.parseInt(properties.getProperty(CONF_HTTP_QUEUESIZE)));
    }

    Thread t = new Thread(this);
    t.setDaemon(true);
    t.setName("[Warp 10 HTTP Plugin " + this.dir + "]");
    t.start();
  }

  public String getPrefix(String uri) {
    // Seek longest match
    int prefixLength = 0;
    String foundPrefix = uri; // Return uri if no prefix found

    for (String prefix: this.prefixes) {
      // Check if prefix is a prefix of uri (in term of path) and longer than previously found
      if (uri.startsWith(prefix) && (uri.length() == prefix.length() || '/' == uri.charAt(prefix.length())) && prefix.length() > prefixLength) {
        foundPrefix = prefix;
        prefixLength = prefix.length();
      }
    }

    return foundPrefix;
  }

  public Macro getMacro(String uri) {
    return this.macros.get(uri);
  }

  public boolean isParsePayload(String uri) {
    return this.parsePayloads.get(uri);
  }

}
