package com.sbk.optionspricer.alert;

/** Where a message to the operator's phone goes: title, text, priority (ntfy's words) and tags; true when it was delivered. */
@FunctionalInterface
public interface Notifier {
    boolean send(String title, String message, String priority, String tags);
}
