/*
 * This is free software, licensed under the Gnu Public License (GPL) get a copy from <http://www.gnu.org/licenses/gpl.html> $Id:
 * SQLSession.java,v 1.33 2005-03-24 13:57:46 hzeller Exp $ author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus;

import henplus.property.BooleanPropertyHolder;
import henplus.property.EnumeratedPropertyHolder;
import henplus.sqlmodel.Table;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.SortedSet;

import jline.ConsoleReader;

/**
 * a SQL session.
 */
public class SQLSession implements Interruptable {

    private long _connectTime;
    private long _statementCount;
    private final String _url;
    private String _username;
    private String _password;
    private final String _databaseInfo;
    private Connection _conn;
    private SQLMetaData _metaData;

    private final PropertyRegistry _propertyRegistry;
    private volatile boolean _interrupted;
	private ConsoleReader _console;

    /**
     * creates a new SQL session. Open the database connection, initializes the readline library
     */
    public SQLSession(ConsoleReader console, final String url, final String user, final String password) throws IllegalArgumentException,
            ClassNotFoundException, SQLException, IOException {
    	_console = console;
        _statementCount = 0;
        _conn = null;
        _url = url;
        _username = user;
        _password = password;
        _propertyRegistry = new PropertyRegistry();

        Driver driver = null;
        // HenPlus.msg().println("connect to '" + url + "'");
        driver = DriverManager.getDriver(url);

        HenPlus.msg().println("HenPlus II connecting ");
        HenPlus.msg().println(" url '" + url + '\'');
        HenPlus.msg().println(" driver version " + driver.getMajorVersion() + "." + driver.getMinorVersion());
        connect();

        int currentIsolation = Connection.TRANSACTION_NONE;
        final DatabaseMetaData meta = _conn.getMetaData();
        _databaseInfo = meta.getDatabaseProductName() + " - " + meta.getDatabaseProductVersion();
        HenPlus.msg().println(" " + _databaseInfo);
        try {
            if (meta.supportsTransactions()) {
                currentIsolation = _conn.getTransactionIsolation();
            } else {
                HenPlus.msg().println("no transactions.");
            }
            _conn.setAutoCommit(false);
        } catch (final SQLException ignoreMe) {
        }

        printTransactionIsolation(meta, Connection.TRANSACTION_NONE, "No Transaction", currentIsolation);
        printTransactionIsolation(meta, Connection.TRANSACTION_READ_UNCOMMITTED, "read uncommitted", currentIsolation);
        printTransactionIsolation(meta, Connection.TRANSACTION_READ_COMMITTED, "read committed", currentIsolation);
        printTransactionIsolation(meta, Connection.TRANSACTION_REPEATABLE_READ, "repeatable read", currentIsolation);
        printTransactionIsolation(meta, Connection.TRANSACTION_SERIALIZABLE, "serializable", currentIsolation);

        final Map<String,Integer> availableIsolations = new HashMap<String,Integer>();
        addAvailableIsolation(availableIsolations, meta, Connection.TRANSACTION_NONE, "none");
        addAvailableIsolation(availableIsolations, meta, Connection.TRANSACTION_READ_UNCOMMITTED, "read-uncommitted");
        addAvailableIsolation(availableIsolations, meta, Connection.TRANSACTION_READ_COMMITTED, "read-committed");
        addAvailableIsolation(availableIsolations, meta, Connection.TRANSACTION_REPEATABLE_READ, "repeatable-read");
        addAvailableIsolation(availableIsolations, meta, Connection.TRANSACTION_SERIALIZABLE, "serializable");

        _propertyRegistry.registerProperty("auto-commit", new AutoCommitProperty());
        _propertyRegistry.registerProperty("read-only", new ReadOnlyProperty());
        _propertyRegistry.registerProperty("isolation-level", new IsolationLevelProperty(availableIsolations, currentIsolation));
    }

    private void printTransactionIsolation(final DatabaseMetaData meta, final int iLevel, final String descript, final int current)
            throws SQLException {
        if (meta.supportsTransactionIsolationLevel(iLevel)) {
            HenPlus.msg().println(" " + descript + (current == iLevel ? " *" : " "));
        }
    }

