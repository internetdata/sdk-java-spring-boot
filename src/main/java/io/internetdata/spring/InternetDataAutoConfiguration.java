package io.internetdata.spring;

import io.internetdata.middleware.Core;
import io.internetdata.middleware.Options;
import io.internetdata.middleware.RequestView;
import io.internetdata.middleware.Selectors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.function.Function;

/**
 * Wires the filter from {@code application.yml}.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so defining your own selector, core or
 * filter replaces ours rather than colliding with it - which is how anything the YAML cannot
 * express (a callable selector, a custom refusal) is done.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "internetdata", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(InternetDataProperties.class)
public class InternetDataAutoConfiguration {
    private static final System.Logger LOG = System.getLogger("io.internetdata");

    private static final Selectors<HttpServletRequest> SELECTORS = new Selectors<>(
            request -> new RequestView(request::getHeader, request::getRemoteAddr));

    /**
     * {@code getRemoteAddr}, the socket peer, unless a header is named.
     *
     * <p>The servlet API has no trusted-proxy setting of its own. Spring Boot's
     * {@code server.forward-headers-strategy=native} is the answer for a chain you trust: on
     * Tomcat it believes {@code X-Forwarded-For} only from an internal proxy, and it rewrites
     * {@code getRemoteAddr} before this ever runs - so setting it makes this default correct
     * rather than being something to work around. {@code framework} takes the header's
     * left-most entry, which the visitor writes.
     */
    @Bean
    @ConditionalOnMissingBean(name = "internetdataIpSelector")
    public Function<HttpServletRequest, String> internetdataIpSelector(
            InternetDataProperties properties) {
        String header = properties.getClientIpHeader();
        return header == null ? SELECTORS.defaultSelector() : SELECTORS.header(header);
    }

    @Bean
    @ConditionalOnMissingBean
    public Core<HttpServletRequest> internetdataCore(
            InternetDataProperties properties,
            Function<HttpServletRequest, String> internetdataIpSelector) {
        Options<HttpServletRequest> options = new Options<HttpServletRequest>()
                .apiKey(apiKey(properties.getApiKey()))
                .baseUrl(properties.getBaseUrl())
                .timeout(properties.getTimeout())
                .retries(properties.getRetries())
                .failClosed(properties.isFailClosed())
                .onMissingField(properties.getOnMissingField())
                .ipSelector(internetdataIpSelector);
        if (properties.getBlockCondition() != null) {
            options.blockCondition(Conditions.typed(properties.getBlockCondition()));
        }
        List<String> skip = properties.getSkipPaths();
        if (!skip.isEmpty()) {
            AntPathMatcher matcher = new AntPathMatcher();
            options.skip(request -> skip.stream()
                    .anyMatch(pattern -> matcher.match(pattern, request.getRequestURI())));
        }
        return new Core<>(options, SELECTORS.defaultSelector());
    }

    /**
     * The configured key, or none.
     *
     * <p>Spring's binder leaves a placeholder it cannot resolve as written, so
     * {@code ${INTERNETDATA_API_KEY}} with the variable unset arrives as that text, which the API
     * refuses on every lookup while the filter fails open and flags nobody. No key contains
     * {@code ${}, so one that does is an unset variable: it goes out as no key, and startup says
     * which variable. Refusing to start instead would make a missing key the app's outage.
     */
    static String apiKey(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        if (configured.contains("${")) {
            LOG.log(System.Logger.Level.WARNING, "internetdata: api-key is " + configured
                    + ", a placeholder nothing resolved, so lookups go without a key, on Unlimited, "
                    + "the free plan. Set the variable it names, or give it an empty default "
                    + "(${INTERNETDATA_API_KEY:}) to run without a key on purpose");
            return null;
        }
        return configured;
    }

    @Bean
    @ConditionalOnMissingBean
    public InternetDataFilter internetdataFilter(Core<HttpServletRequest> internetdataCore) {
        return new InternetDataFilter(internetdataCore, InternetDataFilter::refuse);
    }

    /**
     * Registered high, so a blocked request is refused before the dispatcher does any work, but
     * below Spring Security's own chain (which is {@code HIGHEST_PRECEDENCE + 50} by default) so
     * authentication still answers on its own terms first.
     */
    @Bean
    @ConditionalOnMissingBean(name = "internetdataFilterRegistration")
    public FilterRegistrationBean<InternetDataFilter> internetdataFilterRegistration(
            InternetDataFilter internetdataFilter) {
        FilterRegistrationBean<InternetDataFilter> registration =
                new FilterRegistrationBean<>(internetdataFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return registration;
    }
}
