package io.camunda.bizsol.bb.ai_firewall_agent.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Logback filter that DENYs any log event whose message contains a configured substring. No extra
 * dependencies (like janino) required.
 *
 * <p>Usage in logback-test.xml:
 *
 * <pre>{@code
 * <filter class="io.camunda.bizsol.bb.ai_firewall_agent.config.MessageSubstringFilter">
 *     <substring>CorrelatedMessageSubscriptionMapper.updateHistoryCleanupDate</substring>
 * </filter>
 * }</pre>
 */
public class MessageSubstringFilter extends Filter<ILoggingEvent> {

    private String substring;

    public void setSubstring(String substring) {
        this.substring = substring;
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (substring != null && event.getFormattedMessage().contains(substring)) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