    private void addAvailableIsolation(final Map<String,Integer> result, final DatabaseMetaData meta, final int iLevel, final String key)
            throws SQLException {
        if (meta.supportsTransactionIsolationLevel(iLevel)) {
            result.put(key, new Integer(iLevel));
        }
    }

    public PropertyRegistry getPropertyRegistry() {
        return _propertyRegistry;
    }

    public String getDatabaseInfo() {
        return _databaseInfo;
    }

    public String getURL() {
        return _url;
    }

    public SQLMetaData getMetaData(final SortedSet<String> tableNames) {
        if (_metaData == null) {
            _metaData = new SQLMetaDataBuilder().getMetaData(this, tableNames);
        }
        return _metaData;
    }

    public Table getTable(final String tableName) {
        return new SQLMetaDataBuilder().getTable(this, tableName);
    }

    public boolean printMessages() {
        return !HenPlus.getInstance().getDispatcher().isInBatch() && !HenPlus.getInstance().isQuiet();
    }

    public void print(final String msg) {
        if (printMessages()) {
            HenPlus.msg().print(msg);
        }
    }

    public void println(final String msg) {
        if (printMessages()) {
            HenPlus.msg().println(msg);
        }
    }

    public void connect() throws SQLException, IOException {
        /*
         * close old connection ..
         */
        if (_conn != null) {
            try {
                _conn.close();
            } catch (final Throwable t) { /* ignore */
            }
            _conn = null;
        }

        final Properties props = new Properties();
        /*
         * FIXME make generic plugin for specific database drivers that handle
         * the specific stuff. For now this is a quick hack.
         */
        if (_url.startsWith("jdbc:oracle:")) {
            /*
             * this is needed to make comment in oracle show up in the remarks
             * http://forums.oracle.com/forums/thread.jsp?forum=99&thread=225790
             */
            props.setProperty("remarksReporting", "true");
        }

        /*
         * try to connect directly with the url. Several JDBC-Drivers allow to
         * embed the username and password directly in the URL.
         */
        if (_username == null || _password == null) {
            try {
                _conn = DriverManager.getConnection(_url, props);
            } catch (final SQLException e) {
                HenPlus.msg().println(e.getMessage());
                // only query terminals.
                if (HenPlus.msg().isTerminal()) {
                    promptUserPassword();
                }
            }
        }

        if (_conn == null) {
            _conn = DriverManager.getConnection(_url, _username, _password);
        }

        if (_conn != null && _username == null) {
            try {
                final DatabaseMetaData meta = _conn.getMetaData();
                if (meta != null) {
                    _username = meta.getUserName();
                }
            } catch (final Exception e) {
                /* ok .. at least I tried */
            }
        }
        _connectTime = System.currentTimeMillis();
    }

    private void promptUserPassword() throws IOException {
        HenPlus.msg().println("============ authorization required ===");
        _interrupted = false;
        try {
            SigIntHandler.getInstance().pushInterruptable(this);
            _username = _console.readLine("Username: ");
            if (_interrupted) {
                throw new IOException("connect interrupted ..");
            }
            _password = promptPassword("Password: ");
            if (_interrupted) {
                throw new IOException("connect interrupted ..");
            }
        } finally {
            SigIntHandler.getInstance().popInterruptable();
        }
    }

    private String promptPassword(final String prompt) throws IOException {
    	try {
            SigIntHandler.getInstance().pushInterruptable(this);
            String password =  _console.readLine("Password: ", Character.valueOf('*'));
            if (_interrupted) {
                throw new IOException("connect interrupted ..");
            }
            return password;
        } finally {
            SigIntHandler.getInstance().popInterruptable();
        }
    }

    // -- Interruptable interface
    @Override
    public void interrupt() {
        _interrupted = true;
        HenPlus.msg().attributeBold();
        HenPlus.msg().println(" interrupted; press [RETURN]");
        HenPlus.msg().attributeReset();
    }

    /**
     * return username, if known.
     */
    public String getUsername() {
        return _username;
    }

    public long getUptime() {
        return System.currentTimeMillis() - _connectTime;
    }

