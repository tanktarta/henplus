/*
 * This is free software, licensed under the Gnu Public License (GPL) get a copy from <http://www.gnu.org/licenses/gpl.html> $Id:
 * Command.java,v 1.11 2008-10-19 09:14:49 hzeller Exp $ author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus;

import java.util.Collection;
import java.util.Iterator;

import jline.ConsoleReader;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;

/**
 * Interface to be implemented for user level commands.
 * <p>
 * The CommandDispatcher and the HelpCommand operate on this interface. This interface needs to be implemented by your own Commands
 * or Plugins that should be supported by HenPlus. If the documenation given here is not enough (though I hope it is), just read
 * some of the implementations given in henplus.commands.
 * </p>
 * <p>
 * If you are writing Plugins, consider extending the {@link AbstractCommand} as it provides a default implementation and you are
 * immune to NoSuchMethodErrors if this interface changes but not yet your plugin...
 * </p>
 * <p>
 * This interface is defined as an abstract class so we can define static methods.
 * </p>
 * 
 * @version $Revision: 1.11 $
 * @author Henner Zeller
 */
public interface Command {

    /**
     * constant returned by the {@link #execute(SQLSession,String,String)} method, if everything went fine.
     */
    int SUCCESS = 0;

    /**
     * constant returned by the {@link #execute(SQLSession,String,String)} if the command could not be executed because of an syntax
     * error. In that case, the CommandDispatcher will display the synopsis of that command.
     */
    int SYNTAX_ERROR = 1;

    /**
     * constant returned by the {@link #execute(SQLSession,String,String)} if the command could not be executed because of some
     * problem, that is not a syntax error.
     */
    int EXEC_FAILED = 2;

    /**
     * returns the prefices of all command-strings this command can handle. The special prefix is the empty string that matches
     * anything that is not handled by all other commands. It is used in the SQLCommand.
     */
    String[] getCommandList();

    /**
     * returns 'false', if the commands supported by this Commands should not be part of the toplevel command completion. So if the
     * user presses TAB on an empty string to get the full list of possible commands, this command should not show up. In HenPlus,
     * this returns 'false' for the SQL-commands ('select', 'update', 'drop' ..), since this would clobber the toplevel list of
     * available commands. If unsure, return 'true'.
     */
    boolean participateInCommandCompletion();
    
    /**
     * Initialise
     * 
     * @param context top level instance
     * @param console console on which command will run.
     */
    void init(HenPlus context, ConsoleReader console);

    /**
     * execute the command given. The command is given completely without the final delimiter (which would be newline or semicolon).
     * Before this method is called, the CommandDispatcher checks with the {@link #isComplete(String)} method, if this command is
     * complete.
     * 
     * @param session
     *            the SQLsession this command is executed from.
     * @param command
     *            the command as string.
     * @param parameters
     *            the rest parameters following the command.
     * @return one of SUCCESS, SYNTAX_ERROR, EXEC_FAILED to indicate the exit status of this command. On SYNTAX_ERROR, the
     *         CommandDispatcher displays a synopsis if available.
     */
    int execute(SQLSession session, String command, String parameters);

    /**
     * Returns a list of strings that are possible at this stage. Used for the readline-completion in interactive mode. Based on the
     * partial command and the lastWord you have to determine the words that are available at this stage. Return 'null', if you
     * don't know a possible completion.
     * 
     * @param disp
     *            the CommandDispatcher - you might want to access other values through it.
     * @param partialCommand
     *            The command typed so far
     * @param lastWord
     *            the last word returned by readline.
     */
    Iterator<String> complete(CommandDispatcher disp, String partialCommand, String lastWord);

    /**
     * returns, whether the command is complete.
     * 
     * <p>
     * This method is called, whenever the input encounters a newline or a semicolon to decide if this separator is to separate
     * different commands or if it is part of the command itself.
     * 
     * <p>
     * The delimiter (newline or semicolon) is contained (at the end) in the String passed to this method. This method returns
     * <code>false</code>, if the delimiter is part of the command and will not be regarded as delimiter between commands -- the
     * reading part of the command dispatcher will go on reading characters and not execute the command.
     * 
     * <p>
     * This method will return true for most simple commands like 'help'. For commands that have a more complicated syntax, this
     * might not be true.
     * <ul>
     * <li>'select * from foobar' is not complete after a return, since we can expect a where clause. If it has a semicolon at the
     * end, we know, that is is complete. So newline is <em>not</em> a delimiter while ';' is (return command.endsWith(";")).
     * <li>definitions of stored procedures are even more complicated: it depends on the syntax whether a semicolon is part of the
     * command or can be regarded as delimiter. Here, neither ';' nor newline can be regarded as delimiter per-se. Only the Command
     * implementation can decide upon this. In Oracle, a single '/' on one line is used to denote this command-complete.
     * </ul>
     * Note, this method should only apply a very lazy syntax check so it does not get confused and uses too much cycles
     * unecessarily..
     * 
     * @param command
     *            the partial command read so far given to decide by the command whether it is complete or not.
     */
    boolean isComplete(String command);

    /**
     * returns true, if this command requires a valid SQLSession, i.e. if the {@link #execute(SQLSession,String,String)} method
     * makes use of the session (e.g. to get some Database connection) or not. Return 'true' if unsure (you should be sure..). This
     * is to thwart attempts to execute a command without session.
     * 
     * @param cmd
     *            the subcommand this is requested for; one of the commands returned by {@link #getCommandList()}.
     */
    boolean requiresValidSession(String cmd);

    /**
     * shutdown this command. This is called on exit of the CommandDispatcher and allows you to do some cleanup (close connections,
     * flush files..)
     */
    void shutdown();

    /**
     * return a short string describing the purpose of the commands handled by this Command-implementation. This is the string
     * listed in the bare 'help' overview (like <code>'describe a database object'</code>) Should contain no newline, no leading
     * spaces.
     */
    String getShortDescription();

    /**
     * retuns a synopsis-string. The synopsis string returned should follow the following conventions:
     * <ul>
     * <li>expected parameters are described with angle brackets like in <code>
     * export-xml &lt;table&gt; &lt;filename&gt;</code></li>
     * <li>optional parameters are described with square brackets like in <code>help [command]</code></li>
     * </ul>
     * <p>
     * Should contain no newline, no leading spaces. This synopsis is printed in the detailed help of a command or if the
     * execute()-method returned a SYNTAX_ERROR.
     * 
     * @param cmd
     *            the command the synopsis is for. This is one of the possible commands returned by {@link #getCommandList()}.
     */
    String getSynopsis(String cmd);

    /**
     * returns a longer string describing this action. This should return a String describing details of the given command. This
     * String should start with a TAB-character in each new line (the first line is a new line). The last line should not end with
     * newline.
     * 
     * @param cmd
     *            The command the long description is asked for. This is one of the possible commands returned by
     *            {@link #getCommandList()}.
     */
    String getLongDescription(String cmd);

    /**
     * This method is called before parsing the commandline. You can register your command-specific options here and handle them in
     * {@link #handleCommandline(CommandLine)}.
     */
    Collection<Option> getHandledCommandLineOptions();

    /**
     * After parsing the parameters, this method is called.
     * 
     * there can be some default options left. These are set to the commands through this method. This is only for compatibility
     * with the old commandline options, please use named options only!
     * 
     * @param line
     *            TODO
     */
    void handleCommandline(CommandLine line);
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
