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

package org.springframework.boot.actuate.autoconfigure.observation.web.reactive;

import java.nio.file.Path;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;

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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationConvention;
import org.springframework.web.filter.reactive.ServerHttpObservationFilter;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for instrumentation of Spring
 * WebFlux applications.
 *
 * @author Brian Clozel
 * @author Jon Schneider
 * @author Dmytro Nosan
 * @author Jonatan Ivanov
 * @since 3.0.0
 */
public class WebFluxObservationAutoConfiguration {

	private final ObservationProperties observationProperties;

	public WebFluxObservationAutoConfiguration(ObservationProperties observationProperties) {
		this.observationProperties = observationProperties;
	}

	public ServerHttpObservationFilter webfluxObservationFilter(ObservationRegistry registry, ObjectProvider customConvention) {
		String name = this.observationProperties.getHttp().getServer().getRequests().getName();
		ServerRequestObservationConvention convention = customConvention
				.getIfAvailable(() -> new DefaultServerRequestObservationConvention(name));
		return new ServerHttpObservationFilter(registry, convention);
	}

	public MeterFilter metricsHttpServerUriTagFilter(MetricsProperties metricsProperties, ObservationProperties observationProperties) {
		String name = observationProperties.getHttp().getServer().getRequests().getName();
		MeterFilter filter = new OnlyOnceLoggingDenyMeterFilter(
				() -> "Reached the maximum number of URI tags for '%s'.".formatted(name));
		return MeterFilter.maximumAllowableTags(name, "uri", metricsProperties.getWeb().getServer().getMaxUriTags(),
				filter);
	}

	public MeterFilter metricsHttpServerUriTagFilter(MetricsProperties metricsProperties) {
		String name = observationProperties.getHttp().getServer().getRequests().getName();
		MeterFilter filter = new OnlyOnceLoggingDenyMeterFilter(
				() -> "Reached the maximum number of URI tags for '%s'.".formatted(name));
		MeterFilter filter = new OnlyOnceLoggingDenyMeterFilter(
				() -> "Reached the maximum number of URI tags for '%s'.".formatted(name));
		return MeterFilter.maximumAllowableTags(name, "uri", metricsProperties.getWeb().getServer().getMaxUriTags(),
				filter);
	}

	public ObservationPredicate actuatorWebEndpointObservationPredicate(WebFluxProperties webFluxProperties, PathMappedEndpoints pathMappedEndpoints) {
		return (name, context) -> {
			if (context instanceof ServerRequestObservationContext serverContext) {
				String endpointPath = getEndpointPath(webFluxProperties, pathMappedEndpoints);
				return !serverContext.getCarrier().getURI().getPath().startsWith(endpointPath);
			}
			return true;
		};

	}

	private static String getEndpointPath(WebFluxProperties webFluxProperties, PathMappedEndpoints pathMappedEndpoints) {
		String webFluxBasePath = getWebFluxBasePath(webFluxProperties);
		return Path.of(webFluxBasePath, pathMappedEndpoints.getBasePath()).toString();
	}

	private static String getWebFluxBasePath(WebFluxProperties webFluxProperties) {
		return (webFluxProperties.getBasePath() != null) ? webFluxProperties.getBasePath() : "";
	}

}
