/*
 * This is free software, licensed under the Gnu Public License (GPL) get a copy from <http://www.gnu.org/licenses/gpl.html> $Id:
 * HenPlus.java,v 1.77 2008-10-19 09:14:49 hzeller Exp $ author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus;

import henplus.commands.AboutCommand;
import henplus.commands.AliasCommand;
import henplus.commands.ConnectCommand;
import henplus.commands.DescribeCommand;
import henplus.commands.DriverCommand;
import henplus.commands.DumpCommand;
import henplus.commands.EchoCommand;
import henplus.commands.ExitCommand;
import henplus.commands.HelpCommand;
import henplus.commands.ImportCommand;
import henplus.commands.KeyBindCommand;
import henplus.commands.ListUserObjectsCommand;
import henplus.commands.LoadCommand;
import henplus.commands.PluginCommand;
import henplus.commands.SQLCommand;
import henplus.commands.SetCommand;
import henplus.commands.ShellCommand;
import henplus.commands.SpoolCommand;
import henplus.commands.StatusCommand;
import henplus.commands.SystemInfoCommand;
import henplus.commands.TreeCommand;
import henplus.commands.properties.PropertyCommand;
import henplus.commands.properties.SessionPropertyCommand;
import henplus.io.ConfigurationContainer;
import henplus.logging.Logger;
import henplus.util.StringUtil;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.Map;

import jline.ConsoleReader;
import jline.UnixTerminal;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;

public final class HenPlus implements Interruptable {

	private static final String HISTORY_NAME = "history";
	private static final String HENPLUSDIR = ".henplus";
	private static final String PROMPT = "Hen*Plus> ";

	public static final byte LINE_EXECUTED = 1;
	public static final byte LINE_EMPTY = 2;
	public static final byte LINE_INCOMPLETE = 3;

	private static HenPlus instance = null; // singleton.

	private boolean _fromTerminal;
	private final SQLStatementSeparator _commandSeparator;
	private final StringBuilder _historyLine;

	private boolean _quiet;
	private ConfigurationContainer _historyConfig;

	private SetCommand _settingStore;
	private SessionManager _sessionManager;
	private CommandDispatcher _dispatcher;
	private PropertyRegistry _henplusProperties;
	private ListUserObjectsCommand _objectLister;
	private String _previousHistoryLine;
	private boolean _terminated;
	private String _prompt;
	private String _emptyPrompt;
	private File _configDir;
	private boolean _alreadyShutDown;
	private BufferedReader _fileReader;
	private OutputDevice _output;
	private OutputDevice _msg;

	private volatile boolean _interrupted;
	private boolean _verbose;
	private ConsoleReader _console;

	/**
	 * Only initialize fields so that the instance is up fast.
	 */
	public HenPlus(ConsoleReader console) throws IOException {
		instance = this;
		_console = console;
		_terminated = false;
		_alreadyShutDown = false;

		_commandSeparator = new SQLStatementSeparator();
		_historyLine = new StringBuilder();
		// read options .. like -q

	}

	/**
	 * @param argv
	 * @throws UnsupportedEncodingException
	 */
	public void init(final String[] argv) throws UnsupportedEncodingException {
		String noReadlineMsg = null;

		_fromTerminal = _console.getTerminal() != null;
		_quiet |= !_fromTerminal; // not from terminal: always quiet.

		PrintStream consolePrintStream = new PrintStream(new ConsoleOutputStream(_console));
		if (_fromTerminal) {
			setOutput(new TerminalOutputDevice(consolePrintStream), new TerminalOutputDevice(consolePrintStream));
		} else {
			setOutput(new PrintStreamOutputDevice(consolePrintStream), new PrintStreamOutputDevice(consolePrintStream));
		}

		initializeCommands(argv);
		readCommandLineOptions(argv);

		if (StringUtil.isEmpty(noReadlineMsg)) {
			Logger.info("using jline (Brian Fox, Chet Ramey), Java wrapper by Bernhard Bablok");
		} else {
			Logger.info(noReadlineMsg);
		}

		_historyConfig = createConfigurationContainer(HISTORY_NAME);
		// Readline.initReadline("HenPlus");
		_historyConfig.read(new ConfigurationContainer.ReadAction() {

			@Override
			public void readConfiguration(final InputStream in) throws Exception {
				HistoryWriter.readReadlineHistory(_console, in);
			}
		});

		// TODO..
		// Readline.setWordBreakCharacters(" ,/()<>=\t\n");
		setDefaultPrompt();
	}

	public void initializeCommands(final String[] argv) {
		_henplusProperties = new PropertyRegistry();
		_henplusProperties.registerProperty("comments-remove", _commandSeparator.getRemoveCommentsProperty());

		_sessionManager = SessionManager.getInstance();

		// FIXME: to many cross dependencies of commands now. clean up.
		_settingStore = new SetCommand(this);
		_dispatcher = new CommandDispatcher(this, _console, _settingStore);
		_objectLister = new ListUserObjectsCommand(this);
		_henplusProperties.registerProperty("echo-commands", new EchoCommandProperty(_dispatcher));

		_dispatcher.register(new HelpCommand());

		/*
		 * this one prints as well the initial copyright header.
		 */
		_dispatcher.register(new AboutCommand());

		_dispatcher.register(new ExitCommand());
		_dispatcher.register(new EchoCommand());
		final PluginCommand pluginCommand = new PluginCommand(this);
		_dispatcher.register(pluginCommand);
		_dispatcher.register(new DriverCommand(this));
		final AliasCommand aliasCommand = new AliasCommand(this);
		_dispatcher.register(aliasCommand);
		if (_fromTerminal) {
			_dispatcher.register(new KeyBindCommand(this));
		}

		final LoadCommand loadCommand = new LoadCommand();
		_dispatcher.register(loadCommand);

		_dispatcher.register(new ConnectCommand(this, _sessionManager));
		_dispatcher.register(new StatusCommand());

		_dispatcher.register(_objectLister);
		_dispatcher.register(new DescribeCommand(_objectLister));

		_dispatcher.register(new TreeCommand(_objectLister));

		_dispatcher.register(new SQLCommand(_objectLister, _henplusProperties));

		_dispatcher.register(new ImportCommand(_objectLister));
		// _dispatcher.register(new ExportCommand());
		_dispatcher.register(new DumpCommand(_objectLister, loadCommand));

		_dispatcher.register(new ShellCommand());
		_dispatcher.register(new SpoolCommand(this));
		_dispatcher.register(_settingStore);

		PropertyCommand propertyCommand;
		propertyCommand = new PropertyCommand(this, _henplusProperties);
		_dispatcher.register(propertyCommand);
		_dispatcher.register(new SessionPropertyCommand(this));

		_dispatcher.register(new SystemInfoCommand());

		pluginCommand.load();
		aliasCommand.load();
		propertyCommand.load();

		_console.addCompletor(_dispatcher);
		// Readline.setCompleter(_dispatcher);

		/* FIXME: do this platform independently */
		Runtime.getRuntime().addShutdownHook(new Thread() {

			@Override
			public void run() {
				shutdown();
			}
		});
		/*
		 * if your compiler/system/whatever does not support the sun.misc.
		 * classes, then just disable this call and the SigIntHandler class.
		 */
		try {
			SigIntHandler.install();
		} catch (final Throwable t) {
			// ignore.
		}

		/*
		 * TESTING for ^Z support in the shell. sun.misc.SignalHandler stoptest
		 * = new sun.misc.SignalHandler () { public void handle(sun.misc.Signal
		 * sig) { System.out.println("caught: " + sig); } }; try {
		 * sun.misc.Signal.handle(new sun.misc.Signal("TSTP"), stoptest); }
		 * catch (Exception e) { // ignore. }
		 * 
		 * end testing
		 */
	}

	/**
	 * @param argv
	 */
	private void readCommandLineOptions(final String[] argv) {
		final Options availableOptions = getMainOptions();
		registerCommandOptions(availableOptions);
		final CommandLineParser parser = new PosixParser();
		CommandLine line = null;
		try {
			line = parser.parse(availableOptions, argv);
			if (line.hasOption('h')) {
				usageAndExit(availableOptions, 0);
			}
			if (line.hasOption('s')) {
				_quiet = true;
			}
			if (line.hasOption('v')) {
				_verbose = true;
			}
			handleCommandOptions(line);
		} catch (final Exception e) {
			Logger.error("Error handling command line arguments", e);
			usageAndExit(availableOptions, 1);
		}
	}

	/**
	 * @param availableOptions
	 */
	private void usageAndExit(final Options availableOptions, final int returnCode) {
		final HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("henplus", availableOptions);
		System.exit(returnCode);
	}

	/**
	 * @param line
	 */
	private void handleCommandOptions(final CommandLine line) {
		for (final Iterator<Command> it = _dispatcher.getRegisteredCommands(); it.hasNext();) {
			final Command element = it.next();
			element.handleCommandline(line);
		}
	}

	/**
	 * @param availableOptions
	 */
	private void registerCommandOptions(final Options availableOptions) {
		for (final Iterator<Command> it = _dispatcher.getRegisteredCommands(); it.hasNext();) {
			final Command element = it.next();
			try {
				for (final Option option : element.getHandledCommandLineOptions()) {
					availableOptions.addOption(option);
				}
			} catch (final Throwable e) {
				Logger.error("while registering %s", e, element);
				e.printStackTrace();
			}
		}
	}

	/**
	 * @return
	 */
	private Options getMainOptions() {
		final Options availableOptions = new Options();
		availableOptions.addOption(new Option("h", "help", false, "print this message"));
		availableOptions.addOption(new Option("s", "silent", false, "suppress all output except query results"));
		availableOptions.addOption(new Option("v", "verbose", false, "print debug output"));
		return availableOptions;
	}

	/**
	 * push the current state of the command execution buffer, e.g. to parse a
	 * new file.
	 */
	public void pushBuffer() {
		_commandSeparator.push();
	}

	/**
	 * pop the command execution buffer.
	 */
	public void popBuffer() {
		_commandSeparator.pop();
	}

	public String readlineFromFile() throws IOException {
		if (_fileReader == null) {
			_fileReader = new BufferedReader(new InputStreamReader(System.in));
		}
		final String line = _fileReader.readLine();
		if (line == null) {
			throw new EOFException("EOF");
		}
		return line.length() == 0 ? null : line;
	}

	private void storeLineInHistory() {
		final String line = _historyLine.toString();
		if (!"".equals(line) && !line.equals(_previousHistoryLine)) {
			_console.getHistory().addToHistory(line);
			_previousHistoryLine = line;
		}
		_historyLine.setLength(0);
	}

	/**
	 * add a new line. returns one of LINE_EMPTY, LINE_INCOMPLETE or
	 * LINE_EXECUTED.
	 */
	public byte executeLine(final String line) {
		byte result = LINE_EMPTY;
		/*
		 * special oracle comment 'rem'ark; should be in the comment parser.
		 * ONLY if it is on the beginning of the line, no whitespace.
		 */
		final int startWhite = 0;
		/*
		 * while (startWhite < line.length() &&
		 * Character.isWhitespace(line.charAt(startWhite))) { ++startWhite; }
		 */
		if (line.length() >= 3 + startWhite && line.substring(startWhite, startWhite + 3).toUpperCase().equals("REM")
			&& (line.length() == 3 || Character.isWhitespace(line.charAt(3)))) {
			return LINE_EMPTY;
		}

		final StringBuilder lineBuf = new StringBuilder(line);
		lineBuf.append('\n');
		_commandSeparator.append(lineBuf.toString());
		result = LINE_INCOMPLETE;
		while (_commandSeparator.hasNext()) {
			String completeCommand = _commandSeparator.next();
			completeCommand = varsubst(completeCommand, _settingStore.getVariableMap());
			final Command c = _dispatcher.getCommandFrom(completeCommand);
			if (c == null) {
				_commandSeparator.consumed();
				/*
				 * do not shadow successful executions with the 'line-empty'
				 * message. Background is: when we consumed a command, that is
				 * complete with a trailing ';', then the following newline
				 * would be considered as empty command. So return only the
				 * LINE_EMPTY, if we haven't got a succesfully executed line.
				 */
				if (result != LINE_EXECUTED) {
					result = LINE_EMPTY;
				}
			} else if (!c.isComplete(completeCommand)) {
				_commandSeparator.cont();
				result = LINE_INCOMPLETE;
			} else {
				_dispatcher.execute(_sessionManager.getCurrentSession(), completeCommand);
				_commandSeparator.consumed();
				result = LINE_EXECUTED;
			}
		}
		return result;
	}

	public String getPartialLine() {
		return _historyLine.toString() + _console.getCursorBuffer().getBuffer();
	}

	public void run() {
		String cmdLine = null;
		String displayPrompt = _prompt;
		while (!_terminated) {
			_interrupted = false;
			/*
			 * a CTRL-C will not interrupt the current reading thus it does not
			 * make much sense here to interrupt. WORKAROUND: Print message in
			 * the interrupt() method. TODO: find out, if we can do something
			 * that behaves like a shell. This requires, that CTRL-C makes
			 * Readline.readline() return..
			 */
			SigIntHandler.getInstance().pushInterruptable(this);

			try {
				cmdLine = _fromTerminal ? _console.readLine(displayPrompt) : readlineFromFile();
			} catch (final EOFException e) {
				// EOF on CTRL-D
				if (_sessionManager.getCurrentSession() != null) {
					_dispatcher.execute(_sessionManager.getCurrentSession(), "disconnect");
					displayPrompt = _prompt;
					continue;
				} else {
					break; // last session closed -> exit.
				}
			} catch (final Exception e) {
				if (_verbose) {
					e.printStackTrace();
				}
			}

			SigIntHandler.getInstance().reset();

			// anyone pressed CTRL-C
			if (_interrupted) {
				_historyLine.setLength(0);
				_commandSeparator.discard();
				displayPrompt = _prompt;
				continue;
			}

			if (cmdLine == null) {
				continue;
			}

			/*
			 * if there is already some line in the history, then add newline.
			 * But if the only thing we added was a delimiter (';'), then this
			 * would be annoying.
			 */
			if (_historyLine.length() > 0 && !cmdLine.trim().equals(";")) {
				_historyLine.append("\n");
			}
			_historyLine.append(cmdLine);
			final byte lineExecState = executeLine(cmdLine);
			if (lineExecState == LINE_INCOMPLETE) {
				displayPrompt = _emptyPrompt;
			} else {
				displayPrompt = _prompt;
			}
			if (lineExecState != LINE_INCOMPLETE) {
				storeLineInHistory();
			}
		}
		SigIntHandler.getInstance().reset();
	}

	/**
	 * called at the very end; on signal or called from the shutdown-hook
	 */
	public void shutdown() {
		if (_alreadyShutDown) {
			return;
		}
		Logger.info("storing settings..");
		/*
		 * allow hard resetting.
		 */
		SigIntHandler.getInstance().reset();
		try {
			if (_dispatcher != null) {
				_dispatcher.shutdown();
			}
			if (_historyConfig != null) {
				_historyConfig.write(new ConfigurationContainer.WriteAction() {

					@Override
					public void writeConfiguration(final OutputStream out) throws Exception {
						HistoryWriter.writeReadlineHistory(_console, out);
					}
				});
			}
			// TODO anything?
			// _console.cleanup();
		} finally {
			_alreadyShutDown = true;
		}
		/*
		 * some JDBC-Drivers (notably hsqldb) do some important cleanup (closing
		 * open threads, for instance) in finalizers. Force them to do their
		 * duty:
		 */
		System.gc();
		System.gc();
	}

	public void terminate() {
		_terminated = true;
	}

	public CommandDispatcher getDispatcher() {
		return _dispatcher;
	}

	/**
	 * Provides access to the session manager. He maintains the list of opened
	 * sessions with their names.
	 * 
	 * @return the session manager.
	 */
	public SessionManager getSessionManager() {
		return _sessionManager;
	}

	/**
	 * set current session. This is called from commands, that switch the
	 * sessions (i.e. the ConnectCommand.)
	 */
	public void setCurrentSession(final SQLSession session) {
		getSessionManager().setCurrentSession(session);
	}

	/**
	 * get current session.
	 */
	public SQLSession getCurrentSession() {
		return getSessionManager().getCurrentSession();
	}

	public ListUserObjectsCommand getObjectLister() {
		return _objectLister;
	}

	public void setPrompt(final String p) {
		_prompt = p;
		final StringBuilder tmp = new StringBuilder();
		final int emptyLength = p.length();
		for (int i = emptyLength; i > 0; --i) {
			tmp.append(' ');
		}
		_emptyPrompt = tmp.toString();
		// readline won't know anything about these extra characters:
		// if (_fromTerminal) {
		// prompt = Terminal.BOLD + prompt + Terminal.NORMAL;
		// }
	}

	public void setDefaultPrompt() {
		setPrompt(_fromTerminal ? PROMPT : "");
	}

	/**
	 * substitute the variables in String 'in', that are in the form $VARNAME or
	 * ${VARNAME} with the equivalent value that is found in the Map. Return the
	 * varsubstituted String.
	 * 
	 * @param in the input string containing variables to be substituted (with
	 *            leading $)
	 * @param variables the Map containing the mapping from variable name to
	 *            value.
	 */
	public String varsubst(final String in, final Map<String, String> variables) {
		int pos = 0;
		int endpos = 0;
		int startVar = 0;
		final StringBuilder result = new StringBuilder();
		String varname;
		boolean hasBrace = false;

		if (in == null) {
			return null;
		}

		if (variables == null) {
			return in;
		}

		while ((pos = in.indexOf('$', pos)) >= 0) {
			startVar = pos;
			if (in.charAt(pos + 1) == '$') { // quoting '$'
				result.append(in.substring(endpos, pos));
				endpos = pos + 1;
				pos += 2;
				continue;
			}

			hasBrace = in.charAt(pos + 1) == '{';

			// text between last variable and here
			result.append(in.substring(endpos, pos));

			if (hasBrace) {
				pos++;
			}

			endpos = pos + 1;
			while (endpos < in.length() && Character.isJavaIdentifierPart(in.charAt(endpos))) {
				endpos++;
			}
			varname = in.substring(pos + 1, endpos);

			if (hasBrace) {
				while (endpos < in.length() && in.charAt(endpos) != '}') {
					++endpos;
				}
				++endpos;
			}
			if (endpos > in.length()) {
				if (variables.containsKey(varname)) {
					Logger.info("warning: missing '}' for variable '%s'.", varname);
				}
				result.append(in.substring(startVar));
				break;
			}

			if (variables.containsKey(varname)) {
				result.append(variables.get(varname));
			} else {
				Logger.info("warning: variable '%s' not set.", varname);
				result.append(in.substring(startVar, endpos));
			}

			pos = endpos;
		}
		if (endpos < in.length()) {
			result.append(in.substring(endpos));
		}
		return result.toString();
	}

	// -- Interruptable interface
	@Override
	public void interrupt() {
		// watchout: Readline.getLineBuffer() will cause a segmentation fault!
		getMessageDevice().attributeBold();
		getMessageDevice().print("\n...discarded current command line; press [RETURN] to continue or [CTRL-D] to exit henplus");
		getMessageDevice().attributeReset();

		_interrupted = true;
	}

	// *****************************************************************
	public static HenPlus getInstance() {
		return instance;
	}

	public void setOutput(final OutputDevice out, final OutputDevice msg) {
		_output = out;
		_msg = msg;
	}

	public OutputDevice getOutputDevice() {
		return _output;
	}

	public OutputDevice getMessageDevice() {
		return _msg;
	}

	public static OutputDevice out() {
		return getInstance().getOutputDevice();
	}

	public static OutputDevice msg() {
		return getInstance().getMessageDevice();
	}

	public static void main(final String[] argv) throws Exception {
		UnixTerminal term = new UnixTerminal();
		ConsoleReader reader = new ConsoleReader(System.in, new OutputStreamWriter(System.out), null, term);
		instance = new HenPlus(reader);
		instance.init(argv);
		instance.run();
		instance.shutdown();
		/*
		 * hsqldb does not always stop its log-thread. So do an explicit exit()
		 * here.
		 */
		System.exit(0);
	}

	/**
	 * returns an InputStream for a named configuration. That stream must be
	 * closed on finish.
	 */
	public ConfigurationContainer createConfigurationContainer(final String configName) {
		return new ConfigurationContainer(new File(getConfigDir(), configName));
	}

	public String getConfigurationDirectoryInfo() {
		return getConfigDir().getAbsolutePath();
	}

	private File getConfigDir() {
		if (_configDir != null) {
			return _configDir;
		}
		/*
		 * test local directory and superdirectories.
		 */
		File dir = new File(".").getAbsoluteFile();
		while (dir != null) {
			_configDir = new File(dir, HENPLUSDIR);
			if (_configDir.exists() && _configDir.isDirectory()) {
				break;
			} else {
				_configDir = null;
			}
			dir = dir.getParentFile();
		}

		/*
		 * fallback: home directory.
		 */
		if (_configDir == null) {
			final String homeDir = System.getProperty("user.home", ".");
			_configDir = new File(homeDir + File.separator + HENPLUSDIR);
			if (!_configDir.exists()) {
				Logger.debug("creating henplus config dir.");
				if (!_configDir.mkdir()) {
					Logger.error("henplus config dir at '%s' could not be created.", _configDir.getAbsolutePath());
				}
			}
			try {
				/*
				 * Make this directory accessible only for the user in question.
				 * works only on unix. Ignore Exception other OSes
				 */
				final String[] params = new String[] { "chmod", "700", _configDir.toString() };
				Runtime.getRuntime().exec(params);
			} catch (final Exception e) {
				if (_verbose) {
					e.printStackTrace();
				}
			}
		}
		_configDir = _configDir.getAbsoluteFile();
		try {
			_configDir = _configDir.getCanonicalFile();
		} catch (final IOException ign) { /* ign */
		}

		Logger.info("henplus config at " + _configDir, false);
		return _configDir;
	}

	public boolean isQuiet() {
		return _quiet;
	}

	public boolean isVerbose() {
		return _verbose;
	}
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
