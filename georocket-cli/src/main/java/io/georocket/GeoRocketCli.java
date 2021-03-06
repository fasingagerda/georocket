package io.georocket;

import de.undercouch.underline.CommandDesc;
import de.undercouch.underline.CommandDescList;
import de.undercouch.underline.InputReader;
import de.undercouch.underline.Option.ArgumentType;
import de.undercouch.underline.OptionDesc;
import de.undercouch.underline.OptionParserException;
import de.undercouch.underline.StandardInputReader;
import io.georocket.client.GeoRocketClient;
import io.georocket.commands.AbstractGeoRocketCommand;
import io.georocket.commands.DeleteCommand;
import io.georocket.commands.ExportCommand;
import io.georocket.commands.HelpCommand;
import io.georocket.commands.ImportCommand;
import io.georocket.commands.PropertyCommand;
import io.georocket.commands.SearchCommand;
import io.georocket.commands.TagCommand;
import io.georocket.util.JsonUtils;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.fusesource.jansi.AnsiConsole;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GeoRocket command-line interface
 * @author Michel Kraemer
 */
public class GeoRocketCli extends AbstractGeoRocketCommand {
  /**
   * GeoRocket CLI's home directory
   */
  protected File geoRocketCliHome;
  
  private boolean displayVersion;
  private String host;
  private Integer port;
  private String confFilePath;
  private AbstractGeoRocketCommand command;
  
  /**
   * The Vert.x instance. Use {@link #getVertx()} to access it.
   */
  private Vertx vertx;
  
  /**
   * Get or create the Vert.x instance
   * @return the Vert.x instance
   */
  private Vertx getVertx() {
    if (vertx == null) {
      vertx = Vertx.vertx();
    }
    return vertx;
  }
  
  @Override
  protected JsonObject config() {
    JsonObject config = super.config();
    if (config == null || config.isEmpty()) {
      // load configuration file
      File confFile;
      if (confFilePath != null) {
        confFile = new File(confFilePath);
      } else {
        File confDir = new File(geoRocketCliHome, "conf");
        confFile = new File(confDir, "georocket.yaml");
        if (!confFile.exists()) {
          confFile = new File(confDir, "georocket.yml");
          if (!confFile.exists()) {
            confFile = new File(confDir, "georocket.json");
          }
        }
      }
      config = new JsonObject();
      try {
        String confFileStr = FileUtils.readFileToString(confFile, "UTF-8");
        if (confFile.getName().endsWith(".json")) {
          config = new JsonObject(confFileStr);
        } else {
          Yaml yaml = new Yaml();
          @SuppressWarnings("unchecked")
          Map<String, Object> m = yaml.loadAs(confFileStr, Map.class);
          config = JsonUtils.flatten(new JsonObject(m));
        }
      } catch (IOException e) {
        System.err.println("Could not read config file " + confFile + ": " + e.getMessage());
        System.exit(1);
      } catch (DecodeException e) {
        System.err.println("Invalid config file: " + e.getMessage());
        System.exit(1);
      }
      
      // set default values
      if (!config.containsKey(ConfigConstants.HOST)) {
        config.put(ConfigConstants.HOST, GeoRocketClient.DEFAULT_HOST);
      }
      if (!config.containsKey(ConfigConstants.PORT)) {
        config.put(ConfigConstants.PORT, GeoRocketClient.DEFAULT_PORT);
      }
      
      // overwrite with values from command line
      if (host != null) {
        config.put(ConfigConstants.HOST, host);
      }
      if (port != null) {
        config.put(ConfigConstants.PORT, port);
      }
      
      setConfig(config);
    }
    return config;
  }
  
  /**
   * Set the name of the host where GeoRocket is running
   * @param host the host
   */
  @OptionDesc(longName = "host",
      description = "the name of the host where GeoRocket is running",
      argumentName = "HOST", argumentType = ArgumentType.STRING)
  public void setHost(String host) {
    this.host = host;
  }
  
  /**
   * Set the port GeoRocket server is listening on
   * @param port the port
   */
  @OptionDesc(longName = "port",
      description = "the port GeoRocket server is listening on",
      argumentName = "PORT", argumentType = ArgumentType.STRING)
  public void setPort(String port) {
    try {
      this.port = Integer.parseInt(port);
    } catch (NumberFormatException e) {
      error("invalid port: " + port);
      System.exit(1);
    }
  }
  
  /**
   * Set the path to the application's configuration file
   * @param path the path
   */
  @OptionDesc(longName = "conf", shortName = "c",
      description = "path to the application's configuration file",
      argumentName = "PATH", argumentType = ArgumentType.STRING)
  public void setConfFilePath(String path) {
    this.confFilePath = path;
  }
  
