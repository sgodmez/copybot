package com.copybot.plugin.api.exception;

public class ActionErrorException extends Exception {

    public ActionErrorException(I18NCode message) {
        super(message.code());
    }

    public ActionErrorException(I18NCode message, Throwable cause) {
        super(message.code(), cause);
    }

    public ActionErrorException(Throwable cause) {
        super("error.unknown", cause);
    }

    public record I18NCode(String code) {
        public static I18NCode of(String code) {
            return new I18NCode(code);
        }
    }
}
