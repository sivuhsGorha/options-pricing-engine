package com.sbk.optionspricer.config;

/** A configuration file that cannot be read, parsed or interpreted. The message names the file, line or key. */
public class ConfigException extends IllegalArgumentException {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