  /**
   * Specify if version information should be displayed
   * @param display true if the version should be displayed
   */
  @OptionDesc(longName = "version", shortName = "V",
      description = "output version information and exit",
      priority = 9999)
  public void setDisplayVersion(boolean display) {
    this.displayVersion = display;
  }
  
  /**
   * Set the command to execute
   * @param command the command
   */
  @CommandDescList({
    @CommandDesc(longName = "import",
        description = "import one or more files into GeoRocket",
        command = ImportCommand.class),
    @CommandDesc(longName = "export",
        description = "export from GeoRocket",
        command = ExportCommand.class),
    @CommandDesc(longName = "property",
        description = "update properties of existing chunks in GeoRocket",
        command = PropertyCommand.class),
    @CommandDesc(longName = "tag",
        description = "update tags of existing chunks in GeoRocket",
        command = TagCommand.class),
    @CommandDesc(longName = "search",
        description = "search the GeoRocket data store",
        command = SearchCommand.class),
    @CommandDesc(longName = "delete",
        description = "delete from the GeoRocket data store",
        command = DeleteCommand.class),
    @CommandDesc(longName = "help",
        description = "display help for a given command",
        command = HelpCommand.class)
  })
  public void setCommand(AbstractGeoRocketCommand command) {
    this.command = command;
    this.command.setVertx(getVertx());
    this.command.setConfig(config());
  }
  
  /**
   * Run the GeoRocket command-line interface
   * @param args the command line arguments
   * @throws IOException if a stream could not be read
   */
  public static void main(String[] args) throws IOException {
    // BEGIN WORKAROUND-VERTX-2562: REMOVE THIS ONCE
    // https://github.com/eclipse/vert.x/issues/2562 HAS BEEN RESOLVED
    ClassPool cp = ClassPool.getDefault();
    try {
      CtClass cc = cp.get("io.netty.handler.codec.http.ComposedLastHttpContent");
      CtMethod m = cc.getDeclaredMethod("decoderResult");
      m.insertBefore("{ if (result == null) result = io.netty.handler.codec.DecoderResult.SUCCESS; }");
      cc.toClass();
    } catch (NotFoundException | CannotCompileException e) {
      System.err.println("Could not patch ComposedLastHttpContent. Optimistic " +
        "merging will not work properly.");
      e.printStackTrace();
    }
    // END WORKAROUND-VERTX-2562

    AnsiConsole.systemInstall();

    // start CLI
    GeoRocketCli cli = new GeoRocketCli();
    cli.setup();
    try {
      PrintWriter out = new PrintWriter(new OutputStreamWriter(
          System.out, StandardCharsets.UTF_8));
      cli.setEndHandler(exitCode -> {
        out.flush();
        AnsiConsole.systemUninstall();
        System.exit(exitCode);
      });
      cli.run(args, new StandardInputReader(), out);
    } catch (OptionParserException e) {
      cli.error(e.getMessage());
      AnsiConsole.systemUninstall();
      System.exit(1);
    }
  }

  /**
   * Setup GeoRocket CLI
   */
  public void setup() {
    // get GEOROCKET_CLI_HOME
    String geoRocketCliHomeStr = System.getenv("GEOROCKET_CLI_HOME");
    if (geoRocketCliHomeStr == null) {
      System.err.println("Environment variable GEOROCKET_CLI_HOME not set. "
          + "Using current working directory.");
      geoRocketCliHomeStr = new File(".").getAbsolutePath();
    }

    try {
      geoRocketCliHome = new File(geoRocketCliHomeStr).getCanonicalFile();
    } catch (IOException e) {
      System.err.println("Invalid GeoRocket home: " + geoRocketCliHomeStr);
      System.exit(1);
    }
  }

  @Override
  public String getUsageName() {
    return ""; // the tool's name will be prepended
  }

  @Override
  public String getUsageDescription() {
    return "Command-line interface for GeoRocket";
  }

  @Override
  public void doRun(String[] remainingArgs, InputReader in, PrintWriter out,
      Handler<Integer> handler) throws OptionParserException, IOException {
    if (displayVersion) {
      version();
      handler.handle(0);
      return;
    }
    
    // if there are no commands print usage and exit
    if (command == null) {
      usage();
      handler.handle(0);
      return;
    }
    
    command.setEndHandler(handler);
    command.run(remainingArgs, in, out);
  }
  
  /**
   * Prints out version information
   */
  private void version() {
    System.out.println("georocket " + getVersion());
  }
  
  /**
   * @return the tool's version string
   */
  public static String getVersion() {
    URL u = GeoRocketCli.class.getResource("version.dat");
    String version;
    try {
      version = IOUtils.toString(u, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Could not read version information", e);
    }
    return version;
  }
}
