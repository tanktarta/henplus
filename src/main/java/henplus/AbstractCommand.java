/*
 * This is free software, licensed under the Gnu Public License (GPL) get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.StringTokenizer;

import jline.ConsoleReader;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;

/**
 * Implementation of a Command with default settings. Override what is necessary
 * in your Command. It makes sense to derive plug-ins from this AbstractCommand
 * - this makes the plug-in more robust with regard to newly added methods.
 * 
 * @author Henner Zeller
 */
public abstract class AbstractCommand implements Command {

	private ConsoleReader _console;
	private HenPlus _context;

	// no description by default.
	@Override
	public String getShortDescription() {
		return null;
	}

	@Override
	public void init(HenPlus context, ConsoleReader console) {
		_context = context;
		_console = console;
	}

	@Override
	public String getLongDescription(final String cmd) {
		final Collection<Option> handledCommandLineOptions = getHandledCommandLineOptions();
		if (handledCommandLineOptions != null && handledCommandLineOptions.size() > 0) {
			final StringBuilder sb = new StringBuilder("\tRecognized options are:\n");
			for (final Option option : handledCommandLineOptions) {
				sb.append(String.format("\t -%s %s\n", option.getOpt(), option.getDescription()));
			}
			return sb.toString();
		}
		return null;
	}

	@Override
	public String getSynopsis(final String cmd) {
		return null;
	}

	// All commands are completed by default.
	@Override
	public boolean participateInCommandCompletion() {
		return true;
	}

	// no completion support by the commmand
	@Override
	public Iterator<String> complete(final CommandDispatcher disp, final String partialCommand, final String lastWord) {
		return null;
	}

	@Override
	public void shutdown() { /* don't care */
	}

	// the simple commands are always complete on newline or semicolon
	@Override
	public boolean isComplete(final String command) {
		return true;
	}

	@Override
	public boolean requiresValidSession(final String cmd) {
		return true;
	}

	/**
	 * convenience method: returns the number of elements in this string,
	 * separated by whitespace.
	 */
	protected int argumentCount(final String command) {
		return new StringTokenizer(command).countTokens();
	}

	/**
	 * Override this method if you want to register command-specific options.
	 * 
	 * @param r
	 */
	@Override
	public Collection<Option> getHandledCommandLineOptions() {
		return Collections.emptyList();
	}

	@Override
	public void handleCommandline(final CommandLine line) {

	}

	protected HenPlus getContext() {
		return _context;
	}

	protected ConsoleReader getConsole() {
		return _console;
	}
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
