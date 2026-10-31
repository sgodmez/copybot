package com.copybot.logger;

import com.copybot.resources.ResourcesEngine;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Can either log to :
 * - Standard output
 * - Via JUL
 * - Via UI
 * regarding of config or context.
 * <p>
 * Force use of our i18n engine.
 */
public class CopybotLogger {

    //TODO : possibilité d'enregistrer un watcher pour l'UI ?

    private Logger julLogger;

    public static CopybotLogger getLogger(String name) {
        return new CopybotLogger(name);
    }

    public static CopybotLogger getLogger(Class clazz) {
        return new CopybotLogger(clazz.getCanonicalName());
    }

    private CopybotLogger(String name) {
        julLogger = Logger.getLogger(name);
    }

    public void userMsg(String msgKey, Object... args) {
        System.out.println(ResourcesEngine.getString(msgKey, args));
    }

    public void error(String msgKey, Object... args) {
        log(Level.SEVERE, msgKey, args);
    }

    public void error(Throwable e, String msgKey, Object... args) {
        log(Level.SEVERE, e, msgKey, args);
    }

    public void warn(String msgKey, Object... args) {
        log(Level.WARNING, msgKey, args);
    }

    public void warn(Throwable e, String msgKey, Object... args) {
        log(Level.WARNING, e, msgKey, args);
    }

    public void info(String msgKey, Object... args) {
        log(Level.INFO, msgKey, args);
    }

    public void info(Throwable e, String msgKey, Object... args) {
        log(Level.INFO, e, msgKey, args);
    }

    public void debug(String msgKey, Object... args) {
        log(Level.FINE, msgKey, args);
    }

    public void debug(Throwable e, String msgKey, Object... args) {
        log(Level.FINE, e, msgKey, args);
    }

    public void trace(String msgKey, Object... args) {
        log(Level.FINEST, msgKey, args);
    }

    public void trace(Throwable e, String msgKey, Object... args) {
        log(Level.FINEST, e, msgKey, args);
    }

    public void log(Level level, String msgKey, Object... args) {
        julLogger.log(level, () -> ResourcesEngine.getString(msgKey, args));
    }

    public void log(Level level, Throwable e, String msgKey, Object... args) {
        julLogger.log(level, e, () -> ResourcesEngine.getString(msgKey, args));
    }
}
