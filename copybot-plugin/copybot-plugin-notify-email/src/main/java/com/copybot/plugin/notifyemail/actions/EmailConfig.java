package com.copybot.plugin.notifyemail.actions;

import com.copybot.plugin.api.config.AllowedValues;
import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.config.Secret;

import java.util.List;
import java.util.Objects;

/**
 * The "actionConfig" of email.send.
 *
 * @param host     the SMTP server
 * @param port     its port; absent: 465 with ssl, 587 otherwise
 * @param security starttls (required, not merely tried), ssl (TLS from the start, usually port 465) or none
 * @param user     the login on the server; absent or blank: no authentication. Never put in a message or a log.
 * @param password its password, usually ${secret:name}. Never put in a message or a log.
 * @param from     the sender address
 * @param to       the recipients, one address each
 * @param subject  a template: {pipeline} {outcome} {written} {skipped} {errors} {duration} {output:key}
 * @param details  the items listed in the message: none, errors (the items in error) or all
 */
public record EmailConfig(
        @Required
        String host,

        // no @DefaultValue: the default depends on the security
        Integer port,

        @AllowedValues({"starttls", "ssl", "none"}) @DefaultValue("starttls")
        String security,

        String user,

        @Secret
        String password,

        @Required
        String from,

        @Required
        List<String> to,

        @DefaultValue("Copybot \u2014 {pipeline} : {outcome}")
        String subject,

        @AllowedValues({"none", "errors", "all"}) @DefaultValue("errors")
        String details
) {

    public static final String DEFAULT_SUBJECT = "Copybot \u2014 {pipeline} : {outcome}";

    public EmailConfig {
        to = to == null ? List.of() : to.stream().filter(Objects::nonNull).toList();
    }

    /** Neither the user nor the password: the server, the security and the number of recipients only. */
    @Override
    public String toString() {
        return "EmailConfig[host=" + host + ", port=" + port + ", security=" + security + ", to=" + to.size()
                + ", details=" + details + "]";
    }
}