    public long getStatementCount() {
        return _statementCount;
    }

    public void close() {
        try {
            getConnection().close();
            _conn = null;
        } catch (final Exception e) {
            HenPlus.msg().println(e.toString()); // don't care
        }
    }

    /**
     * returns the current connection of this session.
     */
    public Connection getConnection() {
        return _conn;
    }

    public Statement createStatement() {
        Statement result = null;
        int retries = 2;
        try {
            if (_conn.isClosed()) {
                HenPlus.msg().println("connection is closed; reconnect.");
                connect();
                --retries;
            }
        } catch (final Exception e) { /* ign */
        }

        while (retries > 0) {
            try {
                result = _conn.createStatement();
                ++_statementCount;
                break;
            } catch (final Throwable t) {
                HenPlus.msg().println("connection failure. Try to reconnect.");
                try {
                    connect();
                } catch (final Exception e) { /* ign */
                }
            }
            --retries;
        }
        return result;
    }

    /* ------- Session Properties ----------------------------------- */

    private class ReadOnlyProperty extends BooleanPropertyHolder {

        ReadOnlyProperty() {
            super(false);
            propertyValue = "off"; // 'off' sounds better in this context.
        }

        @Override
        public void booleanPropertyChanged(final boolean switchOn) throws Exception {
            /*
             * readonly requires a closed transaction.
             */
            if (!switchOn) {
                getConnection().rollback(); // save choice.
            } else {
                /*
                 * if we switched off and the user has not closed the current
                 * transaction, setting readonly will throw an exception and
                 * will notify the user about what to do..
                 */
            }
            getConnection().setReadOnly(switchOn);
            if (getConnection().isReadOnly() != switchOn) {
                throw new Exception("JDBC-Driver ignores request; transaction closed before ?");
            }
        }

        @Override
        public String getDefaultValue() {
            return "off";
        }

        @Override
        public String getShortDescription() {
            return "Switches on read only mode for optimizations.";
        }
    }

    private class AutoCommitProperty extends BooleanPropertyHolder {

        AutoCommitProperty() {
            super(false);
            propertyValue = "off"; // 'off' sounds better in this context.
        }

        @Override
        public void booleanPropertyChanged(final boolean switchOn) throws Exception {
            /*
             * due to a bug in Sybase, we have to close the transaction first
             * before setting autcommit. This is probably a save choice to do,
             * since the user asks for autocommit..
             */
            if (switchOn) {
                getConnection().commit();
            }
            getConnection().setAutoCommit(switchOn);
            if (getConnection().getAutoCommit() != switchOn) {
                throw new Exception("JDBC-Driver ignores request");
            }
        }

        @Override
        public String getDefaultValue() {
            return "off";
        }

        @Override
        public String getShortDescription() {
            return "Switches auto commit";
        }
    }

    private class IsolationLevelProperty extends EnumeratedPropertyHolder {

        private final Map<String, Integer> _availableValues;
        private final String _initialValue;

        IsolationLevelProperty(final Map<String, Integer> availableValues, final int currentValue) {
            super(availableValues.keySet());
            _availableValues = availableValues;

            // sequential search .. doesn't matter, not much do do
            String initValue = null;
            for (Entry<String,Integer> entry : availableValues.entrySet()) {
                final Integer isolationLevel = entry.getValue();
                if (isolationLevel.intValue() == currentValue) {
                    initValue = entry.getKey();
                    break;
                }
            }
            propertyValue = _initialValue = initValue;
        }

        @Override
        public String getDefaultValue() {
            return _initialValue;
        }

        @Override
        protected void enumeratedPropertyChanged(final int index, final String value) throws Exception {
            final Integer isolationLevel = _availableValues.get(value);
            if (isolationLevel == null) {
                throw new IllegalArgumentException("invalid value");
            }
            final int isolation = isolationLevel.intValue();
            getConnection().setTransactionIsolation(isolation);
            if (getConnection().getTransactionIsolation() != isolation) {
                throw new Exception("JDBC-Driver ignores request");
            }
        }

        @Override
        public String getShortDescription() {
            return "sets the transaction isolation level";
        }
    }
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */

