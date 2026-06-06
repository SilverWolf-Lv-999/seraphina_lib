package seraphina.seraphina_lib.mixin.service;

import seraphina.seraphina_lib.logger.Logger;
import seraphina.seraphina_lib.logger.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

final class SeraMixinLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("SeraMixin");

    private SeraMixinLogger() {
    }

    static void info(String message, Object... args) {
        LOGGER.info(message, args);
    }

    static void warn(String message, Object... args) {
        LOGGER.warn(message, args);
    }

    static void error(String message, Object... args) {
        LOGGER.error(message, args);
    }

    static void exception(Throwable throwable) {
        if (throwable == null) {
            return;
        }
        LOGGER.error(stackTraceOf(throwable));
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
