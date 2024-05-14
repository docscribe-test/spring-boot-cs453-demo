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

import java.util.EnumSet;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.servlet.WebMvcEndpointManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.test.MetricsRun;
import org.springframework.boot.actuate.autoconfigure.metrics.web.TestController;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.ServerHttpObservationFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link WebMvcObservationAutoConfiguration}.
 *
 * @author Andy Wilkinson
 * @author Dmytro Nosan
 * @author Tadaya Tsuyukubo
 * @author Madhura Bhave
 * @author Chanhyeong LEE
 * @author Jonatan Ivanov
 */
class WebMvcObservationAutoConfigurationTests {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
			.with(MetricsRun.simple())
			.withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class))
			.withConfiguration(AutoConfigurations.of(WebMvcObservationAutoConfiguration.class));

	/**
	 * Verifies that the configuration backs off when MeterRegistry is missing.
	 */
	void backsOffWhenMeterRegistryIsMissing() {
		new WebApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(WebMvcObservationAutoConfiguration.class))
				.run((context) -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class));
	}

	/**
	 * Verifies that the configuration defines a filter when the registry is present.
	 */
	void definesFilterWhenRegistryIsPresent() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasSingleBean(FilterRegistrationBean.class);
			assertThat(context.getBean(FilterRegistrationBean.class).getFilter())
					.isInstanceOf(ServerHttpObservationFilter.class);
		});
	}

	/**
	 * Verifies the behavior when a custom convention is present.
	 */
	void customConventionWhenPresent() {
		this.contextRunner.withUserConfiguration(CustomConventionConfiguration.class)
				.run((context) -> assertThat(context.getBean(FilterRegistrationBean.class).getFilter())
						.extracting("observationConvention")
						.isInstanceOf(CustomConvention.class));
	}

	/**
	 * Verifies that the filter registration has the expected dispatcher types and order.
	 */
	void filterRegistrationHasExpectedDispatcherTypesAndOrder() {
		this.contextRunner.run((context) -> {
			FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
			assertThat(registration).hasFieldOrPropertyWithValue("dispatcherTypes",
					EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
			assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
		});
	}

	/**
	 * Verifies that the filter registration backs off when another ServerHttpObservationFilter registration is present.
	 */
	void filterRegistrationBacksOffWithAnotherServerHttpObservationFilterRegistration() {
		this.contextRunner.withUserConfiguration(TestServerHttpObservationFilterRegistrationConfiguration.class)
				.run((context) -> {
					assertThat(context).hasSingleBean(FilterRegistrationBean.class);
					assertThat(context.getBean(FilterRegistrationBean.class))
							.isSameAs(context.getBean("testServerHttpObservationFilter"));
				});
	}

	/**
	 * Verifies that the filter registration backs off when another ServerHttpObservationFilter is present.
	 */
	void filterRegistrationBacksOffWithAnotherServerHttpObservationFilter() {
		this.contextRunner.withUserConfiguration(TestServerHttpObservationFilterConfiguration.class)
				.run((context) -> assertThat(context).doesNotHaveBean(FilterRegistrationBean.class)
						.hasSingleBean(ServerHttpObservationFilter.class));
	}

	/**
	 * Verifies that the filter registration does not back off when other filter registrations are present.
	 */
	void filterRegistrationDoesNotBackOffWithOtherFilterRegistration() {
		this.contextRunner.withUserConfiguration(TestFilterRegistrationConfiguration.class)
				.run((context) -> assertThat(context).hasBean("testFilter").hasBean("webMvcObservationFilter"));
	}

	/**
	 * Verifies that the filter registration does not back off when other filters are present.
	 */
	void filterRegistrationDoesNotBackOffWithOtherFilter() {
		this.contextRunner.withUserConfiguration(TestFilterConfiguration.class)
				.run((context) -> assertThat(context).hasBean("testFilter").hasBean("webMvcObservationFilter"));
	}

	/**
	 * Verifies the behavior when the maximum number of URIs is reached and further URIs are denied.
	 * @param output The output stream to capture the output.
	 */
	void afterMaxUrisReachedFurtherUrisAreDenied(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(TestController.class)
				.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, ObservationAutoConfiguration.class,
						WebMvcAutoConfiguration.class))
				.withPropertyValues("management.metrics.web.server.max-uri-tags=2")
				.run((context) -> {
					MeterRegistry registry = getInitializedMeterRegistry(context);
					assertThat(registry.get("http.server.requests").meters()).hasSizeLessThanOrEqualTo(2);
					assertThat(output).contains("Reached the maximum number of URI tags for 'http.server.requests'");
				});
	}

	/**
	 * Verifies the behavior when the maximum number of URIs is reached and further URIs are denied using a custom observation name.
	 * @param output The output stream to capture the output.
	 */
	void afterMaxUrisReachedFurtherUrisAreDeniedWhenUsingCustomObservationName(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(TestController.class)
				.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, ObservationAutoConfiguration.class,
						WebMvcAutoConfiguration.class))
				.withPropertyValues("management.metrics.web.server.max-uri-tags=2",
						"management.observations.http.server.requests.name=my.http.server.requests")
				.run((context) -> {
					MeterRegistry registry = getInitializedMeterRegistry(context);
					assertThat(registry.get("my.http.server.requests").meters()).hasSizeLessThanOrEqualTo(2);
					assertThat(output).contains("Reached the maximum number of URI tags for 'my.http.server.requests'");
				});
	}

	/**
	 * Verifies the behavior when the maximum number of URIs is not reached, and no denial or logging occurs.
	 * @param output The output stream to capture the output.
	 */
	void shouldNotDenyNorLogIfMaxUrisIsNotReached(CapturedOutput output) {
		this.contextRunner.withUserConfiguration(TestController.class)
				.withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, ObservationAutoConfiguration.class,
						WebMvcAutoConfiguration.class))
				.withPropertyValues("management.metrics.web.server.max-uri-tags=5")
				.run((context) -> {
					MeterRegistry registry = getInitializedMeterRegistry(context);
					assertThat(registry.get("http.server.requests").meters()).hasSize(3);
					assertThat(output).doesNotContain("Reached the maximum number of URI tags for 'http.server.requests'");
				});
	}

	/**
	 * Verifies that observations are recorded when an Actuator endpoint is called.
	 */
	void whenAnActuatorEndpointIsCalledObservationsShouldBeRecorded() {
		this.contextRunner.withUserConfiguration(TestController.class, TestObservationRegistryConfiguration.class)
				.withConfiguration(AutoConfigurations.of(InfoEndpointAutoConfiguration.class, WebMvcAutoConfiguration.class,
						DispatcherServletAutoConfiguration.class, EndpointAutoConfiguration.class,
						WebEndpointAutoConfiguration.class, WebMvcEndpointManagementContextConfiguration.class,
						MetricsAutoConfiguration.class, ObservationAutoConfiguration.class))
				.withPropertyValues("management.endpoints.web.exposure.include=info")
				.run((context) -> {
					assertThat(context).doesNotHaveBean("actuatorWebEndpointObservationPredicate");
					TestObservationRegistry observationRegistry = getInitializedTestObservationRegistry(context, "/test0",
							"/actuator/info");
					TestObservationRegistryAssert.assertThat(observationRegistry)
							.hasNumberOfObservationsWithNameEqualTo("http.server.requests", 2)
							.hasAnObservationWithAKeyValue("http.url", "/test0")
							.hasAnObservationWithAKeyValue("http.url", "/actuator/info");
				});
	}

	/**
	 * Verifies that observations are recorded when Actuator observations are enabled.
	 */
	void whenActuatorObservationsEnabledObservationsShouldBeRecorded() {
		this.contextRunner.withUserConfiguration(TestController.class, TestObservationRegistryConfiguration.class)
				.withConfiguration(AutoConfigurations.of(InfoEndpointAutoConfiguration.class, WebMvcAutoConfiguration.class,
						DispatcherServletAutoConfiguration.class, EndpointAutoConfiguration.class,
						WebEndpointAutoConfiguration.class, WebMvcEndpointManagementContextConfiguration.class,
						MetricsAutoConfiguration.class, ObservationAutoConfiguration.class))
				.withPropertyValues("management.endpoints.web.exposure.include=info",
						"management.observations.http.server.actuator.enabled=true")
				.run((context) -> {
					assertThat(context).doesNotHaveBean("actuatorWebEndpointObservationPredicate");
					TestObservationRegistry observationRegistry = getInitializedTestObservationRegistry(context, "/test0",
							"/actuator/info");
					TestObservationRegistryAssert.assertThat(observationRegistry)
							.hasNumberOfObservationsWithNameEqualTo("http.server.requests", 2)
							.hasAnObservationWithAKeyValue("http.url", "/test0")
							.hasAnObservationWithAKeyValue("http.url", "/actuator/info");
				});
	}

	/**
	 * Verifies that observations are not recorded when Actuator observations are disabled.
	 */
	void whenActuatorObservationsDisabledObservationsShouldNotBeRecorded() {
		this.contextRunner.withUserConfiguration(TestController.class, TestObservationRegistryConfiguration.class)
				.withConfiguration(AutoConfigurations.of(InfoEndpointAutoConfiguration.class, WebMvcAutoConfiguration.class,
						DispatcherServletAutoConfiguration.class, EndpointAutoConfiguration.class,
						WebEndpointAutoConfiguration.class, WebMvcEndpointManagementContextConfiguration.class,
						MetricsAutoConfiguration.class, ObservationAutoConfiguration.class))
				.withPropertyValues("management.endpoints.web.exposure.include=info",
						"management.observations.http.server.actuator.enabled=false")
				.run((context) -> {
					assertThat(context).hasBean("actuatorWebEndpointObservationPredicate");
					TestObservationRegistry observationRegistry = getInitializedTestObservationRegistry(context, "/test0",
							"/actuator/info");
					TestObservationRegistryAssert.assertThat(observationRegistry)
							.hasNumberOfObservationsWithNameEqualTo("http.server.requests", 1)
							.hasAnObservationWithAKeyValue("http.url", "/test0");
				});
	}

	/**
	 * Verifies that observations are not recorded when Actuator observations are disabled using a custom endpoint base path.
	 */
	void whenActuatorObservationsDisabledObservationsShouldNotBeRecordedUsingCustomEndpointBasePath() {
		this.contextRunner.withUserConfiguration(TestController.class, TestObservationRegistryConfiguration.class)
				.withConfiguration(AutoConfigurations.of(InfoEndpointAutoConfiguration.class, WebMvcAutoConfiguration.class,
						DispatcherServletAutoConfiguration.class, EndpointAutoConfiguration.class,
						WebEndpointAutoConfiguration.class, WebMvcEndpointManagementContextConfiguration.class,
						MetricsAutoConfiguration.class, ObservationAutoConfiguration.class))
				.withPropertyValues("management.endpoints.web.exposure.include=info",
						"management.observations.http.server.actuator.enabled=false",
						"management.endpoints.web.base-path=/management")
				.run((context) -> {
					assertThat(context).hasBean("actuatorWebEndpointObservationPredicate");
					TestObservationRegistry observationRegistry = getInitializedTestObservationRegistry(context, "/test0",
							"/management/info");
					TestObservationRegistryAssert.assertThat(observationRegistry)
							.hasNumberOfObservationsWithNameEqualTo("http.server.requests", 1)
							.hasAnObservationWithAKeyValue("http.url", "/test0");
				});
	}

	/**
	 * Verifies that observations are not recorded when Actuator observations are disabled using a custom context path.
	 */
	void whenActuatorObservationsDisabledObservationsShouldNotBeRecordedUsingCustomContextPath() {
		this.contextRunner.withUserConfiguration(TestController.class, TestObservationRegistryConfiguration.class)
				.withConfiguration(AutoConfigurations.of(InfoEndpointAutoConfiguration.class, WebMvcAutoConfiguration.class,
						DispatcherServletAutoConfiguration.class, EndpointAutoConfiguration.class,
						WebEndpointAutoConfiguration.class, WebMvcEndpointManagementContextConfiguration.class,
						MetricsAutoConfiguration.class, ObservationAutoConfiguration.class))
				.withPropertyValues("management.endpoints.web.exposure.include=info",
						"management.observations.http.server.actuator.enabled=false",
						"server.servlet.context-path=/test-context")
				.run((context) -> {
					assertThat(context).hasBean("actuatorWebEndpointObservationPredicate");
					TestObservationRegistry observationRegistry = getInitializedTestObservationRegistry("/test-context",
							context, "/test-context/test0", "/test-context/actuator/info");
					TestObservationRegistryAssert.assertThat(observationRegistry)
							.hasNumberOfObservationsWithNameEqualTo("http.server.requests", 1)
							.hasAnObservationWithAKeyValue("http.url", "/test-context/test0");
				});
	}

	/**
	 * Verifies that observations are not recorded when Actuator observations are disabled using a custom servlet path.
	 */
	void whenActuatorObservationsDisabledObservationsShouldNotBeRecordedUsingCustomServletPath() {
		this.contextRunner.withUserConfiguration(TestController.class, TestObservationRegistryConfiguration.class)
				.withConfiguration(AutoConfigurations.of(InfoEndpointAutoConfiguration.class, WebMvcAutoConfiguration.class,
						DispatcherServletAutoConfiguration.class, EndpointAutoConfiguration.class,
						WebEndpointAutoConfiguration.class, WebMvcEndpointManagementContextConfiguration.class,
						MetricsAutoConfiguration.class, ObservationAutoConfiguration.class))
				.withPropertyValues("management.endpoints.web.exposure.include=info",
						"management.observations.http.server.actuator.enabled=false",
						"spring.mvc.servlet.path=/test-servlet")
				.run((context) -> {
					assertThat(context).hasBean("actuatorWebEndpointObservationPredicate");
					TestObservationRegistry observationRegistry = getInitializedTestObservationRegistry("/test-servlet",
							context, "/test-servlet/test0", "/test-servlet/actuator/info");
					TestObservationRegistryAssert.assertThat(observationRegistry)
							.hasNumberOfObservationsWithNameEqualTo("http.server.requests", 1)
							.hasAnObservationWithAKeyValue("http.url", "/test-servlet/test0");
				});
	}

	/**
	 * Verifies that observations are not recorded when Actuator observations are disabled using custom context path, servlet path, and endpoint base path.
	 */
	void whenActuatorObservationsDisabledObservationsShouldNotBeRecordedUsingCustomContextPathAndCustomServletPathAndCustomEndpointBasePath() {
		this.contextRunner.withUserConfiguration(TestController.class, TestObservationRegistryConfiguration.class)
				.withConfiguration(AutoConfigurations.of(InfoEndpointAutoConfiguration.class, WebMvcAutoConfiguration.class,
						DispatcherServletAutoConfiguration.class, EndpointAutoConfiguration.class,
						WebEndpointAutoConfiguration.class, WebMvcEndpointManagementContextConfiguration.class,
						MetricsAutoConfiguration.class, ObservationAutoConfiguration.class))
				.withPropertyValues("management.endpoints.web.exposure.include=info",
						"management.observations.http.server.actuator.enabled=false",
						"server.servlet.context-path=/test-context", "spring.mvc.servlet.path=/test-servlet",
						"management.endpoints.web.base-path=/management")
				.run((context) -> {
					assertThat(context).hasBean("actuatorWebEndpointObservationPredicate");
					TestObservationRegistry observationRegistry = getInitializedTestObservationRegistry(
							"/test-context/test-servlet", context, "/test-context/test-servlet/test0",
							"/test-context/test-servlet/management/info");
					TestObservationRegistryAssert.assertThat(observationRegistry)
							.hasNumberOfObservationsWithNameEqualTo("http.server.requests", 1)
							.hasAnObservationWithAKeyValue("http.url", "/test-context/test-servlet/test0");
				});
	}

	private MeterRegistry getInitializedMeterRegistry(AssertableWebApplicationContext context) throws Exception {
		return getInitializedMeterRegistry(context, "/test0", "/test1", "/test2");
	}

	private MeterRegistry getInitializedMeterRegistry(AssertableWebApplicationContext context, String... urls)
			throws Exception {
		assertThat(context).hasSingleBean(FilterRegistrationBean.class);
		Filter filter = context.getBean(FilterRegistrationBean.class).getFilter();
		assertThat(filter).isInstanceOf(ServerHttpObservationFilter.class);
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
		for (String url : urls) {
			mockMvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isOk());
		}
		return context.getBean(MeterRegistry.class);
	}

	private TestObservationRegistry getInitializedTestObservationRegistry(AssertableWebApplicationContext context,
			String... urls) throws Exception {
		return getInitializedTestObservationRegistry("", context, urls);
	}

	private TestObservationRegistry getInitializedTestObservationRegistry(String contextPath,
			AssertableWebApplicationContext context, String... urls) throws Exception {
		assertThat(context).hasSingleBean(FilterRegistrationBean.class);
		Filter filter = context.getBean(FilterRegistrationBean.class).getFilter();
		assertThat(filter).isInstanceOf(ServerHttpObservationFilter.class);
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
		for (String url : urls) {
			mockMvc.perform(MockMvcRequestBuilders.get(url).contextPath(contextPath)).andExpect(status().isOk());
		}
		return context.getBean(TestObservationRegistry.class);
	}
}
