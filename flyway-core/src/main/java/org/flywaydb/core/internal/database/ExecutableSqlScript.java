/*
 * Copyright 2010-2018 Boxfuse GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.core.internal.database;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.errorhandler.ErrorHandler;
import org.flywaydb.core.api.errorhandler.Warning;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;
import org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException;
import org.flywaydb.core.internal.sqlscript.SqlStatement;
import org.flywaydb.core.internal.util.AsciiTable;
import org.flywaydb.core.internal.util.IOUtils;
import org.flywaydb.core.internal.util.StringUtils;
import org.flywaydb.core.internal.util.jdbc.ContextImpl;
import org.flywaydb.core.internal.util.jdbc.ErrorImpl;
import org.flywaydb.core.internal.util.jdbc.JdbcTemplate;
import org.flywaydb.core.internal.util.jdbc.Result;
import org.flywaydb.core.internal.util.line.Line;
import org.flywaydb.core.internal.util.line.LineReader;
import org.flywaydb.core.internal.util.line.PlaceholderReplacingLine;
import org.flywaydb.core.internal.util.placeholder.PlaceholderReplacer;
import org.flywaydb.core.internal.util.scanner.LoadableResource;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Sql script containing a series of statements terminated by a delimiter (eg: ;).
 * Single-line (--) and multi-line (/* * /) comments are stripped and ignored.
 */
public abstract class ExecutableSqlScript<C extends ContextImpl> extends SqlScript<C> {
    private static final Log LOG = LogFactory.getLog(ExecutableSqlScript.class);

    /**
     * The configuration to use.
     */
    protected final Configuration configuration;























    /**
     * Whether to allow mixing transactional and non-transactional statements within the same migration.
     */
    private final boolean mixed;

    /**
     * The sql statements contained in this script.
     */
    private List<SqlStatement<C>> sqlStatements;

    /**
     * Whether this SQL script contains at least one transactional statement.
     */
    private boolean transactionalStatementFound;

    /**
     * Whether this SQL script contains at least one non-transactional statement.
     */
    private boolean nonTransactionalStatementFound;

    /**
     * Creates a new sql script from this source.
     *
     * @param configuration              The configuration to use.
     * @param resource                   The sql script resource.
     * @param mixed                      Whether to allow mixing transactional and non-transactional statements within the same migration.





     * @param placeholderReplacer        The placeholder replacer to use.
     */
    public ExecutableSqlScript(Configuration configuration, LoadableResource resource, boolean mixed




            , PlaceholderReplacer placeholderReplacer
    ) {
        super(resource, placeholderReplacer);
        this.configuration = configuration;
        this.mixed = mixed;







        LOG.debug("Parsing " + resource.getFilename() + " ...");
        LineReader reader = null;
        try {
            reader = resource.loadAsString();
            this.sqlStatements = extractStatements(reader);
        } finally {
            IOUtils.close(reader);
        }
    }

    /**
     * Parses the textual data provided by this reader into a list of statements.
     *
     * @param reader The reader for the textual data.
     * @return The list of statements (in order).
     * @throws IllegalStateException Thrown when the textual data parsing failed.
     */
    private List<SqlStatement<C>> extractStatements(LineReader reader) {
        Line line;

        List<SqlStatement<C>> statements = new ArrayList<>();

        Delimiter nonStandardDelimiter = null;
        SqlStatementBuilder sqlStatementBuilder = createSqlStatementBuilder();

        while ((line = reader.readLine()) != null) {
            line = new PlaceholderReplacingLine(line, placeholderReplacer);
            String lineStr = line.getLine();
            if (sqlStatementBuilder.isEmpty() && !StringUtils.hasText(lineStr)) {
                // Skip empty line between statements.
                continue;
            }

            if (!sqlStatementBuilder.hasNonCommentPart()) {
                Delimiter newDelimiter = sqlStatementBuilder.extractNewDelimiterFromLine(lineStr);
                if (newDelimiter != null) {
                    nonStandardDelimiter = newDelimiter;
                    // Skip this line as it was an explicit delimiter change directive outside of any statements.
                    continue;
                }

                // Start a new statement, marking it with this line number.
                if (nonStandardDelimiter != null) {
                    sqlStatementBuilder.setDelimiter(nonStandardDelimiter);
                }
            }

            try {
                sqlStatementBuilder.addLine(line);
            } catch (Exception e) {
                throw new FlywayException("Flyway parsing bug (" + e.getMessage() + ") at line " + line.getLineNumber() + ": " + lineStr, e);
            }

            if (sqlStatementBuilder.canDiscard()) {
                sqlStatementBuilder = createSqlStatementBuilder();
            } else if (sqlStatementBuilder.isTerminated()) {
                addStatement(statements, sqlStatementBuilder);
                sqlStatementBuilder = createSqlStatementBuilder();
            }
        }

        // Catch any statements not followed by delimiter.
        if (!sqlStatementBuilder.isEmpty() && sqlStatementBuilder.hasNonCommentPart()) {
            addStatement(statements, sqlStatementBuilder);
        }

        return statements;
    }

