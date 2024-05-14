/*
 * Copyright 2012-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.autoconfigure.observation.web.servlet;

import java.nio.file.Path;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsProperties;
import org.springframework.boot.actuate.autoconfigure.metrics.OnlyOnceLoggingDenyMeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationProperties;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.Servlet;
import org.springframework.boot.autoconfigure.web.servlet.ConditionalOnMissingFilterBean;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;
import org.springframework.web.filter.ServerHttpObservationFilter;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for instrumentation of Spring Web
 * MVC servlet-based request mappings.
 *
 * @author Brian Clozel
 * @author Jon Schneider
 * @author Dmytro Nosan
 * @author Jonatan Ivanov
 * @since 3.0.0
 */
public class WebMvcObservationAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	/**
	* Creates a FilterRegistrationBean that registers the ServerHttpObservationFilter for web request observation.
	* @param registry The registry for observation.
	* @param customConvention The provider for custom conventions.
	* @param observationProperties The properties for observation.
	* @return The FilterRegistrationBean for ServerHttpObservationFilter.
	*/
	public FilterRegistrationBean<ServerHttpObservationFilter> webMvcObservationFilter(ObservationRegistry registry, ObjectProvider customConvention, ObservationProperties observationProperties) {
		String name = observationProperties.getHttp().getServer().getRequests().getName();
		ServerRequestObservationConvention convention = customConvention
				.getIfAvailable(() -> new DefaultServerRequestObservationConvention(name));
		ServerHttpObservationFilter filter = new ServerHttpObservationFilter(registry, convention);
		FilterRegistrationBean<ServerHttpObservationFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
		registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
		return registration;
	}
	
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	/**
	* Creates a MeterFilter to filter metrics based on HTTP server URI tags.
	* @param observationProperties The properties for observation.
	* @param metricsProperties The properties for metrics.
	* @return The MeterFilter for HTTP server URI tags.
	*/
	public MeterFilter metricsHttpServerUriTagFilter(ObservationProperties observationProperties, MetricsProperties metricsProperties) {
		String name = observationProperties.getHttp().getServer().getRequests().getName();
		MeterFilter filter = new OnlyOnceLoggingDenyMeterFilter(
				() -> String.format("Reached the maximum number of URI tags for '%s'.", name));
		return MeterFilter.maximumAllowableTags(name, "uri", metricsProperties.getWeb().getServer().getMaxUriTags(),
				filter);
	}

	/**
	* Creates an ObservationPredicate to filter observations based on web endpoint paths.
	* @param serverProperties The properties for server.
	* @param webMvcProperties The properties for Web MVC.
	* @param pathMappedEndpoints The endpoints mapped by path.
	* @return The ObservationPredicate for web endpoint paths.
	*/
	public ObservationPredicate actuatorWebEndpointObservationPredicate(ServerProperties serverProperties, WebMvcProperties webMvcProperties, PathMappedEndpoints pathMappedEndpoints) {
		return (name, context) -> {
			if (context instanceof ServerRequestObservationContext serverContext) {
				String endpointPath = getEndpointPath(serverProperties, webMvcProperties, pathMappedEndpoints);
				return !serverContext.getCarrier().getRequestURI().startsWith(endpointPath);
			}
			return true;
		};
	}

	/**
	* Gets the endpoint path based on server properties, Web MVC properties, and mapped endpoints.
	* @param serverProperties The properties for server.
	* @param webMvcProperties The properties for Web MVC.
	* @param pathMappedEndpoints The endpoints mapped by path.
	* @return The endpoint path.
	*/
	private static String getEndpointPath(ServerProperties serverProperties, WebMvcProperties webMvcProperties, PathMappedEndpoints pathMappedEndpoints) {
		String contextPath = getContextPath(serverProperties);
		String servletPath = getServletPath(webMvcProperties);
		return Path.of(contextPath, servletPath, pathMappedEndpoints.getBasePath()).toString();
	}

	/**
	* Gets the context path from the server properties.
	* @param serverProperties The properties for server.
	* @return The context path.
	*/
	private static String getContextPath(ServerProperties serverProperties) {
		Servlet servlet = serverProperties.getServlet();
		return (servlet.getContextPath() != null) ? servlet.getContextPath() : "";
	}

	/**
	* Gets the servlet path from the Web MVC properties.
	* @param webMvcProperties The properties for Web MVC.
	* @return The servlet path.
	*/
	private static String getServletPath(WebMvcProperties webMvcProperties) {
		WebMvcProperties.Servlet servletProperties = webMvcProperties.getServlet();
		return (servletProperties.getPath() != null) ? servletProperties.getPath() : "";
	}

}
