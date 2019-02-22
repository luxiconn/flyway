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
package org.flywaydb.core.api.logging;

import org.flywaydb.core.internal.util.ClassUtils;
import org.flywaydb.core.internal.util.FeatureDetector;
import org.flywaydb.core.internal.util.logging.android.AndroidLogCreator;
import org.flywaydb.core.internal.util.logging.apachecommons.ApacheCommonsLogCreator;
import org.flywaydb.core.internal.util.logging.javautil.JavaUtilLogCreator;
import org.flywaydb.core.internal.util.logging.slf4j.Slf4jLogCreator;

/**
 * Factory for loggers. Custom MigrationResolver, MigrationExecutor, FlywayCallback, ErrorHandler and JdbcMigration
 * implementations should use this to obtain a logger that will work with any logging framework across all environments
 * (API, Maven, Gradle, CLI, etc).
 */
public class LogFactory {
    /**
     * Factory for implementation-specific loggers.
     */
    private static LogCreator logCreator;

    /**
     * The factory for implementation-specific loggers to be used as a fallback when no other suitable loggers were found.
     */
    private static LogCreator fallbackLogCreator;

    /**
     * Prevent instantiation.
     */
    private LogFactory() {
        // Do nothing
    }

    /**
     * @param logCreator The factory for implementation-specific loggers.
     */
    public static void setLogCreator(LogCreator logCreator) {
        LogFactory.logCreator = logCreator;
    }

    /**
     * @param fallbackLogCreator The factory for implementation-specific loggers to be used as a fallback when no other
     *                           suitable loggers were found.
     */
    public static void setFallbackLogCreator(LogCreator fallbackLogCreator) {
        LogFactory.fallbackLogCreator = fallbackLogCreator;
    }

    /**
     * Retrieves the matching logger for this class.
     *
     * @param clazz The class to get the logger for.
     * @return The logger.
     */
    public static Log getLog(Class<?> clazz) {
        if (logCreator == null) {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            FeatureDetector featureDetector = new FeatureDetector(classLoader);
            if (featureDetector.isAndroidAvailable()) {
                logCreator = ClassUtils.instantiate(AndroidLogCreator.class.getName(), classLoader);
            } else if (featureDetector.isSlf4jAvailable()) {
                logCreator = ClassUtils.instantiate(Slf4jLogCreator.class.getName(), classLoader);
            } else if (featureDetector.isApacheCommonsLoggingAvailable()) {
                logCreator = ClassUtils.instantiate(ApacheCommonsLogCreator.class.getName(), classLoader);
            } else if (fallbackLogCreator == null) {
                logCreator = ClassUtils.instantiate(JavaUtilLogCreator.class.getName(), classLoader);
            } else {
                logCreator = fallbackLogCreator;
            }
        }

        return logCreator.createLogger(clazz);
    }
}