    @Override
    public boolean executeInTransaction() {
        return !nonTransactionalStatementFound;
    }

    @Override
    public List<SqlStatement<C>> getSqlStatements() {
        return sqlStatements;
    }

    @Override
    public void execute(final JdbcTemplate jdbcTemplate) {









        for (int i = 0; i < getSqlStatements().size(); i++) {
            SqlStatement<C> sqlStatement = getSqlStatements().get(i);
            String sql = sqlStatement.getSql();
            if (LOG.isDebugEnabled()) {
                LOG.debug("Executing "



                        + "SQL: " + sql);
            }














            executeStatement(jdbcTemplate, sqlStatement);
        }
    }



























    private void executeStatement(JdbcTemplate jdbcTemplate, SqlStatement<C> sqlStatement) {
        C context = createContext();

        try {
            List<Result> results = sqlStatement.execute(context, jdbcTemplate);






            printWarnings(context);
            handleResults(results);
        } catch (final SQLException e) {










            printWarnings(context);
            handleException(e, sqlStatement, context);
        }
    }

    private void handleResults(List<Result> results) {
        for (Result result : results) {
            long updateCount = result.getUpdateCount();
            if (updateCount != -1) {
                handleUpdateCount(updateCount);
            }






        }
    }

    private void handleUpdateCount(long updateCount) {
        LOG.debug("Update Count: " + updateCount);
    }

    protected void handleException(SQLException e, SqlStatement sqlStatement, C context) {
        throw new FlywaySqlScriptException(resource, sqlStatement, e);
    }

    protected C createContext() {
        //noinspection unchecked
        return (C) new ContextImpl();
    }




























    private void printWarnings(C context) {
        for (Warning warning : context.getWarnings()) {
            if ("00000".equals(warning.getState())) {
                LOG.info("DB: " + warning.getMessage());
            } else {
                LOG.warn("DB: " + warning.getMessage()
                        + " (SQL State: " + warning.getState() + " - Error Code: " + warning.getCode() + ")");
            }
        }
    }

    protected abstract SqlStatementBuilder createSqlStatementBuilder();

    private void addStatement(List<SqlStatement<C>> statements, SqlStatementBuilder sqlStatementBuilder) {
        SqlStatement<C> sqlStatement = sqlStatementBuilder.getSqlStatement();
        statements.add(sqlStatement);

        if (sqlStatementBuilder.executeInTransaction()) {
            transactionalStatementFound = true;
        } else {
            nonTransactionalStatementFound = true;
        }

        if (!mixed && transactionalStatementFound && nonTransactionalStatementFound) {
            throw new FlywayException(
                    "Detected both transactional and non-transactional statements within the same migration"
                            + " (even though mixed is false). Offending statement found at line "
                            + sqlStatement.getLineNumber() + ": " + sqlStatement.getSql()
                            + (sqlStatementBuilder.executeInTransaction() ? "" : " [non-transactional]"));
        }

        LOG.debug("Found statement at line " + sqlStatement.getLineNumber() + ": " + sqlStatement.getSql()
                + (sqlStatementBuilder.executeInTransaction() ? "" : " [non-transactional]"));
    }
}